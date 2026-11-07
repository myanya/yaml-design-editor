package dev.toros.yde.desktop

import dev.toros.yde.app.service.WorkspaceOwnership
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** 図の配置（表示状態）の保存が、文書の排他と外部変更の確認を起こさないこと。 */
class DiagramStateWriteTest {
    private val base = Files.createTempDirectory("diagram-state-write")
    @AfterTest fun cleanup() { base.toFile().deleteRecursively() }

    @Test fun `表示状態の書込の知らせでは文書を確かめ直さず排他中も後回しにしない`(): Unit = runBlocking {
        val file = base.resolve("a.yaml")
        Files.writeString(file, "name: A\n")
        val state = AppState().also { it.openWorkspace(base); it.openFile(file) }
        val record = base.resolve(state.layout.diagramStateDir).resolve("h/s.yaml")
        Files.createDirectories(record.parent)
        Files.writeString(record, "unit: dip\n")
        Files.writeString(file, "name: outside\n")
        val tree = state.fileTreeRevision
        state.noteFileEvents(setOf(record, record.parent))
        assertTrue(state.fileTreeRevision > tree, "ファイル一覧は新しくする")
        assertEquals("name: A\n", state.openDoc!!.source.text.toString(), "状態の書込だけでは文書を確かめ直さない")
        state.host.exclusive("試験") { state.noteFileEvents(setOf(record)) }
        state.noteFileEvents(emptySet())
        assertEquals("name: A\n", state.openDoc!!.source.text.toString(), "排他中の状態の書込を後回しにして全体を確かめ直さない")
        state.noteFileEvents(setOf(file))
        assertEquals("name: outside\n", state.openDoc!!.source.text.toString(), "文書の知らせでは確かめる")
    }

    @Test fun `配置の保存は処理中の表示を出さず編集を止めず終了は書込を待つ`(): Unit = runBlocking {
        val ws = Files.createDirectory(base.resolve("ws"))
        Files.writeString(ws.resolve("a.yaml"), "name: A\n")
        withContext(DesktopUiDispatcher) {
            val runtime = DesktopRuntime(monitorFactory = { null }, ownership = WorkspaceOwnership(base.resolve("locks")))
            try {
                assertTrue(runtime.openWorkspace(ws))
                val state = runtime.state
                val context = DiagramContext(mapOf("kind" to "flow", "view" to "v", "document" to "a.yaml"), emptyMap())
                val memory = state.diagramPositions(context)
                withTimeout(10_000) { while (!memory.ready) delay(10) }
                val busy = mutableListOf<String?>()
                val watching = runtime.host.observe { busy += runtime.host.status.value.busy }
                val id = DiagramNodeId("flow", "受付")
                val first = mapOf(id to DiagramPosition(id, null, 10f, 20f, true))
                state.saveDiagramPositions(context, first)
                // 保存は呼んだ場で始まり、IO が先に終われば戻る前に完了する。保存中か、保存済みのどちらか。
                assertTrue(memory.saving || memory.positions == first, "置いた配置の保存を始める")
                assertFalse(state.fileOperationBusy, "置いたときに「処理しています」を出さない")
                runtime.host.change { }
                withTimeout(10_000) { while (memory.saving) delay(10) }
                assertNull(memory.warning)
                assertEquals(first, DiagramPositionStore(ws, state.layout).load(context).positions)

                val second = mapOf(id to DiagramPosition(id, null, 30f, 40f, true))
                state.saveDiagramPositions(context, second)
                runtime.shutdown()
                withTimeout(10_000) { while (memory.saving) delay(10) }
                watching.close()
                assertNull(memory.warning, "終了は書きかけの配置の保存を待つ")
                assertEquals(second, DiagramPositionStore(ws, state.layout).load(context).positions)
                assertEquals(listOf("終了"), busy.filterNotNull().distinct(), "配置の保存では文書の排他を取らない")
            } finally { runtime.shutdown() }
        }
    }
}
