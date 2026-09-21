package workspace

import schema.StructuralSchemaParser
import validate.Severity
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 制約語彙（R16-01: minLength / maxLength / pattern / min / max）の実行時検証テスト。
 * 旧 validate/ConstraintTest の単一文書ケースが由来（YM-006）。P27 段C の C-2 で、yaml-core の旧い検証のテストをここへ移した
 * ——利用者が書くスキーマから導出した最小の構造のスキーマで検証する（入口は DerivedStructures）。期待値は以前のまま。
 * メタスキーマ検証（validateDefinition）・JSON Schema 伝搬はルート側 ConstraintTest に残る。
 */
class StructuralConstraintTest {

    private val schema = StructuralSchemaParser.parse(
        """
        id: constrained
        name: 制約つき
        definitions:
          shortName:
            type: string
            minLength: 2
            maxLength: 5
        root:
          type: object
          fields:
            - key: title
              type: string
              minLength: 4
              maxLength: 10
            - key: code
              type: string
              pattern: "^[a-z][a-z0-9_]*${'$'}"
            - key: priority
              type: int
              min: 1
              max: 5
            - key: rate
              type: number
              min: 0.5
              max: 1.5
            - key: nick
              ${'$'}ref: shortName
        """.trimIndent(),
        fallbackId = "constrained",
    )

    private fun validate(yaml: String) = DerivedStructures(schema).validate(YamlIO.parse(yaml))

    @Test
    fun `制約を満たす値は指摘されない`() {
        val issues = validate("title: ちょうど良い\ncode: task_1\npriority: 3\nrate: 1.0\nnick: abc\n")
        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR }, "$issues")
    }

    @Test
    fun `文字数・形式・範囲の違反がエラーになる`() {
        val issues = validate("title: 短い\ncode: Task-1\npriority: 9\nrate: 0.1\n")
        assertTrue(issues.any { "4 文字以上" in it.message && "現在 2 文字" in it.message }, "$issues")
        assertTrue(issues.any { "形式（" in it.message && "code" in it.message || "「code」は形式" in it.message }, "$issues")
        assertTrue(issues.any { "「priority」は 5 以下" in it.message }, "$issues")
        assertTrue(issues.any { "「rate」は 0.5 以上" in it.message }, "$issues")
        val over = validate("title: 1234567890あ\n")
        assertTrue(over.any { "10 文字以内" in it.message }, "$over")
    }

    @Test
    fun `ref 先の制約が合成されて効く`() {
        val issues = validate("nick: a\n")
        assertTrue(issues.any { "「nick」は 2 文字以上" in it.message }, "$issues")
    }

    @Test
    fun `id 型の pattern がIDフォーマットを強制する`() {
        // UX-79: type: id の pattern で「フォーマット: TASK-nnn」を description でなく検証として強制できる
        val idSchema = StructuralSchemaParser.parse(
            """
            id: tasks
            root:
              type: object
              fields:
                - key: id
                  type: id
                  pattern: TASK-\d{3}
            """.trimIndent(),
            fallbackId = "tasks",
        )
        fun run(yaml: String) = DerivedStructures(idSchema).validate(YamlIO.parse(yaml))
        assertEquals(emptyList(), run("id: TASK-001\n").filter { it.severity == Severity.ERROR })
        assertTrue(run("id: T1\n").any { "「id」は形式" in it.message }, "${run("id: T1\n")}")
    }
}
