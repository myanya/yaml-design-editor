package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 複合文書ビュー（段階 1）の区画の解決。`fields.in`（同じ ID の別の出現）は変換の写し（`$copy` に `$of: self`）で評価され、
 * 出どころが原本の座標になること。`grid`（表の埋め込み）は開いた文書の値を受け取る値にして行を受け取ること。
 */
class CompositeDocViewTest {

    private val business = DocumentId("model/business.yaml")
    private val physical = DocumentId("model/physical/ENT-1.yaml")
    private val list = DocumentId("model/list.yaml")

    // ENT-1 は業務モデル（定義・概念の側面）と物理テーブル（詳細化・物理の側面）の 2 つの出現を持つ
    private val concept = ScannedId("ENT-1", business, listOf("entities", 0), "TODO", "biz", "model/business.yaml", role = EntityRole.DEFINING)
    private val table = ScannedId("ENT-1", physical, emptyList(), "todos", "phys", "model/physical/ENT-1.yaml", role = EntityRole.DETAIL)
    private val ids = mapOf("ENT-1" to listOf(concept, table))

    private val trees = mapOf(
        business.value to YamlIO.parse("entities:\n  - id: ENT-1\n    name: TODO\n    meaning: 個人が実施する作業\n"),
        physical.value to YamlIO.parse("id: ENT-1\nphysical_name: todos\n"),
        list.value to YamlIO.parse("items:\n  - id: X-1\n"),
    )

    private fun graphOf(): SemanticEntityGraph {
        val scans = ids.values.flatten().groupBy { it.document }.entries.sortedBy { it.key.value }
            .map { (d, occs) -> DocumentScan(d, d.value, occs, emptyList()) }
        return SemanticEntityGraph.build(scans, ids)
    }

    private val gridDef = VirtualGridDef(
        id = "columns", name = "カラム", columns = listOf(VirtualViewColumn(label = "物理名", path = listOf("physical_name"))),
        source = DocumentId("_views/columns.yaml"), params = listOf("table"),
    )

    private fun view(vararg sections: VirtualDocSection) = VirtualDocViewDef(
        id = "doc", name = "物理テーブル定義書", schemaId = "phys", sections = sections.toList(), source = DocumentId("_views/doc.yaml"),
    )

    private fun resolve(view: VirtualDocViewDef, document: DocumentId, received: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()) =
        VirtualViews.resolveDocument(
            view, document, ids, emptyList(), graphOf(), localIds = null, rel = { it.value }, tree = { trees[it.value] },
            gridRows = { id, params ->
                received += id to params
                if (id != gridDef.id) null else gridDef to listOf(
                    VirtualRow(
                        id = "COL-1", title = "todo_id", occurrences = listOf(table),
                        cells = listOf(VirtualCell(gridDef.columns[0], physical, listOf("columns", 0, "physical_name"), YNode.Scalar("todo_id"))),
                        detailDocument = null, detailPath = null,
                    ),
                )
            },
        )

    @Test
    fun `同じ ID の別の出現の区画は 出現の座標と 写しの項と 出どころを持つ`() {
        val section = VirtualDocSection(label = "概念", fieldsPath = emptyList(), fieldsInclude = listOf("meaning"), fieldsIn = DerivationSelector(schemaId = "biz"))
        val resolved = resolve(view(section), physical).single()
        val occ = assertNotNull(resolved.occurrence, resolved.reason)
        assertEquals(business, occ.document)
        assertEquals(listOf<Any>("entities", 0), occ.path)
        // 変換の写し: 項は出現の要素の写しで、出どころは出現の座標（出力の場所は区画のルート）
        val term = assertNotNull(resolved.term) as YNode.Map
        assertEquals("個人が実施する作業", scalarText(term.entries["meaning"]))
        val origin = resolved.origins.single()
        assertEquals(business, origin.document)
        assertEquals(listOf<Any>("entities", 0), origin.path)
        assertTrue(origin.output.isEmpty())
        assertNull(resolved.rows, "被参照の行は無い")
        val text = VirtualViews.renderDocText(view(section), physical.value, listOf(resolved), relOf = { it.value })
        assertTrue("（フォーム: 同じ ID の別の出現）  [model/business.yaml :: entities[0]]（選別: meaning）" in text, text)
    }

    @Test
    fun `出現が無い・ルートに ID が無いときは理由つきで解決しない`() {
        val section = VirtualDocSection(label = "論理", fieldsPath = emptyList(), fieldsIn = DerivationSelector(schemaId = "logical"))
        val missing = resolve(view(section), physical).single()
        assertNull(missing.occurrence)
        assertEquals("同じ ID「ENT-1」の出現がスキーマ「logical」の文書にありません", missing.reason)
        // 一覧文書（ルートに ID が無い）には、側面の区画は出せない
        val noRoot = resolve(view(section), list).single()
        assertNull(noRoot.occurrence)
        assertTrue(noRoot.reason!!.startsWith("この文書のルートに ID がありません"), noRoot.reason)
        val text = VirtualViews.renderDocText(view(section), list.value, listOf(noRoot), relOf = { it.value })
        assertTrue("（この文書のルートに ID がありません" in text, text)
    }

    @Test
    fun `表の区画は 開いた文書の値を受け取る値にして行を受け取り 値が無ければ理由を残す`() {
        val grid = VirtualDocSection(label = "カラム定義", grid = VirtualDocGrid("columns", mapOf("table" to listOf("id"))))
        val received = mutableListOf<Pair<String, Map<String, Any?>>>()
        val resolved = resolve(view(grid), physical, received).single()
        assertEquals(listOf("columns" to mapOf<String, Any?>("table" to "ENT-1")), received, "表の ID と、文書から読んだ受け取る値")
        assertEquals(mapOf<String, Any?>("table" to "ENT-1"), resolved.gridParams)
        assertEquals(listOf("COL-1"), resolved.gridRows!!.map { it.id })
        assertEquals(gridDef, resolved.gridView)
        // 行はビューの行のひな型（$view）で項になる（レポートと同じ形）
        val rows = assertNotNull(resolved.term) as YNode.Seq
        assertEquals("COL-1", scalarText((rows.items.single() as YNode.Map).entries["id"]))
        assertTrue(resolved.origins.isEmpty(), "行は派生なので出どころは無い")
        val text = VirtualViews.renderDocText(view(grid), physical.value, listOf(resolved), relOf = { it.value })
        assertTrue("（表: columns。受け取る値: table=ENT-1）" in text, text)
        assertTrue("todo_id" in text, text)

        // 受け取る値の元の項目が無い文書では、行を求めずに理由を残す
        val noValue = resolve(view(grid), list).single()
        assertNull(noValue.gridRows)
        assertEquals("受け取る値「table」の元の項目「id」に値がありません", noValue.reason)
        // 表が無い（供給元が null）
        val unknown = resolve(view(VirtualDocSection(grid = VirtualDocGrid("nosuch"))), physical).single()
        assertNull(unknown.gridRows)
        assertEquals("表「nosuch」が無いか、行の供給元が渡されていません", unknown.reason)
    }
}
