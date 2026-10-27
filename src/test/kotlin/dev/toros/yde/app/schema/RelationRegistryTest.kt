package dev.toros.yde.app.schema

import dev.toros.yde.cli.commands.inferRelationsDraft
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.semantic.schema.SchemaKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 関係レジストリ（R20-02。architecture/graph-query.md「エンティティ型と関係レジストリ」）のテスト。
 * - `kind: relations` のパースと定義検証（重複・match 禁止・cardinality）
 * - LoadResult.relations の集約と、未宣言 relation の lint（レジストリがあるときだけ）
 * - schemaFor の kind ディスパッチ（builtin-relations）
 * - 表示ラベルの解決（labelOf / inverseLabelOf。未宣言は名前のまま）
 * - infer-relations のドラフトが定義検証を通る（入口保証）
 */
class RelationRegistryTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-relations")
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

    private val registryYaml = """
        id: workspace-relations
        kind: relations
        relations:
          - name: uses
            label: 利用する
            inverseLabel: 利用される
            source: [screen]
            target: [usecase]
            cardinality: { inMin: 1 }
            acyclic: true
            description: 画面がユースケースを利用する
          - name: derives
            label: 由来する
    """.trimIndent()

    @Test
    fun `kind relationsのパースと文書非適用`() {
        val doc = SchemaLoader.parseSchemaText(registryYaml, Path.of("_schemas/relations.yaml"))
        assertEquals(SchemaKind.RELATIONS, doc.kind)
        assertEquals(emptyList(), doc.validateDefinition())
        val uses = doc.relations.first { it.name == "uses" }
        assertEquals("利用する", uses.label)
        assertEquals("利用される", uses.inverseLabel)
        assertEquals(listOf("screen"), uses.source)
        assertEquals(1, uses.cardinality?.inMin)
        assertTrue(uses.acyclic)
        // レジストリ文書はどの文書にもマッチしない（kind: library と同じ）
        assertFalse(doc.matches("docs/a.yaml"))
    }

    @Test
    fun `定義検証が重複nameとmatchと不正なcardinalityを指摘する`() {
        val bad = SchemaLoader.parseSchemaText(
            """
            id: r
            kind: relations
            match: "docs/**"
            relations:
              - name: uses
              - name: uses
              - name: bad
                cardinality: { inMin: 3, inMax: 1 }
            """.trimIndent(),
            Path.of("_schemas/r.yaml"),
        )
        val issues = bad.validateDefinition().map { it.message }
        assertTrue(issues.any { "match は定義できません" in it }, issues.toString())
        assertTrue(issues.any { "関係「uses」が重複" in it }, issues.toString())
        assertTrue(issues.any { "inMin が inMax を超えています" in it }, issues.toString())
    }

    @Test
    fun `レジストリの集約と未宣言relationのlint`() {
        write("_schemas/relations.yaml", registryYaml)
        write("_schemas/screen.yaml", """
            id: screen
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: usecase
                  type: ref
                  relation: uses
                - key: parent
                  type: ref
                  relation: belongs-to
        """)
        val ws = Workspace.open(dir)
        assertEquals(setOf("uses", "derives"), ws.relations.keys)
        // uses は宣言済み・belongs-to だけが lint に出る
        assertTrue(ws.schemaErrors.any { "belongs-to" in it && "宣言されていません" in it }, ws.schemaErrors.toString())
        assertFalse(ws.schemaErrors.any { "「uses」" in it })
        // レジストリ文書には builtin-relations が適用される（kind ディスパッチ）
        assertEquals(BuiltinSchemas.RELATIONS_SCHEMA_ID, ws.schemaFor(dir.resolve("_schemas/relations.yaml"))?.id)
        assertEquals(BuiltinSchemas.META_SCHEMA_ID, ws.schemaFor(dir.resolve("_schemas/screen.yaml"))?.id)
    }

    @Test
    fun `レジストリがなければ未宣言relationのlintは出ない`() {
        write("_schemas/screen.yaml", """
            id: screen
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: usecase
                  type: ref
                  relation: uses
        """)
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        assertEquals(emptyMap(), ws.relations)
    }

    @Test
    fun `レジストリ間のname重複はロードエラーになる`() {
        write("_schemas/r1.yaml", "id: r1\nkind: relations\nrelations:\n  - name: uses")
        write("_schemas/r2.yaml", "id: r2\nkind: relations\nrelations:\n  - name: uses")
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "複数のレジストリで宣言" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `表示ラベルの解決は未宣言なら名前のまま`() {
        val relations = SchemaLoader.parseSchemaText(registryYaml, Path.of("_schemas/r.yaml"))
            .relations.associateBy { it.name }
        assertEquals("利用する", Relations.labelOf(relations, "uses"))
        assertEquals("利用される", Relations.inverseLabelOf(relations, "uses"))
        // inverseLabel 省略時は label → name の順でフォールバック
        assertEquals("由来する", Relations.inverseLabelOf(relations, "derives"))
        assertEquals("unknown", Relations.labelOf(relations, "unknown"))
        assertEquals(null, Relations.labelOf(relations, null))
    }

    @Test
    fun `infer-relationsのドラフトが定義検証を通りレジストリとして効く`() {
        write("_schemas/model.yaml", """
            id: model
            match: "models/**/*.yaml"
            entity: { type: entity-model, idPattern: "ENT-*", defines: true }
            root:
              type: object
              fields:
                - key: id
                  type: id
        """)
        write("_schemas/table.yaml", """
            id: table
            match: "tables/**/*.yaml"
            entity: { type: table-design, idPattern: "TBL-*", defines: true }
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: entity
                  type: ref
                  relation: derives
        """)
        write("models/m.yaml", "id: ENT-TASK")
        write("tables/t.yaml", "id: TBL-TASK\nentity: ENT-TASK")
        val ws = Workspace.open(dir)
        val draft = inferRelationsDraft(ws)
        // 実績（viaKey・参照元／先のエンティティ型）が集計されている
        assertTrue("name: \"derives\"" in draft, draft)
        assertTrue("source: [\"table-design\"]" in draft, draft)
        assertTrue("target: [\"entity-model\"]" in draft, draft)
        // 保存するとレジストリとして効く（入口保証: パース・定義検証を通る形）
        write("_schemas/workspace-relations.yaml", draft)
        ws.refresh()
        assertTrue("derives" in ws.relations)
        assertEquals(emptyList(), ws.schemaErrors, ws.schemaErrors.toString())
    }

    @Test
    fun `infer-relationsのドラフトに保有の線も載る`() {
        write("_schemas/screen.yaml", """
            id: screen
            match: "screens/*.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*", defines: true }
              - { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - key: items
                  type: array
                  relation: has-item
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                - key: extras
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
        """)
        write("screens/SCR-1.yaml", "id: SCR-1\nitems:\n  - id: SI-1\n  - id: SI-2\nextras:\n  - id: SI-9")
        val ws = Workspace.open(dir)
        val draft = inferRelationsDraft(ws)
        assertTrue("name: \"has-item\"" in draft, draft)
        assertTrue("name: \"extras\"" in draft, "宣言の無い保有はキー名で載る: $draft")
        assertTrue("保有 2 件" in draft, draft)
        assertTrue("source: [\"screen\"]" in draft && "target: [\"screen-item\"]" in draft, draft)
        write("_schemas/workspace-relations.yaml", draft)
        ws.refresh()
        assertEquals(emptyList(), ws.schemaErrors, ws.schemaErrors.toString())
    }

    @Test
    fun `relationはrefと要素を書く項目にだけ書ける`() {
        val parsed = SchemaLoader.parseSchemaText(
            """
            id: bad
            match: "x/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: name, type: string, relation: oops }
                - key: parts
                  type: array
                  relation: has-part
                  item: { type: object, fields: [{ key: id, type: id }] }
            """.trimIndent(),
            Path.of("_schemas/bad.yaml"),
        )
        val issues = parsed.validateDefinition()
        assertEquals(1, issues.size, issues.toString())
        assertTrue("relation は ref か" in issues.single().message, issues.single().message)
    }
}
