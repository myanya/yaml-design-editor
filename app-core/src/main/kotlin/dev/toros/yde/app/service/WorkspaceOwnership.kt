package dev.toros.yde.app.service

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

class WorkspaceInUseException(root: Path) : IllegalStateException(
    "ワークスペースは別のデスクトップまたは CLI が使用中です: $root。常駐中の場合はトレイから完全に終了してから再実行してください。",
)

/** 同じ利用者・同じ実体のルートを持つ入口どうしの排他。ワークスペース内や TCP は使わない。 */
class WorkspaceOwnership(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".yaml-design-editor-locks"),
) {
    /** 初期化前の場所も、既存の祖先の実体から識別する。確認のために対象を作成しない。 */
    fun identity(root: Path): Path {
        var existing = root.toAbsolutePath().normalize()
        val missing = mutableListOf<Path>()
        while (true) {
            try {
                val real = existing.toRealPath()
                return missing.asReversed().fold(real) { path, child -> path.resolve(child) }
            } catch (failure: NoSuchFileException) {
                if (Files.isSymbolicLink(existing)) throw failure
                missing.add(existing.fileName ?: throw failure)
                existing = existing.parent ?: throw failure
            }
        }
    }

    fun acquire(root: Path): Lease {
        val canonical = identity(root)
        var key = canonical.toString()
        if (System.getProperty("os.name").startsWith("Windows")) key = key.lowercase(Locale.ROOT)
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Files.createDirectories(directory)
        check(Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) {
            "ワークスペース所有権の保存先が通常のフォルダではありません: $directory"
        }
        val file = directory.toRealPath().resolve("$hash.lock")
        synchronized(held) {
            // 同じ JVM では追加のチャネルを開かない。OS によってはその close で既存ロックも外れる。
            if (file in held) throw WorkspaceInUseException(canonical)
            val channel = FileChannel.open(file, CREATE, WRITE, NOFOLLOW_LINKS)
            try {
                check(Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) {
                    "ワークスペース所有権の保存先が通常のファイルではありません: $file"
                }
                val lock = try { channel.tryLock(0, 1, false) } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw WorkspaceInUseException(canonical)
                held.add(file)
                return Lease(canonical, file, channel)
            } catch (failure: Throwable) {
                try { channel.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }

    class Lease internal constructor(val root: Path, private val file: Path, private val channel: FileChannel) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(held) {
            if (!closed) {
                channel.close()
                held.remove(file)
                closed = true
            }
            // ファイルは削除しない。別プロセスの取得と入れ替わると異なる実体をロックしてしまう。
        }
    }

    private companion object {
        val held = mutableSetOf<Path>()
    }
}
