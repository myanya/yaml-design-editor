package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** アプリのビルドも確定値だけから出力し、設計と生成物の境界を保つ。実際のビルドは別の結合検証で行う。 */
class TodosApplicationTest {
    private val sample = Path.of("samples/todos-sysdev")
    private val input = "design/detail/inputs/application/settings.yaml"

    private fun render(root: Path): Map<String, Reports.Result> {
        val workspace = Workspace.open(root)
        val loaded = Reports.load(root)
        val views = Views.load(root, workspace.schemas)
        assertEquals(emptyList(), loaded.errors)
        assertEquals(emptyList(), views.errors)
        val planReport = loaded.reports.single { it.def.id == "application-outputs" }
        val renderedPlan = Reports.run(planReport, emptyMap(), views, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(emptyList(), renderedPlan.missing)
        val plan = YamlIO.parseToRaw(renderedPlan.text) as Map<*, *>
        return (plan["outputs"] as List<*>).associate { entry ->
            val output = entry as Map<*, *>
            val report = loaded.reports.single { it.def.id == output["report"] }
            val params = (output["params"] as Map<*, *>).entries.associate { it.key.toString() to it.value }
            val key = report.def.id + params.values.singleOrNull()?.let { ":$it" }.orEmpty()
            val rendered = Reports.run(report, params, views, workspace.index, workspace::treeOf, workspace::schemaFor)
            key to rendered.copy(outputRel = output["destination"]?.toString() ?: rendered.outputRel).also {
                assertEquals(emptyList(), it.missing, report.def.id)
                assertTrue(assertNotNull(it.outputRel).startsWith("app/"), report.def.id)
            }
        }
    }

    @Test
    fun `ビルドと起動の生成物は確定設定と一致し 親のビルドに依存しない`() {
        val outputs = render(sample)
        assertEquals(117, outputs.size)
        assertEquals(26, outputs.keys.count { it.startsWith("app-web-page:") })
        for ((id, output) in outputs) assertEquals(Files.readString(sample.resolve(output.outputRel!!)), output.text, id)
        val settings = outputs.getValue("app-settings").text
        val build = outputs.getValue("app-build").text
        assertTrue(listOf("include(", "includeBuild(", "../", "libs.", "appVersion").none { it in settings || it in build })
        assertTrue("JavaLanguageVersion.of(21)" in build && "org.springframework.boot" in build)
        assertTrue(Files.exists(sample.resolve("app/gradle/wrapper/gradle-wrapper.jar")))
        assertTrue("todos-sysdev" !in Files.readString(Path.of("settings.gradle.kts")))
        assertEquals(Files.readString(sample.resolve("design/detail/reports/ddl/schema.sql")), outputs.getValue("ddl").text)
        assertTrue("flyway" !in build)
    }

    @Test
    fun `方式の変更は確定後にだけビルドとJavaと出力先へ届く`() {
        val copy = Files.createTempDirectory("sysdev-application")
        try {
            TodosSysdevFiles.copy(sample, copy)
            val before = render(copy)
            val changed = Files.readString(copy.resolve(input))
                .replace("todos-generated", "review-app")
                .replace("example", "review")
                .replace("0.1.0", "0.2.0")
            Files.writeString(copy.resolve(input), changed)
            assertEquals(before, render(copy), "未確定の入力をレポートが直接読まない")
            val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(copy)), setOf("application-configuration"))
            assertEquals(emptyList(), plan.errors)
            plan.outputs.forEach { output ->
                Files.createDirectories(copy.resolve(output.document.value).parent)
                Files.writeString(copy.resolve(output.document.value), output.text)
            }
            val after = render(copy)
            assertTrue("rootProject.name = \"review-app\"" in after.getValue("app-settings").text)
            assertTrue("group = \"review.todos\"" in after.getValue("app-build").text)
            assertTrue("version = \"0.2.0\"" in after.getValue("app-build").text)
            assertEquals("app/src/main/java/review/todos/TodoApplication.java", after.getValue("app-main").outputRel)
            assertTrue("package review.todos;" in after.getValue("app-main").text)
            assertTrue(Generators.plan(WorkspaceSession(FileSystemDocumentStore(copy)), setOf("application-configuration")).upToDate)
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    @Test
    fun `未対応の版と不正なパッケージとポートを確定前に拒否する`() {
        val originals = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
            .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
        for ((key, value) in listOf("javaVersion" to 17, "package" to emptyList<String>(), "package" to listOf("class"), "port" to 0, "writeConcurrency" to "distributed")) {
            val store = InMemoryDocumentStore(originals)
            val document = (YamlIO.parseToRaw(originals.getValue(input)) as Map<*, *>).toMutableMap()
            val settings = (document.getValue("settings") as Map<*, *>).toMutableMap()
            settings[key] = value
            document["settings"] = settings
            store.write(DocumentId(input), YamlIO.dumpPlain(YamlIO.fromAny(document)))
            val plan = Generators.plan(WorkspaceSession(store), setOf("application-configuration"))
            assertTrue(plan.errors.isNotEmpty(), "$key=$value")
            assertTrue(plan.transaction.operations.isEmpty(), plan.errors.toString())
            assertEquals(originals["design/detail/final/application.yaml"], store.read(DocumentId("design/detail/final/application.yaml"))?.text)
        }
    }
}
