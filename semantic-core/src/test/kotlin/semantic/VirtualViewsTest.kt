package semantic

import model.DocumentId
import model.YNode
import model.scalarText
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 仮想ビュー（グリッド）カーネルの契約テスト（6E 第1弾・RC-01）。
 * 行×セルの解決がすべて原本座標（DocumentId × YPath）＝来歴つきで返ることを固定する。
 * ルート経由の全機能回帰（クエリ行・計算列含む）は ViewsTest がシェル越しに担保する。
 */
class VirtualViewsTest {

    private val aDoc = DocumentId("items/a.yaml")
    private val bDoc = DocumentId("items/b.yaml")

    private val aId = ScannedId("IT-001", aDoc, emptyList(), "A", "item", "items/a.yaml")
    private val bId = ScannedId("IT-002", bDoc, emptyList(), "B", "item", "items/b.yaml")
    private val bRef = ScannedRef(
        targetId = "IT-001", document = bDoc, path = listOf("link"), viaKey = "link",
        relation = "uses", sourceSchemaId = "item", attributes = mapOf("role" to "承認者"),
    )

    private val trees = mapOf(
        "items/a.yaml" to YamlIO.parse("id: IT-001\ntitle: A\nstatus: open"),
        "items/b.yaml" to YamlIO.parse("id: IT-002\ntitle: B\nlink: IT-001"),
    )

    private fun graphOf(ids: Map<String, List<ScannedId>>, refs: List<ScannedRef>): SemanticEntityGraph {
        val byDoc = ids.values.flatten().groupBy { it.document }
        val refsByDoc = refs.groupBy { it.document }
        val scans = (byDoc.keys + refsByDoc.keys).sortedBy { it.value }.map { d ->
            DocumentScan(d, byDoc[d]?.firstOrNull()?.documentId ?: d.value, byDoc[d].orEmpty(), refsByDoc[d].orEmpty())
        }
        return SemanticEntityGraph.build(scans, ids)
    }

    private fun resolve(view: VirtualGridDef, ids: Map<String, List<ScannedId>>, refs: List<ScannedRef>) =
        VirtualViews.resolve(view, ids, refs, graphOf(ids, refs), rel = { it.value }, tree = { trees[it.value] })

    @Test
    fun `行とセルが原本座標つきで解決されbacklinks列は最内IDと属性注記を持つ`() {
        val ids = mapOf("IT-001" to listOf(aId), "IT-002" to listOf(bId))
        val view = VirtualGridDef(
            id = "v", name = "一覧",
            rows = VirtualRowsDef(idPattern = "IT-*"),
            columns = listOf(
                VirtualViewColumn(label = "状態", path = listOf("status")),
                VirtualViewColumn(label = "被参照", path = emptyList(), backlinks = VirtualBacklinksDef(relation = "uses")),
            ),
            source = DocumentId("_views/v.yaml"),
        )
        val rows = resolve(view, ids, listOf(bRef))
        assertEquals(listOf("IT-001", "IT-002"), rows.map { it.id }, "既定はID順")

        val statusCell = rows[0].cells[0]
        assertEquals(aDoc, statusCell.document, "セルの来歴＝原本文書")
        assertEquals(listOf<Any>("status"), statusCell.path)
        assertEquals("open", scalarText(statusCell.value))

        val backlinkCell = rows[0].cells[1]
        val backlink = backlinkCell.backlinks!!.single()
        assertEquals("IT-002", backlink.sourceId, "参照元の最内ID")
        assertEquals(bDoc, backlink.document)
        assertEquals(listOf<Any>("link"), backlink.path, "参照値の座標＝ジャンプ先")
        assertEquals("role: 承認者", backlink.attributes)
        assertTrue(rows[1].cells[1].backlinks!!.isEmpty(), "IT-002 への被参照はない")

        // IT-002 に status キーはない＝値なしだが座標（編集先）は解決される
        val missing = rows[1].cells[0]
        assertEquals(bDoc, missing.document)
        assertNull(missing.value)
    }

    @Test
    fun `orderByは出現の文書内順でschemaIdsは行を絞る`() {
        val z = ScannedId("ZZ-01", aDoc, emptyList(), null, "item", "items/a.yaml")
        val a2 = ScannedId("AA-02", bDoc, emptyList(), null, "item", "items/b.yaml")
        val other = ScannedId("XX-03", DocumentId("notes/x.yaml"), emptyList(), null, "note", "notes/x.yaml")
        val ids = mapOf("ZZ-01" to listOf(z), "AA-02" to listOf(a2), "XX-03" to listOf(other))
        fun view(orderBy: VirtualViewSource?) = VirtualGridDef(
            id = "v", name = "一覧",
            rows = VirtualRowsDef(idPattern = "*", schemaIds = listOf("item")),
            columns = listOf(VirtualViewColumn(label = "T", path = listOf("title"))),
            orderBy = orderBy,
            source = DocumentId("_views/v.yaml"),
        )
        assertEquals(
            listOf("AA-02", "ZZ-01"),
            resolve(view(null), ids, emptyList()).map { it.id },
            "schemaIds で XX-03 が除外され、既定はID順",
        )
        assertEquals(
            listOf("ZZ-01", "AA-02"),
            resolve(view(VirtualViewSource(fileGlob = "items/*")), ids, emptyList()).map { it.id },
            "orderBy はファイル順（a.yaml → b.yaml）",
        )
    }

