package schema

import core.Workspace
import model.YNode
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaLoaderTest {
    private fun write(workspace: Path, rel: String, content: String) {
        val file = workspace.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun <T> withWorkspace(block: (Path) -> T): T {
        val workspace = createTempDirectory("yde-schema-catalog-test")
        return try {
            block(workspace)
        } finally {
            Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

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

    // ---------------------------------------------------------------- 横断 $ref（R7-03）

    @Test
    fun `libraryの定義を修飾refで参照できる`() = withWorkspace { workspace ->
        write(
            workspace,
            "_schemas/_common.yaml",
            """
            id: common
            name: 共通定義
            kind: library
            definitions:
              identifier:
                type: id
              text-value:
                type: string
                description: 共通の文字列
              name-pair:
                type: object
                fields:
                  - key: name
                    ${'$'}ref: text-value
                  - key: alias
                    ${'$'}ref: text-value
            """,
        )
        write(
            workspace,
            "_schemas/item.yaml",
            """
            id: item
            name: 項目
            match: "items/**/*.yaml"
            root:
              type: object
              fields:
                - key: id
                  ${'$'}ref: common#identifier
                - key: names
                  ${'$'}ref: common#name-pair
            """,
        )
        write(
            workspace,
            "items/a.yaml",
            """
            id: ITEM-001
            names:
              name: 正式名
              alias: 別名
            """,
        )

        val loaded = SchemaLoader.loadAllWithErrors(workspace)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val common = loaded.schemas.first { it.id == "common" }
        val item = loaded.schemas.first { it.id == "item" }
        assertEquals(SchemaKind.LIBRARY, common.kind)
        assertFalse(common.matches("items/a.yaml"), "libraryはデータ文書へ適用しない")

        val names = item.resolve(item.root.fields.first { it.key == "names" })
        assertEquals(FieldType.OBJECT, names?.type)
        val name = item.resolve(names?.fields?.first { it.key == "name" })
        assertNotNull(name)
        assertEquals(FieldType.STRING, name.type)
        assertEquals("共通の文字列", name.description, "共有定義内の非修飾refは共有モジュール内で解決する")

        val sample = item.generateSample() as YNode.Map
        val nameValues = sample.entries["names"] as YNode.Map
        assertTrue(nameValues.entries.keys.containsAll(listOf("name", "alias")))

        val opened = Workspace.open(workspace)
        assertTrue("ITEM-001" in opened.index.entries, "索引スキャナも横断refで解決したID型を認識する")
        assertTrue(opened.validate("items/a.yaml").isEmpty())
    }

    @Test
    fun `未定義の横断refはロードエラーになる`() = withWorkspace { workspace ->
        write(
            workspace,
            "_schemas/item.yaml",
            """
            id: item
            name: 項目
            match: "items/**/*.yaml"
            root:
              type: object
              fields:
                - key: value
                  ${'$'}ref: missing#value
            """,
        )

        val loaded = SchemaLoader.loadAllWithErrors(workspace)
        assertTrue(loaded.errors.any { "missing#value" in it && "未定義の参照" in it }, loaded.errors.toString())
    }

    @Test
    fun `libraryにはdefinitionsが必要でmatchとrootを持てない`() = withWorkspace { workspace ->
        write(
            workspace,
            "_schemas/broken.yaml",
            """
            id: broken
            name: 壊れたライブラリ
            kind: library
            match: "**/*.yaml"
            """,
        )
        write(
            workspace,
            "_schemas/with-root.yaml",
            """
            id: with-root
            name: rootつきライブラリ
            kind: library
            definitions:
              value: { type: string }
            root: { type: object, fields: [] }
            """,
        )

        val loaded = SchemaLoader.loadAllWithErrors(workspace)
        assertTrue(loaded.errors.any { "definitions が必要" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "library スキーマに match" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "library スキーマに root" in it }, loaded.errors.toString())
    }
}
