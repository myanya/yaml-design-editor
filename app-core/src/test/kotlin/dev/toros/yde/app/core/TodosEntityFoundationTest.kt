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
    private fun rows(ws: Workspace, name: String, entity: String? = null): List<VirtualRow> {
        val views = Views.load(ws.root, ws.schemas)
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
            Files.walk(root).use { paths -> paths.forEach { p ->
                val relative = root.relativize(p)
                if (!relative.toString().replace('\\', '/').startsWith(".yde/state")) {
                    val target = dir.resolve(relative)
                    if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
                }
            } }
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
    fun `全項目の基底と C U 表は生成原本と一致し 書込の無い項目も残る`() {
        val ws = Workspace.open(root)
        val entities = list(doc(root, "design/basic/entities.yaml"), "entities")
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
        assertEquals(52, writes)
        val todo = rows(ws, "entity-definition-items", "ENT-todo")
        assertEquals("ENT-tag", value(todo.single { it.id == "FLD-todo.tags" }, "要素の参照先"))
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
        val types = list(doc(dir, "design/basic/types.yaml"), "types").associateBy { it["id"] }
        assertEquals(100, types.getValue("TYP-common.一行の文")["maxLength"])
        assertEquals(true, types.getValue("TYP-text")["multiline"])
        assertEquals(55, list(types.getValue("TYP-common.コメント"), "fields").single { it["key"] == "body" }["maxLength"])
        assertEquals("40", value(rows(Workspace.open(dir), "entity-definition-items", "ENT-todo").single { it.id == "FLD-todo.title" }, "最大文字数").toString())
        Files.writeString(dir.resolve("design/basic/corrections.yaml"), """
            corrections:
              - { id: COR-001, target: TYP-common.一行の文, set: { maxLength: 200 }, reason: 共通の文字数 }
              - { id: COR-002, target: FLD-todo.title, set: { maxLength: 60, label: 修正した名称 }, reason: この項目の例外 }
              - { id: COR-003, target: FLD-todo.category, set: { type: TYP-common.タグ参照 }, reason: 参照の変更を確認 }
              - { id: COR-004, target: FLD-todo.tags, set: { unique: false }, reason: 索引への反映を確認 }
              - { id: COR-005, target: FLD-todo.summary, set: { default: '' }, reason: 空文字の初期値を確認 }
              - { id: COR-006, target: FLD-todo.details, set: { type: TYP-common.年月日 }, reason: 基底の異なる型への変更を確認 }
              - { id: COR-007, target: ENT-todo, set: { label: 改訂TODO }, reason: 概要と明細の名前を確認 }
        """.trimIndent() + "\n")
        generate(dir)
        val ws = Workspace.open(dir)
        val shown = rows(ws, "entity-definition-items", "ENT-todo")
        val titleRow = shown.single { it.id == "FLD-todo.title" }
        assertEquals("60", value(titleRow, "最大文字数").toString())
        assertEquals("修正した名称", value(titleRow, "名称"))
        assertTrue(titleRow.cells.single { it.column.label == "最大文字数" }.trace!!.inputs.any { it.placeholder.contains("COR-002") })
        assertEquals("200", value(rows(ws, "entity-definition-items", "ENT-category").single { it.id == "FLD-category.name" }, "最大文字数").toString())
        assertEquals("ENT-tag", value(shown.single { it.id == "FLD-todo.category" }, "参照先"))
        assertEquals("date", value(shown.single { it.id == "FLD-todo.details" }, "基底の種類"))
        val tables = list(doc(dir, "design/detail/tables.yaml"), "tables").associateBy { it["name"] }
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
        val rawTitle = list(list(doc(dir, "design/basic/entities.yaml"), "entities").single { it["id"] == "ENT-todo" }, "items").single { it["key"] == "title" }
        assertEquals(40, rawTitle["maxLength"], "補正で原本は書き換えない")
    }
}
