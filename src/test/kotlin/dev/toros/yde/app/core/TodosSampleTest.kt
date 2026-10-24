package dev.toros.yde.app.core

import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TODO を題材に、共通型・同一要素の詳細化・検証・ビューを組み合わせた本体機能の回帰テスト。
 * 専用 fixture の common.yaml で ID の形式・日付・文字列・ステータスを定義し、期限順は日付の順に並ぶ。
 * 個票の日付の検査（期限 ≧ 作成日など）は、項目の型が日付なので type: date を書かずに日付として比べる。
 * fixture は読み取り専用に扱い、一時ディレクトリへ写してから開く。公開サンプルとは同期しない。
 */
class TodosSampleTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-todos-fixture")
        val source = Path.of("test-fixtures/workspaces/todos")
        Files.walk(source).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, text: String) {
        val target = dir.resolve(rel)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    @Test
    fun `fixture は指摘なしで読め 型は共通の型から解決され 期限順ビューは日付の順に並ぶ`() {
        val ws = Workspace.open(dir)
        val issues = ws.files.flatMap { ws.validate(ws.relPath(it)) }
        assertTrue(issues.isEmpty(), issues.toString())
        // 共通の型: 一覧の id は TODO番号（id の型）、作成日は 年月日（date ＋ 見せ方）、タイトルは 一行の文（line）
        val detail = ws.schemas.first { it.id == "todo-detail" }
        // 個票の root はオブジェクト型 TODO個票（同一性と日付の検査を型が持つ）
        val detailRoot = detail.tree.resolve(detail.root)!!
        assertEquals("todo", detailRoot.entity?.type)
        assertEquals(5, detailRoot.checks.size)
        val created = detail.tree.resolve(detailRoot.fields.first { it.key == "created_on" })!!
        assertEquals("common#年月日", created.lineage.first())
        assertEquals("yyyy年M月d日", created.format)
        val list = ws.schemas.first { it.id == "todo-list" }
        val item = list.tree.resolve(list.root.fields.first { it.key == "todos" }.item)!!
        val title = list.tree.resolve(item.fields.first { it.key == "title" })!!
        assertTrue(title.patterns.any { "\\r\\n" in it }, title.patterns.toString())
        // entity の idType: 走査が ID の型の形式で型を付ける
        assertEquals("todo", ws.index.graph.entities.getValue("TODO-001").entityType)
        assertEquals("category", ws.index.graph.entities.getValue("CAT-001").entityType)
        // ビューは誤りなく読め、期限順は due_on（日付の項目）の順。個票の無い TODO-005 は後ろ
        val views = Views.load(dir, ws.schemas)
        assertTrue(views.errors.isEmpty(), views.errors.toString())
        assertEquals(setOf("todo-management", "todo-by-due", "category-status-matrix"), views.views.map { it.id }.toSet())
        val byDue = Views.resolve(views.view("todo-by-due")!!, ws.index, ws::treeOf, ws::schemaFor).map { it.id }
        assertEquals(listOf("TODO-001", "TODO-002", "TODO-003", "TODO-004", "TODO-005"), byDue)
        // 更新日: コメントの並び順によらず最新の日付（TODO-004 のコメントは新しい方が先に書かれている）
        val management = Views.resolve(views.view("todo-management")!!, ws.index, ws::treeOf, ws::schemaFor)
        val updated = management.first { it.id == "TODO-004" }.cells.first { it.column.label == "更新日" }
        assertEquals("2026-10-02", (updated.value as dev.toros.yde.yaml.model.YNode.Scalar).value.toString())
    }

    @Test
    fun `個票の日付の検査は日付として比べ 一覧の 1 行の文は改行を誤りにする`() {
        write("todos/details/TODO-004.yaml", "id: TODO-004\nstatus: CLOSED\ncreated_on: 2026-10-01\ndue_on: 2026-09-20\ncomments: []\n")
        write(
            "todos/todo-list.yaml",
            Files.readString(dir.resolve("todos/todo-list.yaml")) + "  - id: TODO-6\n    title: |\n      改行を\n      含む\n    summary: x\n",
        )
        val ws = Workspace.open(dir)
        val detailIssues = ws.validate("todos/details/TODO-004.yaml")
        // 期限 2026-09-20 < 作成日 2026-10-01。完了日の欠落も、型に定義した検査でエラー
        val late = detailIssues.first { it.source.ruleId == "due-not-before-created" }
        assertEquals(Severity.ERROR, late.severity)
        // 型の検査: 要素（個票）ごとに評価され、指摘は個票に出る
        assertEquals("common#TODO個票 の要素「TODO-004」が検査を満たしません。反例: due_on: 2026-09-20 < created_on: 2026-10-01 — 期限は作成日以降であること", late.message)
        val closed = detailIssues.first { it.source.ruleId == "closed-has-completed-on" }
        assertEquals(Severity.ERROR, closed.severity)
        assertEquals("完了した TODO「TODO-004」に完了日がありません — 完了した TODO には完了日があること", closed.message)
        val listIssues = ws.validate("todos/todo-list.yaml").map { it.message }
        assertTrue(listIssues.any { "「ID」は形式（^TODO-[0-9]{3,}$）に一致していません（現在値: TODO-6）" in it }, listIssues.toString())
        assertTrue(listIssues.any { it.startsWith("「タイトル」は形式（^[^\\r\\n]*$）に一致していません") }, listIssues.toString())
        // 行の形（オブジェクト型）で型が決まるので、ID の形に合わなくても要素は todo（ID の形の指摘ではなく、型 TODO番号 の形式の誤りが出る）
        assertEquals("todo", ws.index.graph.entities.getValue("TODO-6").entityType)
        assertTrue(listIssues.none { "ID の決まり" in it }, listIssues.toString())
    }
}
