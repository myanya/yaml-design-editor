package dev.toros.yde.app.ui

import java.nio.file.Path

/** 表示順に基づく選択と、親子を重複して操作しない対象の正規化。 */
internal object FileTreeSelection {
    fun select(current: Set<Path>, target: Path, anchor: Path?, visible: List<Path>, toggle: Boolean, range: Boolean): Set<Path> {
        if (range && anchor != null) {
            val from = visible.indexOf(anchor); val to = visible.indexOf(target)
            if (from >= 0 && to >= 0) return (if (toggle) current else emptySet()) + visible.subList(minOf(from, to), maxOf(from, to) + 1)
        }
        return if (toggle) { if (target in current) current - target else current + target } else setOf(target)
    }
    fun roots(paths: Collection<Path>): List<Path> = paths.distinct().filter { path -> paths.none { it != path && path.startsWith(it) } }
}
