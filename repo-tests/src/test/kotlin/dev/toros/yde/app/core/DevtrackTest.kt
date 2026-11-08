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
 * - docs/BACKLOG.md・docs/archive/backlog-closed.md・docs/FEATURES.md・docs/ROADMAP.md・docs/archive/roadmap-history.md が、devtrack の YAML から作り直したものと同じ（古いままコミットしない）
 * - 業務の操作（指摘の登録〔採番〕・状態の変更・一部の解決・解決・再開と、機能の登録〔系統ごとの採番〕）が写しの上で通り、検証のエラーを増やさない
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
    fun `docs の台帳と記録と機能索引とロードマップとその記録は devtrack から作り直したものと同じ`() {
        val ws = Workspace.open(root)
        val stale = mapOf(
            "backlog-md" to "docs/BACKLOG.md", "backlog-closed-md" to "docs/archive/backlog-closed.md", "features-md" to "docs/FEATURES.md",
            "roadmap-md" to "docs/ROADMAP.md", "roadmap-history-md" to "docs/archive/roadmap-history.md",
        )
            .filter { (report, target) -> Path.of(target).readText() != render(ws, report) }
            .values
        assertTrue(stale.isEmpty(), "devtrack から作り直していない文書があります: $stale（./gradlew devtrackDocs で作り直してコミットする）")
    }

    @Test
    fun `登録 状態の変更 一部の解決 解決 再開の操作は 写しの上で検証のエラーを増やさずに通る`() {
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
            fun item(id: String) = YamlIO.parseToRaw(Files.readString(dir.resolve("backlog/$id.yaml"))) as Map<*, *>
            fun kinds(id: String) = (item(id)["resolutions"] as List<*>).map { (it as Map<*, *>)["kind"] }
            // 登録: ID を省くと、使われている最大の番号の次を振る（create の採番）
            val next = "UX-" + (Files.list(dir.resolve("backlog")).use { s -> s.map { it.fileName.toString().removePrefix("UX-").removeSuffix(".yaml").toInt() }.toList() }.max() + 1)
            act("register-item", null, mapOf("title" to "試しの指摘", "origin" to "DevtrackTest", "theme" to "T-03", "related" to listOf("UX-162"),
                "features" to listOf("X-12"), "roadmap" to listOf("R52-05")))
            assertEquals(listOf("R52-05"), item(next)["roadmap"], "ロードマップの項目（R 項目かテーマの参照）")
            assertTrue(Files.isRegularFile(dir.resolve("backlog/$next.yaml")), "採番した $next のファイル")
            act("change-status", next, mapOf("status" to "in-progress"))
            act("record-progress", next, mapOf("date" to "2026-11-07", "note" to "一部を直した"))
            act("resolve-item", next, mapOf("date" to "2026-11-08", "note" to "全部直した", "targets" to "DevtrackTest", "features" to listOf("X-12", "X-03"),
                "roadmap" to listOf("R52-05", "P52")))
            var it = item(next)
            assertEquals("resolved", it["status"])
            assertEquals("2026-11-07", it["origin_on"].toString(), "起点の日は登録した日（auto: created）")
            assertEquals("2026-11-08", it["closed_on"].toString())
            assertEquals(listOf("一部", "解決"), kinds(next))
            assertEquals(listOf("UX-162"), it["related"])
            assertEquals(listOf("X-12", "X-03"), it["features"], "解決で関わる機能を置き換える")
            assertEquals(listOf("R52-05", "P52"), it["roadmap"], "解決でロードマップの項目を置き換える")
            assertTrue(errorsOf(ws).isEmpty(), errorsOf(ws).toString())
            // 解決した指摘は台帳から消え、記録に出る（状態の欄に記録の種類と内容）。同じ日に解決した本物の指摘があっても、自分の行を引く
            assertTrue("| $next |" !in render(ws, "backlog-md"))
            val row = render(ws, "backlog-closed-md").lines().first { line -> line.startsWith("| $next |") }
            assertTrue(row.startsWith("| $next | 2026-11-08 |") && "✅ 2026-11-07 一部: 一部を直した 2026-11-08 解決: 全部直した（DevtrackTest）" in row, row)
            // 再開: 解決の日を消して未解決に戻し、再開の記録を足す。台帳に戻る
            act("reopen-item", next, mapOf("date" to "2026-11-09", "note" to "再現した"))
            it = item(next)
            assertEquals("todo", it["status"])
            assertEquals(null, it["closed_on"])
            assertEquals(listOf("一部", "解決", "再開"), kinds(next))
            assertTrue("| $next |" in render(ws, "backlog-md"))
            // 未解決に戻った指摘は、関わる機能の行の「未解決の指摘」に出る
            val x12 = render(ws, "features-md").lines().first { line -> line.startsWith("| X-12 |") }
            assertTrue(x12.trimEnd().endsWith("$next |") || "$next・" in x12 || "・$next |" in x12, x12)
            assertTrue(errorsOf(ws).isEmpty(), errorsOf(ws).toString())
            // 機能の登録: 系統の接頭辞の中で採番し、テスト名を省くと「手動」。FEATURES.md のその系統の節に出る
            val lastLc = Files.list(dir.resolve("features")).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("LC-") }
                .map { it.removePrefix("LC-").removeSuffix(".yaml").toInt() }.toList() }.max()
            val feature = "LC-%02d".format(lastLc + 1)
            act("register-feature", null, mapOf("family" to "LC", "title" to "試しの機能", "path" to "server/LocalServer.kt", "module" to "local-server",
                "design_label" to "ローカル接続", "design_link" to "architecture/local-connections.md",
                "manual_link" to "manual/local-connections.md", "manual_heading" to "有効・無効と状態"))
            assertTrue(Files.isRegularFile(dir.resolve("features/$feature.yaml")), "採番した $feature のファイル")
            val features = render(ws, "features-md")
            val section = features.substringAfter("## ローカル接続の基盤").substringBefore("## 公開サンプル")
            assertTrue("| $feature | 試しの機能 | `server/LocalServer.kt` | 手動 | [ローカル接続](architecture/local-connections.md) | manual/local-connections.md「有効・無効と状態」 |" in section,
                section.lines().last { it.startsWith("| LC-") })
            // テスト名を省いた機能は手動確認だけで、FEATURES.md の末尾の一覧に出る
            val manualOnly = features.substringAfter("## 手動確認だけの機能").substringBefore("## 公開サンプル")
            assertTrue(feature in manualOnly, manualOnly)
            assertTrue(errorsOf(ws).isEmpty(), errorsOf(ws).toString())
            // 判断待ちの決定: ROADMAP.md では「判断待ち（日付 誰）」の見出しになり、ダッシュボードの判断待ちの区画に出る
            fun pendingRows(): Int {
                val views = Views.load(dir, ws.schemas)
                val resolved = Dashboards.resolve(assertNotNull(views.defaultDashboard()), LocalDate.of(2026, 11, 8), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
                return resolved.sections.first { s -> s.definition.id == "pending-decisions" }.rows.size
            }
            val pendingBefore = pendingRows()
            val headingsBefore = render(ws, "roadmap-md").lines().count { line -> line.startsWith("- **判断待ち（") }
            val theme = dir.resolve("roadmap/P52.yaml")
            val decided = Regex("""- decision: 決めたこと\n(\s+)status: decided\n""")
            val text = Files.readString(theme)
            val first = assertNotNull(decided.find(text), "P52 の決定済みの箇条")
            Files.writeString(theme, text.replaceRange(first.range, "- status: pending\n"))
            ws = Workspace.open(dir)
            assertTrue(errorsOf(ws).isEmpty(), errorsOf(ws).toString())
            assertEquals(headingsBefore + 1, render(ws, "roadmap-md").lines().count { line -> line.startsWith("- **判断待ち（") }, "判断待ちの見出し")
            assertEquals(pendingBefore + 1, pendingRows(), "判断待ちの区画")
        } finally {
            Files.walk(base).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
