package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * samples/todos-sysdev（todos を業務モデルにしたシステム開発の設計書体系）: 設計書の生成物が業務モデルの写しから作り直した
 * 内容と同じ（最新）で、ワークスペース全体の検証にエラーが無い（設計の対応の検査を含む）。業務モデルや生成の定義を直したら、
 * `./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直す。
 */
class TodosSysdevTest {
    private val root = Path.of("samples/todos-sysdev")

    @Test
    fun `生成物は最新`() {
        val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(root)))
        assertEquals(emptyList(), plan.errors)
        assertTrue(plan.upToDate, "生成物が最新ではありません（作る ${plan.created} ・作り直す ${plan.updated} ・消す ${plan.deleted}）。generate samples/todos-sysdev --apply で作り直します")
    }

    /**
     * データ系（エンティティ定義書・テーブル設計書・テーブルアクセス設計書）の決め打ちの規則が、業務モデルから計算で効いていること:
     * 一覧の行と個票は 1 テーブル・明細テーブル・予約語の末尾の _・同じテーブルへの値は 1 つの update・前提の件数・CRUD 表。
     */
    @Test
    fun `データ系の設計書は業務モデルから計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = m[key] as List<*>
        val tables = list(doc("design/detail/final/tables.yaml"), "tables").map { it as Map<*, *> }.associateBy { it["name"] }
        assertEquals(listOf("category", "tag", "todo", "todo_task", "todo_tags", "todo_comments", "todo_history"), tables.keys.toList())
        val todoColumns = list(tables.getValue("todo"), "columns").map { (it as Map<*, *>)["name"] }
        assertTrue("title" in todoColumns && "status" in todoColumns, "一覧の行（title）と個票（status）の項目が 1 テーブルに: $todoColumns")
        val historyColumns = list(tables.getValue("todo_history"), "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("todo_id", "seq", "date", "action", "from_", "to_", "note", "resume_on"), historyColumns, "予約語は末尾に _")
        val taskColumns = list(tables.getValue("todo_task"), "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("id", "todo_id", "seq", "title", "done", "deleted"), taskColumns, "入れ子の型は親の ID と並び順を足す。最後の deleted は、詳細設計の方式の指定（論理削除）が足す")
        val methods = list(doc("design/detail/final/table-access/todo.yaml"), "methods").map { it as Map<*, *> }
        val complete = methods.single { it["name"] == "completeTodo" }
        assertEquals(listOf("completed_on", "result", "status", "updated_on", "version"), list(complete, "set").map { (it as Map<*, *>)["item"] }, "同じテーブルへの値は 1 つの update（最後の version は、同時更新を版で検出する方式が足す）")
        val count = list(doc("design/detail/final/table-access/todo-task.yaml"), "methods").map { it as Map<*, *> }.single { it["precondition"] == "PRE-complete-todo.no-open-task" }
        assertEquals(listOf("ACT-complete-todo"), count["sources"], "メソッドは、作る元（基本設計の操作）と、前提の項目を ID で指す")
        val conditions = list(count, "where").map { (it as Map<*, *>).filterKeys { key -> key != "column" && key != "columnId" } }
        assertEquals<List<*>>(listOf(mapOf("item" to "todo_id", "param" to "id"), mapOf("item" to "done", "equals" to false), mapOf("item" to "deleted", "equals" to false)), conditions, "最後の条件は、論理削除の方式が足す（消した作業項目は数えない）")
        val todo = list(doc("design/basic/final/entities.yaml"), "entities").map { it as Map<*, *> }.single { it["id"] == "ENT-todo" }
        val operations = list(todo, "operations").map { it as Map<*, *> }
        fun status(action: String) = list(operations.single { it["action"] == action }, "writes")
            .map { it as Map<*, *> }.singleOrNull { it["item"] == "FLD-todo.status" }?.get("crud")
        assertEquals("C", status("register-todo"))
        assertEquals("U", status("complete-todo"))
        assertEquals(null, status("edit-todo"), "編集は状態を変えない")
    }

    /**
     * 画面系（画面遷移定義書・画面定義書・Controller・セッション情報設計）は、ダッシュボードを起点に業務モデルから計算で作る:
     * 一覧画面 1・詳細画面 3（入れ子の作業項目は画面にしない）・入力画面 11・確認画面 14 の 29 画面。
     * 並びの 1 件を番号で指す操作（コメントの編集・削除）は、詳細画面のその並びの表の行のボタンから始まり、番号は行が運ぶ。ビューは定義の索引の yde-view。
     */
    @Test
    fun `画面系の設計書はダッシュボードを起点に計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val transitions = doc("design/basic/final/screens/transitions/todo-dashboard.yaml")
        val kinds = list(transitions, "screens").groupingBy { it["kind"].toString() }.eachCount()
        assertEquals(mapOf("list" to 1, "detail" to 3, "input" to 11, "confirm" to 15), kinds)
        // 画面は項目の形（1 画面 1 ファイル）。操作・入力・行き先の画面は ID で指し、指した先にある値（名称・出す条件）は写さない
        assertTrue(java.nio.file.Files.notExists(root.resolve("design/basic/final/screens/todo-task.yaml")), "入れ子の型は詳細画面にしない")
        // 読む側が使う形（画面を解いた形）では、指した先の値に置き換わっている
        val resolved = doc("design/basic/composed/screens-resolved.yaml")
        fun resolved(kind: String, id: String) = list(resolved, kind).map { it["record"] as Map<*, *> }.single { it["id"] == id }
        val start = doc("design/basic/final/screens/start-todo.yaml")
        kotlin.test.assertNull(start["input"], "受け取る値が無い操作は、入力画面を作らない")
        assertEquals("GET /todo/{id}/start-todo/confirm", (start["confirm"] as Map<*, *>)["request"])
        assertEquals(listOf(mapOf("value" to "OPEN", "label" to "未着手")), resolved("operations", "start-todo")["shownWhen"], "ボタンは Lifecycle の状態で出す")
        // 並びの 1 件を番号で指す操作: 起点は詳細画面のその並びの表の行だけ。番号は入力の項目に出さず、画面が持ち回す入力にする
        val editComment = doc("design/basic/final/screens/edit-todo-comment.yaml")
        assertEquals(listOf("PRM-edit-todo-comment.date", "PRM-edit-todo-comment.body"), (editComment["input"] as Map<*, *>)["items"], "番号は人が入れない")
        assertTrue("PRM-edit-todo-comment.row" in (editComment["hidden"] as List<*>))
        val editResolved = resolved("operations", "edit-todo-comment")
        assertEquals(listOf(mapOf("screen" to "todo", "place" to "表「コメント一覧」の行のボタン")), editResolved["starts"])
        assertEquals("row" to "comments", (editResolved["row"] as Map<*, *>).let { it["key"] to it["table"] })
        val deleteComment = doc("design/basic/final/screens/delete-todo-comment.yaml")
        kotlin.test.assertNull(deleteComment["input"], "受け取る値が番号だけの操作は、入力画面を作らない（行のボタンから確認画面へ）")
        assertEquals("GET /todo/{id}/delete-todo-comment/confirm", (deleteComment["confirm"] as Map<*, *>)["request"])
        val todoDetail = doc("design/basic/final/screens/todo.yaml")
        val commentsTable = list(todoDetail, "tables").single { it["id"] == "SEC-todo.comments" }
        assertEquals(listOf("ACT-edit-todo-comment" to "SCR-edit-todo-comment.input", "ACT-delete-todo-comment" to "SCR-delete-todo-comment.confirm"),
            list(commentsTable, "rowButtons").map { it["operation"] to it["to"] })
        kotlin.test.assertNull(list(todoDetail, "tables").single { it["id"] == "SEC-todo.history" }["rowButtons"], "行を指す操作の無い並びには、行のボタンを書かない")
        assertTrue(list(todoDetail, "buttons").none { "todo-comment" in it["operation"].toString() && it["operation"] != "ACT-add-todo-comment" }, "画面のボタンには出さない")
        assertTrue(list(todoDetail, "buttons").none { it.containsKey("label") }, "ボタンの名称は写さない（操作の名称）")
        val list = doc("design/basic/final/screens/todo-dashboard.yaml")
        assertTrue("edit-todo-comment" !in list.toString() && "delete-todo-comment" !in list.toString(), "一覧の行のボタンにも出さない")
        assertEquals(listOf("ACT-register-todo", "ACT-add-category", "ACT-add-tag"), list(list, "buttons").map { it["operation"] }, "上部のボタンはダッシュボードに書いた順")
        assertEquals(listOf("QRY-todo-dashboard.overdue", "SCR-todo"), list(list, "sections").first().let { listOf(it["query"], (it["link"] as Map<*, *>)["to"]) }, "区画は検索条件を、行の ID は詳細画面を指す")
        // 解いた形では、ボタンの名称と行き先が読む側の書き方になる
        assertEquals(listOf("TODOを登録する" to "register-todo/input"), list(resolved("lists", "todo-dashboard"), "buttons").take(1).map { it["label"] to it["to"] })
        // 画面の項目があるエンティティと操作が、ダッシュボードからたどれる範囲
        val reach = resolved["reach"] as Map<*, *>
        assertEquals(listOf(listOf("todo-dashboard"), listOf("todo", "category", "tag"), listOf("todo-task"), 15),
            listOf(reach["dashboards"], reach["entities"], reach["nested"], (reach["operations"] as List<*>).size), "詳細設計は、たどれる範囲をここから読む")
        val screenFiles = java.nio.file.Files.list(root.resolve("design/basic/final/screens")).use { files -> files.filter { java.nio.file.Files.isRegularFile(it) }.count() }
        assertEquals(19L, screenFiles, "一覧 1・詳細 3・操作 15")
        val session = doc("design/detail/final/session.yaml")
        assertEquals(listOf("csrf", "token", "notice"), list(session, "attributes").map { it["key"] })
        val index = dev.toros.yde.app.core.Workspace.open(root).index
        assertEquals("yde-view", index.entries["todo-dashboard"]?.firstOrNull { it.role == dev.toros.yde.semantic.schema.EntityRole.DEFINING }?.entityType, "ビューの定義は定義の索引の yde-view")
    }

    /**
     * 画面設計書（部品の木とフォームのクラス）と一覧の読み出し（Mapper の select）は、業務モデルから計算で作る:
     * 入力検査は業務の型から・値を消すは効果から・区画の条件と計算の列は写す規則から（「手で書く」は無い）。
     */
    @Test
    fun `画面設計書と一覧の読み出しは計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val complete = doc("design/detail/final/screen-designs/operations/complete-todo.yaml")
        val form = complete["form"] as Map<*, *>
        assertEquals("CompleteTodoForm", form["class"])
        val result = list(form, "fields").single { it["name"] == "result" }
        assertEquals(listOf(mapOf("name" to "NotNull"), mapOf("name" to "Size", "min" to 1)), result["validations"])
        assertEquals(listOf("complete-todo/input", "complete-todo/confirm"), list(complete, "screens").map { it["id"] })
        val edit = doc("design/detail/final/screen-designs/operations/edit-todo.yaml").toString()
        assertTrue("flag=clear.category" in edit, "値を消すのチェック")
        assertTrue("comments" !in edit, "コメント一覧は編集の入力に無い（追加・編集・削除の操作で変える）")
        // 番号で指す操作の画面設計: 番号は Form の項目だが入力欄にせず hidden で持ち回す。行のボタンは、行の並び順（seq）を運ぶ
        val editCommentDesign = doc("design/detail/final/screen-designs/operations/edit-todo-comment.yaml")
        assertTrue(list(editCommentDesign["form"] as Map<*, *>, "fields").any { it["item"] == "row" })
        val inputForm = (((list(editCommentDesign, "screens").first()["page"] as Map<*, *>)["body"] as List<*>).map { it as Map<*, *> }.single { "form" in it })["form"] as Map<*, *>
        assertEquals(listOf("row", "version", "from"), inputForm["hidden"], "番号（行のボタンが運ぶ）と、読んだときの版（画面が受け手から読んで運ぶ）")
        assertEquals(mapOf("table" to "comments", "param" to "row", "item" to "seq"), inputForm["row"], "初期値は、番号の指す行から読む")
        assertTrue((inputForm["body"] as List<*>).none { ((it as Map<*, *>)["field"] as? Map<*, *>)?.get("item") == "row" }, "番号の入力欄は無い")
        val deleteCommentDesign = doc("design/detail/final/screen-designs/operations/delete-todo-comment.yaml")
        assertEquals(listOf("delete-todo-comment/confirm"), list(deleteCommentDesign, "screens").map { it["id"] })
        assertTrue("対象の行（コメント一覧）" in deleteCommentDesign.toString(), "確認画面に、番号の指す行の今の値を見せる")
        val detailDesign = doc("design/detail/final/screen-designs/details/todo.yaml").toString()
        assertTrue("to=GET /todo/{id}/delete-todo-comment/confirm, row={param=row, item=seq}" in detailDesign, detailDesign.substringAfter("コメント一覧").take(600))
        val access = list(doc("design/detail/final/table-access/todo.yaml"), "methods")
        val overdue = access.single { it["name"] == "selectTodoDashboardOverdue" }
        assertTrue((overdue["where"] as List<*>).any { (it as Map<*, *>).let { row -> row["item"] == "status" && row["op"] == "in" && row["values"] == listOf("OPEN", "IN_PROGRESS") } }, "区画の条件の State は値の条件")
        assertTrue(access.any { it["name"] == "findDetail" } && access.any { it["name"] == "selectAll" })
        val matrix = list(doc("design/detail/final/table-access/category.yaml"), "methods").single { it["name"] == "selectTodoDashboardCategories" }
        assertEquals("aggregate", matrix["kind"])
        val all = TodosSysdevFiles.documents(root)
        assertTrue(all.none { "手で書く" in java.nio.file.Files.readString(it) }, "todos の業務モデルは、どれも写す規則で作れる")
    }

    /**
     * エラーとメッセージ系（エラー定義書・メッセージ定義書・エラー設計書と、処理設計書のデータの決まりの手順）は、業務モデルから計算で作る:
     * 確かめるのは操作が書くと破れうるデータの決まりだけ（中断の行の決まりは再開予定日を書く操作だけ）・確かめない決まりには理由・
     * 受け取る値が原因の決まりは確認へでも確かめる・入れ子の要素の決まりは書いた後に件数で・使うメッセージのキーはどれもメッセージ定義書にある。
     */
    @Test
    fun `エラーとメッセージ系の設計書は業務モデルから計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        // エラー（基本設計）は項目の形: 操作が断るとき 1 件を 1 項目にし、種類・操作・原因・出すメッセージを ID で指す。
        // どう確かめるか（Java か SQL か）は持たない（詳細設計の処理の手順が持つ）
        val errors = doc("design/basic/final/errors.yaml")
        val refusals = list(errors, "errors")
        fun operationsOf(rule: String) = refusals.filter { it["cause"] == "RUL-$rule" }.map { it["operation"] }
        assertEquals(listOf<Any?>("ACT-suspend-todo"), operationsOf("resume-not-before-suspend"), "中断の行の決まりは、再開予定日を書く操作だけが確かめる")
        assertEquals(listOf<Any?>("ACT-complete-todo"), operationsOf("closed-has-completed-on"), "状態を完了以外に書く操作は、完了の決まりを確かめない")
        assertEquals(emptyList<Any?>(), operationsOf("has-detail") + operationsOf("in-category.outMax"), "書かれた場所の決まりと、警告の決まりは断らない")
        val completeResult = refusals.single { it["id"] == "ERR-complete-todo.check.closed-has-result" }
        assertEquals(listOf("ERK-input-rule", listOf("PRM-complete-todo.result"), "MSG-check.closed-has-result"), listOf(completeResult["kind"], completeResult["inputs"], completeResult["message"]), "受け取る値が原因の決まりは、その入力の欄の横にも出す")
        assertEquals(listOf("ERK-state", "TRN-todo-lifecycle.complete-todo"), refusals.single { it["id"] == "ERR-complete-todo.state" }.let { listOf(it["kind"], it["cause"]) })
        assertTrue(listOf("constraints", "operations").none { it in errors.keys } && refusals.none { "by" in it.keys }, "確かめ方と、入力の検査の写しは持たない")
        val complete = doc("design/detail/final/processes/complete-todo.yaml")
        val confirm = list(complete, "confirm").single()
        assertEquals("checkCompleteTodo", confirm["method"])
        assertEquals(
            listOf("completed-not-before-created" to "date", "closed-has-completed-on" to "date", "closed-has-result" to "result"),
            list(confirm, "constraints").map { it["constraint"] to it["field"] }, "受け取る値が原因で破れうる決まりは、確認へでも確かめる",
        )
        val steps = list(complete, "steps")
        assertTrue(steps.indexOfLast { it["step"] == "write" } < steps.indexOfFirst { it["constraint"] == "closed-has-no-open-task" }, "入れ子の要素の決まりは書いた後に数える")
        assertTrue(steps.single { it["constraint"] == "closed-has-no-open-task" }["by"].toString().startsWith("SQL"), "確かめ方は、詳細設計の処理の手順が持つ")
        // 使うメッセージのキー（処理設計書・エラー定義書・一覧の 0 件・フォームの検査）は、どれもメッセージ定義書にある
        // 断りと知らせのキーは、基本設計のメッセージの項目が持つ。入力の検査のキー（Form と検査の名前を含む）は、Form のプログラム設計が組み立てる
        val messages = doc("design/basic/final/messages.yaml")
        val items = list(messages, "messages")
        assertTrue(items.filter { "check" in it.keys }.all { "key" !in it.keys && it["input"].toString().startsWith("PRM-") }, "入力の検査の文は、キーを持たず、入力と検査で持つ")
        val keys = (items.mapNotNull { it["key"]?.toString() } + list(doc("design/detail/final/application-web-forms.yaml"), "validationMessages").map { it["key"].toString() }).toSet()
        assertEquals(emptyList(), refusals.map { it["message"] }.distinct().filter { id -> items.none { it["id"] == id } }, "断りが指すメッセージは、どれもメッセージの項目にある")
        fun collect(x: Any?, out: MutableList<String>, names: Set<String>) {
            when (x) {
                is Map<*, *> -> x.forEach { (k, v) -> if (k in names && v is String && "." in v && " " !in v) out += v else collect(v, out, names) }
                is List<*> -> x.forEach { collect(it, out, names) }
            }
        }
        val used = mutableListOf<String>()
        java.nio.file.Files.list(root.resolve("design/detail/final/processes")).use { s -> s.toList() }.forEach { collect(doc("design/detail/final/processes/${it.fileName}"), used, setOf("message")) }
        collect(doc("design/detail/final/screen-designs/lists/todo-dashboard.yaml"), used, setOf("empty"))
        java.nio.file.Files.list(root.resolve("design/detail/final/screen-designs/operations")).use { s -> s.toList() }.forEach { f ->
            val form = doc("design/detail/final/screen-designs/operations/${f.fileName}")["form"] as? Map<*, *> ?: return@forEach
            val name = form["class"].toString().replaceFirstChar { it.lowercase() }
            fun fields(fs: Any?, prefix: String) = (fs as List<*>).map { it as Map<*, *> }.forEach { field ->
                list(field, "validations").map { it["name"].toString() }.filter { it != "Valid" }.forEach { v ->
                    used += "validation.${if (v == "DateTimeFormat") "typeMismatch" else v}.$name.$prefix${field["name"]}"
                }
            }
            fields(form["fields"], "")
            // 行のクラス（object の配列の要素）は、その配列の項目の下
            list(form, "fields").filter { it["type"].toString().startsWith("List<") && it["type"].toString().endsWith("Row>") }.forEach { arr ->
                val row = list(form, "classes").single { "List<${it["name"]}>" == arr["type"] }
                fields(row["fields"], "${arr["name"]}.")
            }
        }
        assertTrue(used.size > 50, "キーを集められている: ${used.size}")
        assertEquals(emptyList(), used.distinct().filter { it !in keys }, "メッセージ定義書に無いキー")
        val handling = doc("design/detail/final/error-handling.yaml")
        assertEquals(listOf("RefusedException", "NotFoundException", "InvalidTokenException"), list(handling, "exceptions").take(3).map { it["class"] })
    }

    /**
     * ドメイン定義書とドメイン関連定義書は、ファイルを持たない（ADR-0042「設計書は、モデルをレビューしやすくするビュー」）。
     * 基本設計の項目（エンティティ・状態と遷移・操作・データの決まり）を読むビューを、ダッシュボード「ドメイン定義書」に並べる。
     */
    @Test
    fun `ドメイン定義書はファイルを持たず 基本設計の項目を読むビューで見る`() {
        val ws = Workspace.open(root)
        val views = Views.load(ws.root, ws.schemas)
        assertEquals(emptyList(), views.errors)
        fun shown(name: String) = Views.resolve(assertNotNull(views.view(name)), ws.index, ws::treeOf, ws::schemaFor, emptyMap())
        fun cell(row: dev.toros.yde.semantic.VirtualRow, label: String) = row.cells.single { it.column.label == label }.value?.let(dev.toros.yde.yaml.YamlIO::toAny)
        // 関連（参照）は、エンティティの項目のうち、ほかのエンティティを指す項目から読む
        val relations = shown("domain-definition-relations")
        assertEquals(listOf("in-category" to "1 つまで", "tagged" to "いくつでも", "follow-up-of" to "1 つまで"), relations.map { cell(it, "関連の名前") to cell(it, "いくつ") })
        assertEquals(listOf<Any?>("TODO"), relations.map { cell(it, "持つ側") }.distinct())
        // 保有（作業項目は TODO の中にだけある）は、エンティティ一覧の親エンティティで読む
        assertEquals(listOf<Any?>("TODO"), shown("entity-definition-list").map { cell(it, "親エンティティ") }.filter { it != null && it != "" })
        val dashboard = java.nio.file.Files.readString(root.resolve(".yde/views/design/basic/review/domain-definition.yaml"))
        assertTrue(listOf("entity-definition-list", "state-definition-states", "operation-definition", "rule-definition", "domain-definition-relations").all { "view: $it" in dashboard })
        assertTrue(listOf("design/basic/final/domains", "design/basic/final/domain-relations.yaml", "design/basic/derived/documents/domains").none { java.nio.file.Files.exists(root.resolve(it)) }, "ドメイン定義書のファイルは無い")
    }

    /**
     * エンティティ定義書の原本（基本設計項目一覧。ADR-0035）: 設計項目は ID（ENT-・FLD-・OPS-・CRUD-）と導出元の参照を持ち、
     * 値は業務モデルの項と同じ書き方（型の名前・auto・選択肢・採番）のまま。CRUD はエンティティの中に、操作一覧と同じ書く印から作る。
     */
    @Test
    fun `基本設計項目一覧は業務モデルの値のまま設計項目に ID と導出元を持つ`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val entities = list(doc("design/basic/final/entities.yaml"), "entities")
        assertEquals(listOf("ENT-category", "ENT-tag", "ENT-todo", "ENT-todo-task"), entities.map { it["id"] })
        val todo = entities.single { it["id"] == "ENT-todo" }
        assertEquals("todo", todo["source"], "導出元は業務のエンティティ型")
        val numbering = todo["numbering"] as Map<*, *>
        assertEquals(listOf("FLD-todo.id", "id", "TODO-", 3), listOf(numbering["item"], numbering["path"], numbering["prefix"], numbering["digits"]), "採番は業務モデルの書き方のまま")
        val items = list(todo, "items").associateBy { it["key"] }
        items["created_on"]!!.let {
            assertEquals(listOf("01", "FLD-todo.id"), list(todo, "items").first().let { f -> listOf(f["seq"], f["id"]) })
            assertEquals("TYP-common.年月日", it["type"], "論理型は基本設計の論理型（設計項目）への参照")
            assertEquals("created", it["auto"], "auto は日本語に言い換えない")
            assertEquals("todo.created_on", it["source"], "導出元は業務の項目（定義の索引の yde-field）")
            assertEquals("yyyy年M月d日", it["format"], "継承した表示形式も項目の確定値に保持する")
        }
        assertEquals("TYP-common.タグ参照", (items["tags"]?.get("item") as Map<*, *>)["type"], "配列は item に要素の論理型を持つ")
        assertEquals(5, (items["status"]?.get("values") as List<*>).size, "選択肢も項目の確定値に保持する")
        val task = entities.single { it["id"] == "ENT-todo-task" }
        assertEquals(listOf("ENT-todo", "FLD-todo.tasks"), listOf(task["parent"], task["parentField"]))
        val finish = list(todo, "operations").single { it["action"] == "todo-task.finish" }
        assertEquals(
            listOf("FLD-todo.tasks" to "U", "FLD-todo.updated_on" to "U"), list(finish, "writes").map { it["item"] to it["crud"] },
            "作業項目を済にすると、TODO から見て作業項目（子要素）と個票の更新日が変わる",
        )
        // 段: ルールが無くても段の項は作れ、頭に当てた段の一覧が付く
        val session = WorkspaceSession(FileSystemDocumentStore(root))
        val detail = dev.toros.yde.workspace.Stages.term(session, dev.toros.yde.workspace.BusinessModel.of(session, "business"), "detail")
        assertEquals(emptyList(), detail.errors)
        val stages = (dev.toros.yde.yaml.YamlIO.toAny(detail.term) as Map<*, *>)["stages"] as List<*>
        assertEquals(listOf("basic", "detail"), stages.map { (it as Map<*, *>)["id"] })
    }

    /**
     * テーブル設計書（ADR-0036）: 論理型は基本設計の設計項目（値は業務モデルの型のまま）。詳細設計項目一覧は詳細設計にだけ出るもの
     * （テーブル・カラム名・主キーの順・型対応への参照・明細の日本語名・索引・外部キー）だけを持ち、名称・必須・選択肢は導出元をたどる。
     * 区分（マスタ・トランザクション）は人が書く基本設計項目台帳から読む。
     * DDL のレポートは、ビューが型対応表と基本設計項目一覧と台帳から読んだ値で CREATE TABLE と CREATE INDEX を作る（サンプルの design/detail/reports/ddl/schema.sql と同じ）。
     */
    @Test
    fun `テーブル設計書は論理型と型対応表から作り DDL まで落とせる`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val types = list(doc("design/basic/final/types.yaml"), "types").associateBy { it["id"] }
        types.getValue("TYP-common.一行の文").let {
            assertEquals(listOf("common#一行の文", "string", 100, "common#一行の文"), listOf(it["name"], it["base"], it["maxLength"], it["source"]))
        }
        assertEquals("yyyy年M月d日", types.getValue("TYP-common.年月日")["format"], "表示形式は論理型に")
        assertEquals("TYP-common.年月日", list(types.getValue("TYP-common.コメント"), "fields").first()["type"], "行の型の項目も論理型を参照する")
        val tables = list(doc("design/detail/final/tables.yaml"), "tables").associateBy { it["name"] }
        val title = list(tables.getValue("todo"), "columns").single { it["name"] == "title" }
        assertEquals(setOf("seq", "id", "name", "source", "sqlType", "item", "table", "base", "maxLength"), title.keys.map { it.toString() }.toSet(), "名称・必須は持たず、次の段（行モデル）が要る基底の種類と文字数は運ぶ（ADR-0042）")
        assertEquals(listOf("string", 100), listOf(title["base"], title["maxLength"]))
        val defaults = list(doc("design/detail/final/tables.yaml"), "tables").flatMap { list(it, "columns") }.filter { "default" in it.keys }.associate { it["id"] to it["default"] }
        assertEquals<Map<*, *>>(mapOf("COL-todo.status" to "OPEN", "COL-todo.version" to 0, "COL-todo_task.done" to false, "COL-todo_task.deleted" to false), defaults, "初期値も運ぶ（false・0 も値）。削除の印の初期値は、方式が足す")
        assertEquals("SQL-common.一行の文", title["sqlType"])
        val tags = tables.getValue("todo_tags")
        assertEquals("TODOのタグ", tags["label"], "明細テーブルの日本語名は詳細設計だけのもの")
        assertEquals(listOf("todo_id" to 1, "seq" to 2), list(tags, "columns").filter { it["primaryKey"] != null }.map { it["name"] to it["primaryKey"] }, "主キーの順")
        assertEquals(listOf("TODOのID", "並び順"), list(tags, "columns").take(2).map { it["label"] })
        assertEquals(listOf("ux_todo_tags_tag_id" to listOf("COL-todo_tags.todo_id", "COL-todo_tags.tag_id"), "ix_todo_tags_tag_id" to listOf("COL-todo_tags.tag_id")),
            list(tags, "indexes").map { it["name"] to list(it, "columns").map { c -> c["column"] } }, "配列の unique は一意の索引・外部キーのカラムに索引（主キーの先頭のカラムは除く）")
        assertEquals(listOf("TBL-todo_tags"), list(tags, "indexes").map { it["table"] }.distinct(), "索引は台帳の索引と同じ形でテーブルを指す")
        assertEquals(
            listOf(Triple("fk_todo_tags_todo", listOf("COL-todo_tags.todo_id"), "cascade"), Triple("fk_todo_tags_tag_id", listOf("COL-todo_tags.tag_id"), null)),
            list(tags, "foreignKeys").map { Triple(it["name"], it["columns"], it["onDelete"]) }, "入れ子で持つ親へは一緒に消す・参照へは指定しない",
        )
        assertEquals("pk_todo_tags", tags["primaryKeyName"])
        val classes = list(doc("design/basic/final/classes.yaml"), "classes").associate { it["id"] to it["name"] }
        val classified = list(doc("design/basic/final/entities.yaml"), "entities").groupBy { classes[it["entityClass"]] }.mapValues { (_, es) -> es.map { it["id"] } }
        assertEquals<Map<*, *>>(mapOf("master" to listOf("ENT-category", "ENT-tag"), "transaction" to listOf("ENT-todo", "ENT-todo-task")), classified, "追加した区分と指定を確定項目で共有する")
        // DDL: テーブル設計書のビューの行から作る
        val ws = Workspace.open(root)
        assertEquals(emptyList(), Reports.load(root).errors)
        val loaded = assertNotNull(Reports.load(root).report("ddl"))
        val ddl = Reports.run(loaded, emptyMap(), Views.load(root, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
        assertTrue("  title VARCHAR(100) NOT NULL," in ddl, "型対応表の穴を論理型の最大文字数で埋める: $ddl")
        assertTrue("status VARCHAR DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'SUSPENDED', 'CLOSED', 'CANCELED'))" in ddl)
        assertTrue("  done BOOLEAN DEFAULT FALSE NOT NULL," in ddl, "真偽値は型対応表の方法で出す")
        assertTrue("-- TODOのタグ（トランザクション）\nCREATE TABLE todo_tags (" in ddl, "明細テーブルの日本語名と、配列を持つエンティティの区分: $ddl")
        assertTrue("  CONSTRAINT pk_todo_tags PRIMARY KEY (todo_id, seq)\n" in ddl, "主キーは主キーの順と制約名")
        assertTrue("CREATE UNIQUE INDEX ux_todo_tags_tag_id ON todo_tags (todo_id, tag_id);" in ddl, "配列の unique は一意の索引")
        assertTrue("CREATE INDEX ix_todo_category ON todo (category);" in ddl)
        assertTrue("CREATE INDEX ix_todo_status_due_on ON todo (status, due_on);" in ddl, "人が書く詳細設計項目台帳の索引も")
        assertTrue("ALTER TABLE todo ADD CONSTRAINT fk_todo_category FOREIGN KEY (category) REFERENCES category (id);" in ddl, "外部キーはテーブルを作った後に足す")
        assertTrue("ALTER TABLE todo_tags ADD CONSTRAINT fk_todo_tags_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;" in ddl)
        assertEquals(java.nio.file.Files.readString(root.resolve("design/detail/reports/ddl/schema.sql")), ddl, "サンプルの DDL は最新（report samples/todos-sysdev ddl で作り直す）")
        // 実際に流す（③のアプリと同じ H2）。初期値が入り、TODO を消すと入れ子で持つ明細も消える
        H2.run(ddl) { db ->
            db.exec("INSERT INTO tag (id, name) VALUES ('TAG-001', 'a')")
            db.exec("INSERT INTO todo (id, title, summary, created_on, updated_on) VALUES ('TODO-001', 't', 's', DATE '2026-11-16', DATE '2026-11-16')")
            db.exec("INSERT INTO todo_tags (todo_id, seq, tag_id) VALUES ('TODO-001', 1, 'TAG-001')")
            db.exec("INSERT INTO todo_task (id, todo_id, seq, title) VALUES ('TODO-001-S01', 'TODO-001', 1, 'x')")
            assertEquals(1, db.count("SELECT COUNT(*) FROM todo WHERE status = 'OPEN'"), "初期値 OPEN")
            assertEquals(1, db.count("SELECT COUNT(*) FROM todo_task WHERE done = FALSE"), "初期値 false")
            db.exec("DELETE FROM todo WHERE id = 'TODO-001'")
            assertEquals(0, db.count("SELECT COUNT(*) FROM todo_tags") + db.count("SELECT COUNT(*) FROM todo_task"), "入れ子で持つ明細も消える")
        }
    }

    /**
     * テーブル設計書の体系は、業務モデルの形をどの深さでも写せる（todos に無い形は、test-fixtures/workspaces/todos-sysdev-shapes の形の見本を
     * 写しに足して確かめる）: 埋め込みの object（親の項目が必須でなければ NOT NULL にしない）・入れ子のエンティティ 1 件（親の ID の一意の索引）・
     * スカラーの配列・キーの組で重ならない行の配列・行の配列の中の配列（複合の主キーと、親への複合の外部キー）・マップ（キーが主キー）。
     * DDL は H2 で通り、親の行を消すと 3 段下の明細まで消える。テーブルにできない形（配列の配列）は対応の検査が指摘する。
     */
    @Test
    fun `テーブル設計書は業務モデルのどの形からも作れて DDL が H2 で通る`() {
        val dir = java.nio.file.Files.createTempDirectory("todos-sysdev-shapes")
        try {
            TodosSysdevFiles.copy(root, dir)
            java.nio.file.Files.copy(Path.of("test-fixtures/workspaces/todos-sysdev-shapes/shape-list.yaml"), dir.resolve(".yde/schemas/business/shape-list.yaml"))
            fun edit(path: String, change: (String) -> String) = dir.resolve(path).let { java.nio.file.Files.writeString(it, change(java.nio.file.Files.readString(it))) }
            // 人が書くもの: 区分（基本設計項目台帳）・型対応表・業務モデルから導けない索引（降順を含む。詳細設計項目台帳）
            java.nio.file.Files.writeString(dir.resolve("design/basic/inputs/entities/corrections-shape.yaml"), """
                specifications:
                  - {id: SPC-shape, target: ENT-shape, rule: entity-class, settings: {entityClass: CLS-transaction}, reason: 見本の区分}
                  - {id: SPC-shape-spec, target: ENT-shape-spec, rule: entity-class, settings: {entityClass: CLS-transaction}, reason: 見本の区分}
            """.trimIndent() + "\n")
            edit("design/detail/inputs/tables/sql-types.yaml") {
                // 組込みの整数（SQL-int）は、サンプルの型対応表にある（方式が足す項目「版」が使う）
                it + "  - { seq: '92', id: SQL-shape-list.見本番号, type: TYP-shape-list.見本番号, sql: VARCHAR, literal: string }\n" +
                    "  - { seq: '93', id: SQL-shape-list.仕様番号, type: TYP-shape-list.仕様番号, sql: VARCHAR, literal: string }\n" +
                    "  - { seq: '94', id: SQL-number, type: TYP-number, sql: 'DECIMAL(10, 2)', literal: number }\n"
            }
            // 型対応表が値の出力方法を決める。空文字・0・false・「設定なし」は未設定と取り違えない
            edit(".yde/schemas/business/shape-list.yaml") {
                it.replace("          # 参照\n", """
                    - { key: empty_text, type: text, default: '' }
                    - { key: spaces_text, type: text, default: '   ' }
                    - { key: quoted_text, type: text, default: "O'Brien" }
                    - { key: unset_text, type: text, default: 設定なし }
                    - { key: numeric_text, type: text, default: '001' }
                    - { key: zero_count, type: int, default: 0 }
                    - { key: rate, type: number, default: -2.5 }
                    - { key: enabled, type: boolean, default: true }
                    - { key: fixed_date, type: common#年月日, default: '2026-11-16' }
                    - { key: created_on, type: common#年月日, auto: created }
                    # 参照
                """.trimIndent().prependIndent("          ") + "\n")
            }
            edit("design/detail/inputs/tables/ledger.yaml") {
                it + "  - { seq: '02', id: IDX-ix_shape_parts_qty, name: ix_shape_parts_qty, table: TBL-shape_parts, " +
                    "columns: [{ column: COL-shape_parts.code }, { column: COL-shape_parts.qty, order: desc }], reason: 部品コードごとに数量の多い順に並べるため }\n"
            }
            generate(dir)
            fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(dir.resolve(path)))!!) as Map<*, *>
            fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
            val tables = list(doc("design/detail/final/tables.yaml"), "tables").associateBy { it["name"] }
            fun columns(t: String) = list(tables.getValue(t), "columns").associateBy { it["name"] }
            columns("shape").let {
                assertEquals(listOf("id", "name", "period_from", "period_to", "size_width", "size_height",
                    "empty_text", "spaces_text", "quoted_text", "unset_text", "numeric_text", "zero_count", "rate", "enabled", "fixed_date", "created_on", "owner_tag", "version"),
                    it.keys.toList(), "object は埋め込み（名前の有無によらない）・初期値のある項目も落とさない。最後の version は、区分への方式の指定（同時更新を版で検出する）が足す")
                assertEquals("FLD-shape.version", it.getValue("version")["source"], "方式が足す項目は、別の業務モデルのトランザクションのエンティティにも当たる")
                assertTrue("version" !in columns("shape_spec").keys, "入れ子で持つ要素には足さない（親で守る）")
                assertEquals(false, it.getValue("period_from")["notNull"], "必須の開始日でも、期間が必須でなければ NOT NULL にしない")
                assertEquals("期間の開始日", it.getValue("period_from")["label"])
                assertEquals("FLD-shape.period.from", it.getValue("period_from")["source"], "名前の無い object の中の項目は、入れ子の項目")
                assertEquals("FLD-shape.size.width", it.getValue("size_width")["source"], "名前付きの型も利用箇所の確定項目を参照する")
            }
            assertEquals(listOf("ux_shape_spec_shape_id"), list(tables.getValue("shape_spec"), "indexes").map { it["name"] }, "入れ子のエンティティ 1 件は、親の ID が一意（親の ID の索引を兼ねる）")
            assertEquals(listOf("shape_id", "seq", "labels"), columns("shape_labels").keys.toList(), "スカラーの配列は値のカラム")
            val partIndexes = list(tables.getValue("shape_parts"), "indexes")
            assertEquals(2, partIndexes.size, "導出した一意索引と台帳の索引を同じ確定一覧へ保存する")
            assertEquals(listOf("COL-shape_parts.shape_id", "COL-shape_parts.code"), list(partIndexes.single { it["unique"] == true }, "columns").map { it["column"] }, "キーの組で重ならない")
            val tags = tables.getValue("shape_parts_notes_tags")
            assertEquals(listOf("shape_id" to 1, "parts_seq" to 2, "notes_seq" to 3, "seq" to 4), list(tags, "columns").filter { it["primaryKey"] != null }.map { it["name"] to it["primaryKey"] })
            list(tags, "foreignKeys").first().let {
                assertEquals(listOf("COL-shape_parts_notes_tags.shape_id", "COL-shape_parts_notes_tags.parts_seq", "COL-shape_parts_notes_tags.notes_seq"), it["columns"], "親への外部キーは複合")
                assertEquals(listOf("TBL-shape_parts_notes", "cascade"), listOf(it["referencesTable"], it["onDelete"]))
            }
            assertEquals(listOf("COL-shape_parts_notes.shape_id", "COL-shape_parts_notes.parts_seq", "COL-shape_parts_notes.seq"),
                list(tags, "columns").take(3).map { it["referencesColumn"] }, "どのカラムがどのカラムを指すかは、カラムの側に")
            assertEquals(listOf("shape_id", "attrs_key", "attrs"), columns("shape_attrs").keys.toList(), "マップはキーが主キー")
            assertEquals(2, columns("shape_attrs").getValue("attrs_key")["primaryKey"])
            val rowModels = list(doc("design/detail/final/application-data-model.yaml"), "models").associateBy { it["table"] }
            val tagRow = list(rowModels.getValue("shape_parts_notes_tags"), "columns").associateBy { it["column"] }
            assertEquals("java.lang.Long", (tagRow.getValue("parts_seq")["java"] as Map<*, *>)["qualifiedName"], "複合の親キーは元の並び順の整数型を継承する")
            assertEquals("java.lang.Long", (tagRow.getValue("notes_seq")["java"] as Map<*, *>)["qualifiedName"])
            val shapeRow = list(rowModels.getValue("shape"), "columns").associateBy { it["column"] }
            assertEquals("java.math.BigDecimal", (shapeRow.getValue("rate")["java"] as Map<*, *>)["qualifiedName"], "桁の区切りに空白がある DECIMAL も扱う")
            assertEquals("", (shapeRow.getValue("empty_text")["default"] as Map<*, *>)["value"])
            assertEquals("   ", (shapeRow.getValue("spaces_text")["default"] as Map<*, *>)["value"])
            assertEquals(0, (shapeRow.getValue("zero_count")["default"] as Map<*, *>)["value"])
            val ws = Workspace.open(dir)
            assertEquals(emptyList(), Reports.load(dir).errors)
            val ddl = Reports.run(assertNotNull(Reports.load(dir).report("ddl")), emptyMap(), Views.load(dir, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
            assertTrue("ALTER TABLE shape_parts_notes_tags ADD CONSTRAINT fk_shape_parts_notes_tags_shape_parts_notes FOREIGN KEY (shape_id, parts_seq, notes_seq) REFERENCES shape_parts_notes (shape_id, parts_seq, seq) ON DELETE CASCADE;" in ddl, ddl)
            assertTrue("CREATE INDEX ix_shape_parts_qty ON shape_parts (code, qty DESC);" in ddl, "台帳の索引の降順")
            assertTrue("  labels VARCHAR(100) NOT NULL," in ddl, "配列の要素のカラムも、論理型の値で型の穴を埋める")
            assertTrue("  qty INTEGER DEFAULT 1 NOT NULL," in ddl, "数値に引用符を付けない")
            assertTrue("  empty_text VARCHAR DEFAULT ''," in ddl, "空文字の初期値: $ddl")
            assertTrue("  spaces_text VARCHAR DEFAULT '   '," in ddl, "空白を保つ")
            assertTrue("  quoted_text VARCHAR DEFAULT 'O''Brien'," in ddl, "文字列の引用符を二重にする")
            assertTrue("  unset_text VARCHAR DEFAULT '設定なし'," in ddl, "表示の案内と同じ文字も値")
            assertTrue("  numeric_text VARCHAR DEFAULT '001'," in ddl, "値の見た目から数値にしない")
            assertTrue("  zero_count INTEGER DEFAULT 0," in ddl)
            assertTrue("  rate DECIMAL(10, 2) DEFAULT -2.5," in ddl)
            assertTrue("  enabled BOOLEAN DEFAULT TRUE," in ddl)
            assertTrue("  fixed_date DATE DEFAULT DATE '2026-11-16'," in ddl)
            assertTrue("  created_on DATE," in ddl, "auto は Service が入れるので DEFAULT を作らない")
            H2.run(ddl) { db ->
                db.exec("INSERT INTO tag (id, name) VALUES ('TAG-001', 'a')")
                db.exec("INSERT INTO shape (id, name, size_width) VALUES ('SHP-001', 'n', 1)")
                assertEquals(1, db.count("SELECT COUNT(*) FROM shape WHERE empty_text = '' AND spaces_text = '   ' AND quoted_text = 'O''Brien' AND unset_text = '設定なし' AND numeric_text = '001' AND zero_count = 0 AND rate = -2.5 AND enabled = TRUE AND fixed_date = DATE '2026-11-16' AND created_on IS NULL"), "各型の初期値が実際に入る")
                db.exec("INSERT INTO shape_parts (shape_id, seq, code) VALUES ('SHP-001', 1, 'A')")
                assertEquals(1, db.count("SELECT qty FROM shape_parts"), "初期値 1")
                db.exec("INSERT INTO shape_parts_notes (shape_id, parts_seq, seq, body) VALUES ('SHP-001', 1, 1, 'b')")
                db.exec("INSERT INTO shape_parts_notes_tags (shape_id, parts_seq, notes_seq, seq, tag_id) VALUES ('SHP-001', 1, 1, 1, 'TAG-001')")
                assertTrue(runCatching { db.exec("INSERT INTO shape_parts_notes (shape_id, parts_seq, seq, body) VALUES ('SHP-001', 9, 1, 'b')") }.isFailure, "親の無い明細は入らない（複合の外部キー）")
                assertTrue(runCatching { db.exec("INSERT INTO shape_parts (shape_id, seq, code) VALUES ('SHP-001', 2, 'A')") }.isFailure, "部品コードは重ならない")
                db.exec("DELETE FROM shape WHERE id = 'SHP-001'")
                assertEquals(0, db.count("SELECT COUNT(*) FROM shape_parts_notes_tags"), "親の行を消すと 3 段下まで消える")
            }
            // テーブルにできない形（要素が配列の配列）は、対応の検査が指摘する
            edit(".yde/schemas/business/shape-list.yaml") {
                it.replace("          # 参照\n", "          - { key: grid, label: 格子, type: array, item: { type: array, item: { type: int } } }\n          # 参照\n")
            }
            generate(dir, allowErrors = true)
            assertTrue(errorsIn(dir).any { "FLD-shape.grid" in it && "テーブルにできません" in it }, errorsIn(dir).toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun generate(dir: Path, allowErrors: Boolean = false) {
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        val plan = Generators.plan(session)
        assertEquals(emptyList(), plan.errors)
        assertTrue(session.apply(plan.transaction).ok)
        assertTrue(session.save().ok)
        if (!allowErrors) assertEquals(emptyList(), errorsIn(dir))
    }

    private fun errorsIn(dir: Path): List<String> {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(dir), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(dir)))
        return ws.schemaErrors.map { it.toString() } +
            WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
    }

    /** H2 のメモリの DB に DDL を流し（文の区切りは行末の ;。-- の行は注記）、続けて文を流す。 */
    private class H2(private val st: java.sql.Statement) {
        fun exec(sql: String) { st.execute(sql) }

        /** 1 行 1 列の数を読む。 */
        fun count(sql: String): Int = st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }

        companion object {
            fun run(ddl: String, then: (H2) -> Unit) {
                java.sql.DriverManager.getConnection("jdbc:h2:mem:ddl-${System.nanoTime()}").use { c ->
                    c.createStatement().use { st ->
                        ddl.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                            .split(Regex(""";\s*(\n|$)""")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
                        then(H2(st))
                    }
                }
            }
        }
    }

    /** 生成の系譜: 生成の定義ごとに、業務モデルの項のどこを読むかを定義から取り出す（設計書がどの概念から作られるか）。 */
    @Test
    fun `生成の系譜は定義から取り出す`() {
        @Suppress("UNCHECKED_CAST")
        val lineage = Generators.lineage(WorkspaceSession(FileSystemDocumentStore(root))).associateBy { it["id"] }
        fun concepts(id: String) = lineage[id]?.get("concepts") as List<*>
        // データの決まりは、業務モデル（constraints）ではなく、基本設計の「データの決まり」（designRules）から読む（ADR-0042）
        // 操作は、業務モデル（actions）ではなく、基本設計の「操作」の項目一覧を解いたもの（designActions）から読む
        assertTrue(listOf("designActions.effects", "designActions.checks", "designActions.require", "designRules.rule").all { it in concepts("processes-derived") }, "処理設計書の導出: ${concepts("processes-derived")}")
        assertTrue(concepts("processes-derived").all { it.toString().startsWith("designActions") || it.toString().startsWith("designRules") }, "処理設計書の導出は、基本設計の項目だけを読む: ${concepts("processes-derived")}")
        listOf("error-handling", "processes", "screen-designs-details", "screen-designs-lists", "screen-designs-operations", "screen-flows", "session-design").forEach { id ->
            assertTrue(concepts("$id-derived").none { it.toString().startsWith("actions") }, "$id の導出は、業務モデルの操作を直接読まない: ${concepts("$id-derived")}")
            assertTrue(concepts("$id-derived").any { it.toString().startsWith("designActions") }, "$id の導出は、操作を基本設計の項目から読む: ${concepts("$id-derived")}")
        }
        // 操作を解いた形は、基本設計の項目（操作・エンティティ・型・状態と遷移・データの決まり）だけから作る
        assertTrue(concepts("basic-operations-resolved").all { c -> listOf("operations", "entities", "lifecycles", "rules", "types").any { c.toString() == it || c.toString().startsWith("$it.") } }, "操作を解いた形: ${concepts("basic-operations-resolved")}")
        assertTrue(concepts("processes-derived").none { it.toString().startsWith("constraints") }, "処理設計書の導出は、業務モデルのデータの決まりを直接読まない: ${concepts("processes-derived")}")
        // テーブルへの読み書きは、基本設計の項目（エンティティ・操作・データの決まり・一覧の検索条件・画面）だけを入口で組んで読む。業務モデルも、解いた形も読まない
        val access = concepts("detail-table-access").map { it.toString().substringBefore(".") }.toSet()
        assertEquals(setOf("entities", "designActions", "designRules", "designQueries", "designReach", "designDetails", "designSoftDeleted", "designDecisions"), access, "テーブルへの読み書き: ${concepts("detail-table-access")}")
        val accessDefinition = Generators.definitions(WorkspaceSession(FileSystemDocumentStore(root))).first.single { it.id == "detail-table-access" }
        assertEquals(setOf("basic-entities", "basic-operations", "basic-rules", "basic-queries", "basic-screens", "detail-tables", "detail-document-input-gate"), accessDefinition.dependsOn.toSet(), "作る元は基本設計の項目と、同じ詳細設計のテーブルの項目")
        assertEquals(listOf("documents"), concepts("detail-status"), "状態の型は、基本設計の「状態と遷移」の項目（入力のビュー）だけを読み、業務モデルの概念を読まない")
        assertEquals(emptyList<Any>(), concepts("screen-styles-derived"), "クラスの対応表の導出は業務モデルを読まない（決まった表）")
        assertEquals(null, lineage["basic-entities"]?.get("stage"), "確定項目への移行後は段を再適用しない")
        assertTrue(Generators.definitions(WorkspaceSession(FileSystemDocumentStore(root))).first.single { it.id == "basic-entities" }.dependsOn.contains("basic-final"))
        assertEquals(null, lineage["processes"]?.get("stage"))
        assertTrue(Generators.definitions(WorkspaceSession(FileSystemDocumentStore(root))).first.single { it.id == "processes" }.dependsOn.contains("processes-derived"))
        assertTrue((lineage["processes-derived"]?.get("reads") as List<*>).contains("designActions[*].effects[*].field.key"))
    }

    @Test
    fun `検証にエラーが無い`() {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(root), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(root)))
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
        assertEquals(emptyList(), errors)
    }
}
