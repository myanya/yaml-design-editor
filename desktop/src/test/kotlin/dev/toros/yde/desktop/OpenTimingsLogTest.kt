package dev.toros.yde.desktop

import dev.toros.yde.workspace.Timings
import dev.toros.yde.workspace.layout.WorkspaceLayout
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTimingsLogTest {

    @Test
    fun `開く処理の時間を表示状態の置き場に追記し、新しい 50 件だけ残す`() {
        val root = createTempDirectory("yde-timings")
        try {
            Files.createDirectories(root.resolve(".yde"))
            Files.writeString(root.resolve(".yde/workspace.yaml"), "ledger: [items]\n")
            val layout = WorkspaceLayout.of(root)
            assertEquals(".yde/state/timings.log", OpenTimingsLog.relative(layout))
            repeat(52) { i ->
                val timings = Timings("ワークスペースを開く: 回 $i")
                timings.phase("読み込み") { }
                timings.mark("最初の描画")
                OpenTimingsLog.append(root, layout, OpenTimingsLog.entry(timings, LocalDateTime.of(2026, 11, 12, 9, 0), 5_200))
            }
            val text = Files.readString(root.resolve(".yde/state/timings.log"))
            val entries = text.trim().split("\n\n")
            assertEquals(50, entries.size)
            assertTrue(entries.first().contains("回 2"), entries.first())
            assertTrue(entries.last().contains("回 51"))
            assertTrue(entries.last().startsWith("2026-11-12T09:00:00（JVM の起動から 5.2 秒）"), entries.last())
            assertTrue(entries.last().contains("最初の描画"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
