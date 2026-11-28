package dev.toros.yde.app.core

import dev.toros.yde.workspace.BusinessModel
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 業務モデルを展開した形（生成の入力）: samples/todos の定義から、操作の効果（do・Lifecycle・自動の値の 3 か所を 1 列に）、
 * 展開した Lifecycle、エンティティの側面（型を解いた項目）を 1 つの項にする。文書（データ）は読まない。
 */
class BusinessModelTest {
    @Suppress("UNCHECKED_CAST")
    private val model: Map<String, Any?> by lazy {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(Path.of("samples/todos")))
        assertEquals(emptyList(), ws.schemaErrors)
        YamlIO.toAny(BusinessModel.of(ws)) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String, of: Map<String, Any?> = model) = of[key] as List<Map<String, Any?>>
    private fun byId(key: String, id: String, of: Map<String, Any?> = model) = assertNotNull(list(key, of).firstOrNull { it["id"] == id }, "$key: $id")

    @Test
    fun `操作の効果は do と Lifecycle と自動の値を 1 列にまとめる`() {
        val complete = byId("actions", "complete-todo")
        assertEquals("todo", complete["subject"])
        val lifecycle = complete["lifecycle"] as Map<*, *>
        assertEquals(
            listOf(mapOf("from" to listOf(mapOf("id" to "todo-IN_PROGRESS", "label" to "対応中", "value" to "IN_PROGRESS")), "to" to mapOf("id" to "todo-CLOSED", "label" to "完了", "value" to "CLOSED"))),
            lifecycle["transitions"],
        )
        assertEquals("「{id}」は「{state}」なので、「TODOを完了する」はできません（できるのは「対応中」のとき）", lifecycle["refusal"])
        assertEquals(mapOf("date" to "\$date"), lifecycle["history"])
        assertEquals(listOf("no-open-task"), list("require", complete).map { it["id"] }, "状態の条件は Lifecycle から（前提ではない）")
        val effects = list("effects", complete).map { listOf(it["op"], it["schema"], it["path"], it["source"]) }
        assertEquals(
            listOf(
                listOf("set", "todo-detail", "completed_on", "do"),
                listOf("set", "todo-detail", "result", "do"),
                listOf("set", "todo-detail", "status", "lifecycle"),
                listOf("insert", "todo-detail", "history", "lifecycle"),
                listOf("set", "todo-detail", "updated_on", "auto"),
            ),
            effects,
        )
        // 書き込む項目の事実（どの型の項目か・基底の種類）。列か子の表かは生成の定義が決める
        val history = list("effects", complete).first { it["path"] == "history" }
        assertEquals("todo", history["entity"])
        assertEquals(mapOf("key" to "history", "base" to "array", "item" to mapOf("base" to "object")), history["field"])
        val row = history["value"] as Map<*, *>
        assertEquals("\$date", row["date"], "操作の history の値が既定の今日を上書きする")
        assertEquals(mapOf("\$copy" to "status", "\$of" to "self"), row["from"])
        assertEquals("CLOSED", row["to"])
        // 入力は型を解いた形（完了結果は文章を狭めた型で、1 文字以上・複数行。基底の種類は文字）
        val result = list("params", complete).first { it["key"] == "result" }
        assertEquals("todo-operations#操作の記録", result["type"])
        assertEquals("string", result["base"])
        assertEquals(true, result["multiline"])
        assertEquals(1, result["minLength"])
        assertEquals(true, result["required"])
    }

    @Test
    fun `登録は入口で作り 中止はどの子の状態からでも 型の操作は受け手の中だけ`() {
        val register = byId("actions", "register-todo")
        assertEquals(listOf("todo-OPEN"), ((register["lifecycle"] as Map<*, *>)["entry"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals("todo", register["subject"], "受け手の無い登録は、作る文書の型が主体")
        assertEquals("category", byId("actions", "add-category")["subject"], "一覧へ足す操作は、足す要素の型が主体")
        val effects = list("effects", register)
        assertTrue(effects.any { it["op"] == "create" && it["schema"] == "todo-detail" })
        assertTrue(effects.any { it["path"] == "status" && it["value"] == "OPEN" && it["on"] == "create" })
        assertTrue(effects.any { it["path"] == "created_on" && it["auto"] == "created" }, effects.toString())
        val cancel = byId("actions", "cancel-todo")
        val cancelMove = ((cancel["lifecycle"] as Map<*, *>)["transitions"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("todo-OPEN", "todo-IN_PROGRESS", "todo-SUSPENDED"), (cancelMove["from"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertTrue("できるのは「未完了」のとき" in (cancel["lifecycle"] as Map<*, *>)["refusal"].toString(), "子がそろえば親の名前でまとめる")
        val finish = byId("actions", "todo-task.finish")
        assertEquals("method", finish["kind"])
        assertTrue(list("effects", finish).any { it["path"] == "done" && it["within"] == "todo-task" && it["schema"] == "todo-detail" })
    }

    @Test
    fun `エンティティの側面と State と展開した Lifecycle`() {
        val todo = byId("entities", "todo")
        val aspects = list("aspects", todo)
        assertEquals(listOf("todo-list" to "todos[*]", "todo-detail" to ""), aspects.map { it["schema"] to it["at"] })
        val title = list("fields", aspects[0]).first { it["key"] == "title" }
        assertEquals(100, title["maxLength"])
        assertEquals("common#一行の文", title["type"])
        val status = list("fields", aspects[1]).first { it["key"] == "status" }
        assertEquals(5, (status["values"] as List<*>).size)
        assertEquals("todo-task", (list("fields", aspects[1]).first { it["key"] == "tasks" }["item"] as Map<*, *>)["entity"])
        assertEquals("todo-ACTIVE", byId("states", "todo-OPEN")["parent"])
        assertEquals(listOf("todo-OPEN", "todo-IN_PROGRESS", "todo-SUSPENDED"), byId("states", "todo-ACTIVE")["children"])
        val lifecycle = byId("lifecycles", "todo-lifecycle")
        assertEquals(5, (lifecycle["states"] as List<*>).size)
        assertEquals(listOf("todo-ACTIVE"), list("groups", lifecycle).map { it["id"] })
        assertEquals(listOf("todo-CLOSED", "todo-CANCELED"), list("terminal", lifecycle).map { it["id"] })
    }

    @Test
    fun `ビューとダッシュボードと ダッシュボードからたどれる範囲`() {
        val management = byId("views", "todo-management")
        assertEquals("grid", management["kind"])
        assertEquals("todo", management["rows"])
        assertEquals("カテゴリ", list("columns", management).first()["label"])
        val matrix = byId("views", "category-status-matrix")
        assertEquals("category" to "todo", matrix["rows"] to matrix["counts"], "集計は行の型と、数える要素の型")
        val dashboard = byId("dashboards", "todo-dashboard")
        assertEquals(true, dashboard["default"])
        assertEquals(listOf("register-todo", "add-category", "add-tag"), dashboard["actions"])
        assertTrue(list("sections", dashboard).any { it["id"] == "categories-master" && it["view"] == "category-management" })
        // たどれる範囲: 区画の行の型（todo・category・tag）と参照先、入れ子の型（作業項目）、上部の操作と受け手がたどれる型の操作
        val reach = dashboard["reach"] as Map<*, *>
        assertEquals(listOf("todo", "category", "tag"), reach["entities"])
        assertEquals(listOf("todo-task"), reach["nested"])
        assertEquals(15, (reach["operations"] as List<*>).size)
        assertTrue("todo-task.finish" in (reach["operations"] as List<*>), "入れ子の型の操作も、詳細画面の表からたどれる")
        assertEquals(reach, model["reach"], "ダッシュボードが 1 つなら、和も同じ")
        // 区画の条件の State の名前は、値の条件へ置き換える（YDE の読み込みと同じ）
        val overdue = list("sections", dashboard).first { it["id"] == "overdue" }
        @Suppress("UNCHECKED_CAST")
        val where = (overdue["filter"] as Map<String, Any?>)["where"] as List<Map<String, Any?>>
        assertEquals(mapOf("of" to "t", "path" to "status", "oneOf" to listOf("OPEN", "IN_PROGRESS")), where.first())
        // 共有の入力は、同じ型を使う項目から型を解いた形を添える。区画が基準日を使う
        val tags = list("inputs", dashboard).first { it["key"] == "tags" }
        assertEquals("tag", (tags["field"] as Map<*, *>)["target"])
        assertEquals(true, dashboard["usesToday"])
        // ビューの列は、読む項目の型を解いた形を添える（参照なら参照先と見出しの項目）
        val categoryColumn = list("columns", management).first { it["label"] == "カテゴリ" }
        assertEquals(mapOf("type" to "common#カテゴリ参照", "base" to "ref", "target" to "category", "targetTitle" to "name"), categoryColumn["field"])
    }

    @Test
    fun `並びの 1 件を番号で指す効果は 番号の名前と 書く子の項目を持つ`() {
        // コメントは ID を持たないので、個票への操作が、並びの番号（受け取る値 row）で 1 件を指す
        val edit = list("effects", byId("actions", "edit-todo-comment")).filter { it["source"] == "do" }
        assertEquals(listOf("set", "set"), edit.map { it["op"] })
        edit.forEach {
            assertEquals(mapOf("param" to "row"), it["row"], "番号を受け取る値の名前")
            assertEquals("comments", (it["field"] as Map<*, *>)["key"], "書く先の項目は並びそのもの")
        }
        // 書く子の項目は、足すとき（insert）と同じ形の written
        assertEquals(listOf("date", "body"), edit.map { (it["written"] as List<*>).map { w -> (w as Map<*, *>)["key"] }.single() })
        assertEquals("date", ((edit.first()["written"] as List<*>).single() as Map<*, *>)["base"])
        val delete = list("effects", byId("actions", "delete-todo-comment")).single { it["source"] == "do" }
        assertEquals("delete" to mapOf("param" to "row"), delete["op"] to delete["row"])
        assertEquals(null, delete["written"], "消すときは、書く項目は無い")
        // 番号の無い書き換え（並びの丸ごとの入れ替え）には row が付かない
        val tags = list("effects", byId("actions", "edit-todo")).first { it["op"] == "set" && it["path"] == "tags" }
        assertEquals(null, tags["row"])
    }

    @Test
    fun `見出しの項目と 値を消す受け取る値`() {
        assertEquals("title", byId("entities", "todo")["title"])
        assertEquals("name", byId("entities", "category")["title"])
        val edit = byId("actions", "edit-todo")
        val params = list("params", edit).associateBy { it["key"] }
        assertEquals("clear.category", params.getValue("category")["clearedBy"], "値を消すのチェックが、どの欄のものか（効果の when から）")
        assertEquals("clear.tags", params.getValue("tags")["clearedBy"], "並びの項目を消すのも同じ")
        assertTrue("comments" !in params, "コメントは「TODOを編集する」では変えない（追加・編集・削除の操作で変える）")
        assertEquals(null, params.getValue("title")["clearedBy"])
        assertEquals("name", params.getValue("category")["targetTitle"], "参照の値の名前は参照先の見出しの項目")
    }

    @Test
    fun `採番 入れ子の外側 参照先 State の値 作るエンティティ 書く項目`() {
        // 採番: 作る手の id（TODO）・配列の追加の決まり（作業項目は外側の番号に -S と 2 桁、カテゴリは CAT- と 3 桁）
        assertEquals(mapOf("path" to "id", "prefix" to "TODO-", "digits" to 3), byId("entities", "todo")["numbering"])
        val task = byId("entities", "todo-task")
        assertEquals(mapOf("path" to "id", "prefix" to mapOf("\$format" to "{id}-S", "\$of" to "owner"), "digits" to 2), task["numbering"])
        assertEquals(mapOf("entity" to "todo", "field" to "tasks"), task["owner"], "作業項目は TODO の個票の tasks に入れ子で書かれる")
        assertEquals(mapOf("path" to "id", "prefix" to "CAT-", "digits" to 3), byId("entities", "category")["numbering"])
        // 参照先のエンティティ型と、State の値を持つ列挙型（状態の履歴の前後の状態も同じ型）
        val todoAspects = list("aspects", byId("entities", "todo"))
        assertEquals("category", list("fields", todoAspects[0]).first { it["key"] == "category" }["target"])
        assertEquals("tag", (list("fields", todoAspects[0]).first { it["key"] == "tags" }["item"] as Map<*, *>)["target"])
        assertEquals("todo", list("fields", todoAspects[1]).first { it["key"] == "status" }["stateOf"])
        val historyItem = (list("fields", todoAspects[1]).first { it["key"] == "history" }["item"] as Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val historyFields = historyItem["fields"] as List<Map<String, Any?>>
        assertEquals("todo", historyFields.first { it["key"] == "to" }["stateOf"])
        assertEquals(null, historyFields.first { it["key"] == "action" }["stateOf"], "操作の選択肢は State の値ではない")
        // 操作が作るエンティティ型（文書を作る手・エンティティの要素を足す手）
        assertEquals(listOf("todo"), byId("actions", "register-todo")["creates"])
        assertEquals(listOf("todo-task"), byId("actions", "add-todo-task")["creates"])
        assertEquals(emptyList<Any>(), byId("actions", "complete-todo")["creates"])
        // 個票の tasks へ足す手は、TODO の項目 tasks を書く（一覧そのものではない）。足す要素の項目ごとの基底の種類と採番
        val insertTask = list("effects", byId("actions", "add-todo-task")).first { it["op"] == "insert" }
        assertEquals("todo", insertTask["entity"])
        assertEquals("todo-task", ((insertTask["field"] as Map<*, *>)["item"] as Map<*, *>)["entity"])
        assertEquals(listOf("id" to "id", "title" to "string", "done" to "boolean"), list("written", insertTask).map { it["key"] to it["base"] })
        assertEquals(2, (insertTask["numbering"] as Map<*, *>)["digits"])
        // 文書を作る手は、作る文書のルートの型と、書く値の項目ごとの基底の種類
        val create = list("effects", byId("actions", "register-todo")).first { it["op"] == "create" }
        assertEquals("todo", create["entity"])
        assertEquals(listOf("due_on" to "date", "details" to "string", "comments" to "array"), list("written", create).map { it["key"] to it["base"] })
    }

    @Test
    fun `データの決まりと 操作が書くと破れうる決まり`() {
        // 決まりの形: 型の検査（要素ごと。ID の無い明細の行は場所 at）・入れ子の線の検査・書かれた場所の検査・関係レジストリ
        val due = byId("constraints", "due-not-before-created")
        assertEquals("element", due["form"])
        assertEquals(listOf("todo.due_on", "todo.created_on"), due["reads"])
        assertEquals("TODO「{id}」の期限（{left}）が、作成日（{right}）より前です", due["message"])
        assertEquals("common#TODO個票", due["source"], "書かれた場所は型を書いた文書と型の名前")
        val resume = byId("constraints", "resume-not-before-suspend")
        assertEquals("history[*]", resume["at"])
        assertEquals(listOf("todo.history[*].resume_on", "todo.history[*].date"), resume["reads"])
        val noOpen = byId("constraints", "closed-has-no-open-task")
        assertEquals("pattern", noOpen["form"])
        assertEquals(listOf("todo.status", "todo-task.done", "todo.tasks"), noOpen["reads"], "State の名前は値の項目に、入れ子の線は外側の項目に")
        assertEquals("occurrence", byId("constraints", "has-detail")["form"])
        val acyclic = byId("constraints", "follow-up-of.acyclic")
        assertEquals(listOf("relation", "acyclic", "error", listOf("todo.follows")), listOf(acyclic["form"], acyclic["kind"], acyclic["level"], acyclic["reads"]))
        assertEquals("warning", byId("constraints", "in-category.outMax")["level"], "関係の多重度の重さは関係の宣言の level（既定は警告）")
        // 操作の checks: 書く項目と読む項目が重なり、書いた後の要素が絞り込みに入りうるものだけ
        fun checks(action: String) = list("checks", byId("actions", action)).associateBy { it["constraint"] }
        val complete = checks("complete-todo")
        assertEquals(
            setOf("updated-not-before-created", "completed-not-before-created", "closed-has-completed-on", "closed-has-result", "closed-has-no-open-task"),
            complete.keys,
            "完了は、完了以外の決まり（完了日を残さない）と、再開予定日の無い行の決まりを確かめない",
        )
        assertEquals(listOf("result"), complete["closed-has-result"]?.get("params"), "受け取る値が原因で破れうる")
        assertEquals(listOf("todo.status", "todo.result"), complete["closed-has-result"]?.get("items"))
        assertEquals("element", complete["closed-has-result"]?.get("form"), "決まりの形を写して持つ")
        assertEquals(listOf("resume_on", "date"), checks("suspend-todo")["resume-not-before-suspend"]?.get("params"))
        val register = checks("register-todo")
        assertTrue("completed-not-before-created" !in register && "closed-has-result" !in register, "新しく作る要素は、書かない項目と、書く状態の値で絞り込みを外す")
        assertEquals(listOf("due"), register["due-not-before-created"]?.get("params"))
        assertEquals(listOf("result"), checks("edit-todo")["closed-has-result"]?.get("params"), "「値を消す」で消すのも、その受け取る値が原因")
        assertEquals(setOf("updated-not-before-created", "closed-has-no-open-task"), checks("todo-task.finish").keys)
        assertEquals(emptyMap(), checks("add-category"))
    }

    @Test
    fun `型の説明と集約 関係 参照先と操作の名前`() {
        val todo = byId("entities", "todo")
        assertTrue(todo["description"].toString().startsWith("やることを 1 件ずつ管理する単位です"), "型の説明は関係レジストリの宣言から")
        assertEquals("todo", todo["aggregate"])
        assertEquals("todo", byId("entities", "todo-task")["aggregate"], "入れ子の型の集約は、いちばん外側の持ち主")
        assertEquals("category", byId("entities", "category")["aggregate"])
        val follow = byId("relations", "follow-up-of")
        assertEquals(listOf("元のTODO", "続きのTODO", listOf("todo"), listOf("todo"), true), listOf(follow["label"], follow["inverseLabel"], follow["source"], follow["target"], follow["acyclic"]))
        assertEquals(mapOf("outMax" to 1), follow["cardinality"])
        assertEquals(listOf(mapOf("entity" to "todo", "key" to "tags", "multiple" to true)), byId("relations", "tagged")["fields"])
        // 参照先の型の名前・入れ子の型の名前・項目の説明・遷移の操作の名前
        val fields = list("aspects", todo).flatMap { list("fields", it) }
        assertEquals("カテゴリ", fields.first { it["key"] == "category" }["targetLabel"])
        assertEquals("作業項目", (fields.first { it["key"] == "tasks" }["item"] as Map<*, *>)["entityLabel"])
        assertEquals("未定の場合は省略します。作成日より前の日付は検査が指摘します。", fields.first { it["key"] == "due_on" }["description"])
        val complete = list("transitions", byId("lifecycles", "todo-lifecycle")).first { it["action"] == "complete-todo" }
        assertEquals("TODOを完了する", complete["actionLabel"])
    }
}
