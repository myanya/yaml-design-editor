package dev.toros.yde.app.core

import java.nio.file.Path

/** 参考資料の都度検索。本文の常設索引を作らず、読込量と結果数を制限する。 */
object ReferenceSearch {
    data class Hit(val file: Path, val line: Int, val text: String)
    data class Result(val hits: List<Hit>, val visited: Int, val skipped: Int, val bytes: Long, val incomplete: Boolean)

    fun search(root: Path, query: String, options: FileCatalog.Options, scope: Path = root,
               byteBudget: Long = 64L * 1024 * 1024, maxHits: Int = 2000,
               cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {},
               onBatch: (List<Hit>) -> Unit = {}, timeBudgetMillis: Long = 30_000,
               nanoTime: () -> Long = System::nanoTime): Result {
        require(scope.normalize().startsWith(root.normalize())) { "検索範囲はワークスペース内にしてください" }
        val pending = ArrayDeque<Path>().also { it.add(scope) }
        val hits = mutableListOf<Hit>()
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return Result(emptyList(), 0, 0, 0, false)
        var visited = 0; var skipped = 0; var bytes = 0L
        val started = nanoTime()
        fun stopped() = cancelled() || (timeBudgetMillis > 0 && (nanoTime() - started) / 1_000_000 >= timeBudgetMillis)
        while (pending.isNotEmpty() && !stopped()) {
            val entries = try { FileCatalog.children(root, pending.removeLast(), options) } catch (_: Exception) { skipped++; continue }
            for (entry in entries) {
                if (stopped()) return Result(hits, visited, skipped, bytes, true)
                if (entry.directory) { pending.add(entry.path); continue }
                if (entry.link) { skipped++; continue }
                visited++
                if (visited % 64 == 0) { progress(visited); onBatch(hits.toList()) }
                val size = runCatching { java.nio.file.Files.size(entry.path) }.getOrNull()
                if (size == null || size > 2 * 1024 * 1024) { skipped++; continue }
                if (bytes + size > byteBudget) return Result(hits, visited, skipped, bytes, true)
                bytes += size
                val text = runCatching { FileCatalog.preview(entry.path).text }.getOrNull()
                if (text == null) { skipped++; continue }
                for ((index, line) in text.lineSequence().withIndex()) {
                    if (stopped()) return Result(hits, visited, skipped, bytes, true)
                    val lower = line.lowercase()
                    if (words.all { it in lower }) {
                        hits += Hit(entry.path, index + 1, line.take(240))
                        if (hits.size >= maxHits) return Result(hits, visited, skipped, bytes, true)
                    }
                }
            }
        }
        return Result(hits, visited, skipped, bytes, stopped())
    }
}