    @Test
    fun `文書ビューは被参照表を原本座標つきで解決しアタッチはat一致要素だけを行にする`() {
        val spec = DocumentId("specs/s.yaml")
        val task = DocumentId("tasks/t.yaml")
        val specRoot = ScannedId("SP-1", spec, emptyList(), "仕様", "spec", "specs/s.yaml")
        val specItem = ScannedId("SP-1-A", spec, listOf("items", 0), "項目A", "spec", "specs/s.yaml")
        val taskId = ScannedId("T-1", task, emptyList(), "実装タスク", "task", "tasks/t.yaml")
        val ref = ScannedRef(
            targetId = "SP-1-A", document = task, path = listOf("ref"), viaKey = "ref",
            relation = "impl", sourceSchemaId = "task",
        )
        val ids = mapOf("SP-1" to listOf(specRoot), "SP-1-A" to listOf(specItem), "T-1" to listOf(taskId))
        val docTrees = mapOf("tasks/t.yaml" to YamlIO.parse("id: T-1\nstatus: doing\nref: SP-1-A"))
        val backlinksDef = VirtualDocBacklinksDef(
            filter = VirtualBacklinksDef(relation = "impl"),
            columns = listOf(VirtualDocBacklinkColumn("状態", listOf("status"))),
        )
        val view = VirtualDocViewDef(
            id = "dv", name = "文書ビュー",
            sections = listOf(
                VirtualDocSection(label = "本文", fieldsPath = emptyList()),
                VirtualDocSection(label = "被参照", backlinks = backlinksDef),
            ),
            attachments = listOf(
                VirtualDocAttachment(atText = "items[*]", at = listOf("items", VirtualAnyIndex), backlinks = backlinksDef),
            ),
            source = DocumentId("_views/dv.yaml"),
        )
        val graph = graphOf(ids, listOf(ref))
        val sections = VirtualViews.resolveDocument(
            view, spec, ids, listOf(ref), graph, localIds = null,
            rel = { it.value }, tree = { docTrees[it.value] },
        )
        assertNull(sections[0].rows, "fields セクションは描画側の担当")
        val rows = sections[1].rows!!
        assertEquals(listOf("SP-1", "SP-1-A"), rows.map { it.id }, "文書内パス順")
        val backlink = rows[1].backlinks.single()
        assertEquals("T-1", backlink.sourceId)
        assertEquals(task, backlink.document)
        assertEquals(listOf<Any>("ref"), backlink.refPath, "参照値の座標＝ジャンプ先")
        assertEquals("doing", scalarText(backlink.values.single()), "参照元要素からの列値")
        assertTrue(rows[0].backlinks.isEmpty(), "SP-1 への impl 被参照はない")

        // アタッチは at（items[*]）一致要素だけが行になる
        val attachments = VirtualViews.resolveAttachments(
            view, spec, ids, listOf(ref), graph, localIds = null,
            rel = { it.value }, tree = { docTrees[it.value] },
        )
        assertEquals(listOf("SP-1-A"), attachments.single().rows.map { it.id })

        // localIds（未保存編集の文書内ID）を渡すと行だけがそれへ追随する
        val local = VirtualViews.resolveDocument(
            view, spec, ids, listOf(ref), graph,
            localIds = listOf(specItem.copy(id = "SP-1-B", path = listOf("items", 1))),
            rel = { it.value }, tree = { docTrees[it.value] },
        )
        assertEquals(listOf("SP-1-B"), local[1].rows!!.map { it.id })
    }

