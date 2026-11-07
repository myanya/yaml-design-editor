package dev.toros.yde.app.core

import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 開発管理（devtrack/）を CI で守る（devtrack/README.md「運用」）:
 * - 検証のエラーが無い。ビュー・ダッシュボード・レポートの定義が読める
 * - docs/BACKLOG.md と docs/archive/backlog-closed.md が、devtrack の YAML から作り直したものと同じ（古いままコミットしない）
 * - 業務の操作（登録・状態の変更・一部の解決・解決）が写しの上で通り、検証のエラーを増やさない
 */
class DevtrackTest {

    private val root: Path = Path.of("devtrack")

    private fun errorsOf(ws: Workspace): List<String> = ws.files.flatMap { file ->
        val rel = ws.relPath(file)
        ws.validate(rel).filter { it.severity == Severity.ERROR }.map { "$rel ${it.path.pretty()}: ${it.message}" }
    }

    private fun render(ws: Workspace, report: String): String {
        val loaded = Reports.load(ws.root)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val catalog = Views.load(ws.root, ws.schemas)
        val result = Reports.run(assertNotNull(loaded.report(report), report), emptyMap(), catalog, ws.index, ws::treeOf, ws::schemaFor)
        assertTrue(result.missing.isEmpty(), "$report の埋まらなかった穴: ${result.missing}")
        return result.text
    }

    @Test
    fun `devtrack は検証のエラーが無く ビューとダッシュボードが解決できる`() {
        val ws = Workspace.open(root)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val errors = errorsOf(ws)
        assertTrue(errors.isEmpty(), "devtrack の検証のエラー:\n" + errors.joinToString("\n"))
        val views = Views.load(root, ws.schemas)
        assertTrue(views.errors.isEmpty(), views.errors.toString())
        val dashboard = assertNotNull(views.defaultDashboard())
        val resolved = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 7), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
        resolved.sections.forEach { s -> assertEquals<String?>(null, s.error, s.definition.id) }
    }

    @Test
    fun `docs の台帳と記録は devtrack から作り直したものと同じ`() {
        val ws = Workspace.open(root)
        val stale = mapOf("backlog-md" to "docs/BACKLOG.md", "backlog-closed-md" to "docs/archive/backlog-closed.md")
            .filter { (report, target) -> Path.of(target).readText() != render(ws, report) }
            .values
        assertTrue(stale.isEmpty(), "devtrack から作り直していない文書があります: $stale（./gradlew devtrackDocs で作り直してコミットする）")
    }

    @Test
    fun `登録 状態の変更 一部の解決 解決の操作は 写しの上で検証のエラーを増やさずに通る`() {
        val base = createTempDirectory("yde-devtrack")
        try {
            val dir = base.resolve("devtrack")
            Files.walk(root).use { s ->
                s.forEach { p -> val to = dir.resolve(root.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to) }
            }
            var ws = Workspace.open(dir)
            val now = LocalDateTime.of(2026, 11, 7, 9, 0)
            fun act(action: String, target: String?, params: Map<String, Any?>) {
                val plan = ActionPlanner.plan(ws.mirror, ActionRequest(action, target, params), now)
                val applied = ws.apply(plan.changeSet, requireValid = true)
                assertTrue(applied.ok, "$action: ${applied.results}")
                ws = Workspace.open(dir)
            }
            act("register-item", null, mapOf("id" to "UX-9999", "title" to "試しの指摘", "origin" to "DevtrackTest", "theme" to "T-03"))
            act("change-status", "UX-9999", mapOf("status" to "in-progress"))
            act("record-progress", "UX-9999", mapOf("date" to "2026-11-07", "note" to "一部を直した"))
            act("resolve-item", "UX-9999", mapOf("date" to "2026-11-08", "note" to "全部直した", "targets" to "DevtrackTest"))
            val item = YamlIO.parseToRaw(Files.readString(dir.resolve("backlog/UX-9999.yaml"))) as Map<*, *>
            assertEquals("resolved", item["status"])
            assertEquals("2026-11-07", item["origin_on"].toString(), "起点の日は登録した日（auto: created）")
            assertEquals("2026-11-08", item["closed_on"].toString())
            assertEquals(listOf("一部を直した", "全部直した"), (item["resolutions"] as List<*>).map { (it as Map<*, *>)["note"] })
            assertTrue(errorsOf(ws).isEmpty(), errorsOf(ws).toString())
            // 解決した指摘は台帳から消え、記録の先頭に出る
            assertTrue("| UX-9999 |" !in render(ws, "backlog-md"))
            assertTrue(render(ws, "backlog-closed-md").lines().first { it.startsWith("| UX-") }.startsWith("| UX-9999 | 2026-11-08 |"))
        } finally {
            Files.walk(base).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
