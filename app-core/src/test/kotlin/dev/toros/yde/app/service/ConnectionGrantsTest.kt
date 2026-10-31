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