    @Test
    fun `renderTextは座標つきの機械可読テキストでref値へタイトルを注記する`() {
        val ids = mapOf("IT-001" to listOf(aId), "IT-002" to listOf(bId))
        val view = VirtualGridDef(
            id = "v", name = "一覧",
            rows = VirtualRowsDef(idPattern = "IT-*"),
            columns = listOf(
                VirtualViewColumn(label = "リンク", path = listOf("link"), editable = true),
                VirtualViewColumn(label = "被参照", path = emptyList(), backlinks = VirtualBacklinksDef(relation = "uses")),
            ),
            newRow = VirtualNewRowDef(file = "items/list.yaml", path = listOf("items")),
            source = DocumentId("_views/v.yaml"),
        )
        val rows = resolve(view, ids, listOf(bRef))
        val text = VirtualViews.renderText(view, rows, ids = ids, rel = { it.value })
        assertTrue("新規行: items/list.yaml :: items へ要素を追加し、IDを設定すると行になる" in text, "新規行の作り方の案内:\n$text")
        assertTrue("リンク: IT-001（A）  [items/b.yaml :: link  編集可]" in text, "ref値のタイトル注記＋原本座標＋編集可:\n$text")
        assertTrue("リンク: （未設定）  [items/a.yaml :: link  編集可]" in text, "値なしでも座標（編集先）は出る:\n$text")
        assertTrue("被参照: IT-002 B（role: 承認者）  [items/b.yaml :: link]" in text, "被参照の最内ID・属性注記・参照値の座標:\n$text")
    }

    @Test
    fun `renderDocTextは被参照表を参照値の座標つきで列挙する`() {
        val spec = DocumentId("specs/s.yaml")
        val task = DocumentId("tasks/t.yaml")
        val specItem = ScannedId("SP-1-A", spec, listOf("items", 0), "項目A", "spec", "specs/s.yaml")
        val taskId = ScannedId("T-1", task, emptyList(), "実装タスク", "task", "tasks/t.yaml")
        val ref = ScannedRef(
            targetId = "SP-1-A", document = task, path = listOf("ref"), viaKey = "ref",
            relation = "impl", sourceSchemaId = "task",
        )
        val ids = mapOf("SP-1-A" to listOf(specItem), "T-1" to listOf(taskId))
        val docTrees = mapOf("tasks/t.yaml" to YamlIO.parse("id: T-1\nstatus: doing\nref: SP-1-A"))
        val backlinksDef = VirtualDocBacklinksDef(
            filter = VirtualBacklinksDef(relation = "impl"),
            columns = listOf(VirtualDocBacklinkColumn("状態", listOf("status"))),
        )
        val view = VirtualDocViewDef(
            id = "dv", name = "文書ビュー",
            sections = listOf(
                VirtualDocSection(label = "本文", fieldsPath = emptyList()),
                VirtualDocSection(label = "被参照", backlinks = backlinksDef),
            ),
            attachments = listOf(
                VirtualDocAttachment(atText = "items[*]", at = listOf("items", VirtualAnyIndex), backlinks = backlinksDef),
            ),
            source = DocumentId("_views/dv.yaml"),
        )
        val graph = graphOf(ids, listOf(ref))
        val sections = VirtualViews.resolveDocument(
            view, spec, ids, listOf(ref), graph, localIds = null,
            rel = { it.value }, tree = { docTrees[it.value] },
        )
        val attachments = VirtualViews.resolveAttachments(
            view, spec, ids, listOf(ref), graph, localIds = null,
            rel = { it.value }, tree = { docTrees[it.value] },
        )
        val text = VirtualViews.renderDocText(view, "specs/s.yaml", sections, attachments, relOf = { it.value })
        assertTrue("文書ビュー: 文書ビュー（dv） 対象: specs/s.yaml" in text, "ヘッダ:\n$text")
        assertTrue("セクション1: 本文（フォーム）  [specs/s.yaml :: (root)]" in text, "fieldsセクションは対象座標のみ:\n$text")
        assertTrue("セクション2: 被参照（被参照表）" in text, "backlinksセクションの見出し:\n$text")
        assertTrue("- SP-1-A  項目A  [specs/s.yaml :: items[0]]" in text, "行＝文書内IDと原本座標:\n$text")
        assertTrue("← T-1 実装タスク  [tasks/t.yaml :: ref]  状態: doing" in text, "被参照＝参照値の座標と参照元要素の列値:\n$text")
        assertTrue("アタッチ: 被参照（at: items[*]）" in text, "アタッチの見出し:\n$text")
    }

    @Test
    fun `値ノードは原本ツリーの参照でsnapshotには保持されない`() {
        val ids = mapOf("IT-001" to listOf(aId))
        val view = VirtualGridDef(
            id = "v", name = "一覧",
            rows = VirtualRowsDef(idPattern = "IT-*"),
            columns = listOf(VirtualViewColumn(label = "状態", path = listOf("status"))),
            source = DocumentId("_views/v.yaml"),
        )
        val cell = resolve(view, ids, emptyList()).single().cells.single()
        val original = model.getAt(trees.getValue("items/a.yaml"), listOf("status"))
        assertTrue(cell.value === original, "エフェメラル読み出しモデル＝原本ノードの参照（写しではない）")
    }
}
