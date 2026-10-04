package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.SchemaScaffold
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.ValueSemantics
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 保存時に自動で入る値（印 `auto`）の計画: 印を持つ要素の列挙 × 保存済みとの違い × SetScalar。
 * 「今」は引数で固定する（時計を持たない）。
 */
class SemanticAutoValuesTest {

    private val now = LocalDateTime.of(2026, 10, 5, 14, 30, 15)

    private fun tree(idInSection: Boolean = true): SchemaTree = SchemaTreeParser.parse(
        """
        id: rule
        root:
          type: object
          fields:
            - { key: id, type: id }
            - { key: updated_on, type: date, auto: updated }
            - { key: revision, type: int, auto: revision }
            - key: sections
              type: array
              item:
                type: object
                fields:
                  ${if (idInSection) "- { key: id, type: id }" else ""}
                  - { key: created_on, type: date, auto: created }
                  - { key: updated_on, type: date, auto: updated }
                  - { key: revision, type: int, auto: revision }
                  - { key: title, type: string }
        """.trimIndent(),
        "rule",
    )

    private val base = """
        id: RULE-1
        updated_on: 2026-09-01
        revision: 4
        sections:
          - id: RITM-1
            created_on: 2026-08-01
            updated_on: 2026-08-02
            revision: 2
            title: 一つ目
          - id: RITM-2
            created_on: 2026-08-01
            updated_on: 2026-08-03
            revision: 7
            title: 二つ目
    """.trimIndent()

    private fun ops(plan: SemanticAutoValues.Plan): Map<String, Any?> =
        plan.operations.associate { op -> (op as dev.toros.yde.yaml.edit.YamlOperation.SetScalar).let { it.path.pretty() to it.value } }

    @Test
    fun `中身が変わった要素と その親の文書だけに 今日と版 +1 が入る`() {
        val working = YamlIO.parse(base.replace("title: 二つ目", "title: 二つ目（直した）"))
        val plan = SemanticAutoValues.plan(tree(), YamlIO.parse(base), working, now)
        assertEquals(
            mapOf(
                "updated_on" to "2026-10-05", "revision" to 5L,
                "sections[1].updated_on" to "2026-10-05", "sections[1].revision" to 8L,
            ),
            ops(plan),
        )
        assertEquals("文書: updated_on・revision、sections[1]: updated_on・revision", plan.describe())
    }

    @Test
    fun `コメントや表記だけの違い と 自動の項目だけの手直し は 変わったと見ない`() {
        val commentOnly = YamlIO.parse(base.replace("title: 一つ目", "title: '一つ目'  # 引用符とコメント"))
        assertTrue(SemanticAutoValues.plan(tree(), YamlIO.parse(base), commentOnly, now).isEmpty)
        val handFixedDate = YamlIO.parse(base.replace("updated_on: 2026-08-02", "updated_on: 2026-08-20"))
        assertTrue(SemanticAutoValues.plan(tree(), YamlIO.parse(base), handFixedDate, now).isEmpty, "手で直した日付は残る")
    }

    @Test
    fun `同じ日に何度保存しても 入れる値が今の値と同じなら操作を作らない`() {
        val working = YamlIO.parse(base.replace("title: 二つ目", "title: 二つ目（直した）"))
        val first = SemanticAutoValues.stamp(tree(), YamlIO.parse(base), working, now)
        assertEquals(4, first.operations.size)
        // 保存済み（base）がまだ古いまま、同じ内容をもう一度保存しても、版は保存済み +1 のまま・日付は今日のまま
        assertTrue(SemanticAutoValues.plan(tree(), YamlIO.parse(base), working, now).isEmpty)
    }

    @Test
    fun `手で版を書き換えて中身も変えたら 保存済みの値 +1 が勝つ`() {
        val working = YamlIO.parse(base.replace("revision: 7", "revision: 100").replace("title: 二つ目", "title: 三つ目"))
        val plan = SemanticAutoValues.plan(tree(), YamlIO.parse(base), working, now)
        assertEquals(8L, ops(plan)["sections[1].revision"])
    }

