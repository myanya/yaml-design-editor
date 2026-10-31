package dev.toros.yde.cli

import dev.toros.yde.app.service.WorkspaceOwnership
import java.nio.file.Path

/** 書込を伴うモードだけ、読込の前から処理完了まで所有する。適用規則は既存の窓口に委ねる。 */
internal fun <T> withCliOwnership(root: String?, writing: Boolean, block: () -> T): T {
    if (!writing || root == null) return block()
    return WorkspaceOwnership().acquire(Path.of(root)).use { block() }
}

internal fun appCommandWrites(command: String, rest: List<String>): Boolean = when (command) {
    "apply" -> "--dry-run" !in rest
    "action", "import", "infer-schema", "infer-relations" -> "--apply" in rest
    "agent", "watch", "revert", "export", "migrate" -> true
    "eval" -> rest.getOrNull(1)?.startsWith("--") == false
    "report" -> rest.getOrNull(1)?.startsWith("--") == false && "--print" !in rest
    "run-skill" -> "--yes" in rest
    "init" -> ("--template" in rest || "--adopt" in rest) && "--dry-run" !in rest
    "version", "catalog", "outline", "read", "bundle", "traverse", "query", "transform", "search",
    "view", "validate", "readiness", "checks", "hash", "entity", "refactor", "move", "activities",
    "metrics", "skills", "schema-graph", "bench" -> false // bench --keep は生成先ごとに取得する。
    else -> error("不明なコマンドです: $command\n$CLI_USAGE")
}
