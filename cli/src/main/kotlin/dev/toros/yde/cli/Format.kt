package dev.toros.yde.cli

import dev.toros.yde.yaml.YamlIO

/**
 * CLI の出力形式（計画書 Phase 10: 人間向け text と機械可読 yaml / json を分ける）。
 * 機械可読出力は Map / List / スカラーの入れ子（キー順維持）を受け取り、そのまま書き出す。
 */
enum class OutputFormat { TEXT, YAML, JSON }

/** 引数の共通解釈（`--flag 値` 形式。位置引数はフラグより前に置く規約）。 */
object CliArgs {

    /** `--flag 値` のフラグ値（無ければ null）。 */
    fun stringFlag(args: List<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0) args.getOrNull(i + 1) else null
    }

    /** `--format text|yaml|json`（省略時 text）。 */
    fun format(args: List<String>): OutputFormat = when (val v = stringFlag(args, "--format")) {
        null, "text" -> OutputFormat.TEXT
        "yaml" -> OutputFormat.YAML
        "json" -> OutputFormat.JSON
        else -> error("--format は text / yaml / json のいずれかです: $v")
    }
}

/** 機械可読出力の書き出し（yaml は YamlIO、json は最小エンコーダ）。 */
object CliOutput {

    fun emit(data: Any?, format: OutputFormat, out: (String) -> Unit) {
        when (format) {
            OutputFormat.YAML -> out(YamlIO.dumpPlain(YamlIO.fromAny(data)).trimEnd())
            OutputFormat.JSON -> out(json(data))
            OutputFormat.TEXT -> error("text は emit の対象外（人間向け出力は各コマンドが行う）")
        }
    }

    internal fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long, is Double -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> "${quote(k.toString())}:${json(x)}" }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> quote(v.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
