package schema

import core.Workspace
import index.IdIndex
import validate.Severity
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 組込みメタスキーマ（R11-02）のテスト。
 * - すべての組込みスキーマ・実用形のスキーマ文書がメタスキーマ検証（エラー0）を通る（ドッグフーディング）
 * - `_schemas/` にはメタスキーマだけが適用される（catch-all スキーマの誤適用防止・同IDでの上書き）
 * - `any` 型（R11-02 で追加）は検証を行わない明示であり、構造化出力を無効化する
 */
class MetaSchemaTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-meta")
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

    private val meta: SchemaDef by lazy {
        val all = BuiltinSchemas.all
        val catalog = SchemaCatalog.linked(all.map { it.id to it.definitions })
        all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }.withDefinitionCatalog(catalog)
    }

    private fun metaErrors(schemaYaml: String): List<String> =
        Validator.validate(YamlIO.parse(schemaYaml), null, Path.of("_schemas/x.yaml"), meta, IdIndex())
            .filter { it.severity == Severity.ERROR }
            .map { it.message }

    // ---------------------------------------------------------------- ドッグフーディング

    @Test
    fun `all builtin schemas pass meta validation without errors`() {
        BuiltinSchemas.allYamlTexts.forEach { text ->
            assertEquals(emptyList(), metaErrors(text), text.lineSequence().first())
        }
    }

    @Test
    fun `builtin schemas pass their own definition validation`() {
        BuiltinSchemas.all.forEach { schema ->
            assertEquals(emptyList(), schema.validateDefinition(), schema.id)
        }
    }

    @Test
    fun `realistic schema document passes meta validation`() {
        // 実用形のスキーマが使う記法を網羅する: phases・coverage・requiredFrom・$ref・cases・
        // 単一文字列の idPatterns・リッチ enum 選択肢・型混在の default・derive 宣言
        val realistic = """
            id: table-design
            name: テーブル設計
            description: 段階的詳細化のサンプル
            match: "tables/**/*.{yaml,yml}"
            phases:
              key: phase
              order: [requirements, logical-design]
            definitions:
              note:
                type: object
                fields:
                  - key: text
                    type: text
            coverage:
              - description: 全IDは詳細文書を持つこと
                idPattern: "TBL-*"
                detailIn:
                  schemaIds: [detail-schema]
                  minCount: 1
                level: warning
                activeFrom: logical-design
              - detailIn:
                  schemaIds: detail-schema
                level: error
            root:
              type: object
              fields:
                - key: id
                  type: id
                  required: true
                - key: phase
                  type: enum
                  values: [requirements, logical-design]
                  default: requirements
                - key: status
                  type: enum
                  values:
                    - draft
                    - { value: done, label: 完了, description: 対応が終わった状態 }
                - key: enabled
                  type: boolean
                  default: false
                - key: entity
                  type: ref
                  idPattern: "ENT-*"
                  relation: derives
                  requiredFrom: requirements
                - key: owners
                  type: array
                  item:
                    type: ref
                    idPatterns: "USR-*"
                    scope:
                      schemaIds: [user]
                - key: kind
                  type: enum
                  values: [simple, detailed]
                  cases:
                    detailed:
                      - key: detailNote
                        ${"$"}ref: note
                - key: extras
                  type: map
                  value:
                    type: string
                - key: memo
                  ${"$"}ref: note
            """.trimIndent()
        assertEquals(emptyList(), metaErrors(realistic))

        val deriveDecl = """
            id: record-template
            name: レコード検証
            derive:
              from: "tables/**/*.yaml"
              id: "record-{id}"
              match: "records/{id}/**/*.yaml"
              fields:
                source: columns
                key: name
                type:
                  path: dataType
                  map: { varchar: string }
                  default: string
            root:
              type: object
              fields:
                - key: id
                  type: id
            """.trimIndent()
        assertEquals(emptyList(), metaErrors(deriveDecl))
    }

    // ---------------------------------------------------------------- `_schemas/` への適用規則

    @Test
    fun `meta schema applies to schema files and catch-all schemas do not`() {
        write("_schemas/catch-all.yaml", """
            id: catch-all
            match: "**/*.{yaml,yml}"
            root:
              type: object
              fields:
                - { key: id, type: id }
        """)
        write("docs/a.yaml", "id: DOC-A")
        val ws = Workspace.open(dir)
        // 一般文書には catch-all が効く
        assertEquals("catch-all", ws.schemaFor(dir.resolve("docs/a.yaml"))?.id)
        // `_schemas/` にはメタスキーマだけが適用される（catch-all は及ばない）
        assertEquals(BuiltinSchemas.META_SCHEMA_ID, ws.schemaFor(dir.resolve("_schemas/catch-all.yaml"))?.id)
        // スキーマ文書の検証がメタスキーマで行える（エラー0）
        assertEquals(
            emptyList(),
            ws.validate("_schemas/catch-all.yaml").filter { it.severity == Severity.ERROR },
        )
    }

    @Test
    fun `user can override the meta schema by id`() {
        write("_schemas/my-meta.yaml", """
            id: ${BuiltinSchemas.META_SCHEMA_ID}
            name: 独自メタスキーマ
            match: "_schemas/**/*.{yaml,yml}"
            root:
              type: object
              unknownFields: allow
              fields:
                - { key: id, type: string }
        """)
        val ws = Workspace.open(dir)
        val applied = ws.schemaFor(dir.resolve("_schemas/my-meta.yaml"))
        assertNotNull(applied)
        assertEquals("独自メタスキーマ", applied.name)
    }

    @Test
    fun `broken schema document is flagged by meta validation`() {
        write("_schemas/broken.yaml", """
            id: broken
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: status
                  type: nonsense
        """)
        val ws = Workspace.open(dir)
        val issues = ws.validate("_schemas/broken.yaml")
        // type の値が選択肢外であることをメタスキーマ検証が指摘する（パースエラーは schemaErrors 側にも出る）
        assertTrue(issues.any { it.severity == Severity.ERROR && "nonsense" in it.message }, issues.toString())
        assertTrue(ws.schemaErrors.any { "broken.yaml" in it }, ws.schemaErrors.toString())
    }

    // ---------------------------------------------------------------- any 型（R11-02）

    @Test
    fun `any type accepts every structure and only enforces required`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: any-test
            match: "*.yaml"
            root:
              type: object
              fields:
                - { key: freeform, type: any }
                - { key: mandatory, type: any, required: true }
            """.trimIndent(),
            Path.of("_schemas/any-test.yaml"),
        )
        fun errorsOf(yaml: String) =
            Validator.validate(YamlIO.parse(yaml), null, Path.of("a.yaml"), schema, IdIndex())
                .filter { it.severity == Severity.ERROR }
        // どんな構造でも検証エラー・警告を出さない
        assertEquals(emptyList(), errorsOf("freeform: 文字列\nmandatory: 1"))
        assertEquals(emptyList(), errorsOf("freeform: [1, 2]\nmandatory: { nested: true }"))
        // 必須チェックだけは効く
        assertTrue(errorsOf("freeform: x").any { "mandatory" in it.message })
        // any を含む生成対象は構造化出力を無効化する（map と同じ扱い）
        assertTrue(ai.containsMap(schema.root, schema))
    }
}
