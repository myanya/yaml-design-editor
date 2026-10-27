package dev.toros.yde.app.core

import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.desktop.ext.DesktopExtensions
import dev.toros.yde.cli.runCli
import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.Evals
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.InitRequest
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.WorkspaceTemplates
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 同梱のテンプレートから作ったワークスペースが、そのまま使えることを固定する:
 * 全テンプレート（サンプルデータあり・なし）と「自分の項目で」の代表的な答えについて、検証エラー 0・ビュー定義の誤り 0・
 * ダッシュボードが解決でき、登録の操作が計画・適用できる。CLI `init` も同じ計画を使う。
 */
class WorkspaceTemplatesTest {
    init { Extensions.install(DesktopExtensions.registry) }   // 形式（csv・xlsx …）は入口の一覧で登録する


    private lateinit var base: Path
    private var count = 0

    @BeforeTest
    fun setup() {
        base = createTempDirectory("yde-templates")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(base).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun init(request: InitRequest): Path {
        val dir = base.resolve("ws-${count++}")
        val result = WorkspaceInit.apply(dir, WorkspaceInit.plan(dir, request))
        assertTrue(result.skipped.isEmpty(), result.skipped.toString())
        return dir
    }

    private fun assertClean(dir: Path): Workspace {
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        ws.files.forEach { file ->
            val errors = ws.validate(ws.relPath(file)).filter { it.severity == Severity.ERROR }
            assertTrue(errors.isEmpty(), "${ws.relPath(file)}: $errors")
        }
        val views = Views.load(dir, ws.schemas)
        assertTrue(views.errors.isEmpty(), views.errors.toString())
        views.dashboards.forEach { d ->
            val resolved = Dashboards.resolve(d, LocalDate.of(2026, 10, 25), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
            resolved.sections.forEach { s -> assertEquals<String?>(null, s.error, "${d.id}/${s.definition.id}") }
        }
        return ws
    }

    private val customer: Map<String, Any?> = mapOf(
        "label" to "顧客", "code" to "customer", "prefix" to "CUS",
        "statusValues" to listOf(mapOf("value" to "ACTIVE", "label" to "取引中"), mapOf("value" to "CLOSED", "label" to "終了")),
        "fields" to listOf(
            mapOf("key" to "since", "label" to "取引開始日", "type" to "date"),
            mapOf("key" to "rank", "label" to "ランク", "type" to "enum", "values" to listOf("A", "B", "C")),
            mapOf("key" to "vip", "label" to "重要", "type" to "boolean"),
            mapOf("key" to "staff", "label" to "担当人数", "type" to "int"),
            mapOf("key" to "note", "label" to "メモ", "type" to "text"),
        ),
    )

    @Test
    fun `問いを持たないテンプレートは サンプルデータの有無にかかわらず検証エラー 0 で開ける`() {
        for (template in WorkspaceTemplates.fixed) {
            for (sample in listOf(true, false)) {
                val dir = init(InitRequest.FromTemplate(template, includeSampleData = sample))
                val ws = assertClean(dir)
                assertTrue(ws.layout.configDir != null, "${template.id}: .yde 構成で作る")
                assertTrue(Files.isRegularFile(dir.resolve("README.md")), "${template.id}: README.md")
                if (sample) assertTrue(ws.files.any { ws.layout.inLedger(ws.relPath(it)) }, "${template.id}: サンプルデータ")
                else assertTrue(ws.files.none { ws.layout.inLedger(ws.relPath(it)) && !ws.relPath(it).endsWith("-list.yaml") }, "${template.id}: サンプルデータ無し")
            }
        }
        // 課題管理はエージェント・評価・skill の定義も読める
        val issues = init(InitRequest.FromTemplate(WorkspaceTemplates.get("issues")!!))
        assertTrue(Definitions.load(issues).errors.isEmpty())
        assertTrue(Evals.load(issues).errors.isEmpty())
        val skills = Skills.load(issues)
        assertTrue(skills.errors.isEmpty(), skills.errors.toString())
        assertEquals(setOf("import-issues", "export-issues", "export-issues-md"), skills.skills.map { it.id }.toSet())
    }

    @Test
    fun `自分の項目で作った一覧のワークスペースは ダッシュボードで開き 登録の操作が通る`() {
        val dir = init(InitRequest.FromTemplate(WorkspaceTemplates.custom!!, customer))
        var ws = assertClean(dir)
        val views = Views.load(dir, ws.schemas)
        val dashboard = assertNotNull(views.defaultDashboard())
        assertEquals("customer-dashboard", dashboard.id)
        assertEquals(listOf("all", "status-ACTIVE", "status-CLOSED"), dashboard.sections.map { it.id })
        // 登録の操作: ID は自動採番、状態は最初の選択肢。適用後も検証エラー 0
        val plan = ActionPlanner.plan(ws.mirror, ActionRequest("register-customer", null, mapOf("title" to "山田商事")), LocalDateTime.of(2026, 10, 25, 9, 0))
        val applied = ws.apply(plan.changeSet, requireValid = true)
        assertTrue(applied.ok, applied.results.toString())
        ws = assertClean(dir)
        val entity = ws.index.graph.entities.getValue("CUS-001")
        assertEquals("customer", entity.entityType)
        val list = YamlIO.parseToRaw(Files.readString(dir.resolve("customer/customer-list.yaml"))) as Map<*, *>
        val row = (list["items"] as List<*>).single() as Map<*, *>
        assertEquals("山田商事", row["title"])
        assertEquals("ACTIVE", row["status"])
        val resolved = Dashboards.resolve(dashboard, LocalDate.of(2026, 10, 25), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
        assertEquals(mapOf("all" to "1", "status-ACTIVE" to "1", "status-CLOSED" to "0"), resolved.sections.associate { it.definition.id to it.count })
        // 一覧ビューの列は名前・状態・答えた項目
        val grid = Views.resolve(views.view("customer-grid")!!, ws.index, ws::treeOf, ws::schemaFor)
        assertEquals(listOf("CUS-001"), grid.map { it.id })
        assertEquals(listOf("名前", "状態", "取引開始日", "ランク", "重要", "担当人数", "メモ"), grid.single().cells.map { it.column.label })
    }

    @Test
    fun `1 件 1 ファイルの置き方では 登録の操作が ID のファイルを作る`() {
        val dir = init(InitRequest.FromTemplate(WorkspaceTemplates.custom!!, customer + ("layout" to "files")))
        var ws = assertClean(dir)
        assertTrue(Files.isDirectory(dir.resolve("customer")))
        val plan = ActionPlanner.plan(ws.mirror, ActionRequest("register-customer", null, mapOf("id" to "CUS-010", "title" to "佐藤工業")), LocalDateTime.of(2026, 10, 25, 9, 0))
        val applied = ws.apply(plan.changeSet, requireValid = true)
        assertTrue(applied.ok, applied.results.toString())
        assertTrue(Files.isRegularFile(dir.resolve("customer/CUS-010.yaml")))
        ws = assertClean(dir)
        assertEquals("customer", ws.index.graph.entities.getValue("CUS-010").entityType)
        // 同じ ID の再登録は前提で止まる
        val again = runCatching { ActionPlanner.plan(ws.mirror, ActionRequest("register-customer", null, mapOf("id" to "CUS-010", "title" to "重複")), LocalDateTime.now()) }
        assertTrue(again.isFailure, "同じ ID は拒否する")
    }

    @Test
    fun `状態も項目も無い最小の答えでも作れる`() {
        val dir = init(InitRequest.FromTemplate(WorkspaceTemplates.custom!!, mapOf("label" to "用語", "code" to "term", "prefix" to "TERM", "titleLabel" to "用語")))
        val ws = assertClean(dir)
        val views = Views.load(dir, ws.schemas)
        assertEquals(listOf("all"), views.defaultDashboard()!!.sections.map { it.id })
        val plan = ActionPlanner.plan(ws.mirror, ActionRequest("register-term", null, mapOf("title" to "スキーマ")), LocalDateTime.now())
        assertTrue(ws.apply(plan.changeSet, requireValid = true).ok)
        assertClean(dir)
    }

    @Test
    fun `CLI の init は一覧 展開 答え 後入れ を同じ計画で行い 初期化済みを拒む`() {
        val listing = YamlIO.parseToRaw(runCli(arrayOf("init", base.resolve("x").toString()))) as Map<*, *>
        val names = (listing["templates"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertTrue("issues" in names && WorkspaceTemplates.CUSTOM in names, names.toString())
        // テンプレートの展開（サンプルデータ無し）
        val a = base.resolve("cli-a")
        val out = YamlIO.parseToRaw(runCli(arrayOf("init", a.toString(), "--template", "issues", "--no-sample-data"))) as Map<*, *>
        assertEquals("issues", out["template"])
        assertTrue((out["written"] as List<*>).contains(".yde/schemas/issue.yaml"))
        assertTrue(Files.isDirectory(a.resolve("issues")) && Files.list(a.resolve("issues")).use { it.count() } == 0L)
        assertClean(a)
        // 答えのファイルと --param
        val answers = base.resolve("answers.yaml")
        Files.writeString(answers, "label: 蔵書\ncode: book\nfields:\n  - { key: author, label: 著者, type: line }\n")
        val b = base.resolve("cli-b")
        val dry = YamlIO.parseToRaw(runCli(arrayOf("init", b.toString(), "--template", WorkspaceTemplates.CUSTOM, "--answers", answers.toString(), "--param", "prefix=BK", "--dry-run"))) as Map<*, *>
        assertTrue(!Files.exists(b), "--dry-run は書かない")
        assertTrue((dry["files"] as List<*>).any { (it as Map<*, *>)["path"] == ".yde/schemas/book.yaml" })
        runCli(arrayOf("init", b.toString(), "--template", WorkspaceTemplates.CUSTOM, "--answers", answers.toString(), "--param", "prefix=BK"))
        assertClean(b)
        assertTrue(Files.readString(b.resolve(".yde/schemas/book.yaml")).contains("^BK-[0-9][0-9][0-9]+\$"))
        // 後入れ
        val c = base.resolve("cli-c")
        Files.createDirectories(c.resolve("doc"))
        Files.writeString(c.resolve("doc/memo.yaml"), "title: メモ\n")
        val adopted = YamlIO.parseToRaw(runCli(arrayOf("init", c.toString(), "--adopt", "--ledger", "doc"))) as Map<*, *>
        assertEquals(listOf(".yde/workspace.yaml"), adopted["written"])
        assertEquals(listOf("doc"), Workspace.open(c).layout.ledger)
        // 初期化済みは拒む
        val refused = assertFailsWith<IllegalArgumentException> { runCli(arrayOf("init", c.toString(), "--template", "issues")) }
        assertTrue(refused.message!!.contains("初期化済み"))
    }
}