    @Test
    fun `新しい要素は 空の作成日 更新日 版 が埋まり 書かれた値は尊重する ID で対応するので先頭に挿しても既存は動かない`() {
        val working = YamlIO.parse(
            """
            id: RULE-1
            updated_on: 2026-09-01
            revision: 4
            sections:
              - id: RITM-3
                created_on: 2025-01-01
                title: 取り込んだもの
              - id: RITM-1
                created_on: 2026-08-01
                updated_on: 2026-08-02
                revision: 2
                title: 一つ目
              - id: RITM-2
                created_on: 2026-08-01
                updated_on: 2026-08-03
                revision: 7
                title: 二つ目
            """.trimIndent()
        )
        val plan = SemanticAutoValues.plan(tree(), YamlIO.parse(base), working, now)
        assertEquals(
            mapOf(
                "updated_on" to "2026-10-05", "revision" to 5L,
                "sections[0].updated_on" to "2026-10-05", "sections[0].revision" to 1L,
            ),
            ops(plan),
            "書かれた作成日 2025-01-01 は残り、既存の要素（位置がずれた）は触らない",
        )
    }

    @Test
    fun `ID の無い配列は位置で対応するので 先頭に挿すとずれた要素も変わったと見る`() {
        val baseNoId = """
            id: RULE-1
            updated_on: 2026-09-01
            revision: 4
            sections:
              - { created_on: 2026-08-01, updated_on: 2026-08-02, revision: 2, title: 一つ目 }
              - { created_on: 2026-08-01, updated_on: 2026-08-03, revision: 7, title: 二つ目 }
        """.trimIndent()
        val working = YamlIO.parse(baseNoId.replace("sections:\n", "sections:\n  - { title: 新しいもの }\n"))
        val plan = SemanticAutoValues.plan(tree(idInSection = false), YamlIO.parse(baseNoId), working, now)
        // 位置で対応するので、先頭の新しい要素は「一つ目が変わった」と見え（作成日は入らない）、ずれた二つ目も変わったと見える。
        // 末尾へずれた元の二つ目は「新しい要素」だが値が全部書かれているので何も入らない
        assertEquals(
            mapOf(
                "updated_on" to "2026-10-05", "revision" to 5L,
                "sections[0].updated_on" to "2026-10-05", "sections[0].revision" to 3L,
                "sections[1].updated_on" to "2026-10-05", "sections[1].revision" to 8L,
            ),
            ops(plan),
        )
    }

    @Test
    fun `保存済みが無い新しい文書は 空の項目だけを埋め 書かれた値はそのまま`() {
        val working = YamlIO.parse(
            """
            id: RULE-9
            revision: 3
            sections:
              - id: RITM-9
                title: 新規
            """.trimIndent()
        )
        val plan = SemanticAutoValues.stamp(tree(), null, working, now)
        assertEquals(
            mapOf(
                "updated_on" to "2026-10-05",
                "sections[0].created_on" to "2026-10-05", "sections[0].updated_on" to "2026-10-05", "sections[0].revision" to 1L,
            ),
            ops(plan),
        )
        val root = working as YNode.Map
        assertEquals(3, (root.entries["revision"] as YNode.Scalar).value, "書かれた版は残る")
        assertEquals("2026-10-05", ((root.entries["sections"] as YNode.Seq).items[0].let { it as YNode.Map }.entries["updated_on"] as YNode.Scalar).value)
    }

    @Test
    fun `保存済みの版が読めなければ 1 から 日時と時刻は正準の形`() {
        val odd = base.replace("revision: 7", "revision: 第七版")
        val working = YamlIO.parse(odd.replace("title: 二つ目", "title: 二つ目（直した）"))
        assertEquals(1L, ops(SemanticAutoValues.plan(tree(), YamlIO.parse(odd), working, now))["sections[1].revision"])
        assertEquals("2026-10-05 14:30:15", ValueSemantics.DATETIME.stamp(now))
        assertEquals("14:30:15", ValueSemantics.TIME.stamp(now))
    }

    @Test
    fun `ひな型は now を渡すと自動の項目を新しい要素の値で埋める`() {
        val t = tree()
        val item = t.root.fields.first { it.key == "sections" }.item!!
        val withNow = SchemaScaffold.defaultTree(t, item, now = now) as YNode.Map
        assertEquals("2026-10-05", (withNow.entries["created_on"] as YNode.Scalar).value)
        assertEquals("2026-10-05", (withNow.entries["updated_on"] as YNode.Scalar).value)
        assertEquals(1L, (withNow.entries["revision"] as YNode.Scalar).value)
        val without = SchemaScaffold.defaultTree(t, item) as YNode.Map
        assertTrue("created_on" !in without.entries, "now を渡さなければ今までどおり（必須でない項目は作らない）")
        assertEquals(parseYPath("sections[0]"), SemanticAutoValues.Stamp(parseYPath("sections[0].revision"), item, 1L).elementPath)
    }
}
