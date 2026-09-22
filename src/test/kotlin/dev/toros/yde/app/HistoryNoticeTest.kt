package dev.toros.yde.app

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo/Redo フィードバック（UX-86 / UX-93）のテスト。
 * 横断履歴（D-07）は開いていない文書も戻すため、「何が戻ったか」（historyNotice）と
 * 「次に何が戻るか」（undoPreview / redoPreview）を AppState が組み立てることを固定する。
 * 未保存化（●）の明示は、適用=保存済み・Undo=未保存の非対称（D-12）の説明（UX-93）を兼ねる。
 */
class HistoryNoticeTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-history-notice")
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - { key: depends_on, type: array, item: { type: ref } }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            title: 最初のタスク
        """)
        write("tasks/t2.yaml", """
            id: TASK-002
            title: 二番目
            depends_on:
              - TASK-001
        """)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun openState(): AppState = AppState().also { it.openWorkspace(dir) }

    private fun editTitle(doc: OpenDocument, value: String) {
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar(value)
        doc.markEdited()
    }

    @Test
    fun `編集のUndoは通知と予告を出す`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        assertNull(state.undoPreview())  // 履歴なし＝予告なし
        editTitle(t1, "編集後")
        assertNull(state.historyNotice)  // 編集自体は通知しない

        val preview = state.undoPreview()
        assertNotNull(preview)
        assertTrue("tasks/t1.yaml" in preview, preview)

        state.undo()
        val notice = state.historyNotice
        assertNotNull(notice)
        assertTrue("元に戻しました" in notice.message, notice.message)
        assertTrue("tasks/t1.yaml" in notice.message, notice.message)
        // 保存済み状態へ戻った（dirty でない）ので未保存化の注記は付かない
        assertFalse("未保存" in notice.message, notice.message)
        assertEquals(listOf(dir.resolve("tasks/t1.yaml").normalize()), notice.files)
    }

    @Test
    fun `Redoは「やり直しました」で通知する`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        editTitle(t1, "編集後")
        state.undo()
        val preview = state.redoPreview()
        assertNotNull(preview)
        assertTrue("tasks/t1.yaml" in preview, preview)

        state.redo()
        val notice = state.historyNotice
        assertNotNull(notice)
        assertTrue("やり直しました" in notice.message, notice.message)
        // やり直しで編集が復活＝未保存（●）の明示が付く
        assertTrue("未保存" in notice.message, notice.message)
        assertTrue("保存で確定" in notice.message, notice.message)
    }

    @Test
    fun `リファクタリングのUndoは全対象と未保存化を明示する`() {
        val state = openState()
        state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        state.ensureDoc(dir.resolve("tasks/t2.yaml"))
        val plan = state.planEntityRename("TASK-001", "TASK-100").getOrThrow()
        state.applyEntityPlan(plan).getOrThrow()
        // 適用は保存まで完了する＝●が付かない理由をステータスで明示（UX-93）
        assertTrue("保存しました" in state.status, state.status)
        assertTrue("Ctrl+Z" in state.status, state.status)

        state.undo()
        val notice = state.historyNotice
        assertNotNull(notice)
        assertTrue("元に戻しました" in notice.message, notice.message)
        // 戻した結果は未保存（●）——保存で確定することを案内する（事故防止の中核。UX-86）
        assertTrue("未保存" in notice.message, notice.message)
        assertTrue("保存で確定" in notice.message, notice.message)
        // 影響した両文書へジャンプできる
        assertEquals(
            setOf(dir.resolve("tasks/t1.yaml").normalize(), dir.resolve("tasks/t2.yaml").normalize()),
            notice.files.toSet(),
        )
    }

    @Test
    fun `通知は次の編集と保存で消える`() {
        val state = openState()
        state.openFile(dir.resolve("tasks/t1.yaml"))
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        editTitle(t1, "編集後")
        state.undo()
        assertNotNull(state.historyNotice)

        // 次の編集で消える
        editTitle(t1, "再編集")
        assertNull(state.historyNotice)

        // 保存でも消える
        state.undo()
        assertNotNull(state.historyNotice)
        state.saveCurrent()
        assertNull(state.historyNotice)
    }
}
