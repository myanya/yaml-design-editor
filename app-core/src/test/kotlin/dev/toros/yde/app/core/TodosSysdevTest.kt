package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val tables = list(doc("design/generated/tables/todo.yaml"), "tables").map { it as Map<*, *> }
        assertEquals(listOf("todo", "todo_tags", "todo_comments", "todo_history"), tables.map { it["name"] })
        val todoColumns = list(tables[0], "columns").map { (it as Map<*, *>)["name"] }
        assertTrue("title" in todoColumns && "status" in todoColumns, "一覧の行（title）と個票（status）の項目が 1 テーブルに: $todoColumns")
        val historyColumns = list(tables[3], "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("todo_id", "seq", "date", "action", "from_", "to_", "note", "resume_on"), historyColumns, "予約語は末尾に _")
        val taskColumns = list(list(doc("design/generated/tables/todo-task.yaml"), "tables")[0] as Map<*, *>, "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("id", "todo_id", "seq", "title", "done"), taskColumns, "入れ子の型は親の ID と並び順を足す")
        val methods = list(doc("design/generated/table-access/todo.yaml"), "methods").map { it as Map<*, *> }
        val complete = methods.single { it["name"] == "completeTodo" }
        assertEquals(listOf("completed_on", "result", "status", "updated_on", "updated_by"), list(complete, "set").map { (it as Map<*, *>)["item"] }, "同じテーブルへの値は 1 つの update（更新者は基本設計の規則）")
        val count = list(doc("design/generated/table-access/todo-task.yaml"), "methods").map { it as Map<*, *> }.single { it["precondition"] == "complete-todo.no-open-task" }
        assertEquals("complete-todo.no-open-task", count["precondition"])
        assertEquals(listOf(mapOf("item" to "todo_id", "param" to "id"), mapOf("item" to "done", "equals" to false)), count["where"])
        val crud = list(doc("design/generated/entities/todo.yaml"), "crud").map { it as Map<*, *> }
        val status = crud.single { it["項目"] == "status" }
        assertEquals("C", status["register-todo"])
        assertEquals("U", status["complete-todo"])
        assertEquals("", status["edit-todo"], "編集は状態を変えない")
    }

    /**
     * 画面系（画面遷移定義書・画面定義書・Controller・セッション情報設計）は、ダッシュボードを起点に業務モデルから計算で作る:
     * 一覧画面 1・詳細画面 3（入れ子の作業項目は画面にしない）・入力画面 10・確認画面 12 の 26 画面。ビューは定義の索引の yde-view。
     */
    @Test
    fun `画面系の設計書はダッシュボードを起点に計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val transitions = doc("design/generated/screens/transitions/todo-dashboard.yaml")
        val kinds = list(transitions, "screens").groupingBy { it["kind"].toString() }.eachCount()
        assertEquals(mapOf("list" to 1, "detail" to 3, "input" to 10, "confirm" to 12), kinds)
        assertTrue(java.nio.file.Files.notExists(root.resolve("design/generated/screens/details/todo-task.yaml")), "入れ子の型は詳細画面にしない")
        val start = doc("design/generated/screens/operations/start-todo.yaml")
        kotlin.test.assertNull(start["input"], "受け取る値が無い操作は、入力画面を作らない")
        assertEquals("GET /todo/{id}/start-todo/confirm", (start["confirm"] as Map<*, *>)["request"])
        assertEquals(listOf(mapOf("value" to "OPEN", "label" to "未着手")), start["shownWhen"], "ボタンは Lifecycle の状態で出す")
        val list = doc("design/generated/screens/lists/todo-dashboard.yaml")
        assertEquals(listOf("register-todo", "add-category", "add-tag"), list(list, "buttons").map { it["operation"] }, "上部のボタンはダッシュボードに書いた順")
        val session = doc("design/generated/session.yaml")
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
        val complete = doc("design/generated/screen-designs/operations/complete-todo.yaml")
        val form = complete["form"] as Map<*, *>
        assertEquals("CompleteTodoForm", form["class"])
        val result = list(form, "fields").single { it["name"] == "result" }
        assertEquals(listOf(mapOf("name" to "NotNull"), mapOf("name" to "Size", "min" to 1)), result["validations"])
        assertEquals(listOf("complete-todo/input", "complete-todo/confirm"), list(complete, "screens").map { it["id"] })
        val edit = doc("design/generated/screen-designs/operations/edit-todo.yaml").toString()
        assertTrue("flag=clear.category" in edit && "rows={item=comments" in edit, "値を消すのチェックと、コメント一覧の行の表")
        val access = list(doc("design/generated/table-access/todo.yaml"), "methods")
        val overdue = access.single { it["name"] == "selectTodoDashboardOverdue" }
        assertTrue(mapOf("item" to "status", "op" to "in", "values" to listOf("OPEN", "IN_PROGRESS")) in (overdue["where"] as List<*>), "区画の条件の State は値の条件")
        assertTrue(access.any { it["name"] == "findDetail" } && access.any { it["name"] == "selectAll" })
        val matrix = list(doc("design/generated/table-access/category.yaml"), "methods").single { it["name"] == "selectTodoDashboardCategories" }
        assertEquals("aggregate", matrix["kind"])
        val all = java.nio.file.Files.walk(root.resolve("design/generated")).use { s -> s.filter { it.toString().endsWith(".yaml") }.toList() }
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
        val errors = doc("design/generated/errors.yaml")
        val rules = list(errors, "constraints").associateBy { it["constraint"] }
        assertEquals("SQL", rules["closed-has-no-open-task"]?.get("by"))
        assertEquals("対象外", rules["has-detail"]?.get("by"), "一覧の行と個票は 1 テーブルなので、書かれた場所の決まりは起きない")
        assertEquals("対象外", rules["in-category.outMax"]?.get("by"), "警告は断らない")
        assertEquals(listOf("suspend-todo"), rules["resume-not-before-suspend"]?.get("operations"), "中断の行の決まりは、再開予定日を書く操作だけが確かめる")
        assertEquals(listOf("complete-todo"), rules["closed-has-completed-on"]?.get("operations"), "状態を完了以外に書く操作は、完了の決まりを確かめない")
        val complete = doc("design/generated/processes/complete-todo.yaml")
        val confirm = list(complete, "confirm").single()
        assertEquals("checkCompleteTodo", confirm["method"])
        assertEquals(
            listOf("completed-not-before-created" to "date", "closed-has-completed-on" to "date", "closed-has-result" to "result"),
            list(confirm, "constraints").map { it["constraint"] to it["field"] }, "受け取る値が原因で破れうる決まりは、確認へでも確かめる",
        )
        val steps = list(complete, "steps")
        assertTrue(steps.indexOfLast { it["step"] == "write" } < steps.indexOfFirst { it["constraint"] == "closed-has-no-open-task" }, "入れ子の要素の決まりは書いた後に数える")
        // 使うメッセージのキー（処理設計書・エラー定義書・一覧の 0 件・フォームの検査）は、どれもメッセージ定義書にある
        val messages = doc("design/generated/messages.yaml")
        val keys = (list(messages, "messages").map { it["key"].toString() } + list(messages, "validation").flatMap { list(it, "messages").map { m -> m["key"].toString() } }).toSet()
        fun collect(x: Any?, out: MutableList<String>, names: Set<String>) {
            when (x) {
                is Map<*, *> -> x.forEach { (k, v) -> if (k in names && v is String && "." in v && " " !in v) out += v else collect(v, out, names) }
                is List<*> -> x.forEach { collect(it, out, names) }
            }
        }
        val used = mutableListOf<String>()
        java.nio.file.Files.list(root.resolve("design/generated/processes")).use { s -> s.toList() }.forEach { collect(doc("design/generated/processes/${it.fileName}"), used, setOf("message")) }
        collect(list(errors, "operations"), used, setOf("message"))
        collect(doc("design/generated/screen-designs/lists/todo-dashboard.yaml"), used, setOf("empty"))
        java.nio.file.Files.list(root.resolve("design/generated/screen-designs/operations")).use { s -> s.toList() }.forEach { f ->
            val form = doc("design/generated/screen-designs/operations/${f.fileName}")["form"] as? Map<*, *> ?: return@forEach
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
        val handling = doc("design/generated/error-handling.yaml")
        assertEquals(listOf("RefusedException", "NotFoundException", "InvalidTokenException"), list(handling, "exceptions").take(3).map { it["class"] })
    }

    /**
     * ドメイン定義書とドメイン関連定義書は、業務モデルから計算で作る: ドメインは集約（入れ子の持ち主がいない型と、その中の型）で、
     * todos では TODO（作業項目を含む）・カテゴリ・タグの 3 つ。業務の言葉で書き、関係は出る向きと入る向きの両方。
     */
    @Test
    fun `ドメインの設計書は集約ごとに計算で作る`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val names = java.nio.file.Files.list(root.resolve("design/generated/domains")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("category.yaml", "tag.yaml", "todo.yaml"), names, "入れ子の型（作業項目）は集約の中なので、ドメインにしない")
        val todo = doc("design/generated/domains/todo.yaml")
        assertEquals(listOf("集約の中心", "「TODO」の中にだけある"), list(todo, "members").map { it["role"] })
        val items = list(list(todo, "information")[0], "items").associateBy { it["key"] }
        assertEquals("一行の文（100 文字まで）", items["title"]?.get("kind"))
        assertEquals("カテゴリを 1 つ指す", items["category"]?.get("kind"))
        assertEquals("作業項目の並び", items["tasks"]?.get("kind"))
        assertEquals(listOf("未着手", "対応中", "中断", "完了", "中止"), items["status"]?.get("choices"))
        val text = java.nio.file.Files.readString(root.resolve("design/generated/domains/todo.yaml"))
        assertTrue(listOf("VARCHAR", "Mapper", "テーブル", "Controller").none { it in text }, "ドメイン定義書はシステムの言葉を書かない")
        assertEquals(listOf("出る" to "category", "出る" to "tag", "出る" to "todo", "入る" to "todo"), list(todo, "relations").map { it["direction"] to it["to"] })
        val category = doc("design/generated/domains/category.yaml")
        assertEquals(listOf("このカテゴリのTODO"), list(category, "relations").map { it["label"] }, "関係は入る向きにも出る（逆向きの名前で）")
        val relations = doc("design/generated/domain-relations.yaml")
        assertEquals(listOf("in-category", "tagged", "follow-up-of"), list(relations, "relations").map { it["relation"] })
        assertEquals(listOf("todo" to "todo-task"), list(relations, "holdings").map { it["from"] to it["to"] })
    }

    /**
     * 段（基本設計・詳細設計。ADR-0034）: 基本設計の規則「更新者を記録する」で足した更新者が、基本設計書（エンティティ定義書・
     * アクション定義書）から詳細設計書（テーブル設計書・処理設計書）へ引き継がれ、業務モデルに無いものには出どころが付く。
     */
    @Test
    fun `段の規則で足した更新者は基本設計から詳細設計へ引き継がれる`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val origin = "基本設計「更新者を記録する」（audit-updated-by）"
        val items = list(doc("design/generated/entities/todo.yaml"), "items").associateBy { it["key"] }
        assertEquals(listOf("updated_on", "updated_by"), items.keys.filter { it.toString().startsWith("updated") }, "更新日の後ろに足す")
        assertEquals(origin, items["updated_by"]?.get("origin"))
        assertEquals(null, items["updated_on"]?.get("origin"), "業務モデルから来た項目には出どころが付かない")
        val effects = list(doc("design/generated/actions/start-todo.yaml"), "effects")
        assertEquals(origin, effects.single { it["path"] == "updated_by" }["origin"])
        val columns = list(list(doc("design/generated/tables/todo.yaml"), "tables")[0], "columns").associateBy { it["name"] }
        assertEquals("VARCHAR(50)", columns["updated_by"]?.get("type"))
        assertEquals(origin, columns["updated_by"]?.get("origin"))
        val write = list(doc("design/generated/processes/start-todo.yaml"), "steps").first { it["step"] == "write" }
        assertEquals(listOf("status", "updated_on", "updated_by"), list(write, "set").map { it["item"] })
        // 業務モデルの項は変えない（段を書かない生成の定義は、今までどおり業務モデルの項を読む）
        val session = WorkspaceSession(FileSystemDocumentStore(root))
        val business = dev.toros.yde.workspace.BusinessModel.of(session, "business")
        assertTrue("updated_by" !in dev.toros.yde.yaml.YamlIO.dumpPlain(business))
        val detail = dev.toros.yde.workspace.Stages.term(session, business, "detail")
        assertEquals(emptyList(), detail.errors)
        val stages = (dev.toros.yde.yaml.YamlIO.toAny(detail.term) as Map<*, *>)["stages"] as List<*>
        assertEquals(listOf("basic", "detail"), stages.map { (it as Map<*, *>)["id"] }, "段の項の頭に、当てた段の一覧")
    }

    /** 生成の系譜: 生成の定義ごとに、業務モデルの項のどこを読むかを定義から取り出す（設計書がどの概念から作られるか）。 */
    @Test
    fun `生成の系譜は定義から取り出す`() {
        @Suppress("UNCHECKED_CAST")
        val lineage = Generators.lineage(WorkspaceSession(FileSystemDocumentStore(root))).associateBy { it["id"] }
        fun concepts(id: String) = lineage[id]?.get("concepts") as List<*>
        assertTrue(listOf("actions.effects", "actions.checks", "actions.require", "constraints.rule").all { it in concepts("processes") }, "処理設計書: ${concepts("processes")}")
        assertTrue("relations.cardinality" in concepts("domains") && "lifecycles.transitions" in concepts("domains"), "ドメイン定義書: ${concepts("domains")}")
        assertEquals(emptyList<Any>(), concepts("screen-styles"), "クラスの対応表は業務モデルを読まない（決まった表）")
        assertEquals("basic", lineage["entity-specs"]?.get("stage"), "基本設計書は基本設計の段を読む")
        assertEquals("detail", lineage["processes"]?.get("stage"))
        assertTrue((lineage["processes"]?.get("reads") as List<*>).contains("actions[*].effects[*].field.key"))
    }

    @Test
    fun `検証にエラーが無い`() {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(root), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(root)))
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
        assertEquals(emptyList(), errors)
    }
}
