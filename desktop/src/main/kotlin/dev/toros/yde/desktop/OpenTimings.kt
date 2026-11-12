package dev.toros.yde.desktop

import dev.toros.yde.workspace.Timings
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ワークスペースを開く処理の時間の記録を、ワークスペースの表示状態の置き場（`.yde/state/timings.log`）に追記する。
 * 手元の端末で、開くのに何秒かかったか・どの段階に時間を使ったかを後から読めるようにする（性能の調査用）。
 * 表示状態の置き場は索引・保存・Undo の外で、samples / labs では git の対象外。
 */
internal object OpenTimingsLog {
    const val FILE = "timings.log"

    /** 残す記録の数（古いものから捨てる）。 */
    private const val KEEP = 50
    private const val SEPARATOR = "\n\n"

    fun relative(layout: WorkspaceLayout): String = "${layout.stateDir}/$FILE"

    /** 記録 1 件の文字。時刻と、JVM の起動からの経過（JIT の暖まり具合の目安）を見出しに添える。 */
    fun entry(timings: Timings, now: LocalDateTime, jvmUptimeMillis: Long): String =
        "${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substringBefore('.')}" +
            "（JVM の起動から %.1f 秒）\n".format(jvmUptimeMillis / 1000.0) + timings.report()

    /** 追記する。前の記録は新しい [KEEP] 件だけ残す。書けなければ例外（呼び手が状態欄に出す）。 */
    fun append(root: Path, layout: WorkspaceLayout, entry: String) {
        val file = WorkspacePaths.resolve(root, relative(layout))
        Files.createDirectories(file.parent)
        val previous = try { Files.readString(file).split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() } } catch (_: NoSuchFileException) { emptyList() }
        val kept = (previous + entry.trimEnd()).takeLast(KEEP)
        Files.writeString(file, kept.joinToString(SEPARATOR) + "\n")
    }
}
