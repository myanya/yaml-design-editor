package core

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OS ネイティブのファイル監視（R8-03）。
 * WatchService でワークスペース配下を再帰的に監視し、「変更があったか」をフラグで通知する。
 * 何がどう変わったかの判定は従来どおり呼び出し側のスタンプ比較（pollExternalChanges / tick）が行うため、
 * イベントの取りこぼし・重複はフラグの立てすぎ側にしか倒れず安全。
 * ネットワークドライブ等で WatchService が使えない場合は open() が null を返し、
 * 呼び出し側は従来の周期ポーリングへフォールバックする。
 */
class FileWatcher private constructor(private val root: Path, private val service: WatchService) : AutoCloseable {

    private val changed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val thread: Thread = Thread({ watchLoop() }, "yde-file-watcher").apply { isDaemon = true }

    companion object {
        /** 監視を開始する。WatchService が使えない環境では null（ポーリングへフォールバック）。 */
        fun open(root: Path): FileWatcher? = runCatching {
            val service = root.fileSystem.newWatchService()
            val watcher = FileWatcher(root, service)
            watcher.registerTree(root)
            watcher.thread.start()
            watcher
        }.getOrNull()
    }

    /** 前回の呼び出し以降に変更イベントがあったか（フラグは消費される）。 */
    fun consumeChanged(): Boolean = changed.getAndSet(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { service.close() }
        }
    }

    /** 隠しディレクトリ（.git 等）を除いて配下のディレクトリを登録する。 */
    private fun registerTree(start: Path) {
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                runCatching {
                    dir.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    )
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun watchLoop() {
        try {
            while (!closed.get()) {
                val key = service.take()
                val dir = key.watchable() as? Path
                for (event in key.pollEvents()) {
                    changed.set(true)
                    // 新しく作られたディレクトリは監視対象へ追加する
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && dir != null) {
                        val created = (event.context() as? Path)?.let(dir::resolve)
                        if (created != null && Files.isDirectory(created) &&
                            !created.fileName.toString().startsWith(".")
                        ) {
                            runCatching { registerTree(created) }
                        }
                    }
                }
                if (!key.reset()) {
                    // 監視対象の消滅（ディレクトリ削除など）。変更として通知だけしておく
                    changed.set(true)
                }
            }
        } catch (_: ClosedWatchServiceException) {
            // close() による正常終了
        } catch (_: InterruptedException) {
            // スレッド停止
        }
    }
}
