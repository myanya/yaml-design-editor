package dev.toros.yde.cli

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.AccessWorkspace
import dev.toros.yde.server.client.LocalApiFailure
import dev.toros.yde.yaml.YamlIO
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

/** 通信・CLI・共通セッションを往復する。試験専用の loopback 以外の API は呼ばない。 */
class ConnectedCliIntegrationTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("connected-cli")
        val ownership = WorkspaceOwnership().acquire(root)
        val host = WorkspaceHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val grants = ConnectionGrants()
        val work = WorkspaceWork(host, grants::workGrant, { "ws" }, directGrants = grants::directWorkGrant)
        val server = LocalServer(scope, grants, WorkspaceReads(host, grants::get, { "ws" }), work,
            WorkspaceOperations(host, grants::operationGrant, { "ws" })) { AccessWorkspace("ws", host.currentStatus().epoch) }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        var token: String
        init {
            Files.writeString(root.resolve("a.yaml"), "title: original\n")
            Files.createDirectories(root.resolve("_schemas"))
            Files.writeString(root.resolve("_schemas/item.yaml"), """
                id: item
                name: 項目
                match: '*.yaml'
                root:
                  type: object
                  fields:
                    - {key: title, type: string, required: true}
                actions:
                  - id: rename
                    target: {schema: item}
                    do:
                      - set: {path: title, value: renamed}
            """.trimIndent())
            host.openWorkspace(root)
            configure(true)
            token = issue(ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
                proposalScope = ConnectionWriteScope.LEDGER, commitScope = ConnectionWriteScope.LEDGER,
                actionScope = ConnectionWriteScope.LEDGER, actions = setOf("rename")))
        }
        fun configure(api: Boolean, mcp: Boolean = false) = runBlocking {
            withTimeout(15_000) { server.connections.awaitSettled(server.connections.configure(LocalConnectionSettings(api, mcp, port = port))) }
        }
        fun issue(permissions: ConnectionPermissions) = server.access.issueBearer(server.access.register(LocalFeature.API, "CLI 試験", permissions).id).value
        fun command(vararg args: String) = runConnectedCli(listOf("--port", port.toString()) + args, { token })
        fun data(vararg args: String) = (YamlIO.parseToRaw(command(*args)) as Map<*, *>)["data"] as Map<*, *>
        fun begin(mode: String) = data("work", "begin", "--mode", mode, "--title", "変更")["id"] as String
        fun patch(id: String, value: String) {
            val path = root.resolve("change.txt")
            Files.writeString(path, "changes: [{file: a.yaml, ops: [{op: set, path: title, value: $value}]}]")
            command("work", "edit", id, path.toString())
        }
        fun saved() = Files.readString(root.resolve("a.yaml"))
        override fun close() {
            try { runBlocking { server.connections.shutdown() } }
            finally { scope.cancel(); host.close(); ownership.close(); root.toFile().deleteRecursively() }
        }
    }

    @Test fun `所有中のワークスペースへ提案と直接確定と定義済み操作を送り結果を再照会する`() {
        Fixture().use { f ->
            assertFailsWith<WorkspaceInUseException> { WorkspaceOwnership().acquire(f.root).close() }
            assertContains(f.command("documents"), "a.yaml")
            val direct = f.begin("direct")
            f.patch(direct, "直接確定")
            assertContains(f.saved(), "original")
            assertEquals(true, f.data("work", "validate", direct)["valid"])
            val plan = f.data("work", "plan", direct)["planId"] as String
            assertEquals("applied", f.data("work", "commit", direct, "--plan-id", plan)["state"])
            assertContains(f.saved(), "直接確定")
            assertEquals("applied", f.data("work", "commit", direct, "--plan-id", plan)["state"])
            assertEquals("applied", f.data("work", "result", direct)["state"])

            val proposal = f.begin("proposal")
            f.patch(proposal, "提案内容")
            assertEquals("awaiting_approval", f.data("work", "submit", proposal)["state"])
            assertContains(f.saved(), "直接確定")
            assertEquals(proposal, f.work.inbox.value.single().id)
            assertFailsWith<LocalApiFailure> { f.command("work", "plan", proposal) }

            val operation = f.data("operation", "plan", "rename")["planId"] as String
            assertEquals("committed", f.data("operation", "commit", operation, "--request-id", "cli-1")["state"])
            assertContains(f.saved(), "renamed")
            assertEquals("committed", f.data("operation", "result", "cli-1")["state"])
            assertEquals("committed", f.data("operation", "commit", operation, "--request-id", "cli-1")["state"])
        }
    }

    @Test fun `検証拒否と API 停止と無権限では独立書込に迂回しない`() {
        Fixture().use { f ->
            val id = f.begin("direct")
            f.patch(id, "null")
            assertEquals(2, assertFailsWith<ConnectedResultFailure> { f.command("work", "validate", id) }.exitCode)
            assertEquals(2, assertFailsWith<ConnectedResultFailure> { f.command("work", "plan", id) }.exitCode)
            assertEquals("discarded", f.data("work", "discard", id)["state"])
            f.token = f.issue(ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE))
            assertEquals(403, assertFailsWith<LocalApiFailure> { f.begin("direct") }.status)
            assertEquals(403, assertFailsWith<LocalApiFailure> { f.command("operation", "plan", "rename") }.status)
            f.token = "wrong-token"
            assertEquals(401, assertFailsWith<LocalApiFailure> { f.command("workspace") }.status)
            // MCP の待受を残しても API の OFF は拒否する。
            f.configure(false, true)
            assertEquals(404, assertFailsWith<LocalApiFailure> { f.command("workspace") }.status)
            f.configure(false)
            assertFailsWith<LocalApiFailure> { f.begin("direct") }
            assertTrue(f.server.connections.status.value.stopped)
            assertContains(f.saved(), "original")
            assertFailsWith<WorkspaceInUseException> { WorkspaceOwnership().acquire(f.root).close() }
        }
    }
}
