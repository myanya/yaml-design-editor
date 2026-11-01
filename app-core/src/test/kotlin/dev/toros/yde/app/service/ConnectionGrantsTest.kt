package dev.toros.yde.app.service

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.workspace.changeset.ChangeSets
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ConnectionGrantsTest {
    private val caller = OperationCaller("workspace", "client")
    private val a = DocumentId("a.yaml")
    private val b = DocumentId("b.yaml")

    @Test fun `業務全体の提案と直接確定は別権限で全体閲覧を必要とする`() {
        val grants = ConnectionGrants()
        assertFails { grants.put(caller, ConnectionPermissions(commitScope = ConnectionWriteScope.LEDGER)) }
        val permission = ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE, proposalScope = ConnectionWriteScope.LEDGER)
        grants.put(caller, permission)
        assertNotNull(grants.workGrant(caller))
        assertNull(grants.directWorkGrant(caller))
        val grant = grants.put(caller, permission.copy(proposalScope = ConnectionWriteScope.SELECTED, commitScope = ConnectionWriteScope.LEDGER))
        assertNull(grants.workGrant(caller))
        assertNotNull(grants.directWorkGrant(caller))
        val layout = dev.toros.yde.workspace.layout.WorkspaceLayout(null, emptyList())
        assertTrue(grant.allows(ConnectionCapability.COMMIT, setOf(DocumentId("future.yaml")), layout = layout))
        for (path in listOf("_schemas/item.yaml", "_views/a.yaml", ".yde/workspace.yaml", "../a.yaml")) {
            assertFalse(grant.allows(ConnectionCapability.COMMIT, setOf(DocumentId(path)), layout = layout))
        }
        assertFalse(grant.allows(ConnectionCapability.COMMIT, setOf(a)), "配置の確認を省略して全体指定を解釈しない")
        assertNull(grants.operationGrant(caller))
    }

    @Test fun `全体閲覧は新しい相対文書を許可するが変更権限と別ワークスペースを許可しない`() {
        val grants = ConnectionGrants()
        val grant = grants.put(caller, ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE))
        assertTrue(grant.allows(ConnectionCapability.READ, setOf(DocumentId("future/new.yaml"))))
        for (path in listOf("../outside.yaml", "/outside.yaml", "a/../b.yaml", "./a.yaml")) {
            assertFalse(grant.allows(ConnectionCapability.READ, setOf(DocumentId(path))))
        }
        assertFalse(grant.allows(ConnectionCapability.PROPOSE, setOf(a)))
        assertFalse(grant.allows(ConnectionCapability.APPLY, setOf(a), "complete"))
        assertNull(grants.workGrant(caller)); assertNull(grants.operationGrant(caller))
        assertNull(grants.get(caller.copy(workspaceId = "other")))
        assertFailsWith<IllegalArgumentException> {
            grants.put(caller, ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE, proposable = setOf(DocumentId("../outside.yaml"))))
        }
        assertFailsWith<IllegalArgumentException> {
            grants.put(caller, ConnectionPermissions(readable = setOf(a), readScope = ConnectionReadScope.WORKSPACE))
        }
        val changed = grants.put(caller, ConnectionPermissions(readScope = ConnectionReadScope.WORKSPACE,
            actionable = setOf(a), actions = setOf("complete")))
        assertTrue(changed.allows(ConnectionCapability.APPLY, setOf(a), "complete"))
        assertFalse(changed.allows(ConnectionCapability.APPLY, setOf(b), "complete"))
        assertFalse(changed.allows(ConnectionCapability.APPLY, setOf(a), "new-action"))
    }

    @Test fun `未登録と空集合は全拒否し閲覧と提案と業務適用を分ける`() {
        val grants = ConnectionGrants()
        assertNull(grants.get(caller)); assertNull(grants.operationGrant(caller)); assertNull(grants.workGrant(caller))
        val empty = grants.put(caller, ConnectionPermissions())
        ConnectionCapability.entries.forEach { assertFalse(empty.allows(it)) }
        val read = grants.put(caller, ConnectionPermissions(readable = setOf(a, b)))
        assertTrue(read.allows(ConnectionCapability.READ, setOf(a)))
        assertFalse(read.allows(ConnectionCapability.READ, setOf(DocumentId("secret.yaml"))))
        assertNull(grants.operationGrant(caller)); assertNull(grants.workGrant(caller))
        val proposal = grants.put(caller, ConnectionPermissions(setOf(a, b), proposable = setOf(a)))
        assertEquals(setOf(a), grants.workGrant(caller)?.documents)
        assertFalse(proposal.allows(ConnectionCapability.PROPOSE, setOf(b)))
        assertNull(grants.operationGrant(caller))
        val action = grants.put(caller, ConnectionPermissions(setOf(a), actionable = setOf(a), actions = setOf("complete")))
        assertTrue(action.allows(ConnectionCapability.APPLY, setOf(a), "complete"))
        assertFalse(action.allows(ConnectionCapability.APPLY, setOf(a), "delete"))
        val grant = assertNotNull(grants.operationGrant(caller))
        assertFalse(grant.applyChanges); assertFalse(grant.recover)
        assertNull(grants.workGrant(caller))
        assertNull(grants.get(caller.copy(workspaceId = "other")))
        grants.remove(caller); assertNull(grants.operationGrant(caller))
    }

    @Test fun `登録時に集合を凍結し権限変更と再登録で版を再利用しない`() {
        val grants = ConnectionGrants()
        val mutable = mutableSetOf(a)
        val first = grants.put(caller, ConnectionPermissions(mutable, mutable))
        mutable += b
        assertEquals(setOf(a), first.permissions.readable)
        assertFailsWith<UnsupportedOperationException> { (first.permissions.readable as MutableSet).add(b) }
        val second = grants.put(caller, ConnectionPermissions(setOf(a)))
        assertTrue(second.revision > first.revision)
        grants.remove(caller)
        assertTrue(grants.put(caller, ConnectionPermissions()).revision > second.revision)
    }

    @Test fun `読めない変更対象と相対識別子でない文書を登録できない`() {
        val grants = ConnectionGrants()
        assertFailsWith<IllegalArgumentException> { grants.put(caller, ConnectionPermissions(proposable = setOf(a))) }
        for (path in listOf("../a.yaml", "/a.yaml", "a/../b.yaml", "./a.yaml")) {
            assertFailsWith<IllegalArgumentException> { grants.put(caller, ConnectionPermissions(readable = setOf(DocumentId(path)))) }
        }
    }

    @Test fun `共通サービスも現在の登録権限を使い古い作業と任意確定を拒否する`(): Unit = runBlocking {
        val root = Files.createTempDirectory("connection-grants")
        val host = WorkspaceHost()
        try {
            Files.writeString(root.resolve(a.value), "title: before\n")
            host.openWorkspace(root)
            val grants = ConnectionGrants()
            grants.put(caller, ConnectionPermissions(setOf(a), proposable = setOf(a)))
            val work = WorkspaceWork(host, grants = grants::workGrant, workspaceId = { caller.workspaceId })
            val draft = work.begin(caller, "提案")
            val changes = ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - {op: set, path: title, value: after}\n")
            grants.put(caller, ConnectionPermissions(setOf(a)))
            assertFailsWith<IllegalArgumentException> { work.stage(caller, draft.id, changes) }
            grants.put(caller, ConnectionPermissions(setOf(a), actionable = setOf(a), actions = setOf("complete")))
            val operations = WorkspaceOperations(host, grants = grants::operationGrant, workspaceId = { caller.workspaceId })
            assertFailsWith<IllegalArgumentException> { operations.planChanges(caller, changes) }
            assertEquals("title: before\n", Files.readString(root.resolve(a.value)))
        } finally { host.close(); root.toFile().deleteRecursively() }
    }
}
