package dev.toros.yde.cli.commands

import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.app.core.Bench
import dev.toros.yde.cli.stringFlag
import java.nio.file.Files
import java.nio.file.Path

/**
 * 規模の計測: 合成ワークスペースを作り、開く・全体の検証・準備完了レポートの時間と伸び方を出す（core.Bench）。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun benchCommand(rest: List<String>): CliResult = run {
    val sizes = (stringFlag(rest, "--modules") ?: "2,4,8,16").split(',').map { it.trim().toIntOrNull() ?: error("--modules には整数の並びを指定してください: $it") }
    val keep = stringFlag(rest, "--keep")?.let { Path.of(it) }
    // 1回目は JIT の暖機として捨てる（最小の大きさで1回まわす）
    run {
        val warm = Files.createTempDirectory("yde-bench-warm")
        try { Bench.measure(warm, Bench.generate(warm, sizes.min())) } finally { warm.toFile().deleteRecursively() }
    }
    var previous: Bench.Result? = null
    val rows = sizes.map { modules ->
        val dir = keep?.resolve("m$modules")?.also { Files.createDirectories(it) } ?: Files.createTempDirectory("yde-bench")
        try {
            val result = Bench.measure(dir, Bench.generate(dir, modules))
            val prev = previous
            // 伸び方: 時間の伸び ÷ 文書数の伸び（1.0 前後なら文書数に比例。2.0 なら、規模が2倍で時間が4倍）
            fun growth(now: Long, before: Long?): String? = prev?.let { p ->
                if (before == null || before == 0L) null
                else "%.2f".format((now.toDouble() / before) / (result.size.files.toDouble() / p.size.files))
            }
            previous = result
            linkedMapOf(
                "modules" to modules,
                "files" to result.size.files,
                "lines" to result.size.lines,
                "entities" to result.size.entities,
                "openMs" to result.openMs,
                "validateMs" to result.validateMs,
                "readinessMs" to result.readinessMs,
                "validateMsPerFile" to "%.1f".format(result.validateMs.toDouble() / result.size.files),
                "validateGrowth" to growth(result.validateMs, prev?.validateMs),
                "openGrowth" to growth(result.openMs, prev?.openMs),
                "errors" to result.errors,
                "warnings" to result.warnings,
            ).filterValues { it != null }
        } finally {
            if (keep == null) dir.toFile().deleteRecursively()
        }
    }
    asData(linkedMapOf("bench" to rows))
}
