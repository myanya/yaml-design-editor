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
import schema.SchemaLoader
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
            definitions:
              screen:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: name, label: 画面名, type: string, required: true }
                  - key: type
                    label: 種別
                    type: enum
                    values: [list, detail, form]
                  - { key: summary, label: 概要, type: text }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    ${'$'}ref: screen
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
        assertEquals("SCR-*", view.rows?.idPattern)
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

    // ---------------------------------------------------------------- newRow ファイルモード（R15-01）

    @Test
    fun `newRow file mode is parsed and rendered`() {
        write(
            "_views/detail-only.yaml",
            """
            id: detail-only
            name: 詳細のみ
            rows:
              idPattern: "SCR-*"
            columns:
              - label: レイアウト
                schemaId: screen-detail
                path: layout
            newRow:
              filePattern: "screens/detail/{id}.yaml"
            """,
        )
        val loaded = Views.load(ws, Workspace.open(ws).schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("detail-only")!!
        assertEquals("screens/detail/{id}.yaml", view.newRow?.filePattern)
        assertNull(view.newRow?.file)
        // テキスト表現に新規行の作り方が出る（エージェントは create_document で行を作れる）
        assertTrue("create_document" in Views.renderText(view, emptyList()))
    }

    @Test
    fun `newRow file mode rejects mixed and invalid patterns`() {
        write("_views/bad-nr1.yaml", "id: n1\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nnewRow: { filePattern: \"x/{id}.yaml\", file: a.yaml }")
        write("_views/bad-nr2.yaml", "id: n2\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nnewRow: { filePattern: \"x/all.yaml\" }")
        write("_views/bad-nr3.yaml", "id: n3\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nnewRow: { filePattern: \"x/{id}.md\" }")
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "bad-nr1.yaml" in it && "併用できません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-nr2.yaml" in it && "{id}" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-nr3.yaml" in it && "YAML" in it }, loaded.errors.toString())
    }

    @Test
    fun `lint reports newRow file mode inconsistencies`() {
        // 作成先にマッチするスキーマがない
        write(
            "_views/nr-lint1.yaml",
            "id: l1\nrows: { idPattern: \"SCR-*\" }\ncolumns:\n  - { label: A, schemaId: screen-detail, path: layout }\nnewRow: { filePattern: \"elsewhere/{id}.yaml\" }",
        )
        // 作成先が detail の認定条件（fileGlob）から外れる → 作成直後の行が「未作成」になる事故
        write(
            "_views/nr-lint2.yaml",
            """
            id: l2
            rows: { idPattern: "SCR-*" }
            columns:
              - { label: A, schemaId: screen-detail, path: layout }
            detail: { fileGlob: "screens/detail/*.yaml" }
            newRow: { filePattern: "screens/detail/{id}.yaml" }
            """,
        )
        // 作成先が detail の認定条件（fileGlob）から外れる → 作成直後の行が「未作成」になる事故
        write(
            "_views/nr-lint3.yaml",
            """
            id: l3
            rows: { idPattern: "SCR-*" }
            columns:
              - { label: A, schemaId: screen-detail, path: layout }
            detail: { fileGlob: "screens/detail/*.yaml" }
            newRow: { filePattern: "screens/other/{id}.yaml" }
            """,
        )
        val loaded = Views.load(ws, Workspace.open(ws).schemas)
        assertTrue(loaded.errors.any { "nr-lint1.yaml" in it && "マッチするスキーマ" in it }, loaded.errors.toString())
        // nr-lint2 は作成先が認定条件に一致するため指摘なし
        assertTrue(loaded.errors.none { "nr-lint2.yaml" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "nr-lint3.yaml" in it && "認定条件" in it }, loaded.errors.toString())
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

    // ---------------------------------------------------------------- バックリンク列（R7-02）

    /** 画面を実装するテーブル（1:N詳細化の分裂先）と、バックリンク列を持つビューを追加する。 */
    private fun writeBacklinkFixtures() {
        write(
            "_schemas/table-design.yaml",
            """
            id: table-design
            name: テーブル設計
            match: "tables/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: screen
                  type: ref
                  idPattern: "SCR-*"
                  relation: derives
                - key: see_also
                  type: ref
                  idPattern: "SCR-*"
            """,
        )
        write("tables/TBL-LIST-S.yaml", "id: TBL-LIST-S\nscreen: SCR-LIST\nsee_also: SCR-EDIT\n")
        write("tables/TBL-LIST-M.yaml", "id: TBL-LIST-M\nscreen: SCR-LIST\n")
        write(
            "_views/screen-tables.yaml",
            """
            id: screen-tables
            name: 画面と実装テーブル
            rows:
              idPattern: "SCR-*"
            columns:
              - label: 画面名
                schemaId: screen-list
                path: name
              - label: 実装テーブル
                backlinks:
                  relation: derives
                  schemaIds: [table-design]
            """,
        )
    }

    @Test
    fun `backlinks column lists referencing ids with coordinates`() {
        writeBacklinkFixtures()
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).view("screen-tables")!!
        assertEquals("derives", view.columns[1].backlinks?.relation)
        val rows = Views.resolve(view, ws, workspace.index)

        val list = rows.first { it.id == "SCR-LIST" }
        val backlinks = list.cells[1].backlinks!!
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), backlinks.map { it.sourceId }, "参照元ファイル順で並ぶ")
        assertEquals("tables/TBL-LIST-M.yaml", backlinks[0].rel)
        assertEquals(parseYPath("screen"), backlinks[0].path, "参照値の座標を持つ（ジャンプ・apply_patch に使える）")

        // relation の絞り込み: see_also（relation なし）による参照は derives 列に現れない
        val edit = rows.first { it.id == "SCR-EDIT" }
        assertTrue(edit.cells[1].backlinks!!.isEmpty(), "relation が一致しない参照は数えない")

        // テキスト表現（エージェント・CLI 共用）にも被参照が出る
        val text = Views.renderText(view, rows)
        assertTrue("TBL-LIST-S" in text, text)
        assertTrue("tables/TBL-LIST-M.yaml :: screen" in text, text)
        assertTrue("（被参照なし）" in text, text)
    }

    @Test
    fun `backlinks column cannot combine with path editable or source`() {
        write(
            "_views/bad-bl1.yaml",
            "id: b1\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    path: name\n    backlinks: { relation: r }",
        )
        write(
            "_views/bad-bl2.yaml",
            "id: b2\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    editable: true\n    backlinks: { relation: r }",
        )
        write(
            "_views/bad-bl3.yaml",
            "id: b3\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    schemaId: s\n    backlinks: { relation: r }",
        )
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "bad-bl1.yaml" in it && "併用できません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-bl2.yaml" in it && "editable" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-bl3.yaml" in it && "schemaId" in it }, loaded.errors.toString())
    }

    // ---------------------------------------------------------------- 計算列（R16-02）

    /** 値型台帳・型写像（同一ID出現）・属性（値型への ref）と、計算列を持つビューを追加する。 */
    private fun writeComputedFixtures() {
        write(
            "_schemas/vt.yaml",
            """
            id: vt
            name: 値型台帳
            match: "types.yaml"
            root:
              type: object
              fields:
                - key: types
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string }
                      - { key: maxLength, type: int }
            """,
        )
        write(
            "_schemas/vt-ddl.yaml",
            """
            id: vt-ddl
            name: DDL写像
            match: "ddl-mapping.yaml"
            root:
              type: object
              fields:
                - key: mappings
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: to, type: string }
            """,
        )
        write(
            "_schemas/attr.yaml",
            """
            id: attr
            name: 属性一覧
            match: "attrs.yaml"
            root:
              type: object
              fields:
                - key: attrs
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - key: vtype
                        type: ref
                        idPattern: "VT-*"
            """,
        )
        write(
            "types.yaml",
            """
            types:
              - id: VT-TITLE
                name: タイトル
                maxLength: 100
              - id: VT-NOTE
                name: メモ
            """,
        )
        write(
            "ddl-mapping.yaml",
            """
            mappings:
              - id: VT-TITLE
                to: varchar({maxLength})
              - id: VT-NOTE
                to: "{missing} text"
            """,
        )
        write(
            "attrs.yaml",
            """
            attrs:
              - id: FLD-TITLE
                vtype: VT-TITLE
              - id: FLD-FREE
            """,
        )
        write(
            "_views/vt-computed.yaml",
            """
            id: vt-computed
            name: 値型と写像
            rows:
              idPattern: "VT-*"
            order:
              schemaId: vt
            columns:
              - label: 名前
                schemaId: vt
                path: name
              - label: DDL型
                computed:
                  source: { schemaId: vt-ddl }
                  path: to
                  values:
                    - schemaId: vt
              - label: 方言変換
                computed:
                  source: { schemaId: vt-ddl }
                  path: to
                  values:
                    - schemaId: vt
                  map:
                    "varchar(100)": "VARCHAR2(100 CHAR)"
            """,
        )
        write(
            "_views/fld-computed.yaml",
            """
            id: fld-computed
            name: 属性の導出型
            rows:
              idPattern: "FLD-*"
            order:
              schemaId: attr
            columns:
              - label: DDL型
                computed:
                  lookup: { schemaId: attr, via: vtype }
                  source: { schemaId: vt-ddl }
                  path: to
                  values:
                    - schemaId: vt
                  default: text
            """,
        )
    }

    @Test
    fun `computed column evaluates template with placeholder scopes`() {
        writeComputedFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val rows = Views.resolve(loaded.view("vt-computed")!!, ws, workspace.index)

        // 同一ID出現の写像行のテンプレートを、値型定義（values で選んだ出現）の値で評価する
        val title = rows.first { it.id == "VT-TITLE" }
        val ddl = title.cells[1]
        assertEquals("varchar(100)", (ddl.value as YNode.Scalar).value)
        assertTrue(ddl.computed)
        assertEquals("ddl-mapping.yaml", ddl.rel, "座標は元値（導出規則）の位置")
        assertEquals(parseYPath("mappings[0].to"), ddl.path)

        // 来歴（R16-04）: 元値と、プレースホルダごとの解決値・入力値の座標を持つ（ジャンプ先の選択肢）
        val trace = ddl.trace!!
        assertEquals("varchar({maxLength})", trace.raw)
        val input = trace.inputs.single()
        assertEquals("maxLength", input.placeholder)
        assertEquals("100", input.value)
        assertEquals("types.yaml", input.rel, "入力値の座標は値型定義の位置")
        assertEquals(parseYPath("types[0].maxLength"), input.path)

        // 解決できないプレースホルダは {...} のまま残す（作業を止めない。ARCHITECTURE §4.26）
        val note = rows.first { it.id == "VT-NOTE" }
        assertEquals("{missing} text", (note.cells[1].value as YNode.Scalar).value)
        // 未解決の入力は来歴に「座標なし」として残る（UIは未解決の説明を出す）
        val missing = note.cells[1].trace!!.inputs.single()
        assertEquals("missing", missing.placeholder)
        assertNull(missing.value)
        assertNull(missing.file)

        // 値辞書は評価後の値に適用される
        assertEquals("VARCHAR2(100 CHAR)", (title.cells[2].value as YNode.Scalar).value)

        // テキスト表現（エージェント・CLI 共用）にも評価値・計算列の印・来歴（導出行）が出る
        val text = Views.renderText(loaded.view("vt-computed")!!, rows)
        assertTrue("varchar(100)" in text, text)
        assertTrue("計算値" in text, text)
        assertTrue("導出: varchar({maxLength})" in text, text)
        assertTrue("{maxLength}=100 [types.yaml :: types[0].maxLength]" in text, text)
    }

    @Test
    fun `computed column follows one hop lookup and falls back to default`() {
        writeComputedFixtures()
        val workspace = Workspace.open(ws)
        val rows = Views.resolve(Views.load(ws).view("fld-computed")!!, ws, workspace.index)

        // lookup: 属性の ref（vtype）を1ホップ辿り、参照先IDの写像行から導出する
        val titled = rows.first { it.id == "FLD-TITLE" }
        assertEquals("varchar(100)", (titled.cells[0].value as YNode.Scalar).value)

        // ref が未記入の行は default にフォールバックする（導出元の座標はない）
        val free = rows.first { it.id == "FLD-FREE" }
        assertEquals("text", (free.cells[0].value as YNode.Scalar).value)
        assertNull(free.cells[0].file)
    }

    @Test
    fun `computed column rejects invalid combinations and lints unknown schemas`() {
        write(
            "_views/bad-c1.yaml",
            "id: c1\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    path: name\n    computed: { source: { schemaId: s }, path: to }",
        )
        write(
            "_views/bad-c2.yaml",
            "id: c2\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    editable: true\n    computed: { source: { schemaId: s }, path: to }",
        )
        write(
            "_views/bad-c3.yaml",
            "id: c3\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    computed: { source: {}, path: to }",
        )
        write(
            "_views/bad-c4.yaml",
            "id: c4\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    computed: { source: { schemaId: no-such-schema }, path: to }",
        )
        val loaded = Views.load(ws, SchemaLoader.loadAll(ws))
        assertTrue(loaded.errors.any { "bad-c1.yaml" in it && "併用できません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-c2.yaml" in it && "editable" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-c3.yaml" in it && "schemaId か fileGlob" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-c4.yaml" in it && "no-such-schema" in it }, loaded.errors.toString())
    }

    // ---------------------------------------------------------------- 文書ビュー（R14-01）

    /** 文書ビュー: 画面一覧文書のフォームに、実装テーブル（被参照）の表セクションを合成する。 */
    private fun writeDocViewFixtures() {
        writeBacklinkFixtures()
        write(
            "_views/screen-index-doc.yaml",
            """
            id: screen-index-doc
            name: 画面一覧（文書ビュー）
            kind: document
            match: "screens/index.yaml"
            sections:
              - fields: {}
              - label: 実装テーブル
                backlinks:
                  idPattern: "SCR-*"
                  relation: derives
                  schemaIds: [table-design]
                  columns:
                    - label: テーブルID
                      path: id
            """,
        )
    }

    @Test
    fun `document view parses and applies to matching documents`() {
        writeDocViewFixtures()
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.docView("screen-index-doc")!!
        assertEquals("screens/index.yaml", view.match)
        assertEquals(2, view.sections.size)
        assertEquals(emptyList<Any>(), view.sections[0].fieldsPath, "fields の path 省略はルート全体")
        val bl = view.sections[1].backlinks!!
        assertEquals("SCR-*", bl.idPattern)
        assertEquals("derives", bl.filter.relation)
        assertEquals(listOf("table-design"), bl.filter.schemaIds)
        assertEquals("テーブルID", bl.columns[0].label)
        // グリッドの一覧には現れず、適用判定（docViewFor）は match / schemaId で決まる
        assertNull(loaded.view("screen-index-doc"))
        assertEquals(view, loaded.docViewFor("screens/index.yaml", "screen-list"))
        assertNull(loaded.docViewFor("screens/detail/SCR-LIST.yaml", "screen-detail"))
    }

    @Test
    fun `document view definition errors are collected`() {
        // match / schemaId なし
        write("_views/doc-bad1.yaml", "id: d1\nkind: document\nsections:\n  - fields: {}")
        // fields も backlinks もないセクション
        write("_views/doc-bad2.yaml", "id: d2\nkind: document\nmatch: \"a.yaml\"\nsections:\n  - label: A")
        // グリッド専用キーの混入
        write("_views/doc-bad3.yaml", "id: d3\nkind: document\nmatch: \"a.yaml\"\nrows: { idPattern: \"*\" }\nsections:\n  - fields: {}")
        // 未知の kind
        write("_views/doc-bad4.yaml", "id: d4\nkind: unknown")
        // グリッドへの sections の混入
        write("_views/doc-bad5.yaml", "id: d5\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nsections: []")
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "doc-bad1.yaml" in it && "match" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "doc-bad2.yaml" in it && "fields か backlinks" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "doc-bad3.yaml" in it && "rows" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "doc-bad4.yaml" in it && "kind" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "doc-bad5.yaml" in it && "sections" in it }, loaded.errors.toString())
        // 壊れた定義があっても正常な定義は読める
        assertTrue(loaded.view("screen-catalog") != null)
    }

    @Test
    fun `resolveDocument resolves backlink sections with coordinates`() {
        writeDocViewFixtures()
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).docView("screen-index-doc")!!
        val sections = Views.resolveDocument(view, ws, ws.resolve("screens/index.yaml"), workspace.index)
        assertEquals(2, sections.size)
        assertNull(sections[0].rows, "fields セクションは行を持たない（描画側が文書ツリーから直接描く）")

        val rows = sections[1].rows!!
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "文書内の出現順で並ぶ")
        assertEquals(parseYPath("screens[0]"), rows[0].path)
        val backlinks = rows[0].backlinks
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), backlinks.map { it.sourceId }, "参照元ファイル順で並ぶ")
        assertEquals("tables/TBL-LIST-M.yaml", backlinks[0].rel)
        assertEquals(parseYPath("screen"), backlinks[0].refPath, "参照値の座標を持つ（ジャンプ・apply_patch に使える）")
        assertEquals("TBL-LIST-M", (backlinks[0].values[0] as YNode.Scalar).value, "columns は参照元要素からの相対値")
        assertTrue(rows[1].backlinks.isEmpty(), "relation が一致しない参照（see_also）は現れない")

        // テキスト表現（エージェント・CLI 共用）
        val text = Views.renderDocText(view, "screens/index.yaml", sections)
        assertTrue("screens/index.yaml :: (root)" in text, text)
        assertTrue("tables/TBL-LIST-M.yaml :: screen" in text, text)
        assertTrue("テーブルID: TBL-LIST-M" in text, text)
        assertTrue("（被参照なし）" in text, text)
    }

    @Test
    fun `cli view resolves document view`() {
        writeDocViewFixtures()
        val dir = ws.toString()
        val listOut = runCli(arrayOf("view", dir))
        assertTrue("screen-index-doc" in listOut, listOut)
        val out = runCli(arrayOf("view", dir, "screen-index-doc", "screens/index.yaml"))
        assertTrue("kind: document" in out, out)
        assertTrue("SCR-LIST" in out, out)
        assertTrue("path: screen" in out, out)
        assertTrue("TBL-LIST-S" in out, out)
    }

    @Test
    fun `read_view resolves document view with backlinks`() = kotlinx.coroutines.runBlocking {
        writeDocViewFixtures()
        val workspace = Workspace.open(ws)
        val staging = Staging(workspace)
        val activity = ActivityDef(
            id = "test", name = "テスト", agentId = "a", instruction = "テスト",
            source = ws.resolve("_activities/test.yaml"),
        )
        val toolbox = AgentToolbox(workspace, staging, activity, ToolGroup.entries.toSet())
        val host = object : agent.AgentHost {
            override suspend fun askHuman(question: String): String? = null
        }
        // 適用対象が1件なので file 省略で解決される
        val out = toolbox.execute(
            ToolCall("1", "read_view", buildJsonObject { put("view", JsonPrimitive("screen-index-doc")) }),
            host, mutableListOf(),
        ).content
        assertTrue("文書ビュー" in out, out)
        assertTrue("tables/TBL-LIST-M.yaml :: screen" in out, out)
        // 一覧に文書ビューも載る
        val listOut = toolbox.execute(
            ToolCall("2", "read_view", buildJsonObject {}),
            host, mutableListOf(),
        ).content
        assertTrue("screen-index-doc" in listOut, listOut)
        assertTrue("文書ビュー" in listOut, listOut)
    }

    @Test
    fun `staged broken document view fails validation gate`() {
        val workspace = Workspace.open(ws)
        val staging = Staging(workspace)
        staging.createDocument("_views/bad-doc.yaml", YamlIO.parse("id: bad\nkind: document\nsections: []"))
        val issues = staging.validateAll()
        assertTrue(
            issues["_views/bad-doc.yaml"].orEmpty().any { "定義として解釈できません" in it.message },
            issues.toString(),
        )
    }

    // ---------------------------------------------------------------- 合成の成熟（R14-03: 選別・要素単位アタッチ）

    /** 文書ビューの合成（R14-03）: 選別つき fields セクション＋要素単位アタッチ。 */
    private fun writeComposedDocViewFixtures() {
        writeBacklinkFixtures()
        write(
            "_views/screen-index-composed.yaml",
            """
            id: screen-index-composed
            name: 画面一覧（合成）
            kind: document
            match: "screens/index.yaml"
            schemaId: screen-list
            sections:
              - label: 画面一覧
                fields:
                  include: [screens]
            attachments:
              - at: "screens[*]"
                label: 実装テーブル
                backlinks:
                  relation: derives
                  schemaIds: [table-design]
                  columns:
                    - label: テーブルID
                      path: id
            """,
        )
    }

    @Test
    fun `composed document view parses selection and attachments`() {
        writeComposedDocViewFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.docView("screen-index-composed")!!
        assertEquals(listOf("screens"), view.sections[0].fieldsInclude)
        val attachment = view.attachments.single()
        assertEquals("screens[*]", attachment.atText)
        assertTrue(attachment.matches(parseYPath("screens[3]")), "[*] は任意のインデックスに一致する")
        assertTrue(!attachment.matches(parseYPath("screens")), "配列そのものには一致しない")
        assertTrue(!attachment.matches(parseYPath("screens[0].name")), "より深いパスには一致しない")
    }

    @Test
    fun `resolveAttachments resolves element backlinks with coordinates`() {
        writeComposedDocViewFixtures()
        val workspace = Workspace.open(ws)
        val view = Views.load(ws, workspace.schemas).docView("screen-index-composed")!!
        val file = ws.resolve("screens/index.yaml")
        val attachments = Views.resolveAttachments(view, ws, file, workspace.index)
        val rows = attachments.single().rows
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "要素の文書内順で並ぶ")
        assertEquals(parseYPath("screens[0]"), rows[0].path, "差し込み先の要素座標を持つ")
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), rows[0].backlinks.map { it.sourceId })
        assertEquals("TBL-LIST-M", (rows[0].backlinks[0].values[0] as YNode.Scalar).value, "columns は参照元要素からの相対値")
        assertTrue(rows[1].backlinks.isEmpty(), "relation が一致しない参照は現れない")

        // テキスト表現（エージェント・CLI 共用）に選別・アタッチが載る
        val sections = Views.resolveDocument(view, ws, file, workspace.index)
        val text = Views.renderDocText(view, "screens/index.yaml", sections, attachments)
        assertTrue("（選別: screens）" in text, text)
        assertTrue("アタッチ: 実装テーブル（at: screens[*]）" in text, text)
        assertTrue("tables/TBL-LIST-M.yaml :: screen" in text, text)
    }

    @Test
    fun `composition definition errors and lint`() {
        writeBacklinkFixtures()
        // include と exclude の併用は不可
        write("_views/bad-comp1.yaml", "id: bc1\nkind: document\nmatch: \"a.yaml\"\nsections:\n  - fields: { include: [a], exclude: [b] }")
        // attachments には backlinks が必要
        write("_views/bad-comp2.yaml", "id: bc2\nkind: document\nmatch: \"a.yaml\"\nsections:\n  - fields: {}\nattachments:\n  - at: \"x[*]\"")
        // リント: 未知の選別キー・解決できない at・IDのない at
        write(
            "_views/stale-comp.yaml",
            """
            id: stale-comp
            kind: document
            schemaId: screen-list
            sections:
              - fields: { include: [nosuch] }
            attachments:
              - at: "nosuch[*]"
                backlinks: { relation: derives }
              - at: screens
                backlinks: { relation: derives }
            """,
        )
        val errors = Views.load(ws, Workspace.open(ws).schemas).errors
        assertTrue(errors.any { "bad-comp1.yaml" in it && "併用できません" in it }, errors.toString())
        assertTrue(errors.any { "bad-comp2.yaml" in it && "backlinks" in it }, errors.toString())
        assertTrue(errors.any { "stale-comp.yaml" in it && "選別キー「nosuch」" in it }, errors.toString())
        assertTrue(errors.any { "stale-comp.yaml" in it && "nosuch[*]" in it && "解決できません" in it }, errors.toString())
        assertTrue(errors.any { "stale-comp.yaml" in it && "ID型フィールドがありません" in it }, errors.toString())
    }

    @Test
    fun `cli view outputs attachments`() {
        writeComposedDocViewFixtures()
        val out = runCli(arrayOf("view", ws.toString(), "screen-index-composed", "screens/index.yaml"))
        assertTrue("screens[*]" in out, out)
        assertTrue("TBL-LIST-M" in out, out)
        assertTrue("include:" in out, out)
    }

    // ---------------------------------------------------------------- definition 参照とリント（R14-02）

    @Test
    fun `definition columns expand from named definition`() {
        write(
            "_views/screen-columns.yaml",
            """
            id: screen-columns
            name: 画面一覧（定義参照）
            rows:
              idPattern: "SCR-*"
            columns:
              - definition: screen
                schemaId: screen-list
                editable: true
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("screen-columns")!!
        // ID型は行キーと重複するため除かれ、ラベル・パスはスキーマの定義から導出される
        assertEquals(listOf("画面名", "種別", "概要"), view.columns.map { it.label })
        assertEquals(listOf(parseYPath("name"), parseYPath("type"), parseYPath("summary")), view.columns.map { it.path })
        assertTrue(view.columns.all { it.editable })
        assertTrue(view.columns.all { it.from.schemaId == "screen-list" })

        val rows = Views.resolve(view, ws, workspace.index)
        val list = rows.first { it.id == "SCR-LIST" }
        assertEquals("一覧画面", (list.cells[0].value as YNode.Scalar).value)
    }

    @Test
    fun `definition column errors are collected`() {
        // 未知の定義
        write("_views/bad-def1.yaml", "id: bd1\nrows: { idPattern: \"*\" }\ncolumns:\n  - definition: \"screen-list#nosuch\"")
        // label / path / backlinks との併用不可
        write("_views/bad-def2.yaml", "id: bd2\nrows: { idPattern: \"*\" }\ncolumns:\n  - definition: screen\n    schemaId: screen-list\n    label: A")
        // 非修飾名は schemaId が必要
        write("_views/bad-def3.yaml", "id: bd3\nrows: { idPattern: \"*\" }\ncolumns:\n  - definition: screen")
        val loaded = Views.load(ws, Workspace.open(ws).schemas)
        assertTrue(loaded.errors.any { "bad-def1.yaml" in it && "見つかりません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-def2.yaml" in it && "併用できません" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "bad-def3.yaml" in it && "schemaId" in it }, loaded.errors.toString())
    }

    @Test
    fun `lint reports stale schema references`() {
        writeDocViewFixtures()
        val workspace = Workspace.open(ws)
        // 既存の正しい定義には指摘が出ない（誤検知ガード）
        assertTrue(Views.load(ws, workspace.schemas).errors.isEmpty(), Views.load(ws, workspace.schemas).errors.toString())

        write(
            "_views/stale.yaml",
            """
            id: stale
            name: 陳腐化したグリッド
            rows:
              idPattern: "SCR-*"
              schemaIds: [nosuch-schema]
            columns:
              - label: 画面名
                schemaId: screen-list
                path: renamed
              - label: 被参照
                backlinks:
                  schemaIds: [nosuch-source]
            """,
        )
        write(
            "_views/stale-doc.yaml",
            """
            id: stale-doc
            kind: document
            schemaId: screen-list
            sections:
              - fields: { path: nosuch }
              - label: 表
                backlinks:
                  schemaIds: [table-design]
                  columns:
                    - label: 消えた列
                      path: nosuch
            """,
        )
        val errors = Views.load(ws, workspace.schemas).errors
        assertTrue(errors.any { "stale.yaml" in it && "nosuch-schema" in it }, errors.toString())
        assertTrue(errors.any { "stale.yaml" in it && "renamed" in it && "ID要素から解決できません" in it }, errors.toString())
        assertTrue(errors.any { "stale.yaml" in it && "nosuch-source" in it }, errors.toString())
        assertTrue(errors.any { "stale-doc.yaml" in it && "nosuch" in it && "解決できません" in it }, errors.toString())
        assertTrue(errors.any { "stale-doc.yaml" in it && "消えた列" in it }, errors.toString())
        // リントの指摘はビューを無効化しない（解決・表示は従来どおり）
        assertTrue(Views.load(ws, workspace.schemas).view("stale") != null)
    }

    @Test
    fun `lintDocument checks a single unsaved view definition`() {
        // 検証パネル用: 編集中（未保存）のビュー定義ツリーをその場でリントできる（UX-43）
        val schemas = Workspace.open(ws).schemas
        val editing = yaml.YamlIO.parse(
            """
            id: editing
            rows:
              idPattern: "SCR-*"
            columns:
              - label: 壊れた列
                schemaId: screen-list
                path: nosuch
            """.trimIndent(),
        )
        val errors = Views.lintDocument(editing, ws.resolve("_views/editing.yaml"), schemas)
        assertTrue(errors.any { "壊れた列" in it && "ID要素から解決できません" in it }, errors.toString())
        // パース不能な定義はその内容を1件返す（kind 不正）
        val broken = yaml.YamlIO.parse("id: x\nkind: nope")
        assertTrue(Views.lintDocument(broken, ws.resolve("_views/x.yaml"), schemas).isNotEmpty())
        // 正しい定義には指摘を出さない
        val ok = yaml.YamlIO.parse(
            "id: ok\nrows: { idPattern: \"SCR-*\" }\ncolumns:\n  - { label: 画面名, schemaId: screen-list, path: name }",
        )
        assertTrue(Views.lintDocument(ok, ws.resolve("_views/ok.yaml"), schemas).isEmpty())
    }

    @Test
    fun `lint checks fileGlob columns via representative path`() {
        // fileGlob だけで出現を選ぶ列も、glob の代表パスにマッチするスキーマで検査される（UX-36）
        write(
            "_views/glob-cols.yaml",
            """
            id: glob-cols
            name: fileGlob列の検査
            rows:
              idPattern: "SCR-*"
            columns:
              - label: 正しい列
                fileGlob: "screens/detail/*.yaml"
                path: layout
              - label: 壊れた列
                fileGlob: "screens/detail/*.{yaml,yml}"
                path: layoutt
              - label: スキーマ外
                fileGlob: "notes/*.yaml"
                path: whatever
            """,
        )
        val errors = Views.load(ws, Workspace.open(ws).schemas).errors
        // 解決できる列と、マッチするスキーマの無い glob には指摘を出さない（誤検知ガード）
        assertTrue(errors.none { "正しい列" in it }, errors.toString())
        assertTrue(errors.none { "スキーマ外" in it }, errors.toString())
        assertTrue(
            errors.any { "glob-cols.yaml" in it && "壊れた列" in it && "screen-detail" in it && "ID要素から解決できません" in it },
            errors.toString(),
        )
    }

    @Test
    fun `fields definition declaration is verified by lint`() {
        val workspace = Workspace.open(ws)
        // スキーマのフィールドが宣言どおり definition を参照している → 指摘なし
        write(
            "_views/doc-def.yaml",
            "id: doc-def\nkind: document\nschemaId: screen-list\nsections:\n  - fields: { path: screens, definition: screen }",
        )
        assertTrue(Views.load(ws, workspace.schemas).errors.isEmpty(), Views.load(ws, workspace.schemas).errors.toString())

        // パス先が宣言した definition を参照していない（スキーマが構造を差し替えた想定）
        write(
            "_views/doc-def2.yaml",
            "id: doc-def2\nkind: document\nschemaId: screen-detail\nsections:\n  - fields: { path: areas, definition: \"screen-list#screen\" }",
        )
        val errors = Views.load(ws, workspace.schemas).errors
        assertTrue(errors.any { "doc-def2.yaml" in it && "参照していません" in it }, errors.toString())

        // 存在しない definition はパース段階で拒否される
        write(
            "_views/doc-def3.yaml",
            "id: doc-def3\nkind: document\nschemaId: screen-list\nsections:\n  - fields: { path: screens, definition: nosuch }",
        )
        val errors2 = Views.load(ws, workspace.schemas).errors
        assertTrue(errors2.any { "doc-def3.yaml" in it && "見つかりません" in it }, errors2.toString())
    }

    @Test
    fun `staged view with unknown definition fails gate`() {
        val workspace = Workspace.open(ws)
        val staging = Staging(workspace)
        staging.createDocument(
            "_views/bad-def.yaml",
            YamlIO.parse("id: bad\nrows: { idPattern: \"*\" }\ncolumns:\n  - definition: \"screen-list#nosuch\""),
        )
        val issues = staging.validateAll()
        assertTrue(
            issues["_views/bad-def.yaml"].orEmpty().any { "定義として解釈できません" in it.message },
            issues.toString(),
        )
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

    @Test
    fun `scaffold builds document from newRow filePattern`() {
        val workspace = Workspace.open(ws)
        val scaffold = Views.scaffold("screens/detail/{id}.yaml", "SCR-NEW", workspace.schemas)
        assertEquals("screens/detail/SCR-NEW.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        assertEquals("SCR-NEW", (map.entries["id"] as YNode.Scalar).value)
        assertEquals("single-column", (map.entries["layout"] as YNode.Scalar).value, "作成先にマッチするスキーマの初期値が入る")
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
        // 新規行の入口（配列モード）が機械可読に出る
        assertTrue("newRow" in out, out)
        assertTrue("file: screens/index.yaml" in out, out)
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

    // ---------------------------------------------------------------- クエリ行（rows: match。R22-02）

    /** クエリ行のフィクスチャ: エンティティ型つきの2スキーマ＋uses 関係＋結合ビュー。 */
    private fun setupQueryRows() {
        write(
            "_schemas/qscreen.yaml",
            """
            id: qscreen
            match: "qscreens/*.yaml"
            entity: { type: qscreen, idPattern: "QS-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [qusecase] } }
            """,
        )
        write(
            "_schemas/qusecase.yaml",
            """
            id: qusecase
            match: "qucs/*.yaml"
            entity: { type: qusecase, idPattern: "QU-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: owner, type: string }
            """,
        )
        write("qscreens/QS-1.yaml", "id: QS-1\nname: 画面A\nuses:\n- QU-1\n- QU-2")
        write("qscreens/QS-2.yaml", "id: QS-2\nname: 画面B\nuses:\n- QU-2")
        write("qucs/QU-1.yaml", "id: QU-1\nname: ユースX\nowner: 佐藤")
        write("qucs/QU-2.yaml", "id: QU-2\nname: ユースY\nowner: 鈴木")
        write(
            "_views/join.yaml",
            """
            id: join-view
            name: 画面×ユースケース
            rows:
              match:
                - node: { entity: qscreen, bind: scr }
                - edge: { relation: uses }
                - node: { entity: qusecase, bind: uc }
            columns:
              - { label: 画面名, path: name, editable: true }
              - { label: UC名, of: uc, path: name }
              - { label: UC担当, of: uc, path: owner, editable: true }
            """,
        )
    }

    @Test
    fun `query rows resolve join cells to each binding's origin coordinates`() {
        setupQueryRows()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("join-view")!!
        assertNull(view.rows)
        assertEquals("scr", view.rowKey)
        val rows = Views.resolve(view, ws, workspace.index)
        // 行 = 束縛行（結合）。行IDは行キー束縛で、結合により重複しうる。key は束縛値の組で一意
        assertEquals(listOf("QS-1", "QS-1", "QS-2"), rows.map { it.id })
        assertEquals(
            listOf("scr=QS-1|uc=QU-1", "scr=QS-1|uc=QU-2", "scr=QS-2|uc=QU-2"),
            rows.map { it.key },
        )
        val first = rows[0]
        // of 省略の列は行キー束縛（qscreen）の座標へ、of: uc の列は結合先（qusecase）の座標へ解決される
        assertEquals("qscreens/QS-1.yaml", first.cells[0].rel)
        assertEquals(parseYPath("name"), first.cells[0].path)
        assertEquals("画面A", (first.cells[0].value as? YNode.Scalar)?.value)
        assertEquals("qucs/QU-1.yaml", first.cells[1].rel)
        assertEquals("ユースX", (first.cells[1].value as? YNode.Scalar)?.value)
        assertEquals("qucs/QU-1.yaml", first.cells[2].rel)
        assertEquals(parseYPath("owner"), first.cells[2].path)
        assertTrue(first.cells[2].column.editable)
        // テキスト表現には束縛が明示される
        val text = Views.renderText(view, rows)
        assertTrue("matchクエリ・行キー: scr" in text, text)
        assertTrue("束縛: scr=QS-1, uc=QU-1" in text, text)
    }

    @Test
    fun `query rows definition errors`() {
        setupQueryRows()
        // match と idPattern の併用は排他
        assertFailsWith<RuntimeException> {
            Views.parseView(
                YamlIO.parseToRaw(
                    """
                    id: x
                    rows:
                      idPattern: "QS-*"
                      match:
                        - node: { entity: qscreen, bind: scr }
                    columns:
                      - { label: A, path: name }
                    """.trimIndent()
                ) as Map<*, *>,
                ws.resolve("_views/x.yaml"),
            )
        }
        // of は match のビュー専用・束縛は宣言済みであること
        assertFailsWith<RuntimeException> {
            Views.parseView(
                YamlIO.parseToRaw(
                    """
                    id: x
                    rows: { idPattern: "QS-*" }
                    columns:
                      - { label: A, of: scr, path: name }
                    """.trimIndent()
                ) as Map<*, *>,
                ws.resolve("_views/x.yaml"),
            )
        }
        assertFailsWith<RuntimeException> {
            Views.parseView(
                YamlIO.parseToRaw(
                    """
                    id: x
                    rows:
                      match:
                        - node: { entity: qscreen, bind: scr }
                    columns:
                      - { label: A, of: zzz, path: name }
                    """.trimIndent()
                ) as Map<*, *>,
                ws.resolve("_views/x.yaml"),
            )
        }
        // order はクエリ行と併用不可
        assertFailsWith<RuntimeException> {
            Views.parseView(
                YamlIO.parseToRaw(
                    """
                    id: x
                    rows:
                      match:
                        - node: { entity: qscreen, bind: scr }
                    order: { schemaId: qscreen }
                    columns:
                      - { label: A, path: name }
                    """.trimIndent()
                ) as Map<*, *>,
                ws.resolve("_views/x.yaml"),
            )
        }
    }
}
