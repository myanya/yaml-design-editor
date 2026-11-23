package dev.toros.yde.app.core

import dev.toros.yde.semantic.VirtualRow
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** エンティティ定義書は、生成物が最新なだけでなく、業務の値・項目固有の制限・補正を出力まで保持する。 */
class TodosEntityFoundationTest {
    private val root = Path.of("samples/todos-sysdev")
    private fun doc(dir: Path, name: String) = YamlIO.toAny(YamlIO.parseFirst(Files.readString(dir.resolve(name)))!!) as Map<*, *>
    private fun list(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private var viewWorkspace: Workspace? = null
    private lateinit var views: Views.LoadResult
    private fun rows(ws: Workspace, name: String, entity: String? = null): List<VirtualRow> {
        if (viewWorkspace !== ws) {
            views = Views.load(ws.root, ws.schemas)
            viewWorkspace = ws
        }
        assertEquals(emptyList(), views.errors)
        return Views.resolve(assertNotNull(views.view(name)), ws.index, ws::treeOf, ws::schemaFor,
            entity?.let { mapOf("entity" to it) }.orEmpty())
    }
    private fun value(row: VirtualRow, label: String): Any? = row.cells.single { it.column.label == label }.value?.let(YamlIO::toAny)
    private fun generate(dir: Path) {
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        // 詳細だけ指定しても基本の生成を先に行い、その新しい値を同じ計画で読む。
        val plan = Generators.plan(session, setOf("detail-tables"))
        assertEquals(emptyList(), plan.errors)
        assertTrue(session.apply(plan.transaction).ok)
        assertTrue(session.save().ok)
        assertTrue(Generators.plan(WorkspaceSession(FileSystemDocumentStore(dir)), setOf("detail-tables")).upToDate)
    }
    private fun copy(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("entity-foundation")
        try {
            TodosSysdevFiles.copy(root, dir)
            block(dir)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `導出記録は明記と継承を区別し 確定項目の索引に混ざらない`() = copy { dir ->
        val common = dir.resolve(".yde/schemas/business/common.yaml")
        val title = "      - key: title\n        label: タイトル\n        type: 一行の文\n"
        val original = Files.readString(common)
        assertTrue(title in original)
        // 共通型と同じ値を明記する。値の差から判別する方式ではこの指定が失われる。
        Files.writeString(common, original.replace(title, title + "        maxLength: 100\n"))
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        val plan = Generators.plan(session, setOf("basic-derived"))
        assertEquals(emptyList(), plan.errors)
        assertTrue(session.apply(plan.transaction).ok)
        assertTrue(session.save().ok)
        val model = doc(dir, "design/basic/derived/model.yaml")["model"] as Map<*, *>
        val todo = list(model, "entities").single { it["id"] == "todo" }
        val fields = list(todo, "aspects").flatMap { list(it, "fields") }
        val explicit = fields.single { it["key"] == "title" }
        val declarations = list(explicit, "declarations")
        val own = declarations.single { it["inherited"] == false }
        assertEquals(100, (own["declared"] as Map<*, *>)["maxLength"])
        assertTrue(declarations.any { it["inherited"] == true && (it["declared"] as Map<*, *>)["maxLength"] == 100 })
        assertEquals(".yde/schemas/business/common.yaml", own["document"])
        assertFalse(model.containsKey("stages"), "導出記録は設計の段の規則より前の入力")
        val fresh = WorkspaceSession(FileSystemDocumentStore(dir))
        val ids = fresh.derived.semantic.ids.values.flatten().filter { it.document.value == "design/basic/derived/model.yaml" }
        assertEquals(listOf("DRV-business"), ids.map { it.id }, "導出記録の中の識別子は通常の参照先にしない")
        assertTrue(Generators.plan(fresh, setOf("basic-derived")).upToDate)
    }

    @Test
    fun `エンティティの個票は 概要と レビュー用の表を そのエンティティを根にして重ねる`() {
        val ws = Workspace.open(root)
        val catalog = Views.load(ws.root, ws.schemas)
        assertEquals(emptyList(), catalog.errors)
        val entities = list(doc(root, "design/basic/final/entities.yaml"), "entities")
        for (entity in entities) {
            val id = entity["id"].toString()
            // 当たるのは確定項目のエンティティ（ENT-*）で、根は entities.yaml の中のその要素
            val (sheet, occurrence) = assertNotNull(catalog.elementViewFor(id, ws.index.occurrences(id)), id)
            assertEquals("entity-definition-sheet", sheet.id)
            assertEquals("design/basic/final/entities.yaml", occurrence.document.value)
            val sections = Views.resolveDocument(sheet, occurrence.document, ws.index, loader = ws::treeOf, schemaFor = ws::schemaFor, catalog = catalog, at = occurrence.path)
            assertEquals(
                listOf("概要", "項目", "操作と、書く項目", "項目 × 操作", "子要素 × 操作（C＝足す・U＝変える・D＝消す）", "子要素: {label}", "子要素: {label}"),
                sections.map { it.section.label },
            )
            sections.forEach { assertEquals(null, it.reason, "$id ${it.section.label}") }
            // 表はどれも、このエンティティの id を受け取る値にして解く
            sections.subList(1, 5).forEach { assertEquals(mapOf<String, Any?>("entity" to id), it.gridParams, it.section.label) }
            val items = list(entity, "items")
            assertEquals(items.map { it["id"] }, assertNotNull(sections[1].gridRows).map { it.id }, "項目は確定項目の順")
            assertEquals(list(entity, "operations").map { it["id"] }, assertNotNull(sections[2].gridRows).map { it.id })
            assertEquals(items.map { it["id"] }, assertNotNull(sections[3].gridRows).map { it.id })
            assertEquals(items.filter { it["base"] == "array" }.map { it["id"] }, assertNotNull(sections[4].gridRows).map { it.id }, "子要素は並びの項目")
            // 子要素ごとのまとまり: 並びの中に項目を持つ子要素と、子がエンティティの子要素に分かれ、その子要素を根にして表を解く
            val inline = items.filter { (it["item"] as? Map<*, *>)?.get("fields") != null }
            val nested = items.filter { (it["item"] as? Map<*, *>)?.get("entity") != null }
            val inlineGroups = assertNotNull(sections[5].groups)
            assertEquals(inline.map { it["id"] }, inlineGroups.map { it.id })
            assertEquals(inline.map { "子要素: ${it["label"]}" }, inlineGroups.map { it.label })
            inlineGroups.forEach { g ->
                g.sections.forEach { assertEquals(mapOf<String, Any?>("field" to g.id), it.gridParams, "${g.id} ${it.section.label}") }
                val fields = list(inline.single { it["id"] == g.id }["item"] as Map<*, *>, "fields").map { it["id"] }
                g.sections.forEach { assertEquals(fields, assertNotNull(it.gridRows).map { r -> r.id }, "${g.id} ${it.section.label}") }
            }
            val nestedGroups = assertNotNull(sections[6].groups)
            assertEquals(nested.map { it["id"] }, nestedGroups.map { it.id })
            nestedGroups.forEach { g ->
                val child = (nested.single { it["id"] == g.id }["item"] as Map<*, *>)["entity"]
                g.sections.forEach { assertEquals(mapOf<String, Any?>("entity" to child), it.gridParams, "${g.id} ${it.section.label}") }
                val fields = list(entities.single { it["id"] == child }, "items").map { it["id"] }
                g.sections.forEach { assertEquals(fields, assertNotNull(it.gridRows).map { r -> r.id }, "${g.id} ${it.section.label}") }
            }
        }
        // TODO の個票には、コメント一覧・状態の履歴（並びの中の項目）と、作業項目（子のエンティティ）のまとまりが出る
        val todoSheet = assertNotNull(catalog.elementViewFor("ENT-todo", ws.index.occurrences("ENT-todo")))
        val todoSections = Views.resolveDocument(todoSheet.first, todoSheet.second.document, ws.index, loader = ws::treeOf, schemaFor = ws::schemaFor, catalog = catalog, at = todoSheet.second.path)
        assertEquals(listOf("子要素: コメント一覧", "子要素: 状態の履歴"), todoSections[5].groups?.map { it.label })
        assertEquals(listOf("子要素: 作業項目"), todoSections[6].groups?.map { it.label })
        // レビュー用の表は、値を読みやすい言葉に直すだけ（型は名前・必須は ●・書く項目は名称）
        val title = rows(ws, "entity-definition-review-items", "ENT-todo").single { it.id == "FLD-todo.title" }
        assertEquals("一行の文", value(title, "型"))
        assertEquals("●", value(title, "必須"))
        assertEquals("100 文字まで", value(title, "文字数"))
        val tags = rows(ws, "entity-definition-review-items", "ENT-todo").single { it.id == "FLD-todo.tags" }
        assertEquals("タグ参照 の並び", value(tags, "型"))
        assertEquals(listOf("タグ"), value(tags, "参照先"))
        val comment = rows(ws, "entity-definition-review-operations", "ENT-todo").single { it.id == "OPS-todo.add-todo-comment" }
        assertEquals("更新", value(comment, "作成・更新"))
        assertEquals(setOf("コメント一覧", "更新日"), (value(comment, "書く項目") as List<*>).toSet())
        assertEquals(listOf("コメント一覧", "コメント一覧"), rows(ws, "entity-definition-review-nested", "ENT-todo").take(2).map { value(it, "親の項目") })
        // 文書ビューとしては entities.yaml に当たらない（編集画面のビューのタブには出ない）
        assertEquals(null, catalog.docViewFor("design/basic/final/entities.yaml", "basic-entity-list")?.takeIf { it.id == "entity-definition-sheet" })
    }

    @Test
    fun `子要素への作用は 業務モデルの足す 入れ替える 消すから導き 子要素ごとの表は確定項目と一致する`() {
        val ws = Workspace.open(root)
        val entities = list(doc(root, "design/basic/final/entities.yaml"), "entities")
        val todo = entities.single { it["id"] == "ENT-todo" }
        fun write(action: String, item: String) = list(list(todo, "operations").single { it["action"] == action }, "writes").single { it["item"] == "FLD-todo.$item" }
        // 足す（insert）＝C と、そのとき書く子の項目
        assertEquals(listOf("C"), write("add-todo-comment", "comments")["element"])
        assertEquals(listOf("FLD-todo.comments.item.date", "FLD-todo.comments.item.body"), write("add-todo-comment", "comments")["elementItems"])
        // 状態の履歴は操作ごとに書く項目が違う（中断だけが再開予定日を書く）
        assertEquals(listOf("date", "action", "to"), (write("register-todo", "history")["elementItems"] as List<*>).map { it.toString().substringAfterLast('.') })
        assertTrue("FLD-todo.history.item.resume_on" in write("suspend-todo", "history")["elementItems"] as List<*>)
        assertTrue("FLD-todo.history.item.resume_on" !in write("resume-todo", "history")["elementItems"] as List<*>)
        // 丸ごと入れ替える（set）と消す（delete）＝C・U・D で、子要素のすべての項目が対象。子に項目の無い並び（タグ）は項目を持たない
        assertEquals(listOf("C", "U", "D"), write("edit-todo", "comments")["element"])
        assertEquals(listOf("FLD-todo.comments.item.date", "FLD-todo.comments.item.body"), write("edit-todo", "comments")["elementItems"])
        assertEquals(listOf("C", "U", "D"), write("edit-todo", "tags")["element"])
        assertEquals(null, write("edit-todo", "tags")["elementItems"])
        // 作るときに値を入れる並び（タグ）は C。空で作る並び（コメント一覧）と、並びでない項目には付かない
        assertEquals(listOf("C"), write("register-todo", "tags")["element"])
        assertEquals(null, write("register-todo", "comments")["element"])
        assertEquals(null, write("register-todo", "title")["element"])
        // 子のエンティティ: 足すのは親の並びへの C、子の中への作用（済にする）は親から見て U
        assertEquals(listOf("C") to listOf("FLD-todo-task.id", "FLD-todo-task.title", "FLD-todo-task.done"),
            write("add-todo-task", "tasks").let { it["element"] to it["elementItems"] })
        assertEquals(listOf("U") to listOf("FLD-todo-task.done"), write("todo-task.finish", "tasks").let { it["element"] to it["elementItems"] })
        assertEquals("U", write("todo-task.finish", "tasks")["crud"])

        // 子要素 × 操作: 行は並びの項目、セルは確定項目の element のまま
        val children = rows(ws, "entity-definition-children", "ENT-todo")
        assertEquals(listOf("FLD-todo.tags", "FLD-todo.tasks", "FLD-todo.comments", "FLD-todo.history"), children.map { it.id })
        for (operation in list(todo, "operations")) for (row in children) {
            val expected = list(operation, "writes").singleOrNull { it["item"] == row.id }?.get("element") as? List<*>
            assertEquals(expected.orEmpty(), value(row, operation["label"].toString()), "${row.id} × ${operation["label"]}")
        }
        assertEquals(listOf("U"), value(children.single { it.id == "FLD-todo.tasks" }, "作業項目を済にする"))
        assertEquals(listOf("C", "U", "D"), value(children.single { it.id == "FLD-todo.comments" }, "TODOを編集する"))

        // 子要素の 項目 × 操作: 列はその子要素に触る操作だけ。セルは、その項目を書く操作の element
        fun childRows(name: String, field: String) = Views.resolve(assertNotNull(views.view(name)), ws.index, ws::treeOf, ws::schemaFor, mapOf("field" to field))
        val history = childRows("entity-definition-child-crud", "FLD-todo.history")
        assertEquals(
            listOf("項番", "項目", "名称", "TODOを登録する", "TODOに着手する", "TODOを中断する", "TODOを再開する", "TODOを完了する", "TODOを中止する"),
            history.first().cells.map { it.column.label },
        )
        val resumeOn = history.single { it.id == "FLD-todo.history.item.resume_on" }
        assertEquals(listOf("C"), value(resumeOn, "TODOを中断する"))
        assertEquals(emptyList<Any>(), value(resumeOn, "TODOを再開する"))
        assertEquals(emptyList<Any>(), value(history.single { it.id == "FLD-todo.history.item.from" }, "TODOを登録する"), "登録には前の状態が無い")
        val comments = childRows("entity-definition-child-crud", "FLD-todo.comments")
        assertEquals(listOf("TODOにコメントを追加する", "TODOを編集する"), comments.first().cells.drop(3).map { it.column.label })
        comments.forEach {
            assertEquals(listOf("C"), value(it, "TODOにコメントを追加する"))
            assertEquals(listOf("C", "U", "D"), value(it, "TODOを編集する"))
        }
        // 子要素の項目の表は、その子要素の項目だけ（一緒くたにしない）
        assertEquals(listOf("日付", "コメント本文"), childRows("entity-definition-child-items", "FLD-todo.comments").map { value(it, "名称") })
        assertEquals(6, childRows("entity-definition-child-items", "FLD-todo.history").size)
    }

    @Test
    fun `全項目の基底と C U 表は生成原本と一致し 書込の無い項目も残る`() {
        val ws = Workspace.open(root)
        val entities = list(doc(root, "design/basic/final/entities.yaml"), "entities")
        var writes = 0
        for (entity in entities) {
            val id = entity["id"].toString()
            val items = list(entity, "items")
            val shown = rows(ws, "entity-definition-items", id)
            val crud = rows(ws, "entity-definition-crud", id)
            assertEquals(items.map { it["id"] }, shown.map { it.id })
            assertEquals(shown.map { it.id }, crud.map { it.id })
            for (item in items) assertEquals(assertNotNull(item["base"]), value(shown.single { it.id == item["id"] }, "基底の種類"))
            for (operation in list(entity, "operations")) {
                val expected = list(operation, "writes").associate { it["item"] to it["crud"] }
                writes += expected.size
                for (row in crud) assertEquals(expected[row.id]?.let { listOf(it) }.orEmpty(), value(row, operation["label"].toString()))
            }
            assertEquals(listOf("項番", "項目", "名称") + list(entity, "operations").map { it["label"] }, crud.first().cells.map { it.column.label })
        }
        // 52 個の C/U と、子のエンティティの中への作用（作業項目を済にする → TODO の作業項目を U）の 1 個
        assertEquals(53, writes)
        val todo = rows(ws, "entity-definition-items", "ENT-todo")
        assertEquals(listOf("ENT-tag"), value(todo.single { it.id == "FLD-todo.tags" }, "要素の参照先"))
        assertEquals("ENT-todo-task", value(todo.single { it.id == "FLD-todo.tasks" }, "要素のエンティティ"))
        assertEquals(5, (value(todo.single { it.id == "FLD-todo.status" }, "選択肢") as List<*>).size)
    }

    @Test
    fun `項目固有の制限と共通型を分けて保持し 補正は参照と詳細生成にも届く`() = copy { dir ->
        val common = dir.resolve(".yde/schemas/business/common.yaml")
        val title = "      - key: title\n        label: タイトル\n        type: 一行の文\n"
        val original = Files.readString(common)
        assertTrue(title in original)
        Files.writeString(common, original.replace(title, title + "        maxLength: 40\n")
            .replace("- { key: body, label: コメント本文, type: text, required: true }", "- { key: body, label: コメント本文, type: text, maxLength: 55, required: true }"))
        generate(dir)
        val types = list(doc(dir, "design/basic/final/types.yaml"), "types").associateBy { it["id"] }
        assertEquals(100, types.getValue("TYP-common.一行の文")["maxLength"])
        assertEquals(true, types.getValue("TYP-text")["multiline"])
        assertEquals(55, list(types.getValue("TYP-common.コメント"), "fields").single { it["key"] == "body" }["maxLength"])
        assertEquals("40", value(rows(Workspace.open(dir), "entity-definition-items", "ENT-todo").single { it.id == "FLD-todo.title" }, "最大文字数").toString())
        // サンプルで了承済みの代表の説明は残し、この試験の補正を別文書で追加する。
        Files.writeString(dir.resolve("design/basic/inputs/entities/corrections-test.yaml"), """
            corrections:
              - { id: COR-101, target: TYP-common.一行の文, set: { maxLength: 200 }, reason: 共通の文字数 }
              - { id: COR-102, target: FLD-todo.title, set: { maxLength: 60, label: 修正した名称 }, reason: この項目の例外 }
              - { id: COR-103, target: FLD-todo.category, set: { type: TYP-common.タグ参照 }, reason: 参照の変更を確認 }
              - { id: COR-104, target: FLD-todo.tags, set: { unique: false }, reason: 索引への反映を確認 }
              - { id: COR-105, target: FLD-todo.summary, set: { default: '' }, reason: 空文字の初期値を確認 }
              - { id: COR-106, target: FLD-todo.details, set: { type: TYP-common.年月日 }, reason: 基底の異なる型への変更を確認 }
              - { id: COR-107, target: ENT-todo, set: { label: 改訂TODO }, reason: 概要と明細の名前を確認 }
        """.trimIndent() + "\n")
        generate(dir)
        val ws = Workspace.open(dir)
        val shown = rows(ws, "entity-definition-items", "ENT-todo")
        val titleRow = shown.single { it.id == "FLD-todo.title" }
        assertEquals("60", value(titleRow, "最大文字数").toString())
        assertEquals("修正した名称", value(titleRow, "名称"))
        assertTrue((value(titleRow, "根拠") as List<*>).any { (it as Map<*, *>)["source"] == "COR-102" })
        assertEquals("200", value(rows(ws, "entity-definition-items", "ENT-category").single { it.id == "FLD-category.name" }, "最大文字数").toString())
        assertEquals(listOf("ENT-tag"), value(shown.single { it.id == "FLD-todo.category" }, "参照先"))
        assertEquals("date", value(shown.single { it.id == "FLD-todo.details" }, "基底の種類"))
        val tables = list(doc(dir, "design/detail/final/tables.yaml"), "tables").associateBy { it["name"] }
        val category = list(tables.getValue("todo"), "columns").single { it["name"] == "category" }
        assertEquals("SQL-common.タグ参照", category["sqlType"])
        assertEquals("TBL-tag", category["referencesTable"])
        assertTrue(list(tables.getValue("todo_tags"), "indexes").none { it["unique"] == true })
        val ddl = Reports.run(assertNotNull(Reports.load(dir).report("ddl")), emptyMap(), Views.load(dir, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
        assertTrue("title VARCHAR(60) NOT NULL" in ddl, ddl)
        assertTrue("name VARCHAR(200) NOT NULL" in ddl, ddl)
        assertTrue("summary VARCHAR(200) DEFAULT '' NOT NULL" in ddl, ddl)
        assertTrue("details DATE" in ddl, ddl)
        assertTrue("-- 改訂TODO（トランザクション）" in ddl, ddl)
        val rawTitle = list(list(doc(dir, "design/basic/final/entities.yaml"), "entities").single { it["id"] == "ENT-todo" }, "items").single { it["key"] == "title" }
        assertEquals(60, rawTitle["maxLength"], "保存する確定項目とビューは同じ補正後の値")
        assertTrue("maxLength: 40" in Files.readString(common), "業務モデルの原文は変更しない")
        val cross = rows(ws, "entity-definition-items").single { it.id == titleRow.id }
        assertEquals(value(titleRow, "最大文字数"), value(cross, "最大文字数"))
        assertEquals(rawTitle["maxLength"].toString(), value(cross, "最大文字数").toString())
    }

    @Test
    fun `区分の追加と指定と補正を確定して 既存ビューと詳細生成で共有する`() = copy { dir ->
        Files.writeString(dir.resolve("design/basic/inputs/entities/additions-test.yaml"), """
            additions:
              - id: ADD-900
                kind: class
                reason: レビュー用の区分
                class: {designId: CLS-review, name: review, label: 確認中}
        """.trimIndent() + "\n")
        val input = dir.resolve("design/basic/inputs/entities/corrections.yaml")
        Files.writeString(input, Files.readString(input).replace("entityClass: CLS-transaction", "entityClass: CLS-review"))
        Files.writeString(dir.resolve("design/basic/inputs/entities/corrections-test.yaml"), """
            corrections:
              - {id: COR-900, target: CLS-review, set: {label: レビュー対象, description: 確認するデータ}, reason: 区分の名称と説明}
        """.trimIndent() + "\n")
        generate(dir)
        val ws = Workspace.open(dir)
        assertEquals("レビュー対象", value(rows(ws, "entity-definition-summary", "ENT-todo").single(), "区分"))
        val finalClass = list(doc(dir, "design/basic/final/classes.yaml"), "classes").single { it["id"] == "CLS-review" }
        assertEquals("レビュー対象", finalClass["label"])
        assertEquals("確認するデータ", finalClass["description"])
        assertEquals(setOf("ADD-900", "COR-900"), list(finalClass, "origins").map { it["source"] }.toSet())
        val ddl = Reports.run(assertNotNull(Reports.load(dir).report("ddl")), emptyMap(), Views.load(dir, ws.schemas), ws.index, ws::treeOf, ws::schemaFor).text
        assertTrue("-- TODO（レビュー対象）" in ddl, ddl)
    }

    @Test
    fun `未対応の指定と消えた対象を確定せず 前回の生成物も変更しない`() = copy { dir ->
        val cases = listOf(
            "corrections: [{id: COR-900, target: ENT-missing, set: {label: 無い対象}, reason: 検査}]",
            "corrections: [{id: COR-900, target: ENT-todo, set: {unknown: true}, reason: 検査}]",
            "specifications: [{id: SPC-900, target: ENT-todo, rule: unknown, reason: 検査}]",
        )
        val before = Files.readString(dir.resolve("design/basic/final/entities.yaml"))
        for (input in cases) {
            Files.writeString(dir.resolve("design/basic/inputs/entities/corrections-test.yaml"), "$input\n")
            val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(dir)), setOf("detail-tables"))
            assertTrue(plan.errors.any { "basic-final" in it }, plan.errors.toString())
            assertTrue(plan.transaction.operations.isEmpty())
            assertEquals(before, Files.readString(dir.resolve("design/basic/final/entities.yaml")))
        }
        Files.delete(dir.resolve("design/basic/inputs/entities/corrections-test.yaml"))
        Files.writeString(dir.resolve("design/basic/inputs/entities/additions-test.yaml"), """
            additions:
              - {id: ADD-900, kind: class, class: {designId: CLS-duplicate, name: master, label: 重複}, reason: 同じ名前の追加}
        """.trimIndent() + "\n")
        val duplicate = Generators.plan(WorkspaceSession(FileSystemDocumentStore(dir)), setOf("basic-entities"))
        assertTrue(duplicate.errors.isNotEmpty())
        assertTrue(duplicate.transaction.operations.isEmpty())
    }
}
