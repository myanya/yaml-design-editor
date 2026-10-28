package dev.toros.yde.desktop.ui

import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.core.DocViewSection
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Path
import kotlin.test.*

class FormOutlineTest {
    @Test
    fun `操作対象は明示選択した項目の所有者で一覧の先頭を推測しない`() {
        val root = YamlIO.parse("rows: [{code: A, title: 一}, {code: B, title: 二}]")
        val ids = DocScanner.scan(root, DocumentId("test.yaml"), schema).first
        assertEquals("B", actionTargetAt(ids, listOf("rows", 1, "title")))
        assertNull(actionTargetAt(ids, listOf("rows")))
        assertNull(activeActionTarget(ids, null))
        assertEquals("B", activeActionTarget(ids.reversed(), "B"))
        assertNull(activeActionTarget(ids.filter { it.id != "B" }, "B"))
    }
    private val schema = SchemaLoader.parseSchemaText("""
        id: test
        name: テスト
        kind: document
        match: '*.yaml'
        root:
          type: object
          fields:
            - {key: title, label: 名前, type: string}
            - key: rows
              type: array
              item:
                type: object
                fields:
                  - {key: code, type: id}
                  - {key: title, type: string, role: title}
            - {key: optional, type: object, fields: [{key: child, type: string}]}
    """.trimIndent(), Path.of("test.yaml"))

    @Test
    fun `未設定の項目とスキーマ外キーを含み未作成の子は生成しない`() {
        val root = YamlIO.parse("rows: [{code: A, title: 一行目}]\nextra: 5")
        val before = YamlIO.dumpPlain(root)
        val items = documentOutline(root, schema)
        assertEquals("名前", items.single { it.path == listOf("title") }.title)
        assertNull(items.single { it.path == listOf("optional") }.node)
        assertFalse(items.any { it.path == listOf("optional", "child") })
        assertTrue(items.any { it.path == listOf("extra") })
        assertEquals("1. 一行目", items.single { it.path == listOf("rows", 0) }.title)
        assertEquals(before, YamlIO.dumpPlain(root))
    }

    @Test
    fun `参照で再帰する型も実データの範囲だけを表示する`() {
        val recursive = SchemaLoader.parseSchemaText("""
            id: recursive
            kind: document
            root: {type: object, fields: [{key: next, ${'$'}ref: Node}]}
            definitions:
              Node: {type: object, fields: [{key: next, ${'$'}ref: Node}]}
        """.trimIndent(), Path.of("recursive.yaml"))
        val items = documentOutline(YamlIO.parse("next: {}"), recursive)
        assertEquals(listOf(emptyList(), listOf("next"), listOf("next", "next")), items.map { it.path })
    }

    @Test
    fun `配列を並べ替えても選択中の子項目は同じ要素に追随する`() {
        val root = YamlIO.parse("rows: [{code: A, title: 一}, {code: B, title: 二}]") as YNode.Map
        val previous = documentOutline(root, schema)
        val rows = root.entries["rows"] as YNode.Seq
        rows.items.add(0, rows.items.removeAt(1))
        val items = documentOutline(root, schema)
        assertEquals(listOf("rows", 0, "title"), retainedFormSelection(items, listOf("rows", 1, "title"), previous))
        val restored = documentOutline(YamlIO.parse("rows: [{code: A, title: 一}, {code: B, title: 二}]"), schema)
        assertEquals(listOf("rows", 1, "title"), retainedFormSelection(restored, listOf("rows", 0, "title"), items))
    }

    @Test
    fun `削除された選択は存在する親へ戻り同じIDが複数なら勝手に移動しない`() {
        val old = documentOutline(YamlIO.parse("rows: [{code: A, title: 一}, {code: A, title: 二}]"), schema)
        val now = documentOutline(YamlIO.parse("rows: [{code: A, title: 二}, {code: A, title: 一}]"), schema)
        assertEquals(listOf("rows", 0), retainedFormSelection(now, listOf("rows", 0), old))
        assertEquals(listOf("rows"), nearestFormPath(listOf(emptyList(), listOf("rows")), listOf("rows", 3, "title")))
    }

    @Test
    fun `スクロールの基準線を含む子項目を選び空白と末尾でも追従する`() {
        val bounds = linkedMapOf<YPath, Pair<Float, Float>>(
            emptyList<Any>() to (0f to 900f), listOf("a") to (20f to 100f),
            listOf("b") to (150f to 700f), listOf("b", 0) to (180f to 250f))
        assertEquals(listOf("b", 0), activeFormPath(bounds, 200f))
        assertEquals(listOf("b"), activeFormPath(bounds, 300f))
        assertEquals(emptyList(), activeFormPath(bounds, 120f))
        assertNull(activeFormPath(emptyMap(), 0f))
    }

    @Test
    fun `文書ビューで除外した項目はツリーにも出さず関連情報は残す`() {
        val items = documentOutline(YamlIO.parse("title: 一覧\nextra: 5\nrows: []"), schema)
        val view = DocViewDef("view", "表示", sections = listOf(
            DocViewSection(fieldsPath = emptyList(), fieldsInclude = listOf("title", "rows"), fieldsExclude = listOf("rows")),
            DocViewSection(label = "関連", embed = dev.toros.yde.semantic.VirtualDocEmbed("flow", "flow", "フロー"))), source = DocumentId("view.yaml"))
        val projected = documentViewOutline(items, view)
        assertEquals(listOf(emptyList(), listOf("title"), listOf("@view", 1)), projected.map { it.path })
        assertEquals("関連", projected.last().title)
    }
    @Test
    fun `文書ビューの指定順を子項目ごとに保持し重複セクションは一度だけ載せる`() {
        val items = documentOutline(YamlIO.parse("title: 一覧\nrows: [{code: A, title: 一}]"), schema)
        val section = DocViewSection(fieldsPath = emptyList(), fieldsInclude = listOf("rows", "title"))
        val view = DocViewDef("view", "表示", sections = listOf(section, section), source = DocumentId("view.yaml"))
        val paths = documentViewOutline(items, view).map { it.path }
        assertEquals(emptyList(), paths.first())
        assertEquals(listOf("rows"), paths[1])
        assertEquals(listOf("title"), paths.last())
        assertEquals(paths.distinct(), paths)
    }

    @Test
    fun `文書ビューで子のセクションを親より先に置いても構成を辿れる`() {
        val items = documentOutline(YamlIO.parse("group: {nested: {value: 1}, other: 2}"), null)
        val view = DocViewDef("view", "表示", sections = listOf(
            DocViewSection(fieldsPath = listOf("group", "nested")),
            DocViewSection(fieldsPath = listOf("group"))), source = DocumentId("view.yaml"))
        val projected = documentViewOutline(items, view)
        val seen = mutableSetOf<YPath>()
        projected.forEach { item ->
            assertTrue(item.parent == null || item.parent in seen)
            seen.add(item.path)
        }
        assertEquals(listOf("group", "nested"), projected[1].path)
    }

}
