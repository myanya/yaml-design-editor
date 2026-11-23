package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 保存した確定値から、画面と入口の対応を検査してコード生成へ渡す。 */
class TodosWebPagesTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun dump(value: Any?) = YamlIO.dumpPlain(YamlIO.fromAny(value))
    private fun rows(value: Map<*, *>, key: String) = (value[key] as? List<*>)?.map { it as Map<*, *> }.orEmpty()
    private fun sources() = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
        .associate {
            val file = sample.relativize(it).toString().replace('\\', '/')
            // この検査は再導出せず、保存済みの確定値を入力として差し替える。
            file to Files.readString(it).let { text -> if ("/final/" in file) dump(raw(text)) else text }
        }

    private fun probe(changes: Map<String, Any?> = emptyMap()): dev.toros.yde.workspace.GenerationPlan {
        val files = sources()
        val definition = raw(files.getValue(TodosSysdevFiles.definition(sample, "transforms", "application-web"))).toMutableMap()
        definition["id"] = "web-pages-probe"
        definition["dependsOn"] = emptyList<String>()
        definition["output"] = "probe/web-pages.yaml"
        val store = InMemoryDocumentStore(files)
        store.write(DocumentId(".yde/transforms/design/detail/web-pages-probe.yaml"), dump(definition))
        changes.forEach { (file, value) -> store.write(DocumentId(file), dump(value)) }
        return Generators.plan(WorkspaceSession(store), setOf("web-pages-probe"))
    }

    @Test fun `全画面と入口と読出しは保存した確定値から一致する`() {
        val plan = probe()
        assertEquals(emptyList(), plan.errors)
        val model = raw(plan.outputs.single().text)
        assertEquals(raw(Files.readString(sample.resolve("design/detail/final/application-web.yaml"))), model)
        assertEquals(30, rows(model, "pages").size)
        assertEquals(5, rows(model, "controllers").size)
        assertEquals(56, rows(model, "controllers").sumOf { rows(it, "routes").size })
        assertEquals(4, rows(model, "readServices").size)
        assertEquals(1, rows(model, "readServices").count { it["standalone"] == true })
    }

    @Test fun `画面の補正後の題と列順を生成入力へ保持する`() {
        val file = "design/detail/final/screen-designs/details/todo.yaml"
        val original = raw(Files.readString(sample.resolve(file)))
        val changed = original.toMutableMap().also { document ->
            document["screens"] = rows(original, "screens").map { screen -> screen.toMutableMap().also { copy ->
                val page = (screen["page"] as Map<*, *>).toMutableMap()
                page["title"] = "補正した詳細の題"
                val body = (page["body"] as List<*>).toMutableList()
                val facts = (body[1] as Map<*, *>)["facts"] as Map<*, *>
                body[1] = mapOf("facts" to facts.toMutableMap().also { it["items"] = (facts["items"] as List<*>).reversed() })
                page["body"] = body
                copy["page"] = page
            } }
        }
        val plan = probe(mapOf(file to changed))
        assertEquals(emptyList(), plan.errors)
        val page = rows(raw(plan.outputs.single().text), "pages").single { it["key"] == "todo" }
        assertEquals(rows(changed, "screens").single(), page["screen"])
    }

    @Test fun `未対応の画面部品と存在しない遷移先は確定前に拒否する`() {
        val screenFile = "design/detail/final/screen-designs/details/todo.yaml"
        val screen = raw(Files.readString(sample.resolve(screenFile))).toMutableMap()
        screen["screens"] = rows(screen, "screens").map { it.toMutableMap().also { item ->
            item["page"] = (item["page"] as Map<*, *>).toMutableMap().also { page -> page["body"] = listOf(mapOf("unsupported" to mapOf("text" to "未対応"))) }
        } }
        val controllerFile = "design/detail/final/controllers/todo.yaml"
        val controller = raw(Files.readString(sample.resolve(controllerFile))).toMutableMap()
        controller["screens"] = rows(controller, "screens").map { it.toMutableMap().also { item -> item["screen"] = "missing-screen" } }
        for (change in listOf(mapOf(screenFile to screen), mapOf(controllerFile to controller))) {
            val plan = probe(change)
            assertTrue(plan.errors.isNotEmpty())
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test fun `子の行から表示項目への対応が外れたら未設定で通さない`() {
        val file = "design/detail/final/application-data-model.yaml"
        val document = raw(Files.readString(sample.resolve(file))).toMutableMap()
        document["models"] = rows(document, "models").map { model ->
            if (model["table"] != "todo_comments") model else model.toMutableMap().also { copy ->
                copy["columns"] = rows(model, "columns").map { column ->
                    if (column["column"] != "body") column else column.toMutableMap().also { it["source"] = "COL-missing" }
                }
            }
        }
        val plan = probe(mapOf(file to document))
        assertTrue(plan.errors.any { "対応" in it || "mapping" in it }, plan.errors.toString())
        assertTrue(plan.transaction.operations.isEmpty())
    }
}
