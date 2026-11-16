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
        val tables = list(doc("design/detail/tables.yaml"), "tables").map { it as Map<*, *> }.associateBy { it["name"] }
        assertEquals(listOf("category", "tag", "todo", "todo_task", "todo_tags", "todo_comments", "todo_history"), tables.keys.toList())
        val todoColumns = list(tables.getValue("todo"), "columns").map { (it as Map<*, *>)["name"] }
        assertTrue("title" in todoColumns && "status" in todoColumns, "一覧の行（title）と個票（status）の項目が 1 テーブルに: $todoColumns")
        val historyColumns = list(tables.getValue("todo_history"), "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("todo_id", "seq", "date", "action", "from_", "to_", "note", "resume_on"), historyColumns, "予約語は末尾に _")
        val taskColumns = list(tables.getValue("todo_task"), "columns").map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("id", "todo_id", "seq", "title", "done"), taskColumns, "入れ子の型は親の ID と並び順を足す")
        val methods = list(doc("design/generated/table-access/todo.yaml"), "methods").map { it as Map<*, *> }
        val complete = methods.single { it["name"] == "completeTodo" }
        assertEquals(listOf("completed_on", "result", "status", "updated_on"), list(complete, "set").map { (it as Map<*, *>)["item"] }, "同じテーブルへの値は 1 つの update")
        val count = list(doc("design/generated/table-access/todo-task.yaml"), "methods").map { it as Map<*, *> }.single { it["precondition"] == "complete-todo.no-open-task" }
        assertEquals("complete-todo.no-open-task", count["precondition"])
        assertEquals(listOf(mapOf("item" to "todo_id", "param" to "id"), mapOf("item" to "done", "equals" to false)), count["where"])
        val todo = list(doc("design/basic/entities.yaml"), "entities").map { it as Map<*, *> }.single { it["id"] == "ENT-todo" }
        val crud = list(todo, "crud").map { it as Map<*, *> }
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
     * エンティティ定義書の原本（基本設計項目一覧。ADR-0035）: 設計項目は ID（ENT-・FLD-・OPS-・CRUD-）と導出元の参照を持ち、
     * 値は業務モデルの項と同じ書き方（型の名前・auto・選択肢・採番）のまま。CRUD はエンティティの中に、操作一覧と同じ書く印から作る。
     */
    @Test
    fun `基本設計項目一覧は業務モデルの値のまま設計項目に ID と導出元を持つ`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val entities = list(doc("design/basic/entities.yaml"), "entities")
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
            assertEquals(null, it["format"], "表示形式は型の性質なので、エンティティ定義には書かない")
        }
        assertEquals("TYP-common.タグ参照", items["tags"]?.get("itemType"), "配列は要素の論理型を持つ")
        assertEquals(null, items["status"]?.get("values"), "選択肢は論理型の性質なので、項目には写さない")
        val task = entities.single { it["id"] == "ENT-todo-task" }
        assertEquals(listOf("ENT-todo", "FLD-todo.tasks"), listOf(task["parent"], task["parentField"]))
        val finish = list(todo, "operations").single { it["action"] == "todo-task.finish" }
        assertEquals(listOf("FLD-todo.updated_on" to "U"), list(finish, "writes").map { it["item"] to it["crud"] }, "作業項目を済にすると個票の更新日が変わる")
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
     * DDL のレポートは、ビューが型対応表と基本設計項目一覧と台帳から読んだ値で CREATE TABLE と CREATE INDEX を作る（サンプルの design/ddl/schema.sql と同じ）。
     */
    @Test
    fun `テーブル設計書は論理型と型対応表から作り DDL まで落とせる`() {
        fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(root.resolve(path)))!!) as Map<*, *>
        fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
        val types = list(doc("design/basic/types.yaml"), "types").associateBy { it["id"] }
        types.getValue("TYP-common.一行の文").let {
            assertEquals(listOf("common#一行の文", "string", 100, "common#一行の文"), listOf(it["name"], it["base"], it["maxLength"], it["source"]))
        }
        assertEquals("yyyy年M月d日", types.getValue("TYP-common.年月日")["format"], "表示形式は論理型に")
        assertEquals("TYP-common.年月日", list(types.getValue("TYP-common.コメント"), "fields").first()["type"], "行の型の項目も論理型を参照する")
        val tables = list(doc("design/detail/tables.yaml"), "tables").associateBy { it["name"] }
        val title = list(tables.getValue("todo"), "columns").single { it["name"] == "title" }
        assertEquals(setOf("seq", "id", "name", "source", "sqlType"), title.keys.map { it.toString() }.toSet(), "基本設計の値（名称・必須・文字数）は持たない")
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
        val classes = list(doc("design/basic/ledger.yaml"), "entityClasses").associate { it["name"] to it["entities"] }
        assertEquals<Map<*, *>>(mapOf("master" to listOf("ENT-category", "ENT-tag"), "transaction" to listOf("ENT-todo", "ENT-todo-task")), classes, "区分は人が書く台帳に")
        // DDL: テーブル設計書のビューの行から作る
        val ws = Workspace.open(root)
        val loaded = assertNotNull(Reports.load(root).report("ddl"))
        val ddl = Reports.run(loaded, emptyMap(), Views.load(root, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
        assertTrue("  title VARCHAR(100) NOT NULL," in ddl, "型対応表の穴を論理型の最大文字数で埋める: $ddl")
        assertTrue("status VARCHAR DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'SUSPENDED', 'CLOSED', 'CANCELED'))" in ddl)
        assertTrue("  done BOOLEAN DEFAULT 'false' NOT NULL," in ddl, "初期値 false も出す")
        assertTrue("-- TODOのタグ（トランザクション）\nCREATE TABLE todo_tags (" in ddl, "明細テーブルの日本語名と、配列を持つエンティティの区分: $ddl")
        assertTrue("  CONSTRAINT pk_todo_tags PRIMARY KEY (todo_id, seq)\n" in ddl, "主キーは主キーの順と制約名")
        assertTrue("CREATE UNIQUE INDEX ux_todo_tags_tag_id ON todo_tags (todo_id, tag_id);" in ddl, "配列の unique は一意の索引")
        assertTrue("CREATE INDEX ix_todo_category ON todo (category);" in ddl)
        assertTrue("CREATE INDEX ix_todo_status_due_on ON todo (status, due_on);" in ddl, "人が書く詳細設計項目台帳の索引も")
        assertTrue("ALTER TABLE todo ADD CONSTRAINT fk_todo_category FOREIGN KEY (category) REFERENCES category (id);" in ddl, "外部キーはテーブルを作った後に足す")
        assertTrue("ALTER TABLE todo_tags ADD CONSTRAINT fk_todo_tags_todo FOREIGN KEY (todo_id) REFERENCES todo (id) ON DELETE CASCADE;" in ddl)
        assertEquals(java.nio.file.Files.readString(root.resolve("design/ddl/schema.sql")), ddl, "サンプルの DDL は最新（report samples/todos-sysdev ddl で作り直す）")
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
            java.nio.file.Files.walk(root).use { paths ->
                paths.forEach { p ->
                    val to = dir.resolve(root.relativize(p).toString())
                    if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(to) else java.nio.file.Files.copy(p, to)
                }
            }
            java.nio.file.Files.copy(Path.of("test-fixtures/workspaces/todos-sysdev-shapes/shape-list.yaml"), dir.resolve(".yde/schemas/business/shape-list.yaml"))
            fun edit(path: String, change: (String) -> String) = dir.resolve(path).let { java.nio.file.Files.writeString(it, change(java.nio.file.Files.readString(it))) }
            // 人が書くもの: 区分（基本設計項目台帳）・型対応表・業務モデルから導けない索引（降順を含む。詳細設計項目台帳）
            edit("design/basic/ledger.yaml") { it.replace("entities: [ENT-todo, ENT-todo-task]", "entities: [ENT-todo, ENT-todo-task, ENT-shape, ENT-shape-spec]") }
            edit("design/detail/sql-types.yaml") {
                it + "  - { seq: '91', id: SQL-int, type: TYP-int, sql: INTEGER }\n" +
                    "  - { seq: '92', id: SQL-shape-list.見本番号, type: TYP-shape-list.見本番号, sql: VARCHAR }\n" +
                    "  - { seq: '93', id: SQL-shape-list.仕様番号, type: TYP-shape-list.仕様番号, sql: VARCHAR }\n"
            }
            edit("design/detail/ledger.yaml") {
                it + "  - { seq: '02', id: IDX-ix_shape_parts_qty, name: ix_shape_parts_qty, table: TBL-shape_parts, " +
                    "columns: [{ column: COL-shape_parts.code }, { column: COL-shape_parts.qty, order: desc }], reason: 部品コードごとに数量の多い順に並べるため }\n"
            }
            generate(dir)
            fun doc(path: String) = dev.toros.yde.yaml.YamlIO.toAny(dev.toros.yde.yaml.YamlIO.parseFirst(java.nio.file.Files.readString(dir.resolve(path)))!!) as Map<*, *>
            fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
            val tables = list(doc("design/detail/tables.yaml"), "tables").associateBy { it["name"] }
            fun columns(t: String) = list(tables.getValue(t), "columns").associateBy { it["name"] }
            columns("shape").let {
                assertEquals(listOf("id", "name", "period_from", "period_to", "size_width", "size_height", "owner_tag"), it.keys.toList(), "object は埋め込み（名前の有無によらない）")
                assertEquals(false, it.getValue("period_from")["notNull"], "必須の開始日でも、期間が必須でなければ NOT NULL にしない")
                assertEquals("期間の開始日", it.getValue("period_from")["label"])
                assertEquals("FLD-shape.period.from", it.getValue("period_from")["source"], "名前の無い object の中の項目は、入れ子の項目")
                assertEquals("FLD-shape-list.大きさ.width", it.getValue("size_width")["source"], "名前付きの型の中の項目は、型の項目")
            }
            assertEquals(listOf("ux_shape_spec_shape_id"), list(tables.getValue("shape_spec"), "indexes").map { it["name"] }, "入れ子のエンティティ 1 件は、親の ID が一意（親の ID の索引を兼ねる）")
            assertEquals(listOf("shape_id", "seq", "labels"), columns("shape_labels").keys.toList(), "スカラーの配列は値のカラム")
            assertEquals(listOf("COL-shape_parts.shape_id", "COL-shape_parts.code"), list(list(tables.getValue("shape_parts"), "indexes").single(), "columns").map { it["column"] }, "キーの組で重ならない")
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
            val ws = Workspace.open(dir)
            val ddl = Reports.run(assertNotNull(Reports.load(dir).report("ddl")), emptyMap(), Views.load(dir, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
            assertTrue("ALTER TABLE shape_parts_notes_tags ADD CONSTRAINT fk_shape_parts_notes_tags_shape_parts_notes FOREIGN KEY (shape_id, parts_seq, notes_seq) REFERENCES shape_parts_notes (shape_id, parts_seq, seq) ON DELETE CASCADE;" in ddl, ddl)
            assertTrue("CREATE INDEX ix_shape_parts_qty ON shape_parts (code, qty DESC);" in ddl, "台帳の索引の降順")
            assertTrue("  labels VARCHAR(100) NOT NULL," in ddl, "配列の要素のカラムも、論理型の値で型の穴を埋める")
            H2.run(ddl) { db ->
                db.exec("INSERT INTO tag (id, name) VALUES ('TAG-001', 'a')")
                db.exec("INSERT INTO shape (id, name, size_width) VALUES ('SHP-001', 'n', 1)")
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
        assertTrue(listOf("actions.effects", "actions.checks", "actions.require", "constraints.rule").all { it in concepts("processes") }, "処理設計書: ${concepts("processes")}")
        assertTrue("relations.cardinality" in concepts("domains") && "lifecycles.transitions" in concepts("domains"), "ドメイン定義書: ${concepts("domains")}")
        assertEquals(emptyList<Any>(), concepts("screen-styles"), "クラスの対応表は業務モデルを読まない（決まった表）")
        assertEquals("basic", lineage["basic-entities"]?.get("stage"), "基本設計項目一覧は基本設計の段を読む")
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
