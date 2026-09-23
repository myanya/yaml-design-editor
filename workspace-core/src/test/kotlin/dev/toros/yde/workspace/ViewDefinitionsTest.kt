package dev.toros.yde.workspace

import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ビュー定義の読み取りとリント（P27 段D の D-c で root の core/Views.kt から移した）が、カーネルだけで動くこと。
 * 書き方の細かなケースは、root の ViewsTest が、今までどおり保証する（root の core.Views は、ファイルを読んでここを呼ぶ窓口）。
 */
class ViewDefinitionsTest {

    private val screenSchema = """
        id: screen
        match: "screens/*.yaml"
        definitions:
          item:
            type: object
            fields:
              - { key: id, type: id, required: true }
              - { key: label, label: 表示名, type: string }
              - { key: required, type: boolean }
              - { key: notes, type: array, item: { type: string } }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: name, type: string }
            - key: items
              type: array
              item: { ${'$'}ref: item }
    """.trimIndent()

    private fun schemas() = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/screen.yaml" to screenSchema))).schemas

    private val grid = """
        id: screen-list
        name: 画面一覧
        rows: { idPattern: "SCR-*", schemaIds: [screen] }
        columns:
          - { label: 名前, schemaId: screen, path: name, editable: true }
        detail: { schemaId: screen, filePattern: "screens/{id}.yaml" }
    """.trimIndent()

    private val document = """
        id: screen-doc
        kind: document
        schemaId: screen
        sections:
          - label: 項目
            fields: { path: items, definition: item, include: [label, required] }
        attachments:
          - at: "items[*]"
            backlinks: { relation: uses }
    """.trimIndent()

    @Test
    fun `グリッドと文書ビューを読み取り 定義ファイル名順に並べる`() {
        val catalog = ViewDefinitions.load(
            listOf(DocumentId("_views/a-grid.yaml") to grid, DocumentId("_views/b-doc.yaml") to document),
            schemas(),
        )
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val view = assertNotNull(catalog.view("screen-list"))
        assertEquals(DocumentId("_views/a-grid.yaml"), view.source)
        assertEquals("screens/{id}.yaml", view.detail?.filePattern?.text)
        val doc = assertNotNull(catalog.docViewFor("screens/SCR-1.yaml", "screen"))
        assertEquals("screen#item", doc.sections.single().fieldsDefinition)
        assertTrue(doc.attachments.single().matches(listOf("items", 3)))
        assertEquals(null, catalog.docViewFor("screens/SCR-1.yaml", "other"))
    }

    @Test
    fun `definition を参照する列は 定義のセル型の項目へ展開される`() {
        val raw = YamlIO.parseToRaw(
            "id: items\nrows: { idPattern: \"IT-*\" }\ncolumns:\n  - { definition: \"screen#item\", schemaId: screen, editable: true }"
        ) as Map<*, *>
        val view = ViewDefinitions.parseView(raw, DocumentId("_views/items.yaml"), schemas()) as VirtualGridDef
        // ID の項目と、配列の項目は列にならない。ラベルはスキーマから
        assertEquals(listOf("表示名" to listOf<Any>("label"), "required" to listOf<Any>("required")), view.columns.map { it.label to it.path })
        assertTrue(view.columns.all { it.editable })
    }

    @Test
    fun `リントは スキーマから解決できない参照と 同じ ID の重複を報告する`() {
        val broken = """
            id: screen-list
            rows: { idPattern: "SCR-*", schemaIds: [nothing] }
            columns:
              - { label: 名前, schemaId: screen, path: title }
            newRow: { filePattern: "elsewhere/{id}.yaml" }
        """.trimIndent()
        val catalog = ViewDefinitions.load(
            listOf(DocumentId("_views/a-grid.yaml") to grid, DocumentId("_views/z-broken.yaml") to broken, DocumentId("_views/bad.yaml") to "- not-a-map"),
            schemas(),
        )
        val errors = catalog.errors
        assertTrue(errors.any { it == "bad.yaml: ルートはobjectである必要があります" }, errors.toString())
        assertTrue(errors.any { "ビューID「screen-list」が重複しています（a-grid.yaml, z-broken.yaml）" in it }, errors.toString())
        assertTrue(errors.any { it == "z-broken.yaml: rows.schemaIds のスキーマ「nothing」が見つかりません" }, errors.toString())
        assertTrue(errors.any { "z-broken.yaml: 列「名前」のパス「title」はスキーマ「screen」のID要素から解決できません" in it }, errors.toString())
        assertTrue(errors.any { "newRow.filePattern「elsewhere/{id}.yaml」の作成先にマッチするスキーマがありません" in it }, errors.toString())
        // 編集中の1件の検査（保存を待たない）も、同じリント
        val lint = ViewDefinitions.lintDocument(YamlIO.parse(broken), DocumentId("_views/z-broken.yaml"), schemas())
        assertEquals(3, lint.size, lint.toString())
    }
}
