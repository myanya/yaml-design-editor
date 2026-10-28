package dev.toros.yde.desktop

import androidx.compose.runtime.snapshots.Snapshot
import dev.toros.yde.desktop.ui.DesktopUiDispatcher
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 描画開始後の文書生成・改名を、待ち時間に依存せずスナップショットの順序で検証する。 */
class DocumentCacheSnapshotTest {
    @Test
    fun `スキーマフォルダーの改名とUndoとRedoを跨いでも古い描画は安全に読める`() = runBlocking {
        val dir = createTempDirectory("yde-rename-snapshot")
        try {
            val schemas = Files.createDirectories(dir.resolve(".yde/schemas"))
            val file = schemas.resolve("task.yaml")
            Files.writeString(file, "id: task\nmatch: 'tasks/*.yaml'\nroot: {type: object}\n")
            val task = Files.createDirectories(dir.resolve("tasks")).resolve("a.yaml")
            Files.writeString(task, "title: A\n")
            val state = AppState().also { it.openWorkspace(dir); it.openFile(file) }
            assertEquals("task", state.schemaFor(task)?.id, "規定の schemas 配置でスキーマを認識する")
            val originalDoc = assertNotNull(state.openDoc)
            val renamed = dir.resolve(".yde/schema/task.yaml")
            val plan = state.planFileRename(schemas, "schema")
            val composition = Snapshot.takeSnapshot()
            try {
                state.applyFileArrangementFromUi(plan).getOrThrow()
                assertTrue(Files.exists(renamed))
                assertEquals(renamed, state.openDoc?.file)
                assertOldDocument(composition, state, originalDoc, renamed)
                assertNull(state.docFor(file))
                assertFalse(state.hasDirtyDocs())

                for (undoing in listOf(true, false)) {
                    val previousDoc = assertNotNull(state.openDoc)
                    val destination = if (undoing) file else renamed
                    val beforeHistory = Snapshot.takeSnapshot()
                    try {
                        val job = withContext(DesktopUiDispatcher) {
                            assertNotNull(state.historyFromUi(undoing)).also {
                                assertTrue(state.fileOperationBusy)
                                assertTrue(state.fileHistoryBusy)
                            }
                        }
                        job.join()
                        assertTrue(Files.exists(destination))
                        assertEquals(destination, state.openDoc?.file)
                        assertFalse(state.fileOperationBusy)
                        assertFalse(state.fileHistoryBusy)
                        assertFalse(state.hasDirtyDocs())
                        assertOldDocument(beforeHistory, state, previousDoc, destination)
                        assertOldDocument(composition, state, originalDoc, renamed)
                        if (undoing) assertEquals("task", state.schemaFor(task)?.id)
                    } finally {
                        beforeHistory.dispose()
                    }
                }
            } finally {
                composition.dispose()
            }
        } finally {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `描画開始後に生成した文書は次の描画から見える`() {
        val dir = createTempDirectory("yde-document-snapshot")
        try {
            val file = dir.resolve("a.yaml")
            Files.writeString(file, "title: A\n")
            val state = AppState().also { it.openWorkspace(dir) }
            val composition = Snapshot.takeSnapshot()
            try {
                state.ensureDoc(file)
                assertNotNull(state.docFor(file))
                composition.enter {
                    assertNull(state.docFor(file), "古い描画へ新しい文書を公開しない")
                    assertFalse(state.hasDirtyDocs())
                }
                assertFalse(state.hasDirtyDocs())
            } finally {
                composition.dispose()
            }
        } finally {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `適用せず破棄した描画の文書はキャッシュへ漏れない`() {
        val dir = createTempDirectory("yde-document-unapplied")
        try {
            val file = dir.resolve("a.yaml")
            Files.writeString(file, "title: A\n")
            val state = AppState().also { it.openWorkspace(dir) }
            val abandoned = Snapshot.takeMutableSnapshot()
            try {
                abandoned.enter { assertNotNull(state.ensureDoc(file)) }
                assertNull(state.docFor(file))
            } finally {
                abandoned.dispose()
            }
            assertNull(state.docFor(file))
            val accepted = Snapshot.takeMutableSnapshot()
            try {
                accepted.enter { assertNotNull(state.ensureDoc(file)) }
                accepted.apply().check()
            } finally {
                accepted.dispose()
            }
            assertNotNull(state.docFor(file))
            assertFalse(state.hasDirtyDocs())
        } finally {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun assertOldDocument(snapshot: Snapshot, state: AppState, doc: OpenDocument, newPath: java.nio.file.Path) {
        snapshot.enter {
            assertSame(doc, state.openDoc)
            assertSame(doc, state.docFor(doc.file))
            assertNull(state.docFor(newPath))
            assertFalse(state.hasDirtyDocs())
        }
    }
}
