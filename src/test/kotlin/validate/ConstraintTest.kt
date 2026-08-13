package validate

import index.IdIndex
import schema.SchemaLoader
import schema.validateDefinition
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 制約語彙（R16-01: minLength / maxLength / pattern / min / max）のテスト。
 * 実行時検証・メタスキーマ検証（型適合・範囲整合・正規表現）・`$ref` 合成・JSON Schema 伝搬を検証する。
 */
class ConstraintTest {

    private val schema = SchemaLoader.parseSchemaText(
        """
        id: constrained
        name: 制約つき
        match: "docs/*.yaml"
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
        Path.of("_schemas/constrained.yaml"),
    )

    private fun validate(yaml: String) =
        Validator.validate(YamlIO.parse(yaml), null, Path.of("docs/a.yaml"), schema, IdIndex())

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
    fun `メタスキーマ検証が型不適合・範囲逆転・不正な正規表現を指摘する`() {
        val bad = SchemaLoader.parseSchemaText(
            """
            id: bad
            name: bad
            match: "x.yaml"
            root:
              type: object
              fields:
                - key: flag
                  type: boolean
                  minLength: 3
                - key: count
                  type: int
                  min: 10
                  max: 1
                - key: name
                  type: string
                  minLength: 5
                  maxLength: 2
                - key: code
                  type: string
                  pattern: "["
            """.trimIndent(),
            Path.of("_schemas/bad.yaml"),
        )
        val issues = bad.validateDefinition().map { it.message }
        assertTrue(issues.any { "string / text 型でのみ" in it }, "$issues")
        assertTrue(issues.any { "min が max を超えています" in it }, "$issues")
        assertTrue(issues.any { "minLength が maxLength を超えています" in it }, "$issues")
        assertTrue(issues.any { "正規表現として不正" in it }, "$issues")
    }

    @Test
    fun `id 型の pattern がIDフォーマットを強制する`() {
        // UX-79: type: id の pattern で「フォーマット: TASK-nnn」を description でなく検証として強制できる
        val idSchema = SchemaLoader.parseSchemaText(
            """
            id: tasks
            name: tasks
            match: "docs/*.yaml"
            root:
              type: object
              fields:
                - key: id
                  type: id
                  pattern: TASK-\d{3}
            """.trimIndent(),
            Path.of("_schemas/tasks.yaml"),
        )
        assertEquals(emptyList(), idSchema.validateDefinition(), "id 型の pattern はメタ検証エラーにならない")
        fun run(yaml: String) = Validator.validate(YamlIO.parse(yaml), null, Path.of("docs/a.yaml"), idSchema, IdIndex())
        assertEquals(emptyList(), run("id: TASK-001\n").filter { it.severity == Severity.ERROR })
        assertTrue(run("id: T1\n").any { "「id」は形式" in it.message }, "${run("id: T1\n")}")
    }

    @Test
    fun `JSON Schema へ制約が伝搬する`() {
        val json = ai.fieldToJsonSchema(schema.root, schema).toString()
        assertTrue("\"minLength\":4" in json, json)
        assertTrue("\"maxLength\":10" in json, json)
        assertTrue("\"pattern\"" in json, json)
        assertTrue("\"minimum\":1" in json, json)
        assertTrue("\"maximum\":5" in json, json)
    }
}
