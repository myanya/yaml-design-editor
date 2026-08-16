package validate

import schema.SchemaLoader
import schema.validateDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 制約語彙（R16-01: minLength / maxLength / pattern / min / max）のメタスキーマ検証と
 * JSON Schema 伝搬のテスト。実行時検証（文字数・形式・範囲・`$ref` 合成）のケースは
 * yaml-core の StructuralConstraintTest へ移設した（3層コア移行 YM-006）。
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
    fun `id 型の pattern はメタスキーマ検証を通る`() {
        // UX-79: type: id の pattern はメタ検証エラーにならない（実行時の強制は StructuralConstraintTest）
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
