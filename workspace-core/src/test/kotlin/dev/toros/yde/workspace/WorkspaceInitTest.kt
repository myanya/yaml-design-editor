package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ワークスペースの初期化（テンプレートの展開・答えからの組み立て・後入れ）。
 * テンプレートは資源 templates/（ビルド時に リポジトリの templates/ から写す）を読む。
 */
class WorkspaceInitTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-init")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private val customerAnswers: Map<String, Any?> = mapOf(
        "label" to "顧客",
        "code" to "customer",
        "prefix" to "CUS",
        "statusValues" to listOf(mapOf("value" to "ACTIVE", "label" to "取引中"), mapOf("value" to "CLOSED", "label" to "終了")),
        "fields" to listOf(
            mapOf("key" to "since", "label" to "取引開始日", "type" to "date"),
            mapOf("key" to "rank", "label" to "ランク", "type" to "enum", "values" to listOf("A", "B", "C"), "required" to true),
            mapOf("key" to "note", "label" to "メモ", "type" to "text"),
        ),
    )

    private fun raw(plan: InitPlan, path: String): Map<*, *> =
        YamlIO.parseToRaw(plan.files.first { it.path == path }.text) as Map<*, *>

    @Test
    fun `同梱のテンプレートが読め 問いの有無で分かれる`() {
        val ids = WorkspaceTemplates.all.map { it.id }
        assertTrue("issues" in ids && WorkspaceTemplates.CUSTOM in ids, ids.toString())
        val issues = WorkspaceTemplates.get("issues")!!
        assertFalse(issues.hasParams)
        assertEquals("課題管理", issues.name)
        assertTrue(issues.files.any { it.path == ".yde/schemas/issue.yaml" })
        assertTrue(issues.files.none { it.path == WorkspaceTemplate.MANIFEST })
        val custom = WorkspaceTemplates.custom!!
        assertTrue(custom.hasParams)
        assertEquals(listOf("label", "code", "prefix", "titleLabel", "statusValues", "fields", "layout"), custom.params.map { it.key })
        assertEquals(WorkspaceTemplates.fixed.map { it.id }, listOf("issues"))
    }

    @Test
    fun `固定のテンプレートはそのまま写し サンプルデータを外すと台帳の中を書かない`() {
        val issues = WorkspaceTemplates.get("issues")!!
        val withData = WorkspaceInit.plan(dir, InitRequest.FromTemplate(issues))
        assertTrue(withData.files.any { it.path == "issues/ISSUE-001.yaml" && it.sample })
        assertTrue(withData.files.any { it.path == ".yde/skills/import-issues.yaml" && !it.sample })
        assertEquals(listOf("issues"), withData.directories)
        assertEquals(issues.files.first { it.path == ".yde/schemas/issue.yaml" }.text, withData.files.first { it.path == ".yde/schemas/issue.yaml" }.text)
        val withoutData = WorkspaceInit.plan(dir, InitRequest.FromTemplate(issues, includeSampleData = false))
        assertTrue(withoutData.files.none { it.path.startsWith("issues/") }, withoutData.files.map { it.path }.toString())
        assertTrue(withoutData.files.any { it.path == "README.md" })
    }

    @Test
    fun `答えから一覧 1 ファイルのワークスペースを組み立てる`() {
        val plan = WorkspaceInit.plan(dir, InitRequest.FromTemplate(WorkspaceTemplates.custom!!, customerAnswers))
        assertEquals(
            setOf(".yde/workspace.yaml", ".yde/schemas/customer.yaml", ".yde/views/customer-grid.yaml", ".yde/views/customer-dashboard.yaml", "customer/customer-list.yaml", "README.md"),
            plan.files.map { it.path }.toSet(),
        )
        assertEquals("ledger: customer\n", plan.files.first { it.path == ".yde/workspace.yaml" }.text)
        assertEquals("items: []\n", plan.files.first { it.path == "customer/customer-list.yaml" }.text)
        val schema = raw(plan, ".yde/schemas/customer.yaml")
        assertEquals("customer", schema["id"])
        assertEquals("顧客", schema["name"])
        assertEquals("customer/customer-list.yaml", schema["match"])
        assertNull(schema["filePattern"])
        val entity = schema["entity"] as Map<*, *>
        assertEquals("CUS-*", entity["idPattern"])
        val root = schema["root"] as Map<*, *>
        val items = (root["fields"] as List<*>).single() as Map<*, *>
        assertEquals("items", items["key"])
        assertEquals("CUS-", ((items["methods"] as Map<*, *>)["insert"] as Map<*, *>).let { (it["id"] as Map<*, *>)["prefix"] })
        val fields = ((items["item"] as Map<*, *>)["fields"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("id", "title", "status", "since", "rank", "note"), fields.map { it["key"] })
        assertEquals("^CUS-[0-9][0-9][0-9]+$", fields[0]["pattern"])
        assertEquals("名前", fields[1]["label"])
        assertEquals("title", fields[1]["role"])
        assertEquals("ACTIVE", fields[2]["default"])
        assertEquals(listOf("ACTIVE", "CLOSED"), (fields[2]["values"] as List<*>).map { (it as Map<*, *>)["value"] })
        assertNull(fields[3]["required"])
        assertEquals(true, fields[4]["required"])
        assertEquals(listOf("A", "B", "C"), fields[4]["values"])
        assertNull(fields[5]["values"])
        val action = (schema["actions"] as List<*>).single() as Map<*, *>
        assertEquals("register-customer", action["id"])
        assertEquals(listOf("title"), (action["params"] as List<*>).map { (it as Map<*, *>)["key"] })
        assertNull(action["require"])
        val step = (action["do"] as List<*>).single() as Map<*, *>
        val insert = step["insert"] as Map<*, *>
        assertEquals(mapOf("schemaId" to "customer"), insert["in"])
        assertEquals(mapOf("title" to "\$title"), insert["params"])
        val grid = raw(plan, ".yde/views/customer-grid.yaml")
        assertEquals(listOf("名前", "状態", "取引開始日", "ランク", "メモ"), (grid["columns"] as List<*>).map { (it as Map<*, *>)["label"] })
        assertEquals(mapOf("file" to "customer/customer-list.yaml", "path" to "items"), grid["newRow"])
        val dashboard = raw(plan, ".yde/views/customer-dashboard.yaml")
        assertEquals(true, dashboard["default"])
        assertEquals(listOf("register-customer"), dashboard["actions"])
        val sections = (dashboard["sections"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("all", "status-ACTIVE", "status-CLOSED"), sections.map { it["id"] })
        assertEquals("CLOSED", (((sections[2]["filter"] as Map<*, *>)["where"] as List<*>).single() as Map<*, *>)["equals"])
        assertTrue(plan.files.first { it.path == "README.md" }.text.startsWith("# 顧客の台帳"))
    }

    @Test
    fun `1 件 1 ファイルでは match が glob になり 一覧のファイルを作らず ID を受け取る`() {
        val answers = customerAnswers + ("layout" to "files") - "statusValues"
        val plan = WorkspaceInit.plan(dir, InitRequest.FromTemplate(WorkspaceTemplates.custom!!, answers))
        assertTrue(plan.files.none { it.path == "customer/customer-list.yaml" })
        assertEquals(listOf("customer"), plan.directories)
        val schema = raw(plan, ".yde/schemas/customer.yaml")
        assertEquals("customer/*.yaml", schema["match"])
        assertEquals("customer/{id}.yaml", schema["filePattern"])
        val root = schema["root"] as Map<*, *>
        assertEquals(listOf("id", "title", "since", "rank", "note"), (root["fields"] as List<*>).map { (it as Map<*, *>)["key"] })
        val action = (schema["actions"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("id", "title"), (action["params"] as List<*>).map { (it as Map<*, *>)["key"] })
        val require = (action["require"] as List<*>).single() as Map<*, *>
        assertEquals("\$id は既に使われています", require["message"])
        val create = ((action["do"] as List<*>).single() as Map<*, *>)["create"] as Map<*, *>
        assertEquals("customer", create["schema"])
        val grid = raw(plan, ".yde/views/customer-grid.yaml")
        assertNull(grid["newRow"])
        assertEquals(listOf("名前", "取引開始日", "ランク", "メモ"), (grid["columns"] as List<*>).map { (it as Map<*, *>)["label"] })
        val dashboard = raw(plan, ".yde/views/customer-dashboard.yaml")
        assertEquals(listOf("all"), (dashboard["sections"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun `答えは整えてから点検し 必須と形式の誤りを文で返す`() {
        val custom = WorkspaceTemplates.custom!!
        val normalized = WorkspaceInit.normalizeAnswers(custom, mapOf("label" to " 顧客 ", "code" to "customer", "prefix" to "CUS", "fields" to listOf(mapOf("key" to "note", "label" to "メモ", "values" to emptyList<String>()))))
        assertEquals("顧客", normalized["label"])
        assertEquals("名前", normalized["titleLabel"])
        assertEquals("list", normalized["layout"])
        assertEquals(mapOf("key" to "note", "label" to "メモ", "type" to "line"), (normalized["fields"] as List<*>).single())
        assertTrue(WorkspaceInit.checkAnswers(custom, normalized).isEmpty())
        val problems = WorkspaceInit.checkAnswers(custom, WorkspaceInit.normalizeAnswers(custom, mapOf("code" to "Customer List", "prefix" to "cus", "fields" to listOf(mapOf("key" to "x")))))
        assertTrue(problems.any { it == "「管理するものの名前」を入力してください" }, problems.toString())
        assertTrue(problems.any { it.startsWith("「英語名」は形式（^[a-z][a-z0-9-]*$）に合いません") }, problems.toString())
        assertTrue(problems.any { it.startsWith("「ID の接頭辞」は形式") }, problems.toString())
        assertTrue(problems.any { it == "「表示名」を入力してください" }, problems.toString())
        val failure = runCatching { WorkspaceInit.plan(dir, InitRequest.FromTemplate(custom, mapOf("code" to "customer"))) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `後入れは台帳の宣言だけを書き 初期化済みの判定は配置で決まる`() {
        Files.createDirectories(dir.resolve("doc"))
        Files.writeString(dir.resolve("doc/a.yaml"), "id: A-1\n")
        Files.createDirectories(dir.resolve("build"))
        Files.createDirectories(dir.resolve(".git"))
        assertFalse(WorkspaceInit.isInitialized(dir))
        assertEquals(listOf("build" to 0, "doc" to 1), WorkspaceInit.ledgerCandidates(dir))
        assertEquals(1, WorkspaceInit.yamlCount(dir))
        val plan = WorkspaceInit.plan(dir, InitRequest.Adopt(listOf("doc")))
        assertEquals(listOf(".yde/workspace.yaml"), plan.files.map { it.path })
        val result = WorkspaceInit.apply(dir, plan)
        assertEquals(listOf(".yde/workspace.yaml"), result.written)
        assertEquals(listOf("doc"), dev.toros.yde.workspace.layout.WorkspaceLayout.of(dir).ledger)
        assertTrue(WorkspaceInit.isInitialized(dir))
        // 2 度目は既存のファイルを書かない
        val again = WorkspaceInit.plan(dir, InitRequest.Adopt())
        assertTrue(again.files.single().exists)
        assertEquals(listOf(".yde/workspace.yaml"), WorkspaceInit.apply(dir, again).skipped)
    }

    @Test
    fun `テンプレートの展開は既存のファイルを上書きせず 台帳のフォルダを作る`() {
        Files.createDirectories(dir.resolve("issues"))
        Files.writeString(dir.resolve("issues/ISSUE-001.yaml"), "id: ISSUE-001\ntitle: 手で書いた課題\n")
        val plan = WorkspaceInit.plan(dir, InitRequest.FromTemplate(WorkspaceTemplates.get("issues")!!))
        assertEquals(listOf("issues/ISSUE-001.yaml"), plan.skipped)
        val result = WorkspaceInit.apply(dir, plan)
        assertEquals(listOf("issues/ISSUE-001.yaml"), result.skipped)
        assertEquals("id: ISSUE-001\ntitle: 手で書いた課題\n", Files.readString(dir.resolve("issues/ISSUE-001.yaml")))
        assertTrue(Files.isRegularFile(dir.resolve(".yde/schemas/issue.yaml")))
        val custom = WorkspaceInit.plan(dir.resolve("new"), InitRequest.FromTemplate(WorkspaceTemplates.custom!!, customerAnswers + ("layout" to "files")))
        WorkspaceInit.apply(dir.resolve("new"), custom)
        assertTrue(Files.isDirectory(dir.resolve("new/customer")))
    }
}
