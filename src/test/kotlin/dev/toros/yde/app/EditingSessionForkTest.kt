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
import kotlin.test.assertTrue

/**
 * 編集用のセッションを、ディスクを映すセッションの読み込み済みの状態から起こす（P27 段D の D-d・D-e。意図的差分 D-38）。
 * 読み込みを1回にしても、二層（ディスクの索引＝正／見たままの索引＝未保存の編集を重ねたもの。architecture/index-references.md「索引の二層」）が
 * 崩れないことを固定する。
 */
class EditingSessionForkTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-session-fork")
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

    private fun setId(doc: OpenDocument, value: String) {
        (doc.root as YNode.Map).entries["id"] = YNode.Scalar(value)
        doc.markEdited()
    }

    @Test
    fun `未保存の編集は見たままの索引にだけ現れ、ディスクの索引へ漏れない`() {
        val state = AppState().also { it.openWorkspace(dir) }
        assertEquals(setOf("TASK-001", "TASK-002"), state.overlayIndex().entries.keys)

        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        setId(t1, "TASK-100")

        assertTrue("TASK-100" in state.overlayIndex().entries, "見たままの索引には、未保存の ID が現れる")
        assertFalse("TASK-100" in state.index.entries, "ディスクの索引は変わらない")
        assertTrue("TASK-001" in state.index.entries)
        // ディスクの側では、TASK-001 への参照が解決できるまま（未保存の編集の影響を受けない）
        assertEquals(1, state.index.backlinksTo(setOf("TASK-001")).size)
        assertTrue(state.index.refs.all { state.index.resolve(it).isNotEmpty() })

        // 再読込（ディスクを映すセッションを読み直し、編集用をそこから起こし直す）でも、未保存の編集は保たれる
        state.reload()
        assertTrue(t1.dirty)
        assertTrue("TASK-100" in state.overlayIndex().entries)
        assertFalse("TASK-100" in state.index.entries)

        // 保存すると、ディスクの索引にも現れる
        state.saveAll()
        assertTrue("TASK-100" in state.index.entries)
        assertFalse(t1.dirty)
    }
}
