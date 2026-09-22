package dev.toros.yde.app.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** R8-03: WatchService ベースのファイル監視。 */
class FileWatcherTest {

    private val dir: Path = createTempDirectory("yde-watch")
    private var watcher: FileWatcher? = null

    @AfterTest
    fun cleanup() {
        watcher?.close()
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** イベント反映まで最大 deadlineMillis 待つ（OSにより通知遅延があるため）。 */
    private fun awaitChange(w: FileWatcher, deadlineMillis: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + deadlineMillis
        while (System.currentTimeMillis() < end) {
            if (w.consumeChanged()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `file changes are reported and flag is consumed`() {
        val w = FileWatcher.open(dir)
        assertNotNull(w, "ローカルディレクトリでは WatchService が使えるはず")
        watcher = w
        assertFalse(w.consumeChanged())
        Files.writeString(dir.resolve("a.yaml"), "id: A\n")
        assertTrue(awaitChange(w), "ファイル作成が検出されること")
        // フラグは消費される（新しい変更がなければ false に戻る）
        Thread.sleep(200)
        w.consumeChanged()
        Files.writeString(dir.resolve("a.yaml"), "id: A2\n")
        assertTrue(awaitChange(w), "ファイル変更が検出されること")
    }

    @Test
    fun `changes inside newly created directories are watched`() {
        val w = FileWatcher.open(dir)
        assertNotNull(w)
        watcher = w
        val sub = dir.resolve("sub")
        Files.createDirectory(sub)
        assertTrue(awaitChange(w), "ディレクトリ作成が検出されること")
        // 新ディレクトリの登録が追いつくまで少し待ってから配下へ書く
        Thread.sleep(300)
        w.consumeChanged()
        Files.writeString(sub.resolve("b.yaml"), "id: B\n")
        assertTrue(awaitChange(w), "新規ディレクトリ配下の変更が検出されること")
    }
}
