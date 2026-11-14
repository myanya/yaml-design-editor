package dev.toros.yde.yaml.structure

import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.validate.StructuralIssueKind
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 最小の構造のスキーマ（P27 段C の C-1）の読み取りと検証。yaml-core 単体で閉じる——ID・参照・フェーズを知らず、
 * その値1つと、その項目の決まりだけを見て決まるチェックだけを行う。
 */
class StructureValidatorTest {

    private fun schema(yaml: String): StructureSchema = StructureParser.parse(YamlIO.parseToRaw(yaml.trimIndent()), "s")

    private fun validate(schema: StructureSchema, document: String) = StructureValidator.validate(YamlIO.parse(document), schema)

    private val screen = schema(
        """
        id: screen
        root:
          type: object
          fields:
            - { key: id, label: 画面ID, type: string, required: true, pattern: "SCR-[0-9]+" }
            - { key: name, type: string, maxLength: 5 }
            - { key: count, type: int, min: 1, max: 10 }
            - key: kind
              type: enum
              values: [list, form]
              cases:
                form:
                  - { key: submit, type: string, required: true }
            - key: items
              type: array
              item: { ${'$'}ref: item }
        definitions:
          item:
            type: object
            fields:
              - { key: label, type: string, required: true }
              - { key: children, type: array, item: { ${'$'}ref: item } }   # 定義への参照で、再帰の形を書ける
        """
    )

    @Test
    fun `値の形 必須 値の形式的なチェックを検証する`() {
        assertEquals(emptyList(), validate(screen, "id: SCR-1\nname: 一覧\ncount: 3\nkind: list"))
        val issues = validate(screen, "id: screen\nname: 長すぎる名前\ncount: 11\nkind: table\nextra: 1")
        assertEquals(
            setOf(StructuralIssueKind.FORMAT, StructuralIssueKind.LENGTH, StructuralIssueKind.RANGE, StructuralIssueKind.ENUM, StructuralIssueKind.UNKNOWN_KEY),
            issues.map { it.kind }.toSet(),
        )
        assertTrue(validate(screen, "name: 一覧").any { it.kind == StructuralIssueKind.REQUIRED && it.message == "必須項目「画面ID」が未設定です" })
    }

    @Test
    fun `選択肢の値ごとの項目は その値のときだけ有効になる`() {
        assertEquals(emptyList(), validate(screen, "id: SCR-1\nkind: form\nsubmit: 登録"))
        assertTrue(validate(screen, "id: SCR-1\nkind: form").any { it.kind == StructuralIssueKind.REQUIRED && it.path == listOf<Any>("submit") })
        val inactive = validate(screen, "id: SCR-1\nkind: list\nsubmit: 登録").single()
        assertEquals(StructuralIssueKind.INACTIVE_KEY, inactive.kind)
        assertTrue("form のときのみ" in inactive.message, inactive.message)
    }

    @Test
    fun `定義への参照で 入れ子の深さが決まっていない形を検証できる`() {
        val document = "id: SCR-1\nitems:\n  - label: 親\n    children:\n      - label: 子\n        children:\n          - { other: 孫 }"
        val issues = validate(screen, document)
        val path = listOf<Any>("items", 0, "children", 0, "children", 0)
        assertTrue(issues.any { it.kind == StructuralIssueKind.REQUIRED && it.path == path + "label" }, issues.toString())
        assertTrue(issues.any { it.kind == StructuralIssueKind.UNKNOWN_KEY && it.path == path + "other" }, issues.toString())
    }

