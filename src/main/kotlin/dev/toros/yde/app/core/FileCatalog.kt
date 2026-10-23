package dev.toros.yde.app.core

import dev.toros.yde.workspace.WorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.prefs.Preferences

/** ファイルの一覧は文書の索引とは独立。本文は要求されたときだけ上限付きで読む。 */
object FileCatalog {
    data class Entry(val path: Path, val directory: Boolean, val link: Boolean = false)
    data class Options(val allFiles: Boolean = false, val hidden: Boolean = false, val excludes: String = "")
    data class Search(val entries: List<Entry>, val visited: Int, val truncated: Boolean, val errors: Int)
    data class Preview(val text: String?, val size: Long, val message: String)

    fun supported(path: Path): Boolean = path.fileName.toString().substringAfterLast('.', "").lowercase() in
        setOf("yaml", "yml", "md") || dev.toros.yde.semantic.schema.FilePath.isImage(path.fileName.toString())

    fun children(root: Path, directory: Path, options: Options): List<Entry> {
        if (directory != root) WorkspacePaths.resolve(root, root.relativize(directory).toString(), allowLinks = false)
        val excludes = exclusions(options)
        return Files.newDirectoryStream(directory).use { stream ->
            stream.mapNotNull { path ->
                val rel = root.relativize(path).toString().replace('\\', '/')
                if (!options.hidden && path.fileName.toString().startsWith(".") && rel != ".yde") return@mapNotNull null
                if (excludes.any { it.matches(rel) }) return@mapNotNull null
                val link = Files.isSymbolicLink(path)
                val dir = Files.isDirectory(path, NOFOLLOW_LINKS)
                if (!dir && !options.allFiles && !supported(path)) null else Entry(path, dir, link)
            }.sortedWith(compareBy<Entry> { !it.directory }.thenBy { it.path.fileName.toString().lowercase() }
                .thenBy { it.path.toString() })
        }
    }

    private fun exclusions(options: Options): List<Regex> = options.excludes.split(';').mapNotNull { raw ->
        raw.trim().takeIf(String::isNotEmpty)?.let { dev.toros.yde.semantic.schema.globToRegex(it) }
    }

    /** 深さ優先の逐次走査。リンクは辿らず、取消・結果上限・読めない領域を結果へ反映する。 */
    fun search(root: Path, query: String, options: Options, limit: Int = 5000,
               cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Search {
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val pending = ArrayDeque<Path>().also { it.add(root) }
        val found = mutableListOf<Entry>()
        var visited = 0
        var errors = 0
        while (pending.isNotEmpty() && !cancelled()) {
            val children = try { children(root, pending.removeLast(), options) } catch (_: Exception) { errors++; continue }
            for (entry in children) {
                if (cancelled()) break
                visited++
                if (visited % 512 == 0) progress(visited)
                val rel = root.relativize(entry.path).toString().replace('\\', '/').lowercase()
                if (words.all { it in rel }) {
                    found += entry
                    if (found.size >= limit) return Search(found, visited, true, errors)
                }
                if (entry.directory && !entry.link) pending.add(entry.path)
            }
        }
        return Search(found, visited, cancelled(), errors)
    }

    fun preview(file: Path, maxBytes: Int = 2 * 1024 * 1024): Preview {
        val size = Files.size(file)
        if (size > maxBytes) return Preview(null, size, "大きなファイルです。外部のアプリで開いてください")
        // ChannelInputStream は要求した長さで確保するため、小ファイルごとに上限分を確保しない。
        val bytes = Files.newInputStream(file).use { it.readNBytes((size + 1).toInt()) }
        if (bytes.size > size) return Preview(null, size, "読込中にファイルが変わりました。再読込してください")
        if (bytes.size > maxBytes) return Preview(null, size, "読込上限を超えました")
        if (bytes.any { it == 0.toByte() }) return Preview(null, size, "バイナリファイルです")
        val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }.getOrNull()
        return Preview(text, size, if (text == null) "UTF-8 で読めません。外部のアプリで開いてください" else "読み取り専用・UTF-8")
    }
}

/** 長い展開一覧を単一の Preferences 値に詰めず、パスごとの項目で保存する。 */
class FileTreePreferences(private val preferences: Preferences) {
    companion object {
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun forWorkspace(root: Path) = FileTreePreferences(Preferences.userRoot()
            .node("yaml-design-editor/tree/${hash(root.toAbsolutePath().normalize().toString())}"))
    }
    fun expanded(): Map<String, Boolean> = preferences.keys().filter { it.startsWith("p-") }.mapNotNull { key ->
        val value = preferences.get(key, "")
        if (value.length < 2) null else value.substring(1) to value.startsWith("1")
    }.toMap()
    fun expand(path: String, value: Boolean) { preferences.put("p-${hash(path)}", (if (value) "1" else "0") + path) }
    fun clearExpansion() { preferences.keys().filter { it.startsWith("p-") }.forEach(preferences::remove) }
    fun options() = FileCatalog.Options(preferences.getBoolean("all", false), preferences.getBoolean("hidden", false), preferences.get("excludes", ""))
    fun options(value: FileCatalog.Options) {
        preferences.putBoolean("all", value.allFiles); preferences.putBoolean("hidden", value.hidden)
        preferences.put("excludes", value.excludes)
    }
}
