package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.validate.RuleStatus
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P29 Phase 6 第3段で足した、検査の宣言のテスト（意図的差分 D-21）。
 * - 充足の状況の一覧（status）と、直せる場所への案内（fixSite）は、詳細文書の存在（coverage）専用ではなく、
 *   利用者の検査も同じ仕組みで使える（組込みは特権を持たない。CALC-28）
 * - 書かれた場所の数（値の式 occurrenceCount）
 * - スキーマの検査の報告先は、その検査を宣言したスキーマの文書の中で決まる
 */
class RuleStatusTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-rule-status")
        write(
            "_schemas/feature-list.yaml",
            """
            id: feature-list
            match: "x-features.yaml"
            phases: [draft, review]
            coverage:
              - description: 全機能は仕様書を持つこと
                detailIn: { schemaIds: [feature-spec] }
            constraints:
              - id: reviewed-twice
                description: レビュー段階の機能はレビュー記録を2件以上持つこと
                activeFrom: review
                match:
                  - node: { schema: feature-list, bind: f }
                check:
                  - gte: [{ occurrenceCount: { of: f, schema: review-note } }, 2]
                message: 機能「{id}」のレビュー記録が足りません（現在 {left} 件／必要 {right} 件）
                status: レビュー記録 {left} 件（必要 {right} 件）
                fixSite:
                  schema: review-note
                  message: "レビュー記録が足りない機能が {count} 件あります: {ids}"
            root:
              type: object
              fields:
                - { key: phase, type: string }
                - key: features
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/feature-spec.yaml",
            """
            id: feature-spec
            match: "specs/*.yaml"
            root: { type: object, fields: [{ key: id, type: id, required: true }] }
            """,
        )
        write(
            "_schemas/review-note.yaml",
            """
            id: review-note
            match: "reviews/*.yaml"
            root:
              type: object
              fields:
                - key: notes
                  type: array
                  item: { type: object, fields: [{ key: id, type: id, required: true }, { key: comment, type: string }] }
            """,
        )
        write("x-features.yaml", "phase: review\nfeatures:\n- { id: F-1, name: 検索 }\n- { id: F-2, name: 予約 }")
        // specs/ と reviews/ は x-features.yaml より前に並ぶ——F-1 のワークスペース全体での先頭の出現は仕様書の側にある
        write("specs/F-1.yaml", "id: F-1")
        write("reviews/r1.yaml", "notes:\n- { id: F-1, comment: よい }\n- { id: F-1, comment: 再確認済み }")
        write("reviews/r2.yaml", "notes:\n- { id: F-2, comment: 要修正 }")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun statuses(ws: Workspace, id: String): List<RuleStatus> {
        val schema = ws.schemas.first { it.id == "feature-list" }
        val root = YamlIO.parseFirst(Files.readString(dir.resolve("x-features.yaml")))
        return Validator.ruleStatuses(id, schema, ws.index, root, ws.schemas, ws::treeOf)
    }

    @Test
    fun `statusを宣言した検査は利用者の検査も組込みのcoverageも同じ一覧に出る`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        assertEquals(
            listOf("レビュー記録 2 件（必要 2 件） — レビュー段階の機能はレビュー記録を2件以上持つこと", "出現 1 件（必要 1 件） — 全機能は仕様書を持つこと"),
            statuses(ws, "F-1").map { it.text },
        )
        val f2 = statuses(ws, "F-2")
        assertEquals(listOf(false, false), f2.map { it.satisfied })
        assertEquals("機能「F-2」のレビュー記録が足りません（現在 1 件／必要 2 件） — レビュー段階の機能はレビュー記録を2件以上持つこと", f2[0].text)
        assertTrue("ID「F-2」の詳細文書が不足しています（現在 0 件／必要 1 件、詳細スキーマ: feature-spec（specs/*.yaml に作成））" in f2[1].text, f2[1].text)
    }

    @Test
    fun `スキーマの検査の報告先はその検査を宣言したスキーマの文書の中で決まる`() {
        // F-1 の先頭の出現は reviews/ や specs/ の側にあるが、feature-list の検査は x-features.yaml に報告される
        val ws = Workspace.open(dir)
        val issues = ws.validate("x-features.yaml")
        val review = issues.single { "レビュー記録が足りません" in it.message }
        assertEquals(listOf<Any>("features", 1), review.path)
        val coverage = issues.single { "詳細文書が不足" in it.message }
        assertEquals(listOf<Any>("features", 1), coverage.path)
        assertTrue(issues.none { "F-1" in it.message }, issues.toString())
    }

    @Test
    fun `直せる場所への案内は書き足し先のスキーマの文書に出てフェーズ未到達なら出ない`() {
        val ws = Workspace.open(dir)
        val guide = ws.validate("reviews/r1.yaml").single { "レビュー記録が足りない機能" in it.message }
        assertEquals(Severity.INFO, guide.severity)
        assertEquals("レビュー記録が足りない機能が 1 件あります: F-2", guide.message)
        assertEquals(listOf("F-2"), guide.relatedIds)
        // 同じ ID が書かれた他の文書には、指摘の案内が出る（r2 には F-2 が書かれている）
        assertTrue(ws.validate("reviews/r2.yaml").any { it.severity == Severity.INFO && "機能「F-2」のレビュー記録が足りません" in it.message })

        // 報告先の文書がまだレビュー段階でなければ、指摘も案内も出ない（意図的差分 D-21 ②）
        write("x-features.yaml", "phase: draft\nfeatures:\n- { id: F-1, name: 検索 }\n- { id: F-2, name: 予約 }")
        val draft = Workspace.open(dir)
        assertTrue(draft.validate("x-features.yaml").none { "レビュー記録" in it.message })
        assertTrue(draft.validate("reviews/r1.yaml").none { "レビュー記録" in it.message })
        assertEquals("フェーズ未到達（review から判定。現在 レビュー記録 1 件（必要 2 件）） — レビュー段階の機能はレビュー記録を2件以上持つこと", statuses(draft, "F-2")[0].text)
    }

    @Test
    fun `coverageの書き方の誤りは語彙の書き方の形として定義エラーになる`() {
        write(
            "_schemas/feature-spec.yaml",
            """
            id: feature-spec
            match: "specs/*.yaml"
            coverage:
              - description: 出現先がない
                detailIn: { minCount: 1 }
            root: { type: object, fields: [{ key: id, type: id, required: true }] }
            """,
        )
        val errors = Workspace.open(dir).schemaErrors
        assertTrue(errors.any { "coverage[0] には detailIn.schemaIds が必要です" in it }, errors.toString())
    }
}
