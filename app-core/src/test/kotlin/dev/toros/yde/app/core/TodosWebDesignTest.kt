package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** デザインと Form の確定値を共有し、未確定の台帳や補正を画面へ混ぜない。 */
class TodosWebDesignTest {
    private val sample = Path.of("samples/todos-sysdev")
    private val themeInput = "design/detail/inputs/appearance/theme.yaml"
    private val formsOutput = "design/detail/final/application-web-forms.yaml"
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun originals() = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }.associate {
        sample.relativize(it).toString().replace('\\', '/') to Files.readString(it)
    }
    private fun put(store: InMemoryDocumentStore, file: String, value: Any) = store.write(DocumentId(file), YamlIO.dumpPlain(YamlIO.fromAny(value)))
    private fun render(root: Path, id: String, params: Map<String, Any?> = emptyMap()): String {
        val workspace = Workspace.open(root)
        val reports = Reports.load(root)
        val views = Views.load(root, workspace.schemas)
        assertEquals(emptyList(), reports.errors)
        assertEquals(emptyList(), views.errors)
        return Reports.run(reports.reports.single { it.def.id == id }, params, views, workspace.index, workspace::treeOf, workspace::schemaFor).also {
            assertEquals(emptyList(), it.missing)
        }.text
    }

    @Test
    fun `画面デザインは設計書別の予約IDを使わず確定テーマとクラスを並べる`() {
        val workspace = Workspace.open(sample)
        val views = Views.load(sample, workspace.schemas)
        assertEquals(emptyList(), views.errors)
        val dashboard = views.dashboards.single { it.id == "appearance-definition" }
        val result = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 21), views, workspace.index, workspace::schemaFor, workspace::treeOf)
        assertEquals(setOf("APP-theme", "DOC-screen-styles.screen-styles"), result.sections.flatMap { it.rows }.map { it.id }.toSet())
        assertTrue(result.sections.all { it.error == null && it.pending.isEmpty() })
    }

    @Test
    fun `デザイン台帳を確定してからCSSとHTMLが同じ値を読む`() {
        val copy = Files.createTempDirectory("sysdev-web-design")
        try {
            TodosSysdevFiles.copy(sample, copy)
            val before = render(copy, "app-theme-css")
            Files.writeString(copy.resolve(themeInput), Files.readString(copy.resolve(themeInput)).replace("#63F5EC", "#C0FFEE").replace("NEON OPS", "REVIEW <OPS>"))
            assertEquals(before, render(copy, "app-theme-css"))
            val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(copy)), setOf("application-theme"))
            assertEquals(emptyList(), plan.errors)
            plan.outputs.forEach { Files.createDirectories(copy.resolve(it.document.value).parent); Files.writeString(copy.resolve(it.document.value), it.text) }
            assertTrue("--primary: #C0FFEE" in render(copy, "app-theme-css"))
            assertTrue("REVIEW &lt;OPS&gt;" in render(copy, "app-design-preview"))
            assertTrue("#C0FFEE" in render(copy, "app-design-preview"))
            assertTrue(Generators.plan(WorkspaceSession(FileSystemDocumentStore(copy)), setOf("application-theme")).upToDate)
            val forms = raw(Files.readString(copy.resolve(formsOutput))).toMutableMap()
            forms["forms"] = rows(forms, "forms").map { form -> form.toMutableMap().also { updated ->
                if (form["className"] == "RegisterTodoForm") updated["fields"] = rows(form, "fields").map { field -> field.toMutableMap().also {
                    if (field["item"] == "title") { it["name"] = "heading"; it["accessor"] = "Heading" }
                } }
            } }
            Files.writeString(copy.resolve(formsOutput), YamlIO.dumpPlain(YamlIO.fromAny(forms)))
            assertTrue("getHeading()" in render(copy, "app-web-form", mapOf("form" to "JFORM-register-todo")))
            assertTrue("th:field=\"*{heading}\"" in render(copy, "app-design-preview"))
        } finally { copy.toFile().deleteRecursively() }
    }

    @Test
    fun `不正なCSS値と未対応の方式では確定を止める`() {
        val sources = originals()
        for ((key, value) in listOf("colors" to mapOf("primary" to "red; } body { display: none"), "implementation" to "unknown")) {
            val store = InMemoryDocumentStore(sources)
            val input = raw(sources.getValue(themeInput)).toMutableMap()
            val theme = (input.getValue("theme") as Map<*, *>).toMutableMap()
            theme[key] = if (key == "colors") (theme[key] as Map<*, *>).toMutableMap().also { it["primary"] = (value as Map<*, *>)["primary"] } else value
            input["theme"] = theme
            put(store, themeInput, input)
            val plan = Generators.plan(WorkspaceSession(store), setOf("application-theme"))
            assertTrue(plan.errors.isNotEmpty(), key)
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `入力を持つ全操作と入れ子を生成し未知の検査やメッセージ不足を拒否する`() {
        val saved = raw(Files.readString(sample.resolve(formsOutput)))
        val forms = rows(saved, "forms")
        assertEquals(12, forms.size)
        // 編集の入力の入れ子は「値を消す」だけ（コメント一覧の行は、追加・編集・削除の操作へ移った）
        assertEquals(listOf("EditTodoClear"), rows(forms.single { it["className"] == "EditTodoForm" }, "classes").map { it["name"] })
        val sources = originals().mapValues { (file, text) -> if ("/final/" in file) YamlIO.dumpPlain(YamlIO.fromAny(raw(text))) else text }
        for (change in listOf("valid", "unknown", "message", "type", "limit")) {
            val store = InMemoryDocumentStore(sources)
            val definition = raw(Files.readString(sample.resolve(TodosSysdevFiles.definition(sample, "transforms", "application-web-forms")))).toMutableMap()
            definition["id"] = "web-forms-probe"
            definition["dependsOn"] = emptyList<String>()
            definition["output"] = "probe/forms.yaml"
            put(store, ".yde/transforms/design/detail/web-forms-probe.yaml", definition)
            val file = "design/detail/final/screen-designs/operations/register-todo.yaml"
            val document = raw(sources.getValue(file)).toMutableMap()
            val form = (document["form"] as Map<*, *>).toMutableMap()
            if (change == "message") form["class"] = "MissingMessagesForm"
            form["fields"] = rows(form, "fields").map { field -> field.toMutableMap().also {
                if (it["name"] == "title") {
                    if (change == "unknown") it["validations"] = listOf(mapOf("name" to "UnknownCheck"))
                    if (change == "type") it["type"] = "Long"
                    if (change == "limit") it["validations"] = listOf(mapOf("name" to "Size", "max" to "invalid"))
                }
            } }
            document["form"] = form
            put(store, file, document)
            val plan = Generators.plan(WorkspaceSession(store), setOf("web-forms-probe"))
            if (change == "valid") {
                assertEquals(emptyList(), plan.errors)
                assertEquals(12, rows(raw(plan.outputs.single().text), "forms").size)
            } else {
                assertTrue(plan.errors.isNotEmpty(), change)
                assertTrue(plan.transaction.operations.isEmpty(), change)
            }
        }
    }
}
