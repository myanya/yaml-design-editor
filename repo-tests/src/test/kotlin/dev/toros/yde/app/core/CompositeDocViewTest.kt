package dev.toros.yde.app.core

import dev.toros.yde.cli.runCli
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
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
 * 複合文書ビュー（段階 1）を端から端まで: 物理テーブルの文書を開いたとき、同じ ID の概念の側面（別の文書）のフォームと、
 * その文書の ID を受け取る値にしたカラムの表が、同じ 1 枚に解決されること。CLI `view` も同じ合成を返す。
 */
class CompositeDocViewTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-composite-doc-view")
        write(
            "_schemas/business.yaml",
            """
            id: biz
            name: 業務モデル
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
                      - { key: name, type: string, role: title }
                      - { key: meaning, type: text }
            """,
        )
        write(
            "_schemas/physical.yaml",
            """
            id: phys
            name: 物理テーブル
            match: "model/physical/*.yaml"
            entity: { type: concept, idPattern: "ENT-*", defines: false, aspect: physical, owns: [physical_name, columns] }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: physical_name, type: string, role: title }
                - key: columns
                  type: array
                  relation: has-column
                  item:
                    type: object
                    entity: { type: column, defines: true }
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: physical_name, type: string, role: title }
            """,
        )
        write(
            "_views/columns.yaml",
            """
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
              - { label: 物理名, of: c, path: physical_name, editable: true }
            """,
        )
        write(
            "_views/physical-doc.yaml",
            """
            id: physical-doc
            name: 物理テーブル定義書
            kind: document
            schemaId: phys
            sections:
              - { label: 業務上の意味, fields: { in: { schemaId: biz }, include: [meaning] } }
              - { label: カラム定義, grid: { view: columns, params: { table: { path: id } } } }
              - { label: 物理, fields: { include: [physical_name] } }
            """,
        )
        write(
            "model/business.yaml",
            """
            entities:
              - { id: ENT-1, name: TODO, meaning: 個人が実施する作業 }
              - { id: ENT-2, name: カテゴリ, meaning: TODO を分類する区分 }
            """,
        )
        write(
            "model/physical/ENT-1.yaml",
            """
            id: ENT-1
            physical_name: todos
            columns:
              - { id: COL-1-A, physical_name: todo_id }
              - { id: COL-1-B, physical_name: title }
            """,
        )
        write("model/physical/ENT-2.yaml", "id: ENT-2\nphysical_name: categories\ncolumns:\n  - { id: COL-2-A, physical_name: category_id }\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `物理テーブルの文書に 概念の側面のフォームと その文書のカラムの表が合成される`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = assertNotNull(loaded.docView("physical-doc"))
        assertEquals(view, loaded.docViewFor("model/physical/ENT-1.yaml", "phys"))

        val sections = Views.resolveDocument(
            view, DocumentId("model/physical/ENT-1.yaml"), workspace.index,
            loader = workspace::treeOf, schemaFor = workspace::schemaFor, catalog = loaded,
        )
        // 概念の側面: 業務モデルの中の ENT-1 の要素（編集はその座標へ）
        val concept = sections[0]
        val occ = assertNotNull(concept.occurrence, concept.reason)
        assertEquals("model/business.yaml", occ.document.value)
        assertEquals(parseYPath("entities[0]"), occ.path)
        assertEquals("biz", occ.schemaId)
        assertEquals(occ.document, concept.origins.single().document)
        // 表: この文書の ID（ENT-1）を受け取る値にしたので、ENT-2 のカラムは混ざらない
        val grid = sections[1]
        assertEquals(mapOf<String, Any?>("table" to "ENT-1"), grid.gridParams)
        assertEquals(listOf("COL-1-A", "COL-1-B"), grid.gridRows!!.map { it.id }, grid.reason)
        assertEquals("model/physical/ENT-1.yaml", grid.gridRows!![0].cells[0].document?.value, "セルは原本の座標へ")
        // 開いた文書のフォーム区画は従来どおり（描画側が描く）
        assertTrue(sections[2].section.isLocalFields)

        val text = Views.renderDocText(view, "model/physical/ENT-1.yaml", sections)
        assertTrue("業務上の意味（フォーム: 同じ ID の別の出現）  [model/business.yaml :: entities[0]]（選別: meaning）" in text, text)
        assertTrue("カラム定義（表: columns。受け取る値: table=ENT-1）" in text, text)
        assertTrue("COL-1-A" in text && "COL-2-A" !in text, text)

        // ENT-2 の文書では、受け取る値も出現も ENT-2 のもの
        val other = Views.resolveDocument(
            view, DocumentId("model/physical/ENT-2.yaml"), workspace.index,
            loader = workspace::treeOf, schemaFor = workspace::schemaFor, catalog = loaded,
        )
        assertEquals(parseYPath("entities[1]"), other[0].occurrence?.path)
        assertEquals(listOf("COL-2-A"), other[1].gridRows!!.map { it.id })
        // 目録を渡さなければ表の区画は理由つきで解決しない（側面の区画は変わらない）
        val bare = Views.resolveDocument(view, DocumentId("model/physical/ENT-1.yaml"), workspace.index, loader = workspace::treeOf)
        assertEquals("表「columns」が無いか、行の供給元が渡されていません", bare[1].reason)
        assertNotNull(bare[0].occurrence)
    }

    @Test
    fun `CLI の view は 側面の座標と 表の行を返す`() {
        val out = runCli(arrayOf("view", ws.toString(), "physical-doc", "model/physical/ENT-1.yaml"))
        assertTrue("kind: document" in out, out)
        assertTrue("schemaId: biz" in out, out)
        assertTrue("file: model/business.yaml" in out, out)
        assertTrue("path: entities[0]" in out, out)
        assertTrue("view: columns" in out, out)
        assertTrue("table: ENT-1" in out, out)
        assertTrue("COL-1-A" in out && "COL-2-A" !in out, out)
    }
}
