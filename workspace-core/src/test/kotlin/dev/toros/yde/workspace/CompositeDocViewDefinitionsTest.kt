package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 複合文書ビュー（段階 1）の読み取りとリント: `fields.in`（同じ ID の別の出現）と `grid`（受け取る値つきの表の埋め込み）。
 */
class CompositeDocViewDefinitionsTest {

    private val businessSchema = """
        id: biz
        match: "model/business.yaml"
        root:
          type: object
          fields:
            - key: entities
              type: array
              item:
                type: object
                entity: { type: concept, defines: true, aspect: conceptual, owns: [name, meaning] }
                fields:
                  - { key: id, type: id, required: true }
                  - { key: name, type: string }
                  - { key: meaning, type: text }
    """.trimIndent()

    private val physicalSchema = """
        id: phys
        match: "model/physical/*.yaml"
        entity: { type: concept, idPattern: "ENT-*", defines: false, aspect: physical, owns: [physical_name, columns] }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: physical_name, type: string }
            - key: columns
              type: array
              relation: has-column
              item:
                type: object
                entity: { type: column, defines: true }
                fields:
                  - { key: id, type: id, required: true }
                  - { key: physical_name, type: string }
    """.trimIndent()

    private val grid = """
        id: columns
        name: カラム
        params: [table]
        rows:
          match:
            - { node: { entity: concept, bind: t } }
            - { edge: { relation: has-column } }
            - { node: { entity: column, bind: c } }
          where:
            - { of: t, path: id, equals: ${'$'}table }
          rowKey: c
        columns:
          - { label: 物理名, of: c, path: physical_name }
    """.trimIndent()

    private val doc = """
        id: physical-doc
        kind: document
        schemaId: phys
        sections:
          - { label: 概念, fields: { in: { schemaId: biz }, include: [meaning] } }
          - { label: カラム定義, grid: { view: columns, params: { table: { path: id } } } }
          - { label: 物理, fields: { include: [physical_name] } }
    """.trimIndent()

    private fun schemas() = WorkspaceReader.load(
        InMemoryDocumentStore(mapOf("_schemas/business.yaml" to businessSchema, "_schemas/physical.yaml" to physicalSchema)),
    ).schemas

    private fun load(vararg docs: Pair<String, String>) =
        ViewDefinitions.load(docs.map { (name, text) -> DocumentId("_views/$name.yaml") to text }, schemas())

    @Test
    fun `in と grid の区画を読み取り 表の params と文書の値の対応を持つ`() {
        val catalog = load("columns" to grid, "physical-doc" to doc)
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val view = assertNotNull(catalog.docView("physical-doc"))
        val concept = view.sections[0]
        assertEquals("biz", concept.fieldsIn?.schemaId)
        assertEquals(emptyList<Any>(), concept.fieldsPath, "in の区画の fieldsPath は空（別の出現のルート要素）")
        assertTrue(!concept.isLocalFields, "開いた文書のフォーム区画ではない")
        val columns = assertNotNull(view.sections[1].grid)
        assertEquals("columns", columns.view)
        assertEquals(mapOf("table" to listOf<Any>("id")), columns.params)
        assertTrue(view.sections[2].isLocalFields)
    }

    @Test
    fun `書き方の誤りは読み取りで拒む`() {
        fun errorOf(text: String): String = load("columns" to grid, "bad" to text).errors.single { "bad.yaml" in it }
        assertTrue("in と path は一緒に書けません" in errorOf(doc.replace("in: { schemaId: biz }", "in: { schemaId: biz }, path: entities")))
        assertTrue("schemaId か fileGlob が必要です" in errorOf(doc.replace("in: { schemaId: biz }", "in: {}")))
        assertTrue("fields / backlinks / grid" in errorOf("id: bad\nkind: document\nschemaId: phys\nsections:\n  - { fields: {}, grid: { view: columns } }"))
        assertTrue("grid.view が必要です" in errorOf("id: bad\nkind: document\nschemaId: phys\nsections:\n  - { grid: { params: {} } }"))
        assertTrue("{path: 文書の中のパス}" in errorOf(doc.replace("table: { path: id }", "table: id")))
        assertTrue("未知のキーです: limit" in errorOf(doc.replace("params: { table: { path: id } }", "params: { table: { path: id } }, limit: 3")))
        assertTrue("列の見出しの並び" in errorOf(doc.replace("params: { table: { path: id } }", "params: { table: { path: id } }, columns: 物理名")))
        assertTrue("見出し「物理名」が重複" in errorOf(doc.replace("params: { table: { path: id } }", "params: { table: { path: id } }, columns: [物理名, 物理名]")))
    }

    @Test
    fun `見せる列は表の列の見出しで選び 無い見出しは点検で知らせる`() {
        val withColumns = doc.replace("params: { table: { path: id } }", "params: { table: { path: id } }, columns: [物理名]")
        val catalog = load("columns" to grid, "physical-doc" to withColumns)
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        assertEquals(listOf("物理名"), catalog.docView("physical-doc")!!.sections[1].grid!!.columns)
        val errors = load("columns" to grid, "physical-doc" to withColumns.replace("columns: [物理名]", "columns: [物理名, 論理名]")).errors
        assertTrue(errors.any { "grid.columns「論理名」はビュー「columns」の列にありません（列: 物理名）" in it }, errors.toString())
    }

    @Test
    fun `読み込み時の点検は 表の存在と受け取る値の宣言を見て リントはスキーマとの整合を見る`() {
        // 表が無い・表でないビュー（文書ビュー）・宣言していない受け取る値
        val errors = load(
            "columns" to grid,
            "physical-doc" to doc,
            "bad1" to doc.replace("id: physical-doc", "id: bad1").replace("view: columns", "view: nosuch"),
            "bad2" to doc.replace("id: physical-doc", "id: bad2").replace("view: columns", "view: physical-doc"),
            "bad3" to doc.replace("id: physical-doc", "id: bad3").replace("table: { path: id }", "owner: { path: id }"),
            // リント: in のスキーマが無い・選別キーがそのスキーマに無い・受け取る値のパスが解決できない
            "bad4" to doc.replace("id: physical-doc", "id: bad4").replace("schemaId: biz", "schemaId: nothing"),
            "bad5" to doc.replace("id: physical-doc", "id: bad5").replace("include: [meaning]", "include: [nosuch]"),
            "bad6" to doc.replace("id: physical-doc", "id: bad6").replace("table: { path: id }", "table: { path: nosuch }"),
        ).errors
        assertTrue(errors.any { "bad1.yaml" in it && "grid.view「nosuch」は一意なグリッドかクロス集計" in it }, errors.toString())
        assertTrue(errors.any { "bad2.yaml" in it && "grid.view「physical-doc」は一意なグリッドかクロス集計" in it }, errors.toString())
        assertTrue(errors.any { "bad3.yaml" in it && "grid.params「owner」はビュー「columns」の params にありません（params: table）" in it }, errors.toString())
        assertTrue(errors.any { "bad4.yaml" in it && "in.schemaId「nothing」が見つかりません" in it }, errors.toString())
        assertTrue(errors.any { "bad5.yaml" in it && "選別キー「nosuch」はスキーマ「biz」の ID を持つ要素にありません" in it }, errors.toString())
        assertTrue(errors.any { "bad6.yaml" in it && "受け取る値「table」のパス「nosuch」はスキーマ「phys」から解決できません" in it }, errors.toString())
        assertTrue(errors.none { "physical-doc.yaml" in it }, errors.toString())
    }
}
