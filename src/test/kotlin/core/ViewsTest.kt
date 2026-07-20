package core

import agent.ActivityDef
import agent.AgentToolbox
import agent.Staging
import agent.ToolGroup
import ai.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import model.YNode
import model.parseYPath
import schema.FieldType
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ビュー機構（P6）のテスト。
 * 定義のロード・行×セルへの解決（座標・並び順・詳細判定）・フィールド解決・
 * スキャフォールド・CLI・エージェントツール（read_view）を検証する。
 */
class ViewsTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-views-test")
        write(
            "_schemas/screen-list.yaml",
            """
            id: screen-list
            name: 画面一覧
            match: "screens/index.yaml"
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, required: true }
                      - key: type
                        type: enum
                        values: [list, detail, form]
                      - { key: summary, type: text }
            """,
        )
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            name: 画面詳細
            match: "screens/detail/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: layout, type: string, required: true, default: single-column }
                - key: areas
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: name, type: string }
            """,
        )
        write(
            "screens/index.yaml",
            """
            _document:
              id: screen-index
              name: 画面一覧
            screens:
              - id: SCR-LIST
                name: 一覧画面
                type: list
                summary: タスクを一覧表示する
              - id: SCR-EDIT
                name: 編集画面
                type: form
                summary: タスクを編集する
            """,
        )
        write(
            "screens/detail/SCR-LIST.yaml",
            """
            id: SCR-LIST
            layout: two-pane
            areas:
              - id: AREA-SEARCH
                name: 検索フォーム
              - id: AREA-TABLE
                name: 一覧テーブル
            """,
        )
        write(
            "_views/screen-catalog.yaml",
            """
            id: screen-catalog
            name: 画面カタログ
            rows:
              idPattern: "SCR-*"
            order:
              schemaId: screen-list
            columns:
              - label: 画面名
                schemaId: screen-list
                path: name
                editable: true
              - label: 種別
                schemaId: screen-list
                path: type
                editable: true
              - label: エリア
                schemaId: screen-detail
                path: areas
            detail:
              schemaId: screen-detail
              filePattern: "screens/detail/{id}.yaml"
            newRow:
              file: screens/index.yaml
              path: screens
            """,
        )
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

    // ---------------------------------------------------------------- 定義のロード

    @Test
    fun `load parses view definition`() {
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("screen-catalog")!!
        assertEquals("画面カタログ", view.name)
        assertEquals("SCR-*", view.rows.idPattern)
        assertEquals(listOf("画面名", "種別", "エリア"), view.columns.map { it.label })
        assertEquals("screen-list", view.orderBy?.schemaId)
        assertEquals("screens/detail/{id}.yaml", view.detail?.filePattern)
        assertEquals(parseYPath("screens"), view.newRow?.path)
    }

    @Test
    fun `load collects definition errors`() {
        write("_views/broken.yaml", "id: broken\ncolumns:\n  - label: A\n    path: name")
        write("_views/badpattern.yaml", "id: bad\nrows: { idPattern: \"X-*\" }\ncolumns:\n  - { label: A, path: name }\ndetail: { filePattern: \"x/{id}.md\" }")
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "broken.yaml" in it && "rows" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "badpattern.yaml" in it && "YAML" in it }, loaded.errors.toString())
        // 壊れた定義があっても正常な定義は読める
        assertTrue(loaded.view("screen-catalog") != null)
    }

    @Test
    fun `duplicate view ids are reported`() {
        write("_views/dup.yaml", "id: screen-catalog\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }")
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "重複" in it }, loaded.errors.toString())
    }

    // ---------------------------------------------------------------- 解決

    @Test
    fun `resolve joins occurrences by id with coordinates`() {
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).view("screen-catalog")!!
        val rows = Views.resolve(view, ws, workspace.index)
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "一覧文書の出現順で並ぶ")

        val list = rows[0]
        // 一覧側のセル: 値と座標
        val name = list.cells[0]
        assertEquals("一覧画面", (name.value as YNode.Scalar).value)
        assertEquals("screens/index.yaml", name.rel)
        assertEquals(parseYPath("screens[0].name"), name.path)
        assertTrue(name.column.editable)
        // 詳細側のセル: 別文書の同一IDから解決される
        val areas = list.cells[2]
        assertEquals("screens/detail/SCR-LIST.yaml", areas.rel)
        assertEquals(2, (areas.value as YNode.Seq).items.size)
        // 詳細出現の判定
        assertEquals("screens/detail/SCR-LIST.yaml", list.detailRel)

        val edit = rows[1]
        assertNull(edit.cells[2].file, "詳細未作成の行はセルが出現なしになる")
        assertNull(edit.detailRel)
    }

    @Test
    fun `resolve reflects injected loader`() {
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).view("screen-catalog")!!
        // 未保存編集に相当するオーバーレイを loader で注入する
        val edited = YamlIO.parse(Files.readString(ws.resolve("screens/index.yaml")))
        Patcher.apply(edited, PatchOp.Set(parseYPath("screens[0].name"), YNode.Scalar("編集済みの画面名")))
        val rows = Views.resolve(view, ws, workspace.index) { f ->
            if (workspace.relPath(f) == "screens/index.yaml") edited
            else runCatching { YamlIO.parse(Files.readString(f)) }.getOrNull()
        }
        assertEquals("編集済みの画面名", (rows[0].cells[0].value as YNode.Scalar).value)
    }

    // ---------------------------------------------------------------- フィールド解決

    @Test
    fun `fieldAt resolves field definition along data path`() {
        val workspace = Workspace.open(ws)
        val schema = workspace.schemas.first { it.id == "screen-list" }
        val root = workspace.load("screens/index.yaml").root!!
        val typeField = Views.fieldAt(schema, root, parseYPath("screens[0].type"))!!
        assertEquals(FieldType.ENUM, typeField.type)
        assertEquals(listOf("list", "detail", "form"), typeField.values)
        assertNull(Views.fieldAt(schema, root, parseYPath("screens[0].unknown")))
    }

    // ---------------------------------------------------------------- スキャフォールド

    @Test
    fun `detailScaffold builds new document with id and defaults`() {
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).view("screen-catalog")!!
        val scaffold = Views.detailScaffold(view, "SCR-EDIT", workspace.schemas)
        assertEquals("screens/detail/SCR-EDIT.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        assertEquals("SCR-EDIT", (map.entries["id"] as YNode.Scalar).value)
        assertEquals("single-column", (map.entries["layout"] as YNode.Scalar).value, "スキーマの default を尊重する")
        // 不正なIDはファイル名にできない
        assertFailsWith<IllegalArgumentException> { Views.detailScaffold(view, "../escape", workspace.schemas) }
    }

    // ---------------------------------------------------------------- CLI

    @Test
    fun `cli view lists and resolves`() {
        val dir = ws.toString()
        val listOut = runCli(arrayOf("view", dir))
        assertTrue("screen-catalog" in listOut, listOut)
        val out = runCli(arrayOf("view", dir, "screen-catalog"))
        assertTrue("SCR-LIST" in out, out)
        assertTrue("screens[0].name" in out, out)
        assertTrue("detailCreatePath: screens/detail/SCR-EDIT.yaml" in out, out)
    }

    // ---------------------------------------------------------------- エージェントツール（read_view）

    @Test
    fun `read_view reflects staged edits and exposes coordinates`() = kotlinx.coroutines.runBlocking {
        val workspace = Workspace.open(ws)
        val staging = Staging(workspace)
        val activity = ActivityDef(
            id = "test", name = "テスト", agentId = "a", instruction = "テスト",
            source = ws.resolve("_activities/test.yaml"),
        )
        val toolbox = AgentToolbox(workspace, staging, activity, ToolGroup.entries.toSet())
        assertTrue(toolbox.specs().any { it.name == "read_view" })

        // ステージングへの編集がビューに反映される
        staging.applyOps(
            "screens/index.yaml",
            listOf(PatchOp.Set(parseYPath("screens[1].name"), YNode.Scalar("ステージ編集済み"))),
        )
        val host = object : agent.AgentHost {
            override suspend fun askHuman(question: String): String? = null
        }
        val out = toolbox.execute(
            ToolCall("1", "read_view", buildJsonObject { put("view", JsonPrimitive("screen-catalog")) }),
            host, mutableListOf(),
        ).content
        assertTrue("ステージ編集済み" in out, out)
        assertTrue("screens/index.yaml :: screens[1].name" in out, out)
        assertTrue("詳細: 未作成" in out, out)
        assertTrue("screens/detail/SCR-EDIT.yaml" in out, out)

        // view 省略時は一覧
        val listOut = toolbox.execute(
            ToolCall("2", "read_view", buildJsonObject {}),
            host, mutableListOf(),
        ).content
        assertTrue("screen-catalog" in listOut, listOut)
    }

    // ---------------------------------------------------------------- ステージングの定義ゲート

    @Test
    fun `staged broken view definition fails validation gate`() {
        val workspace = Workspace.open(ws)
        val staging = Staging(workspace)
        staging.createDocument("_views/bad.yaml", YamlIO.parse("id: bad\ncolumns: []"))
        val issues = staging.validateAll()
        assertTrue(
            issues["_views/bad.yaml"].orEmpty().any { "定義として解釈できません" in it.message },
            issues.toString(),
        )
    }
}
