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
        relative(path)
        return ReadBoundary(root).resolve(path, allowLinks)
    }

    /**
     * 一回の列挙・読込で共有する境界。固定するのはルートだけで、対象はアクセスごとに解決する。
     * 途中でルートや親のリンクが外へ差し替わっても、開始時の境界の外なら拒否する。
     * ストアには保持せず、書込・別の読込操作では新しい境界を使う。
     */
    class ReadBoundary(root: Path) {
        private val base = root.toAbsolutePath().normalize()
        private val realRoot = base.toRealPath()

        fun resolve(path: String, allowLinks: Boolean = true): Path {
            val target = base.resolve(relative(path)).normalize()
            require(target != base && target.startsWith(base)) { "ワークスペース外のパスは扱えません: $path" }
            // 新規ファイルでも既存の親を調べる。壊れたリンクは toRealPath で失敗する。
            var existing = target
            while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
            val realTarget = existing.toRealPath()
            require(realTarget.startsWith(realRoot)) { "ワークスペース外を指すリンクは扱えません: $path" }
            require(allowLinks || realTarget == realRoot.resolve(base.relativize(existing))) {
                "AI の書込先にリンクは使えません: $path"
            }
            return target
        }
    }
}
