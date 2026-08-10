package validate

import index.IdIndex
import schema.SchemaLoader
import schema.validateDefinition
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

    // ---------------------------------------------------------------- 属性付き参照（R20-03）

    private val attributedSchema = SchemaLoader.parseSchemaText(
        """
        id: attributed
        name: 属性付き参照
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: owner
              type: ref
              fields:
                - key: role
                  type: string
                - key: since
                  type: int
        """.trimIndent(),
        Path.of("_schemas/attributed.yaml"),
    )

    private fun validateAttributed(yaml: String) =
        Validator.validate(YamlIO.parse(yaml), null, Path.of("screens/a.yaml"), attributedSchema, IdIndex(), indexed = false)

    @Test
    fun `属性付き参照はtoの必須と属性の型と未宣言キーが検証される`() {
        // to 未設定はエラー（座標は to）
        val missing = validateAttributed("id: SCR-1\nowner:\n  role: 管理者")
        assertTrue(missing.any { it.severity == Severity.ERROR && it.path == listOf("owner", "to") }, missing.toString())
        // 属性は object と同じ流儀で型検証される
        val badType = validateAttributed("id: SCR-1\nowner:\n  to: ACT-1\n  since: 文字列")
        assertTrue(badType.any { it.severity == Severity.ERROR && it.path == listOf("owner", "since") }, badType.toString())
        // 未宣言の属性キーは警告
        val unknown = validateAttributed("id: SCR-1\nowner:\n  to: ACT-1\n  extra: x")
        assertTrue(unknown.any { it.severity == Severity.WARNING && it.path == listOf("owner", "extra") }, unknown.toString())
        // 正しい形式はエラー・警告なし（未解決参照は indexed=false で INFO 降格）
        val ok = validateAttributed("id: SCR-1\nowner:\n  to: ACT-1\n  role: 管理者\n  since: 2026")
        assertEquals(emptyList(), ok.filter { it.severity != Severity.INFO }, ok.toString())
        // スカラー形式も従来どおり許可
        val scalar = validateAttributed("id: SCR-1\nowner: ACT-1")
        assertEquals(emptyList(), scalar.filter { it.severity != Severity.INFO }, scalar.toString())
    }

    @Test
    fun `fields宣言のないrefにオブジェクトが来たら従来どおり型不一致の警告`() {
        val plain = SchemaLoader.parseSchemaText(
            """
            id: plain
            name: 通常ref
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: owner
                  type: ref
            """.trimIndent(),
            Path.of("_schemas/plain.yaml"),
        )
        val issues = Validator.validate(
            YamlIO.parse("owner:\n  to: ACT-1"), null, Path.of("screens/a.yaml"), plain, IdIndex(), indexed = false,
        )
        assertTrue(issues.any { it.severity == Severity.WARNING && "スカラー値" in it.message }, issues.toString())
    }

    @Test
    fun `属性への入れ子refはメタスキーマ検証で不許可`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: bad
            name: 属性に入れ子ref
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: owner
                  type: ref
                  fields:
                    - key: delegate
                      type: ref
                    - key: to
                      type: string
            """.trimIndent(),
            Path.of("_schemas/bad.yaml"),
        )
        val issues = schema.validateDefinition().map { it.message }
        assertTrue(issues.any { "ref / id 型は使えません" in it }, issues.toString())
        assertTrue(issues.any { "予約キー" in it }, issues.toString())
    }
}
