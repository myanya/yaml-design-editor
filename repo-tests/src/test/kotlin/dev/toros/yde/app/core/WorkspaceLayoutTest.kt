package dev.toros.yde.app.core

import dev.toros.yde.cli.runCli
import dev.toros.yde.agent.Definitions
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.validate.Severity
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
 * ワークスペース構造の再編（P13）のテスト。
 * - レガシー構成（`.yde` なし）が従来動作のままであること（R13-01 の後方互換）
 * - `.yde` 構成の検出・`ledger` 宣言・ゾーン判定（R13-02）
 * - 台帳外へのスキーマ適用と検証の降格＝エディタ層（R13-03）
 * - CLI `migrate` によるレガシー→`.yde` 移行（R13-04）
 */
class WorkspaceLayoutTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-layout-test")
    }

    @AfterTest
    fun teardown() {
        runCatching { Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    // ---------------------------------------------------------------- レイアウト判定（R13-02）

    @Test
    fun `legacy layout when config dir is absent`() {
        val layout = WorkspaceLayout.of(ws)
        assertNull(layout.configDir)
        assertEquals("_schemas", layout.schemasDir)
        assertEquals(WorkspaceLayout.Zone.RUNS, layout.zoneOf("_runs/x.yaml"))
        assertTrue(layout.isReserved("_agents/a.yaml"))
        assertTrue(layout.inLedger("doc/a.yaml"))
        assertTrue(layout.inLedger("anything/else.yaml"))
        assertFalse(layout.inLedger("_schemas/s.yaml"))
        assertFalse(layout.inLedger(".git/x.yaml"))
    }

    @Test
    fun `yde layout resolves zones and ledger declaration`() {
        Files.createDirectories(ws.resolve(".yde"))
        write(".yde/workspace.yaml", """ledger: doc""")
        val layout = WorkspaceLayout.of(ws)
        assertEquals(".yde", layout.configDir)
        assertEquals(listOf("doc"), layout.ledger)
        assertEquals(".yde/schemas", layout.schemasDir)
        assertEquals(WorkspaceLayout.Zone.AGENTS, layout.zoneOf(".yde/agents/r.yaml"))
        assertNull(layout.zoneOf("_agents/r.yaml"))          // レガシー予約は .yde 構成では通常パス
        assertTrue(layout.isReserved(".yde/workspace.yaml")) // 構成ファイルは予約領域
        assertNull(layout.zoneOf(".yde/workspace.yaml"))
        assertTrue(layout.inLedger("doc/a.yaml"))
        assertFalse(layout.inLedger("other/b.yaml"))         // 台帳外
        assertTrue(layout.indexExcluded(".yde/workspace.yaml"))
        assertFalse(layout.indexExcluded(".yde/agents/r.yaml")) // 定義ゾーンは索引に載る（従来どおり）
        assertTrue(layout.writableByMeta(".yde/agents/r.yaml"))
        assertFalse(layout.writableByMeta(".yde/schemas/s.yaml"))
        assertFalse(layout.writableByMeta(".yde/workspace.yaml"))
    }

    @Test
    fun `ledger accepts list form`() {
        Files.createDirectories(ws.resolve(".yde"))
        write(".yde/workspace.yaml", """ledger: [doc, spec]""")
        val layout = WorkspaceLayout.of(ws)
        assertEquals(listOf("doc", "spec"), layout.ledger)
        assertTrue(layout.inLedger("spec/x.yaml"))
        assertFalse(layout.inLedger("src/x.yaml"))
    }

    // ---------------------------------------------------------------- 走査（R13-01）

    @Test
    fun `scan skips hidden directories in legacy mode`() {
        write("doc/a.yaml", "id: A-001")
        write(".git/config.yaml", "hidden: true")
        write("_schemas/s.yaml", "id: s\nmatch: \"doc/**/*.yaml\"\nroot: { type: object, fields: [ { key: id, type: id } ] }")
        val files = WorkspaceScan.yamlFiles(ws).map { ws.relativize(it).toString().replace('\\', '/') }
        assertTrue("doc/a.yaml" in files)
        assertTrue("_schemas/s.yaml" in files)  // 予約ディレクトリは走査対象（従来どおり）
        assertFalse(files.any { it.startsWith(".git") })
    }

    @Test
    fun `scan includes config dir but excludes out-of-ledger files in yde mode`() {
        write(".yde/workspace.yaml", "ledger: doc")
        write(".yde/schemas/req.yaml", "id: req\nmatch: \"doc/**/*.yaml\"\nroot: { type: object, fields: [ { key: id, type: id } ] }")
        write("doc/a.yaml", "id: A-001")
        write("other/b.yaml", "id: B-001")
        val files = WorkspaceScan.yamlFiles(ws).map { ws.relativize(it).toString().replace('\\', '/') }
        assertTrue("doc/a.yaml" in files)
        assertTrue(".yde/schemas/req.yaml" in files)
        assertTrue(".yde/workspace.yaml" in files)
        assertFalse("other/b.yaml" in files)
    }

    // ---------------------------------------------------------------- ワークスペース統合（R13-02 / R13-03）

    private fun setupYdeWorkspace() {
        write(".yde/workspace.yaml", "ledger: doc")
        write(
            ".yde/schemas/req.yaml",
            """
            id: req
            name: 要求
            match: "{doc,other}/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: depends_on, type: ref }
            """,
        )
        write("doc/a.yaml", "id: A-001\ndepends_on: MISSING-1")
        write("other/b.yaml", "id: B-001\ndepends_on: MISSING-2")
    }

    @Test
    fun `yde workspace loads schemas from config dir and scopes index to ledger`() {
        setupYdeWorkspace()
        val cw = Workspace.open(ws)
        assertEquals(".yde", cw.layout.configDir)
        assertTrue(cw.schemas.any { it.id == "req" })
        // 台帳の文書は索引に載り、台帳外は載らない
        assertTrue(cw.index.entries.containsKey("A-001"))
        assertFalse(cw.index.entries.containsKey("B-001"))
        // カタログにも台帳外・構成ファイルは現れない
        val catalogFiles = Disclosure.catalog(cw).map { it.file }
        assertTrue("doc/a.yaml" in catalogFiles)
        assertFalse(catalogFiles.any { it.startsWith("other/") })
        assertFalse(catalogFiles.any { it.startsWith(".yde/") })
    }

    @Test
    fun `builtin meta schema applies inside schemas zone and config file has no schema`() {
        setupYdeWorkspace()
        write(".yde/agents/reviewer.yaml", "id: reviewer\npersona: 慎重")
        val cw = Workspace.open(ws)
        assertEquals(BuiltinSchemas.META_SCHEMA_ID, cw.schemaFor(cw.resolve(".yde/schemas/req.yaml"))?.id)
        assertEquals("builtin-agent", cw.schemaFor(cw.resolve(".yde/agents/reviewer.yaml"))?.id)
        assertNull(cw.schemaFor(cw.resolve(".yde/workspace.yaml")))
        // 定義ローダも .yde 配下から読める
        val defs = Definitions.load(ws)
        assertTrue(defs.agents.any { it.id == "reviewer" })
    }

    @Test
    fun `editor layer applies schema outside ledger and downgrades ref validation`() {
        setupYdeWorkspace()
        val cw = Workspace.open(ws)
        // 台帳外の文書にも match によるスキーマ適用は効く（エディタ層）
        assertEquals("req", cw.schemaFor(cw.resolve("other/b.yaml"))?.id)
        // 台帳内: 未解決参照はエラー
        val ledgerIssues = cw.validate("doc/a.yaml")
        assertTrue(ledgerIssues.any { it.severity == Severity.ERROR && "MISSING-1" in it.message })
        // 台帳外: 未解決参照は情報レベルへ降格し、エラーにならない
        val editorIssues = cw.validate("other/b.yaml")
        assertTrue(editorIssues.none { it.severity == Severity.ERROR })
        assertTrue(editorIssues.any { it.severity == Severity.INFO && "MISSING-2" in it.message })
    }

    @Test
    fun `config file change triggers full refresh`() {
        setupYdeWorkspace()
        val cw = Workspace.open(ws)
        assertFalse(cw.files.any { cw.relPath(it) == "other/b.yaml" })
        // ledger 宣言を広げると、noteDiskChanges 経由で走査・索引が作り直される
        write(".yde/workspace.yaml", "ledger: [doc, other]")
        cw.noteDiskChanges(listOf(cw.resolve(".yde/workspace.yaml")))
        assertTrue(cw.files.any { cw.relPath(it) == "other/b.yaml" })
        assertTrue(cw.index.entries.containsKey("B-001"))
    }

    // ---------------------------------------------------------------- 移行（R13-04）

    @Test
    fun `migrate moves reserved directories into config dir`() {
        write("_schemas/req.yaml", "id: req\nmatch: \"doc/**/*.yaml\"\nroot: { type: object, fields: [ { key: id, type: id } ] }")
        write("_agents/reviewer.yaml", "id: reviewer\npersona: 慎重")
        write("doc/a.yaml", "id: A-001")
        val out = runCli(arrayOf("migrate", ws.toString()))
        assertTrue(".yde" in out)
        assertFalse(Files.exists(ws.resolve("_schemas")))
        assertTrue(Files.isRegularFile(ws.resolve(".yde/schemas/req.yaml")))
        assertTrue(Files.isRegularFile(ws.resolve(".yde/agents/reviewer.yaml")))
        assertTrue(Files.isRegularFile(ws.resolve(".yde/workspace.yaml")))
        // 移行後も同じスキーマ・索引が効く（ledger 未宣言＝全体が台帳）
        val cw = Workspace.open(ws)
        assertTrue(cw.schemas.any { it.id == "req" })
        assertEquals("req", cw.schemaFor(cw.resolve("doc/a.yaml"))?.id)
        assertTrue(cw.index.entries.containsKey("A-001"))
        assertTrue(Definitions.load(ws).agents.any { it.id == "reviewer" })
    }

    @Test
    fun `migrate with ledger declares ledger and refuses second run`() {
        write("_schemas/req.yaml", "id: req\nmatch: \"doc/**/*.yaml\"\nroot: { type: object, fields: [ { key: id, type: id } ] }")
        write("doc/a.yaml", "id: A-001")
        runCli(arrayOf("migrate", ws.toString(), "--ledger", "doc"))
        assertEquals(listOf("doc"), WorkspaceLayout.of(ws).ledger)
        val again = runCatching { runCli(arrayOf("migrate", ws.toString())) }
        assertTrue(again.isFailure)
    }
}
