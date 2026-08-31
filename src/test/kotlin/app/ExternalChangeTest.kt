package app

import model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 外部変更検出（A-4・RA-07）: イベント起点（eventHint）では、スタンプ（mtime）が不変でも
 * セッションの content diff（externalChanges）で既知文書の変更を検出する。
 * タイマー・sleep は使わず、pollExternalChanges を直接呼ぶ（AutomationTest と同じ流儀）。
 */
class ExternalChangeTest {

    private val dir: Path = Files.createTempDirectory("yde-watch")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** 同一 mtime のまま内容だけ書き換える（スタンプ比較の盲点を再現する）。 */
    private fun overwriteKeepingMtime(file: Path, text: String) {
        val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, text)
        Files.setLastModifiedTime(file, mtime)
    }

    @Test
    fun `同一mtimeの上書きはイベント起点のcontent diffで検出される`() {
        val file = dir.resolve("a.yaml")
        Files.writeString(file, "id: DOC-A\n")
        val state = AppState()
        state.openWorkspace(dir)
        val doc = state.ensureDoc(file)
        assertTrue("DOC-A" in state.overlayIndex().entries)

        overwriteKeepingMtime(file, "id: DOC-A2\n")

        // 周期ポーリング（スタンプ比較）では見えない
        state.pollExternalChanges()
        assertTrue("DOC-A2" !in state.overlayIndex().entries)

        // イベント起点では content diff が検出し、文書・索引が追随する
        state.pollExternalChanges(eventHint = true)
        assertTrue("DOC-A2" in state.overlayIndex().entries)
        assertTrue("DOC-A" !in state.overlayIndex().entries)
        assertEquals("DOC-A2", ((doc.root as YNode.Map).entries["id"] as YNode.Scalar).value)
        assertFalse(doc.externalConflict)
    }

    @Test
    fun `未保存編集がある文書は上書きせず競合として提示する`() {
        val file = dir.resolve("b.yaml")
        Files.writeString(file, "id: DOC-B\n")
        val state = AppState()
        state.openWorkspace(dir)
        val doc = state.ensureDoc(file)
        (doc.root as YNode.Map).entries["id"] = YNode.Scalar("DOC-LOCAL")
        doc.markEdited()

        overwriteKeepingMtime(file, "id: DOC-B2\n")
        state.pollExternalChanges(eventHint = true)

        // ローカル編集は保全され、競合フラグが立つ
        assertTrue(doc.externalConflict)
        assertEquals("DOC-LOCAL", ((doc.root as YNode.Map).entries["id"] as YNode.Scalar).value)
    }
}
