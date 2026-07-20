package schema

import model.YNode
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaLoaderTest {
    @Test
    fun `スキーマ全体の説明を読み込める`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: requirement
            name: 要求
            description: |
              要求事項を管理するスキーマです。
              要求の状態と関連を記録します。
            match: "requirements/**/*.yaml"
            root:
              type: object
              fields: []
            """.trimIndent(),
            Path.of("_schemas/requirement.yaml"),
        )

        assertEquals("要求事項を管理するスキーマです。\n要求の状態と関連を記録します。\n", schema.description)
    }

    // ---------------------------------------------------------------- enum の cases

    private fun screenSchema(): SchemaDef = SchemaLoader.parseSchemaText(
        """
        id: screen
        name: 画面
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: name
              type: string
            - key: type
              type: enum
              values: [text, radio, checkbox]
              default: text
              cases:
                radio:
                  - key: options
                    label: 選択肢
                    type: array
                    required: true
                    item:
                      type: string
                checkbox:
                  - key: options
                    type: array
                    item:
                      type: string
        """.trimIndent(),
        Path.of("_schemas/screen.yaml"),
    )

    @Test
    fun `enumのcasesを読み込める`() {
        val schema = screenSchema()
        val type = schema.root.fields.first { it.key == "type" }
        assertEquals(listOf("radio", "checkbox"), type.cases.keys.toList())
        assertEquals(listOf("options"), type.cases["radio"]?.map { it.key })
        assertEquals(true, type.cases["radio"]?.single()?.required)
        assertTrue(schema.validateDefinition().isEmpty())
    }

    @Test
    fun `effectiveFieldsはenum値に応じたcaseフィールドを判別子の直後に合成する`() {
        val schema = screenSchema()
        val node = YamlIO.parse("name: 対象\ntype: radio\n") as YNode.Map
        val effective = schema.effectiveFields(schema.root.fields, node)
        assertEquals(listOf("name", "type", "options"), effective.fields.map { it.key })
        assertTrue(effective.inactive.isEmpty())
    }

    @Test
    fun `effectiveFieldsは非アクティブなcaseキーと有効になる値を報告する`() {
        val schema = screenSchema()
        val node = YamlIO.parse("name: 対象\ntype: text\n") as YNode.Map
        val effective = schema.effectiveFields(schema.root.fields, node)
        assertEquals(listOf("name", "type"), effective.fields.map { it.key })
        assertEquals(listOf("radio", "checkbox"), effective.inactive["options"]?.values)
    }

    @Test
    fun `effectiveFieldsはcaseから追加されたenumのcasesも同じobjectへ展開する`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: nested-cases
            name: ネストしたcases
            match: "*.yaml"
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
                            - key: max
                              type: int
                          number:
                            - key: scale
                              type: int
                - key: description
                  type: text
            """.trimIndent(),
            Path.of("_schemas/nested-cases.yaml"),
        )
        val node = YamlIO.parse("value-type: direct\ntype: string\n") as YNode.Map

        val effective = schema.effectiveFields(schema.root.fields, node)

        assertEquals(listOf("value-type", "type", "min", "max", "description"), effective.fields.map { it.key })
        assertEquals(listOf("number"), effective.inactive["scale"]?.values)
        assertTrue("type" !in effective.inactive)
    }

    @Test
    fun `refで参照したenumのcasesが引き継がれる`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: screen
            name: 画面
            match: "screens/**/*.yaml"
            definitions:
              input-type:
                type: enum
                values: [text, radio]
                cases:
                  radio:
                    - key: options
                      type: array
                      item:
                        type: string
            root:
              type: object
              fields:
                - key: type
                  ${'$'}ref: input-type
            """.trimIndent(),
            Path.of("_schemas/screen.yaml"),
        )
        assertTrue(schema.validateDefinition().isEmpty())
        val resolved = schema.resolve(schema.root.fields.single())!!
        assertEquals(listOf("options"), resolved.cases["radio"]?.map { it.key })
        val node = YamlIO.parse("type: radio\n") as YNode.Map
        assertEquals(listOf("type", "options"), schema.effectiveFields(schema.root.fields, node).fields.map { it.key })
    }

    @Test
    fun `casesの値が選択肢にないと定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: type
                  type: enum
                  values: [a]
                  cases:
                    b:
                      - key: x
                        type: string
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("選択肢に含まれていません") })
    }

    @Test
    fun `casesはenum型以外に書くと定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: name
                  type: string
                  cases:
                    a:
                      - key: x
                        type: string
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("casesはenum型でのみ使用できます") })
    }

    @Test
    fun `caseのキーが共通フィールドと重複すると定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: options
                  type: string
                - key: type
                  type: enum
                  values: [a]
                  cases:
                    a:
                      - key: options
                        type: string
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("既存のフィールドと重複") })
    }

    @Test
    fun `caseのキーが別の判別フィールドのcasesと重複すると定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: kind
                  type: enum
                  values: [a]
                  cases:
                    a:
                      - key: extra
                        type: string
                - key: type
                  type: enum
                  values: [b]
                  cases:
                    b:
                      - key: extra
                        type: string
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("両方のcases") })
    }

    @Test
    fun `generateSampleは既定値に対応するcaseフィールドを含む`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: type
                  type: enum
                  values: [radio, text]
                  cases:
                    radio:
                      - key: options
                        type: array
                        item:
                          type: string
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        val sample = schema.generateSample() as YNode.Map
        assertEquals("radio", (sample.entries["type"] as YNode.Scalar).value)
        assertTrue(sample.entries.containsKey("options"))
    }
}
