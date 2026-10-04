package dev.toros.yde.app

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保存時に自動で入る値（印 `auto`）のアプリ側: 保存の直前に更新日と版が入って UI の木にも映る・Ctrl+Z で日付と版だけ戻る・
 * 新規作成は作成日と版 1 で始まる・一括変更も内容の更新として入る。
 */
class AppStateAutoValuesTest {

    private lateinit var dir: Path
    private val today = LocalDate.now().toString()

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-autovalues")
        write("_schemas/rule.yaml", """
            id: rule
            match: "rules/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string, required: true }
                - { key: created_on, type: date, auto: created, required: true }
                - { key: updated_on, type: date, auto: updated, required: true }
                - { key: revision, type: int, auto: revision, format: "'rev-'000" }
        """)
        write("rules/r1.yaml", """
            id: RULE-1
            title: 最初
            created_on: 2026-01-01
            updated_on: 2026-01-02
            revision: 2
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

    private fun scalar(doc: OpenDocument, key: String) = ((doc.root as YNode.Map).entries[key] as YNode.Scalar).value

    @Test
    fun `保存すると更新日と版が入ってファイルと画面の木に映り Undo で日付と版だけ戻る`() {
        val state = openState()
        val file = dir.resolve("rules/r1.yaml")
        state.openFile(file)
        val doc = state.openDoc!!
        (doc.root as YNode.Map).entries["title"] = YNode.Scalar("直した")
        doc.markEdited()

        state.saveCurrent()
        val text = Files.readString(file)
        assertTrue("updated_on: $today" in text && "revision: 3" in text && "created_on: 2026-01-01" in text, text)
        assertEquals(today, scalar(doc, "updated_on"), "画面の木もセッションから写し直されている")
        assertEquals(3L, scalar(doc, "revision"))
        assertFalse(doc.dirty)
        assertTrue("自動の値を設定" in state.status, state.status)

        // Undo 1 回: 日付と版だけ戻り、内容（title）は残る＝未保存
        state.undo()
        assertEquals("2026-01-02", scalar(doc, "updated_on"))
        assertEquals(2, scalar(doc, "revision"))
        assertEquals("直した", scalar(doc, "title"))
        assertTrue(doc.dirty)

        // 内容まで戻して保存: 保存済み（直した・版 3）と比べると中身が違うので、版は保存済みの 3 から数えて 4
        state.undo()
        assertEquals("最初", scalar(doc, "title"))
        state.saveCurrent()
        val again = Files.readString(file)
        assertTrue("title: 最初" in again && "revision: 4" in again, again)

        // 何も変えずに保存しても自動の値は入らない
        state.saveCurrent()
        assertFalse("自動の値を設定" in state.status, state.status)
        assertTrue("revision: 4" in Files.readString(file))
    }

    @Test
    fun `新規作成は作成日 更新日 版 1 で始まり 一括変更も自動の値を入れる`() {
        val state = openState()
        val created = state.createYaml(dir.resolve("rules"), "r2").getOrThrow()
        val text = Files.readString(created)
        assertTrue("created_on: $today" in text && "updated_on: $today" in text && "revision: 1" in text, text)

        val r1 = dir.resolve("rules/r1.yaml")
        state.bulkUpdate(setOf(r1), "title", "一括").getOrThrow()
        val bulk = Files.readString(r1)
        assertTrue("title: 一括" in bulk && "updated_on: $today" in bulk && "revision: 3" in bulk, bulk)
    }
}
