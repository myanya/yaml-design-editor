package dev.toros.yde.app.service

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.*

class WorkspaceReadsTest {
    private class Fixture(val root: Path, val host: WorkspaceHost) {
        val caller = OperationCaller("workspace", "client")
        val grants = ConnectionGrants()
        val a = DocumentId("a.yaml")
        val b = DocumentId("b.yaml")
        init { grants.put(caller, ConnectionPermissions(readable = setOf(a, b))) }
        fun service(limits: ReadLimits = ReadLimits(), captured: suspend () -> Unit = {}) =
            WorkspaceReads(host, grants::get, { "workspace" }, limits, afterCapture = captured)
        suspend fun read(service: WorkspaceReads = service(), query: ReadQuery = ReadQuery(ReadResource.DOCUMENT, document = a)) = service.read(caller, query)
    }

    private fun runCase(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("saved-reads")
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            Files.writeString(root.resolve("a.yaml"), "title: saved\n")
            Files.writeString(root.resolve("b.yaml"), "title: SECRET\n")
            withContext(dispatcher) {
                val host = WorkspaceHost(owner = dispatcher)
                host.openWorkspace(root)
                try { Fixture(root, host).block() } finally { host.close() }
            }
        } finally { dispatcher.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `未保存編集とUndoに触れず保存でだけ読取版が変わる`() = runCase {
        val before = read()
        val doc = host.documents.ensureDocument(root.resolve("a.yaml"))
        doc.applySourceText("title: editing\n")
        val history = host.documents.kernel!!.historySize
        assertEquals(before, read())
        assertEquals(history, host.documents.kernel!!.historySize)
        assertTrue(host.documents.save(doc).ok)
        val after = read()
        assertNotEquals(before.revision, after.revision)
        assertContains(after.data.getValue("text") as String, "editing")
        val failure = assertFailsWith<ReadFailure> {
            read(query = ReadQuery(ReadResource.DOCUMENT, document = a, revision = before.revision))
        }
        assertEquals(ReadFailureCode.STALE_REVISION, failure.code)
    }

    @Test fun `許可外の本文と存在と派生情報を返さず許可外の保存は版にも出さない`() = runCase {
        grants.put(caller, ConnectionPermissions(readable = setOf(a)))
        val before = read()
        for (id in listOf(b, DocumentId("missing.yaml"))) {
            assertEquals(ReadFailureCode.FORBIDDEN, assertFailsWith<ReadFailure> {
                read(query = ReadQuery(ReadResource.DOCUMENT, document = id))
            }.code)
        }
        assertEquals(emptyList<Any>(), read(query = ReadQuery(ReadResource.SEARCH, text = "SECRET")).data["items"])
        for (resource in listOf(ReadResource.ENTITY, ReadResource.VIEW, ReadResource.VIEWS, ReadResource.VALIDATION)) {
            assertEquals(ReadFailureCode.FORBIDDEN, assertFailsWith<ReadFailure> {
                read(query = ReadQuery(resource, id = "one"))
            }.code)
        }
        Files.writeString(root.resolve("b.yaml"), "title: other secret\n")
        assertEquals(before, read())
    }

    @Test fun `取得中の保存と切替と権限失効を応答前に検出する`() = runCase {
        assertEquals(ReadFailureCode.CHANGED_DURING_READ, assertFailsWith<ReadFailure> {
            read(service { Files.writeString(root.resolve("a.yaml"), "title: changed\n") })
        }.code)
        assertEquals(ReadFailureCode.CHANGED_DURING_READ, assertFailsWith<ReadFailure> {
            read(service { withContext(host.owner) { host.openWorkspace(root) } })
        }.code)
        assertEquals(ReadFailureCode.FORBIDDEN, assertFailsWith<ReadFailure> {
            read(service { grants.remove(caller) })
        }.code)
    }

    @Test fun `読取中も所有側は動き同時実行上限と取消後の受付を守る`() = runCase {
        coroutineScope {
            val entered = kotlinx.coroutines.channels.Channel<Unit>(2)
            val release = CompletableDeferred<Unit>()
            val service = service { entered.send(Unit); release.await() }
            val first = async { read(service) }
            val second = async { read(service) }
            entered.receive(); entered.receive()
            assertNull(host.currentStatus().busy)
            assertEquals(ReadFailureCode.BUSY, assertFailsWith<ReadFailure> { read(service) }.code)
            first.cancelAndJoin()
            release.complete(Unit)
            second.await()
            assertEquals("title: saved\n", read(service).data["text"])
        }
    }

    @Test fun `パスと取得量と本文量と時間に上限がある`() = runCase {
        for (query in listOf(ReadQuery(ReadResource.DOCUMENT, document = DocumentId("../a.yaml")), ReadQuery(ReadResource.DOCUMENTS, limit = 0))) {
            assertEquals(ReadFailureCode.INVALID_REQUEST, assertFailsWith<ReadFailure> { read(query = query) }.code)
        }
        assertEquals(ReadFailureCode.LIMIT_EXCEEDED, assertFailsWith<ReadFailure> { read(service(ReadLimits(bytes = 4))) }.code)
        assertEquals(ReadFailureCode.BUSY, assertFailsWith<ReadFailure> {
            read(service(ReadLimits(timeoutMillis = 50)) { awaitCancellation() })
        }.code)
    }

    @Test fun `解析できない保存原本も取得でき検証では指摘として返る`() = runCase {
        Files.writeString(root.resolve("a.yaml"), "broken: [\n")
        assertEquals("broken: [\n", read().data["text"])
        val validation = read(query = ReadQuery(ReadResource.VALIDATION, document = a))
        @Suppress("UNCHECKED_CAST") val item = (validation.data["items"] as List<Map<String, Any?>>).single()
        assertTrue((item["issues"] as List<*>).isNotEmpty())
        assertEquals("not_checked", validation.data["fileExistence"])
    }

    @Test fun `対象外の実行記録は本文量や版に含めない`() = runCase {
        val service = service(ReadLimits(bytes = 100))
        val before = read(service)
        Files.createDirectories(root.resolve("_runs"))
        Files.writeString(root.resolve("_runs/log.yaml"), "記録".repeat(1000))
        assertEquals(before, read(service))
    }

}
