package dev.toros.yde.app.core

import dev.toros.yde.app.cli.runCli
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.app.agent.ActivityDef
import dev.toros.yde.app.agent.AgentHost
import dev.toros.yde.app.agent.AgentToolbox
import dev.toros.yde.app.agent.Staging
import dev.toros.yde.app.agent.ToolGroup
import dev.toros.yde.app.ai.ToolCall
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.patch.Patcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertEquals("screens/detail/{id}.yaml", view.detail?.filePattern?.text)
        assertEquals(parseYPath("screens"), view.newRow?.path)
    }

    @Test
    fun `column display grouped is parsed for path and value columns`() {
        write(
            "_views/grouped.yaml",
            """
            id: grouped
            name: 桁区切り
            rows: { idPattern: "SCR-*" }
            columns:
              - { label: 名前, schemaId: screen-list, path: name }
              - { label: 幅, schemaId: screen-list, path: width, display: grouped }
              - { label: 合計, schemaId: screen-list, value: { sum: { path: "sizes[*]" } }, display: grouped }
            """
        )
        write(
            "_views/bad-display.yaml",
            """
            id: bad-display
            name: 桁区切りの誤り
            rows: { idPattern: "SCR-*" }
            columns:
              - { label: 幅, schemaId: screen-list, path: width, display: table }
            """
        )
        val loaded = Views.load(ws)
        val view = loaded.view("grouped")!!
        assertEquals(
            listOf(null, dev.toros.yde.semantic.schema.NumberDisplay.GROUPED, dev.toros.yde.semantic.schema.NumberDisplay.GROUPED),
            view.columns.map { it.display },
        )
        assertTrue(loaded.errors.any { "bad-display.yaml" in it && "grouped" in it }, loaded.errors.toString())
    }

    @Test
    fun `order path sorts rows by a value read from the anchor occurrence`() {
        write(
            "_schemas/tx.yaml",
            """
            id: tx
            name: 取引
            match: "tx/*.yaml"
            root:
              type: object
              fields:
                - key: entries
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: date, type: date }
                      - { key: amount, type: int }
            """,
        )
        write(
            "tx/a.yaml",
            """
            entries:
              - { id: TX-1, date: "2021-08-31", amount: 9 }
              - { id: TX-2, date: "2021-08-02", amount: 10000 }
              - { id: TX-3, amount: 500 }
              - { id: TX-4, date: "2021-08-15", amount: 10000 }
            """,
        )
        fun view(id: String, order: String) = write(
            "_views/$id.yaml",
            """
            id: $id
            name: $id
            rows: { idPattern: "TX-*" }
            order: $order
            columns:
              - { label: 金額, schemaId: tx, path: amount }
            """,
        )
        view("by-date", "{ schemaId: tx, path: date, type: date, direction: desc }")
        view("by-amount", "{ path: amount, type: number }")
        view("by-text", "{ path: amount }")
        view("bad-order", "{ direction: desc }")
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        fun ids(id: String) = Views.resolve(loaded.view(id)!!, workspace.index, workspace::treeOf).map { it.id }
        // 日付の新しい順。日付の無い行は後ろ
        assertEquals(listOf("TX-1", "TX-4", "TX-2", "TX-3"), ids("by-date"))
        // 数として: 9 < 500 < 10000。同じ値は ID 順
        assertEquals(listOf("TX-1", "TX-3", "TX-2", "TX-4"), ids("by-amount"))
        // 文字として: "10000" < "500" < "9"
        assertEquals(listOf("TX-2", "TX-4", "TX-3", "TX-1"), ids("by-text"))
        assertTrue(loaded.errors.any { "bad-order.yaml" in it && "order.direction" in it }, loaded.errors.toString())
    }

    // ---------------------------------------------------------------- クロス集計（kind: matrix）

    private fun writeMatrixFixtures() {
        write(
            "_schemas/months.yaml",
            """
            id: months
            name: 月
            match: "m/*.yaml"
            entity: { type: month, idPattern: "M-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: yearMonth, type: string, role: title }
            """,
        )
        write(
            "_schemas/categories.yaml",
            """
            id: categories
            name: 費目
            match: "cat/*.yaml"
            entity: { type: category, idPattern: "CAT-*", defines: true }
            root:
              type: object
              fields:
                - key: categories
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/tx.yaml",
            """
            id: tx
            name: 取引
            match: "tx/*.yaml"
            entity: { type: transaction, idPattern: "TX-*", defines: true }
            root:
              type: object
              fields:
                - key: entries
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: kind, type: enum, values: [income, expense] }
                      - { key: amount, type: int }
                      - { key: category, type: ref, idPattern: "CAT-*" }
                      - { key: month, type: ref, idPattern: "M-*" }
            """,
        )
        write("m/2021-08.yaml", "id: M-2021-08\nyearMonth: \"2021-08\"")
        write("m/2021-09.yaml", "id: M-2021-09\nyearMonth: \"2021-09\"")
        // 費目は文書内の順（家賃 → 食費）が ID 順（CAT-FOOD → CAT-RENT）と違う
        write("cat/list.yaml", "categories:\n  - { id: CAT-RENT, name: 家賃 }\n  - { id: CAT-FOOD, name: 食費 }")
        write(
            "tx/a.yaml",
            """
            entries:
              - { id: TX-1, kind: expense, amount: 100, category: CAT-FOOD, month: M-2021-08 }
              - { id: TX-2, kind: expense, amount: 200, category: CAT-RENT, month: M-2021-08 }
              - { id: TX-3, kind: income, amount: 50, month: M-2021-08 }
              - { id: TX-4, kind: expense, amount: 300, category: CAT-FOOD, month: M-2021-09 }
            """,
        )
    }

    @Test
    fun `matrix view expands columns from referenced entities with unclassified and totals`() {
        writeMatrixFixtures()
        write(
            "_views/month-by-category.yaml",
            """
            id: month-by-category
            name: 月×費目
            kind: matrix
            rows: { idPattern: "M-*" }
            display: grouped
            cells:
              match:
                - node: { entity: month, bind: m }
                - edge: { via: month, dir: in }
                - node: { entity: transaction, bind: tx }
              of: tx
              columns: { via: category, idPattern: "CAT-*", order: { schemaId: categories } }
              aggregate: sum
              path: amount
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.none { "month-by-category" in it }, loaded.errors.toString())
        val view = loaded.view("month-by-category")!!
        // 列は索引から広げる（費目は文書内の順）。ヘッダ用の列と、解決した行のセルの列は同じ
        val effective = Views.effective(view, workspace.index)
        assertEquals(listOf("家賃", "食費", "（未分類）", "合計"), effective.columns.map { it.label })
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
        fun cells(id: String) = rows.single { it.id == id }.cells.map { Views.cellText(it.value) }
        assertEquals(listOf("M-2021-08", "M-2021-09", "合計"), rows.map { it.id })
        assertEquals(effective.columns.map { it.label }, rows.first().cells.map { it.column.label })
        assertEquals(listOf("200", "100", "50", "350"), cells("M-2021-08"))
        assertEquals(listOf("0", "300", "0", "300"), cells("M-2021-09"))
        assertEquals(listOf("200", "400", "50", "650"), cells("合計"))
        assertTrue(rows.last().occurrences.isEmpty())
        assertTrue(rows.first().cells.all { it.computed && it.column.display == dev.toros.yde.semantic.schema.NumberDisplay.GROUPED })
    }

    @Test
    fun `matrix view can use values of a field as columns and count without totals`() {
        writeMatrixFixtures()
        write(
            "_views/month-by-kind.yaml",
            """
            id: month-by-kind
            name: 月×区分
            kind: matrix
            rows: { idPattern: "M-*" }
            totals: false
            unclassified: false
            cells:
              match:
                - node: { entity: month, bind: m }
                - edge: { via: month, dir: in }
                - node: { entity: transaction, bind: tx }
              of: tx
              columns: { path: kind, values: [ { value: income, label: 収入 }, expense ] }
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.none { "month-by-kind" in it }, loaded.errors.toString())
        val rows = Views.resolve(loaded.view("month-by-kind")!!, workspace.index, workspace::treeOf)
        assertEquals(listOf("M-2021-08", "M-2021-09"), rows.map { it.id })
        assertEquals(listOf("収入", "expense"), rows.first().cells.map { it.column.label })
        assertEquals(listOf("1", "2"), rows[0].cells.map { Views.cellText(it.value) })
        assertEquals(listOf("0", "1"), rows[1].cells.map { Views.cellText(it.value) })
    }

    @Test
    fun `each column expands into one column per entity substituting the column id`() {
        writeMatrixFixtures()
        write(
            "_views/month-by-category-b.yaml",
            """
            id: month-by-category-b
            name: 月×費目（列を広げる）
            rows: { idPattern: "M-*" }
            columns:
              - { label: 年月, schemaId: months, path: yearMonth }
              - each: { idPattern: "CAT-*", bind: cat, order: { schemaId: categories } }
                display: grouped
                value:
                  sum:
                    all:
                      of: tx
                      path: amount
                      match:
                        - node: { entity: month, bind: m }
                        - edge: { via: month, dir: in }
                        - node: { entity: transaction, bind: tx }
                        - edge: { via: category }
                        - node: { id: ${'$'}cat }
              - label: 合計
                schemaId: months
                value:
                  sum: { all: { of: tx, path: amount, match: [ { node: { entity: month, bind: m } }, { edge: { via: month, dir: in } }, { node: { entity: transaction, bind: tx } } ] } }
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.none { "month-by-category-b" in it }, loaded.errors.toString())
        val view = loaded.view("month-by-category-b")!!
        assertEquals(listOf("年月", "家賃", "食費", "合計"), Views.effective(view, workspace.index).columns.map { it.label })
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
        assertEquals(listOf("2021-08", "200", "100", "350"), rows[0].cells.map { Views.cellText(it.value) })
        assertEquals(listOf("2021-09", "0", "300", "300"), rows[1].cells.map { Views.cellText(it.value) })
        assertTrue(rows[0].cells[1].column.display == dev.toros.yde.semantic.schema.NumberDisplay.GROUPED)

        write("_views/bad-each1.yaml", "id: bad-each1\nrows: { idPattern: \"M-*\" }\ncolumns:\n  - each: { idPattern: \"CAT-*\" }\n    value: 1\n")
        write("_views/bad-each2.yaml", "id: bad-each2\nrows: { idPattern: \"M-*\" }\ncolumns:\n  - each: { idPattern: \"CAT-*\", bind: c }\n    label: x\n    value: 1\n")
        val again = Views.load(ws, workspace.schemas)
        assertTrue(again.errors.any { "bad-each1" in it && "bind" in it }, again.errors.toString())
        assertTrue(again.errors.any { "bad-each2" in it && "label" in it }, again.errors.toString())
    }

    @Test
    fun `matrix view definition errors are reported at load`() {
        writeMatrixFixtures()
        val head = "kind: matrix\nrows: { idPattern: \"M-*\" }\n"
        val cellsOk = "cells:\n  match: [ { node: { entity: month, bind: m } } ]\n  of: m\n  columns: { path: yearMonth, values: [a] }\n"
        write("_views/bad-m1.yaml", "id: bad-m1\n$head${cellsOk}columns: [ { label: x, path: id } ]")   // columns は書けない
        write("_views/bad-m2.yaml", "id: bad-m2\n${head}cells:\n  match: [ { node: { entity: month, bind: m } } ]\n  columns: { path: yearMonth, values: [a] }")  // of が無い
        write("_views/bad-m3.yaml", "id: bad-m3\n${head}cells:\n  match: [ { node: { entity: month, bind: m } } ]\n  of: m\n  columns: { via: x, path: y }")  // via と path
        write("_views/bad-m4.yaml", "id: bad-m4\n${head}cells:\n  match: [ { node: { entity: month, bind: m } } ]\n  of: m\n  aggregate: sum\n  columns: { path: yearMonth, values: [a] }")  // sum に path が無い
        write("_views/bad-m5.yaml", "id: bad-m5\nrows: { idPattern: \"M-*\" }\ncolumns: [ { label: x, path: id } ]\ntotals: true")  // grid に matrix のキー
        val loaded = Views.load(ws, Workspace.open(ws).schemas)
        listOf("bad-m1" to "columns", "bad-m2" to "cells.of", "bad-m3" to "どちらか一方", "bad-m4" to "cells.path", "bad-m5" to "totals").forEach { (file, text) ->
            assertTrue(loaded.errors.any { "$file.yaml" in it && text in it }, "$file: ${loaded.errors}")
        }
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
        assertEquals("screens/detail/{id}.yaml", view.newRow?.filePattern?.text)
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
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "一覧文書の出現順で並ぶ")

        val list = rows[0]
        // 一覧側のセル: 値と座標
        val name = list.cells[0]
        assertEquals("一覧画面", (name.value as YNode.Scalar).value)
        assertEquals("screens/index.yaml", name.document?.value)
        assertEquals(parseYPath("screens[0].name"), name.path)
        assertTrue(name.column.editable)
        // 詳細側のセル: 別文書の同一IDから解決される
        val areas = list.cells[2]
        assertEquals("screens/detail/SCR-LIST.yaml", areas.document?.value)
        assertEquals(2, (areas.value as YNode.Seq).items.size)
        // 詳細出現の判定
        assertEquals("screens/detail/SCR-LIST.yaml", list.detailDocument?.value)

        val edit = rows[1]
        assertNull(edit.cells[2].document, "詳細未作成の行はセルが出現なしになる")
        assertNull(edit.detailDocument?.value)
    }

    @Test
    fun `resolve reflects injected loader`() {
        val workspace = Workspace.open(ws)
        val view = Views.load(ws).view("screen-catalog")!!
        // 未保存編集に相当するオーバーレイを loader で注入する
        val edited = YamlIO.parse(Files.readString(ws.resolve("screens/index.yaml")))
        Patcher.apply(edited, PatchOp.Set(parseYPath("screens[0].name"), YNode.Scalar("編集済みの画面名")))
        val rows = Views.resolve(view, workspace.index, loader = { d ->
            if (d.value == "screens/index.yaml") edited
            else workspace.treeOf(d)
        })
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
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)

        val list = rows.first { it.id == "SCR-LIST" }
        val backlinks = list.cells[1].backlinks!!
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), backlinks.map { it.sourceId }, "参照元ファイル順で並ぶ")
        assertEquals("tables/TBL-LIST-M.yaml", backlinks[0].document.value)
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
    private fun writeDerivedFixtures() {
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
                schemaId: vt
                value:
                  format: { in: { schemaId: vt-ddl }, path: to }
                  scopes:
                    - { in: { schemaId: vt-ddl } }
              - label: 方言変換
                schemaId: vt
                value:
                  map:
                    format: { in: { schemaId: vt-ddl }, path: to }
                    scopes:
                      - { in: { schemaId: vt-ddl } }
                  using:
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
                schemaId: attr
                value:
                  first:
                    - format: { via: vtype, in: { schemaId: vt-ddl }, path: to }
                      scopes:
                        - { via: vtype, in: { schemaId: vt-ddl } }
                        - { via: vtype, in: { schemaId: vt } }
                    - text
            """,
        )
    }

    @Test
    fun `value column expands a template read from another occurrence of the same id`() {
        writeDerivedFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val rows = Views.resolve(loaded.view("vt-computed")!!, workspace.index, workspace::treeOf)

        // 同じ ID の写像行（in: vt-ddl）のひな型を、写像行 → 行の要素（値型の定義。暗黙の末尾）の順で穴埋めする
        val title = rows.first { it.id == "VT-TITLE" }
        val ddl = title.cells[1]
        assertEquals("varchar(100)", (ddl.value as YNode.Scalar).value)
        assertTrue(ddl.computed)
        assertEquals("ddl-mapping.yaml", ddl.document?.value, "座標はひな型（元の値）の位置")
        assertEquals(parseYPath("mappings[0].to"), ddl.path)

        // 来歴: ひな型と、穴ごとの解決値・入力値の座標（ジャンプ先の選択肢）
        val trace = ddl.trace!!
        assertEquals("varchar({maxLength})", trace.raw)
        val input = trace.inputs.single()
        assertEquals("maxLength", input.placeholder)
        assertEquals("100", input.value)
        assertEquals("types.yaml", input.document?.value, "入力値の座標は値型定義の位置")
        assertEquals(parseYPath("types[0].maxLength"), input.path)

        // 穴が埋まらなければ「得られない」（値なし・理由つき。値の式の規則。表示で気づける）
        val note = rows.first { it.id == "VT-NOTE" }
        assertNull(note.cells[1].value)
        assertEquals("埋まらない穴: {missing}", note.cells[1].trace!!.raw)

        // 値辞書は評価後の値に適用される
        assertEquals("VARCHAR2(100 CHAR)", (title.cells[2].value as YNode.Scalar).value)

        // テキスト表現（エージェント・CLI 共用）にも評価値・計算列の印・来歴（導出行）が出る
        val text = Views.renderText(loaded.view("vt-computed")!!, rows)
        assertTrue("varchar(100)" in text, text)
        assertTrue("計算値" in text, text)
        assertTrue("導出: varchar({maxLength})" in text, text)
        assertTrue("{maxLength}=100 [types.yaml :: types[0].maxLength]" in text, text)
        assertTrue("（値が得られません: 埋まらない穴: {missing}）" in text, text)
    }

    @Test
    fun `value column follows one reference with via and falls back with first`() {
        writeDerivedFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val rows = Views.resolve(loaded.view("fld-computed")!!, workspace.index, workspace::treeOf)

        // via: 属性の参照（vtype）を 1 段辿り、参照先 ID の写像行のひな型を、写像行 → 値型の定義 → 行の要素の順で穴埋めする
        val titled = rows.first { it.id == "FLD-TITLE" }
        assertEquals("varchar(100)", (titled.cells[0].value as YNode.Scalar).value)
        assertEquals("ddl-mapping.yaml", titled.cells[0].document?.value)
        assertEquals("types.yaml", titled.cells[0].trace!!.inputs.single().document?.value)

        // 参照が書かれていない行は first の既定値に落ちる（決まった値なので座標はない）
        val free = rows.first { it.id == "FLD-FREE" }
        assertEquals("text", (free.cells[0].value as YNode.Scalar).value)
        assertNull(free.cells[0].document)
    }

    @Test
    fun `computed column is rejected with a hint that shows the equivalent value column`() {
        writeDerivedFixtures()
        write(
            "_views/old-computed.yaml",
            """
            id: old
            rows: { idPattern: "FLD-*" }
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
        val errors = Views.load(ws, SchemaLoader.loadAll(ws)).errors
        val error = errors.single { "old-computed.yaml" in it }
        assertTrue("computed は廃止されました" in error, error)
        // 案内には同じ意味の value の列（参照を辿る via・相手の in・穴の入れ物・既定値）が出る
        assertTrue("schemaId: attr" in error, error)
        assertTrue("via: vtype" in error, error)
        assertTrue("schemaId: vt-ddl" in error, error)
        assertTrue("path: to" in error, error)
        assertTrue("first" in error && "text" in error, error)
    }

    @Test
    fun `value column reads query row bindings with of`() {
        setupQueryRows()
        write(
            "_views/join-value.yaml",
            """
            id: join-value
            name: 画面×ユースケース（値の式）
            rows:
              match:
                - node: { entity: qscreen, bind: scr }
                - edge: { relation: uses }
                - node: { entity: qusecase, bind: uc }
            columns:
              - { label: UC名, value: { of: uc, path: name } }
              - { label: 要約, value: { format: "{name} → {uc}", with: { uc: { of: uc, path: name } } } }
              - { label: uses数, value: { count: { all: { of: scr, path: "uses[*]" } } } }
            """,
        )
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val rows = Views.resolve(loaded.view("join-value")!!, workspace.index, workspace::treeOf)
        val first = rows.first { it.key == "scr=QS-1|uc=QU-1" }
        // 束縛の読み出しは結合先の出現から（座標も結合先）
        assertEquals("ユースX", (first.cells[0].value as YNode.Scalar).value)
        assertEquals("qucs/QU-1.yaml", first.cells[0].document?.value)
        assertEquals("画面A → ユースX", (first.cells[1].value as YNode.Scalar).value)
        // 束縛の配列にわたる集計（行を 1 つの解と見なす）
        assertEquals("2", (first.cells[2].value as YNode.Scalar).value)
    }

    // ---------------------------------------------------------------- 値の式の列

    /** TODO 個票（コメントの日付の配列）と、最終更新日をコメントから導く一覧（値の式の列）。 */
    private fun writeValueColumnFixtures() {
        write(
            "_schemas/todo-detail.yaml",
            """
            id: todo-detail
            name: TODO 個票
            match: "todos/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, label: 件名, type: string, role: title }
                - { key: status, type: string }
                - key: comments
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: date, label: 日付, type: date }
                      - { key: body, type: text }
            """,
        )
        write(
            "todos/T-1.yaml",
            """
            id: T-1
            name: 検索の改善
            status: doing
            comments:
              - { date: "2026-09-02", body: 対応を開始した。 }
              - { date: "2026-09-05", body: 動作確認を実施した。 }
              - { date: "2026-09-01", body: 過去の記録を追記した。 }
            """,
        )
        write("todos/T-2.yaml", "id: T-2\nname: 未着手の件\nstatus: todo\n")
        write("todos/T-3.yaml", "id: T-3\nname: 日付なし\nstatus: doing\ncomments:\n  - { date: \"2026-09-02\", body: a }\n  - { body: 日付を忘れた }\n")
        write(
            "_views/todo-board.yaml",
            """
            id: todo-board
            name: TODO 一覧
            rows: { idPattern: "T-*" }
            columns:
              - label: 件名
                schemaId: todo-detail
                path: name
              - label: 最終更新日
                schemaId: todo-detail
                value:
                  max: { path: "comments[*].date", type: date }
              - label: 最終更新日（既定値つき）
                schemaId: todo-detail
                value:
                  first:
                    - max: { path: "comments[*].date", type: date }
                    - 未更新
              - label: 要約
                schemaId: todo-detail
                value: "{id} {name}（{status}）"
              - label: コメント数
                schemaId: todo-detail
                value: { count: { path: "comments[*]" } }
            """,
        )
    }

    @Test
    fun `value column aggregates array dates regardless of order and follows unsaved edits`() {
        writeValueColumnFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("todo-board")!!
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)

        val t1 = rows.first { it.id == "T-1" }
        val latest = t1.cells[1]
        assertEquals("2026-09-05", (latest.value as YNode.Scalar).value)
        assertTrue(latest.computed)
        // 座標は選ばれた要素（最新のコメント）＝ジャンプ先。来歴は全ての材料
        assertEquals("todos/T-1.yaml", latest.document?.value)
        assertEquals(parseYPath("comments[1].date"), latest.path)
        assertEquals("最大: 2026-09-02・2026-09-05・2026-09-01", latest.trace!!.raw)
        assertEquals(listOf("comments[0].date", "comments[1].date", "comments[2].date"), latest.trace!!.inputs.map { it.placeholder })
        // 短い書き方は行の要素の値と予約の名前で穴を埋める（値は特定の 1 か所ではないので座標なし）
        assertEquals("T-1 検索の改善（doing）", (t1.cells[3].value as YNode.Scalar).value)
        assertNull(t1.cells[3].document)
        assertEquals("3", (t1.cells[4].value as YNode.Scalar).value)

        // 配列が無い行: 得られない（値なし・理由つき）。first で既定値に落とせる
        val t2 = rows.first { it.id == "T-2" }
        assertNull(t2.cells[1].value)
        assertEquals("値がありません", t2.cells[1].trace!!.raw)
        assertEquals("未更新", (t2.cells[2].value as YNode.Scalar).value)
        assertEquals("0", (t2.cells[4].value as YNode.Scalar).value)

        // 日付が書かれていないコメントが 1 つでもあれば得られない（厳格。表示で気づける）
        val t3 = rows.first { it.id == "T-3" }
        assertNull(t3.cells[1].value)
        assertEquals("comments[1].date が書かれていません", t3.cells[1].trace!!.raw)

        // 未保存の編集に追随: 読み手（loader）が編集中の木を返せば、保存しなくても値が変わる
        val edited = YamlIO.parse(Files.readString(ws.resolve("todos/T-1.yaml")).replace("2026-09-05", "2026-09-09"))
        val liveRows = Views.resolve(view, workspace.index, loader = { d -> if (d.value == "todos/T-1.yaml") edited else workspace.treeOf(d) })
        assertEquals("2026-09-09", (liveRows.first { it.id == "T-1" }.cells[1].value as YNode.Scalar).value)

        // テキスト表現（エージェント・CLI 共用）にも値・座標・来歴が出る
        val text = Views.renderText(view, rows)
        assertTrue("最終更新日: 2026-09-05  [todos/T-1.yaml :: comments[1].date  計算値（座標は値の出どころ）]" in text, text)
        assertTrue("導出: 最大: 2026-09-02・2026-09-05・2026-09-01" in text, text)
        assertTrue("（値が得られません: comments[1].date が書かれていません）" in text, text)
        assertTrue("T-1 検索の改善（doing）  [計算値（座標なし）]" in text, text)
    }

    @Test
    fun `value column rejects invalid combinations and lints paths and date ordering`() {
        writeValueColumnFixtures()
        write("_views/bad-v1.yaml", "id: v1\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    path: name\n    value: \"{name}\"")
        write("_views/bad-v2.yaml", "id: v2\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    editable: true\n    value: \"{name}\"")
        write("_views/bad-v3.yaml", "id: v3\nrows: { idPattern: \"*\" }\ncolumns:\n  - label: A\n    value: { max: { path: \"comments[*].date\", type: text } }")
        write(
            "_views/lint-v4.yaml",
            """
            id: v4
            rows: { idPattern: "*" }
            columns:
              - label: A
                schemaId: todo-detail
                value: { max: { path: "comments[*].date", type: number } }
              - label: B
                schemaId: todo-detail
                value: "{nosuch}"
              - label: C
                schemaId: todo-detail
                value: { max: { path: "comments[*].body", type: date } }
              - label: D
                schemaId: todo-detail
                value: { max: { path: "comments[*].date" } }
            """,
        )
        val errors = Views.load(ws, SchemaLoader.loadAll(ws)).errors
        assertTrue(errors.any { "bad-v1.yaml" in it && "value と path は併用できません" in it }, errors.toString())
        assertTrue(errors.any { "bad-v2.yaml" in it && "editable" in it }, errors.toString())
        assertTrue(errors.any { "bad-v3.yaml" in it && "type は number / date" in it }, errors.toString())
        // 書いた順序の種類が項目の型と食い違えば指摘。省けば項目の型で比べるので指摘しない（列 D）
        assertTrue(errors.any { "lint-v4.yaml" in it && "列「A」" in it && "日付の項目です" in it }, errors.toString())
        assertTrue(errors.any { "lint-v4.yaml" in it && "列「B」" in it && "「nosuch」" in it && "解決できません" in it }, errors.toString())
        assertTrue(errors.any { "lint-v4.yaml" in it && "列「C」" in it && "日付の項目ではありません" in it }, errors.toString())
        assertTrue(errors.none { "lint-v4.yaml" in it && "列「D」" in it }, errors.toString())
        // 正しい定義には指摘が無い
        assertTrue(errors.none { "todo-board.yaml" in it }, errors.toString())
    }

    // ---------------------------------------------------------------- 解の件数の列（カテゴリ × 状態の集計表）

    /** カテゴリ一覧・TODO 一覧（カテゴリ参照）・TODO 個票（状態）と、行＝カテゴリ・列＝状態ごとの件数の集計表。 */
    private fun writeCountFixtures() {
        write(
            "_schemas/category-list.yaml",
            """
            id: category-list
            match: "todos/category-list.yaml"
            entity: { type: category, idPattern: "CAT-*", defines: true }
            root:
              type: object
              fields:
                - key: categories
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/todo-list.yaml",
            """
            id: todo-list
            match: "todos/todo-list.yaml"
            entity: { type: todo, idPattern: "TODO-*", defines: true }
            root:
              type: object
              fields:
                - key: todos
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: title, type: string, role: title }
                      - { key: category, type: ref, idPattern: "CAT-*", scope: { schemaIds: [category-list] } }
            """,
        )
        write(
            "_schemas/todo-detail.yaml",
            """
            id: todo-detail
            match: "todos/details/*.yaml"
            entity: { type: todo, idPattern: "TODO-*", defines: false }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: enum, values: [OPEN, IN_PROGRESS, CLOSED, CANCELED] }
            """,
        )
        write(
            "todos/category-list.yaml",
            """
            categories:
              - { id: CAT-001, name: 環境整備 }
              - { id: CAT-002, name: データ登録 }
            """,
        )
        write(
            "todos/todo-list.yaml",
            """
            todos:
              - { id: TODO-001, title: 一覧の作成, category: CAT-001 }
              - { id: TODO-002, title: TODO の追加, category: CAT-002 }
              - { id: TODO-003, title: 個票の作成, category: CAT-002 }
              - { id: TODO-004, title: 集計表の検討, category: CAT-001 }
              - { id: TODO-005, title: README の更新, category: CAT-002 }
              - { id: TODO-006, title: 未分類の作業 }
              - { id: TODO-007, title: 中止した作業, category: CAT-001 }
            """,
        )
        mapOf(
            "TODO-001" to "CLOSED", "TODO-002" to "CLOSED", "TODO-003" to "CLOSED", "TODO-004" to "OPEN",
            "TODO-005" to "IN_PROGRESS", "TODO-006" to "OPEN", "TODO-007" to "CANCELED",
        ).forEach { (id, status) -> write("todos/details/$id.yaml", listOf("id: $id", "status: $status").joinToString(System.lineSeparator())) }
        fun countColumn(label: String, status: String?) = """
              - label: $label
                schemaId: category-list
                value:
                  count:
                    all:
                      of: t
                      match:
                        - node: { entity: category, bind: cat }
                        - edge: { via: category, dir: in }
                        - node: { entity: todo, bind: t }
        """.trimEnd() + (status?.let { System.lineSeparator() + "                      where: [ { of: t, path: status, equals: $it } ]" } ?: "")
        write(
            "_views/category-status.yaml",
            """
            id: category-status
            name: カテゴリ×ステータス
            rows: { idPattern: "CAT-*" }
            columns:
              - { label: カテゴリ名, schemaId: category-list, path: name }
            ${countColumn("未着手", "OPEN")}
            ${countColumn("対応中", "IN_PROGRESS")}
            ${countColumn("完了", "CLOSED")}
            ${countColumn("中止", "CANCELED")}
            ${countColumn("合計", null)}
            """,
        )
        // クエリ行のビュー: 束縛（of）を起点にして数える
        write(
            "_views/category-status-query.yaml",
            """
            id: category-status-query
            name: カテゴリ×未着手（クエリ行）
            rows:
              match:
                - node: { entity: category, bind: c }
            columns:
              - { label: カテゴリ名, path: name }
              - label: 未着手
                value:
                  count:
                    all:
                      from: c
                      of: t
                      match:
                        - node: { entity: category, bind: cat }
                        - edge: { via: category, dir: in }
                        - node: { entity: todo, bind: t }
                      where: [ { of: t, path: status, equals: OPEN } ]
            """,
        )
    }

    @Test
    fun `value column counts the solutions collected by all from the row and lists the counted nodes`() {
        writeCountFixtures()
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val view = loaded.view("category-status")!!
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
        fun cells(id: String) = rows.first { it.id == id }.cells.drop(1).map { (it.value as YNode.Scalar).value }
        // 行＝カテゴリ、列＝状態ごとの件数と合計。カテゴリの無い TODO-006 はどの行にも数えられない
        assertEquals(listOf("1", "0", "1", "1", "3"), cells("CAT-001"))
        assertEquals(listOf("0", "1", "2", "0", "3"), cells("CAT-002"))

        // 来歴: 数えた TODO（座標は一覧の定義出現＝ジャンプ先）。件数そのものは特定の 1 か所ではない（座標なし）
        val open = rows.first { it.id == "CAT-001" }.cells[1]
        assertTrue(open.computed)
        assertNull(open.document)
        assertEquals("件数: TODO-004", open.trace!!.raw)
        val input = open.trace!!.inputs.single()
        assertEquals("t", input.placeholder)
        assertEquals("TODO-004", input.value)
        assertEquals("todos/todo-list.yaml", input.document?.value)
        assertEquals(parseYPath("todos[3]"), input.path)
        val zero = rows.first { it.id == "CAT-002" }.cells[1]
        assertEquals("0", (zero.value as YNode.Scalar).value)
        assertTrue(zero.trace!!.inputs.isEmpty())

        // 未保存の編集に追随: 読み手が編集中の木を返せば、個票の状態を変えただけで件数が動く
        val edited = YamlIO.parse(listOf("id: TODO-004", "status: IN_PROGRESS").joinToString(System.lineSeparator()))
        val liveRows = Views.resolve(view, workspace.index, loader = { d -> if (d.value == "todos/details/TODO-004.yaml") edited else workspace.treeOf(d) })
        assertEquals(listOf("0", "1", "1", "1", "3"), liveRows.first { it.id == "CAT-001" }.cells.drop(1).map { (it.value as YNode.Scalar).value })

        // テキスト表現（エージェント・CLI 共用）にも件数と来歴が出る
        val text = Views.renderText(view, rows)
        assertTrue("未着手: 1  [計算値（座標なし）]" in text, text)
        assertTrue("導出: 件数: TODO-004" in text, text)

        // クエリ行のビュー: of の束縛を起点に数える
        val queryRows = Views.resolve(loaded.view("category-status-query")!!, workspace.index, workspace::treeOf)
        assertEquals(listOf("1", "0"), queryRows.map { (it.cells[1].value as YNode.Scalar).value })
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
    fun `文書ビュー定義のエラーを収集する`() {
        // match / schemaId なし
        write("_views/doc-bad1.yaml", "id: d1\nkind: document\nsections:\n  - fields: {}")
        // fields・backlinks・flowのどれもないセクション
        write("_views/doc-bad2.yaml", "id: d2\nkind: document\nmatch: \"a.yaml\"\nsections:\n  - label: A")
        // グリッド専用キーの混入
        write("_views/doc-bad3.yaml", "id: d3\nkind: document\nmatch: \"a.yaml\"\nrows: { idPattern: \"*\" }\nsections:\n  - fields: {}")
        // 未知の kind
        write("_views/doc-bad4.yaml", "id: d4\nkind: unknown")
        // グリッドへの sections の混入
        write("_views/doc-bad5.yaml", "id: d5\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nsections: []")
        val loaded = Views.load(ws)
        assertTrue(loaded.errors.any { "doc-bad1.yaml" in it && "match" in it }, loaded.errors.toString())
        assertTrue(loaded.errors.any { "doc-bad2.yaml" in it && "fields / backlinks / flow" in it }, loaded.errors.toString())
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
        val sections = Views.resolveDocument(view, DocumentId("screens/index.yaml"), workspace.index, loader = workspace::treeOf)
        assertEquals(2, sections.size)
        assertNull(sections[0].rows, "fields セクションは行を持たない（描画側が文書ツリーから直接描く）")

        val rows = sections[1].rows!!
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "文書内の出現順で並ぶ")
        assertEquals(parseYPath("screens[0]"), rows[0].path)
        val backlinks = rows[0].backlinks
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), backlinks.map { it.sourceId }, "参照元ファイル順で並ぶ")
        assertEquals("tables/TBL-LIST-M.yaml", backlinks[0].document.value)
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
        val host = object : AgentHost {
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
        val attachments = Views.resolveAttachments(view, workspace.documentOf(file), workspace.index, loader = workspace::treeOf)
        val rows = attachments.single().rows
        assertEquals(listOf("SCR-LIST", "SCR-EDIT"), rows.map { it.id }, "要素の文書内順で並ぶ")
        assertEquals(parseYPath("screens[0]"), rows[0].path, "差し込み先の要素座標を持つ")
        assertEquals(listOf("TBL-LIST-M", "TBL-LIST-S"), rows[0].backlinks.map { it.sourceId })
        assertEquals("TBL-LIST-M", (rows[0].backlinks[0].values[0] as YNode.Scalar).value, "columns は参照元要素からの相対値")
        assertTrue(rows[1].backlinks.isEmpty(), "relation が一致しない参照は現れない")

        // テキスト表現（エージェント・CLI 共用）に選別・アタッチが載る
        val sections = Views.resolveDocument(view, workspace.documentOf(file), workspace.index, loader = workspace::treeOf)
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

        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
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
        val editing = dev.toros.yde.yaml.YamlIO.parse(
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
        val broken = dev.toros.yde.yaml.YamlIO.parse("id: x\nkind: nope")
        assertTrue(Views.lintDocument(broken, ws.resolve("_views/x.yaml"), schemas).isNotEmpty())
        // 正しい定義には指摘を出さない
        val ok = dev.toros.yde.yaml.YamlIO.parse(
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
        val scaffold = Views.detailScaffold(view, ScaffoldSource.idOnly("SCR-EDIT"), workspace.schemas)
        assertEquals("screens/detail/SCR-EDIT.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        assertEquals("SCR-EDIT", (map.entries["id"] as YNode.Scalar).value)
        assertEquals("single-column", (map.entries["layout"] as YNode.Scalar).value, "スキーマの default を尊重する")
        // 不正なIDはファイル名にできない
        assertFailsWith<IllegalArgumentException> { Views.detailScaffold(view, ScaffoldSource.idOnly("../escape"), workspace.schemas) }
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
        val host = object : AgentHost {
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
        val rows = Views.resolve(view, workspace.index, workspace::treeOf)
        // 行 = 束縛行（結合）。行IDは行キー束縛で、結合により重複しうる。key は束縛値の組で一意
        assertEquals(listOf("QS-1", "QS-1", "QS-2"), rows.map { it.id })
        assertEquals(
            listOf("scr=QS-1|uc=QU-1", "scr=QS-1|uc=QU-2", "scr=QS-2|uc=QU-2"),
            rows.map { it.key },
        )
        val first = rows[0]
        // of 省略の列は行キー束縛（qscreen）の座標へ、of: uc の列は結合先（qusecase）の座標へ解決される
        assertEquals("qscreens/QS-1.yaml", first.cells[0].document?.value)
        assertEquals(parseYPath("name"), first.cells[0].path)
        assertEquals("画面A", (first.cells[0].value as? YNode.Scalar)?.value)
        assertEquals("qucs/QU-1.yaml", first.cells[1].document?.value)
        assertEquals("ユースX", (first.cells[1].value as? YNode.Scalar)?.value)
        assertEquals("qucs/QU-1.yaml", first.cells[2].document?.value)
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

    @Test
    fun `filePattern は値の式 newRow に書ける穴は id だけで detail の id 無しは注意になる`() {
        write("_views/bad-nr4.yaml", "id: n4\nrows: { idPattern: \"*\" }\ncolumns:\n  - { label: A, path: name }\nnewRow: { filePattern: \"x/{id}_{name}.yaml\" }")
        write("_views/no-id.yaml", "id: noid\nrows: { idPattern: \"SCR-*\" }\ncolumns:\n  - { label: A, path: name }\ndetail: { filePattern: \"screens/detail/{name}.yaml\" }")
        // リント（detail の {id} の注意）はスキーマ付きの読み込みで走る
        val loaded = Views.load(ws, Workspace.open(ws).schemas)
        assertTrue(loaded.errors.any { "bad-nr4" in it && "書ける穴は {id} だけ" in it }, loaded.errors.toString())
        assertNotNull(loaded.view("noid"), "{id} が無い detail.filePattern は定義エラーにしない")
        assertTrue(loaded.errors.any { "no-id" in it && "{id} がありません" in it }, loaded.errors.toString())
    }
}
