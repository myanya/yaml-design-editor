package workspace

import schema.StructuralSchemaParser
import validate.Severity
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 構造の検証のテスト: 利用者が書くスキーマ（cases・属性付き参照など）で書いた文書を、導出した最小の構造のスキーマで検証する
 * （入口は DerivedStructures。P27 段C の C-2 で、yaml-core の旧い検証 StructuralValidator のテストをここへ移した——期待値は以前のまま）。
 * 旧 validate/ValidatorTest の単一文書ケースが由来（YM-006）。
 * メタスキーマ検証（validateDefinition）のケースはルート側 ValidatorTest に残る。
 */
class StructuralValidatorTest {

    private val schema = StructuralSchemaParser.parse(
        """
        id: screen
        name: 画面
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
        fallbackId = "screen",
    )

    private fun validate(yaml: String) = DerivedStructures(schema).validate(YamlIO.parse(yaml))

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
        val nestedSchema = StructuralSchemaParser.parse(
            """
            id: nested-cases
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
            fallbackId = "nested-cases",
        )

        val issues = DerivedStructures(nestedSchema).validate(YamlIO.parse("value-type: direct\ntype: string\n"))

        assertTrue(issues.any { it.severity == Severity.ERROR && it.path == listOf("min") })
    }

    // ---------------------------------------------------------------- 属性付き参照（R20-03）

    private val attributedSchema = StructuralSchemaParser.parse(
        """
        id: attributed
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
        fallbackId = "attributed",
    )

    private fun validateAttributed(yaml: String) =
        DerivedStructures(attributedSchema).validate(YamlIO.parse(yaml))

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
        // 正しい形式は指摘なし（参照の存在確認は構造検証の対象外＝意味検証の仕事）
        val ok = validateAttributed("id: SCR-1\nowner:\n  to: ACT-1\n  role: 管理者\n  since: 2026")
        assertEquals(emptyList(), ok, ok.toString())
        // スカラー形式も従来どおり許可
        val scalar = validateAttributed("id: SCR-1\nowner: ACT-1")
        assertEquals(emptyList(), scalar, scalar.toString())
    }

    @Test
    fun `fields宣言のないrefにオブジェクトが来たら従来どおり型不一致の警告`() {
        val plain = StructuralSchemaParser.parse(
            """
            id: plain
            root:
              type: object
              fields:
                - key: owner
                  type: ref
            """.trimIndent(),
            fallbackId = "plain",
        )
        val issues = DerivedStructures(plain).validate(YamlIO.parse("owner:\n  to: ACT-1"))
        assertTrue(issues.any { it.severity == Severity.WARNING && "スカラー値" in it.message }, issues.toString())
    }
}
