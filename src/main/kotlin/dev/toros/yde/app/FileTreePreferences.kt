package dev.toros.yde.app

import dev.toros.yde.app.core.FileCatalog
import java.nio.file.Path
import java.security.MessageDigest
import java.util.prefs.Preferences

/**
 * ファイルツリーの覚え（展開・表示の選択）の永続化。java.util.prefs は画面側（desktop）の持ち物で、app-core には入れない。
 * 長い展開一覧を単一の Preferences 値に詰めず、パスごとの項目で保存する。
 */
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
