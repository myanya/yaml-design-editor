package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 閲覧専用モードが封じるのは文書の編集だけで、操作の入力の欄（受け取る値）は文書ではない。
 * 登録の操作の配列の入力（タグ）に、閲覧専用でも要素を足せる。文書の配列には足せない（今までどおり）。
 */
class ActionInputReadOnlyTest {
    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = createTempDirectory("yde-action-readonly")
        val sample = Path.of("samples/todos")
        Files.walk(sample).use { paths -> paths.forEach { source ->
            val relative = sample.relativize(source)
            if (!relative.startsWith(Path.of(".yde/state"))) {
                val target = root.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        } }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `閲覧専用でも操作の入力の配列に要素を足せ 文書の配列には足せない`() {
        val state = AppState()
        state.openWorkspace(root)
        state.readOnlyMode = true
        state.startAction("register-todo", null)
        val work = assertNotNull(state.actionWork)
        // 受け取る値の欄は、操作パネルと同じく操作の params の定義で描く
        val tags = assertNotNull(work.entry.params.firstOrNull { it.key == "tags" })
        // 操作の入力: 足せる（2 回）
        state.addArrayElement(work.form, listOf("tags"), tags).getOrThrow()
        state.addArrayElement(work.form, listOf("tags"), tags).getOrThrow()
        assertEquals(2, ((work.form.root as YNode.Map).entries["tags"] as YNode.Seq).items.size)
        // 文書（TODO 一覧）の配列: 閲覧専用では足せない
        state.openFile(root.resolve("todos/todo-list.yaml"))
        val doc = assertNotNull(state.openDoc)
        val todos = assertNotNull(doc.schema?.root?.fields?.firstOrNull { it.key == "todos" })
        assertTrue(state.addArrayElement(doc, listOf("todos"), todos).isFailure)
    }
}
