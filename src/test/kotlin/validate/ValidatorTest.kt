package validate

import index.IdIndex
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorTest {

    private val schema = SchemaLoader.parseSchemaText(
        """
        id: screen
        name: 画面
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: inputs
              type: array
              item:
                type: object
                fields:
                  - key: name
                    type: string
                  - key: type
                    label: 入力種別
                    type: enum
                    values: [text, radio]
                    cases:
                      radio:
                        - key: options
                          type: array
                          required: true
                          item:
                            type: string
        """.trimIndent(),
        Path.of("_schemas/screen.yaml"),
    )

    private fun validate(yaml: String) =
        Validator.validate(YamlIO.parse(yaml), null, Path.of("screens/a.yaml"), schema, IdIndex())

    @Test
    fun `アクティブなcaseの必須フィールドが検証される`() {
        val issues = validate(
            """
            inputs:
              - name: 対象
                type: radio
            """.trimIndent()
        )
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("options") })
    }

    @Test
    fun `アクティブなcaseのフィールドはスキーマ外扱いにならない`() {
        val issues = validate(
            """
            inputs:
              - name: 対象
                type: radio
                options: [タイトル, 説明]
            """.trimIndent()
        )
        assertTrue(issues.isEmpty(), "検証エラーなしのはずが: $issues")
    }

    @Test
    fun `非アクティブなcaseのキーは有効になる値の案内つき警告になる`() {
        val issues = validate(
            """
            inputs:
              - name: キーワード
                type: text
                options: [a, b]
            """.trimIndent()
        )
        val issue = issues.single { it.message.contains("options") }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue(issue.message.contains("入力種別"))
        assertTrue(issue.message.contains("radio"))
    }

    @Test
    fun `判別フィールドが未設定ならcaseフィールドは要求されない`() {
        val issues = validate(
            """
            inputs:
              - name: キーワード
            """.trimIndent()
        )
        assertTrue(issues.none { it.message.contains("options") })
    }

    @Test
    fun `ネストしたcaseの必須フィールドも検証される`() {
        val nestedSchema = SchemaLoader.parseSchemaText(
            """
            id: nested-cases
            name: ネストしたcases
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: value-type
                  type: enum
                  values: [direct, reference]
                  cases:
                    direct:
                      - key: type
                        type: enum
                        values: [string, number]
                        cases:
                          string:
                            - key: min
                              type: int
                              required: true
            """.trimIndent(),
            Path.of("_schemas/nested-cases.yaml"),
        )

        val issues = Validator.validate(
            YamlIO.parse("value-type: direct\ntype: string\n"),
            null,
            Path.of("screens/a.yaml"),
            nestedSchema,
            IdIndex(),
        )

        assertTrue(issues.any { it.severity == Severity.ERROR && it.path == listOf("min") })
    }
}
