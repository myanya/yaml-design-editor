package dev.toros.yde.workspace

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 文書・定義の境界。リンク先もルート内に限る（同時のリンク差し替えを隔離する機構ではない）。 */
object WorkspacePaths {
    fun relative(path: String): Path {
        val text = path.replace('\\', '/')
        require(text.isNotBlank() && !text.startsWith('/') && ':' !in text) { "相対パスを指定してください: $path" }
        val normalized = Path.of(text).normalize()
        require(!normalized.isAbsolute && normalized.toString().isNotEmpty() && !normalized.startsWith("..")) {
            "ワークスペース外のパスは扱えません: $path"
        }
        return normalized
    }

    fun resolve(root: Path, path: String, allowLinks: Boolean = true): Path {
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relative(path)).normalize()
        require(target != base && target.startsWith(base)) { "ワークスペース外のパスは扱えません: $path" }
        val realRoot = base.toRealPath()
        // 新規ファイルでも、既存の親が外部リンクなら拒否する。壊れたリンクも toRealPath で失敗する。
        var existing = target
        while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
        require(existing.toRealPath().startsWith(realRoot)) { "ワークスペース外を指すリンクは扱えません: $path" }
        require(allowLinks || existing.toRealPath() == realRoot.resolve(base.relativize(existing))) {
            "AI の書込先にリンクは使えません: $path"
        }
        return target
    }
}