    @Test
    fun `値の形ごとの決まりは 値の形を見て1つに決まる`() {
        val owner = schema(
            """
            id: s
            root:
              type: object
              fields:
                - key: owner
                  label: 担当
                  required: true
                  shapes:
                    scalar: { type: string }
                    object:
                      type: object
                      fields:
                        - { key: to, label: 担当 の to, type: string, required: true }
                        - { key: since, type: int }
            """
        )
        assertEquals(emptyList(), validate(owner, "owner: ACT-1"))
        assertEquals(emptyList(), validate(owner, "owner: { to: ACT-1, since: 2026 }"))
        // オブジェクトの形なら、オブジェクトの決まりで検証する
        val wrong = validate(owner, "owner: { since: 去年, extra: x }")
        assertEquals(
            setOf(StructuralIssueKind.REQUIRED, StructuralIssueKind.TYPE, StructuralIssueKind.UNKNOWN_KEY),
            wrong.map { it.kind }.toSet(),
        )
        // 決まりの無い形（配列）は、書ける形を案内する。必須は、外側の項目のものを引き継ぐ
        val form = validate(owner, "owner: [ACT-1]").single()
        assertEquals(Severity.WARNING to "「担当」は、スカラーかオブジェクトのどちらかで書きます", form.severity to form.message)
        assertTrue(validate(owner, "owner:").any { it.kind == StructuralIssueKind.REQUIRED })
        assertTrue(validate(owner, "other: 1").any { it.kind == StructuralIssueKind.REQUIRED && it.path == listOf<Any>("owner") })
    }

    @Test
    fun `構造のスキーマに書けない語彙は 読み取りの段階で誤りになる`() {
        // ID・参照・フェーズ・表示のヒントは、構造のスキーマの語彙に無い（導出のときに、意味の側が畳むか落とす）
        for (field in listOf("{ key: id, type: id }", "{ key: uses, type: ref }", "{ key: a, requiredFrom: 詳細設計 }", "{ key: a, relation: uses }", "{ key: a, role: title }")) {
            assertFailsWith<IllegalStateException>(field) { schema("id: s\nroot:\n  type: object\n  fields:\n    - $field") }
        }
    }

    @Test
    fun `配列の unique は重なった要素を指摘し 数は数として比べ 値の無い要素は比べない`() {
        val tags = schema(
            """
            id: s
            root:
              type: object
              fields:
                - { key: tags, label: タグ, type: array, unique: true, item: { type: string } }
                - { key: scores, type: array, unique: true, item: { type: number } }
                - key: columns
                  label: カラム
                  type: array
                  unique: [name]
                  item:
                    type: object
                    fields:
                      - { key: name, label: 名前, type: string }
                      - { key: type, type: string }
                - key: pairs
                  type: array
                  unique: [[a, b], [c]]   # 決まりを複数（型と使う側の両方に書いたときの導出の出力）
                  item:
                    type: object
                    fields:
                      - { key: a, type: string }
                      - { key: b, type: string }
                      - { key: c, type: string }
            """
        )
        val dup = validate(tags, "tags: [TAG-001, TAG-002, TAG-002]").single()
        assertEquals(StructuralIssueKind.UNIQUE, dup.kind)
        assertEquals(Severity.ERROR, dup.severity)
        assertEquals(listOf<Any>("tags", 2), dup.path)
        assertEquals("「タグ」に同じ値「TAG-002」があります（2 件目と同じ）", dup.message)
        assertTrue(validate(tags, "tags: [TAG-001, TAG-002]").isEmpty())
        // 数は数として比べる（1 と 1.0 は同じ）
        assertEquals(listOf<Any>("scores", 1), validate(tags, "scores: [1, 1.0, 2]").single().path)
        // object の要素は書いたキーの値で比べる。キーが書かれていない要素は比べない
        val col = validate(tags, "columns: [{name: id, type: int}, {type: x}, {type: y}, {name: id, type: text}]").single()
        assertEquals(listOf<Any>("columns", 3), col.path)
        assertEquals("「カラム」の「名前」が「id」の要素が重なっています（1 件目と同じ）", col.message)
        // 決まりが複数なら、どれも成り立つこと
        val pairs = validate(tags, "pairs: [{a: x, b: y, c: p}, {a: x, b: z, c: p}, {a: x, b: y, c: q}]")
        assertEquals(listOf(listOf<Any>("pairs", 2), listOf<Any>("pairs", 1)), pairs.map { it.path })
        // 書き方の誤り
        assertFailsWith<IllegalStateException> { schema("id: s\nroot:\n  type: object\n  fields:\n    - { key: a, type: array, unique: yes-please }") }
        assertFailsWith<IllegalArgumentException> { schema("id: s\nroot:\n  type: object\n  fields:\n    - { key: a, type: array, unique: [] }") }
    }
}
