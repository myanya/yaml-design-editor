import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ビルド時に git から取る版の情報。root の配布タスクと app-core の build-info.properties の生成が共用する。
 * 内部バージョン（開発管理用。yyyymmdd.ビルド番号）: HEAD のコミット日（yyyymmdd）と、その日の何番目のコミットか
 * （同じコミットからは同じ値になる）。未コミットの変更を含むビルドには "-dirty" を付ける。-PbuildVersion=20260923.7 のように手動でも指定できる。
 */
object GitInfo {
    fun git(rootDir: File, vararg args: String): String? = runCatching {
        val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) out else null
    }.getOrNull()

    fun buildVersion(rootDir: File, override: String? = null): String = override ?: run {
        val day = git(rootDir, "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
        if (day == null) {
            LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".0"
        } else {
            val n = git(rootDir, "rev-list", "--count", "HEAD", "--since=$day 00:00") ?: "0"
            val dirty = if (git(rootDir, "status", "--porcelain")?.isNotEmpty() == true) "-dirty" else ""
            day.replace("-", "") + "." + n + dirty
        }
    }

    /** ビルド元のコミット（短縮ハッシュ）。 */
    fun commit(rootDir: File): String = git(rootDir, "rev-parse", "--short", "HEAD") ?: "unknown"
}
