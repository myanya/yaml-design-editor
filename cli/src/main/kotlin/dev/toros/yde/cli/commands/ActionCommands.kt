package dev.toros.yde.cli.commands

import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.app.core.changeSetToYaml
import dev.toros.yde.cli.issueMap
import dev.toros.yde.cli.pruneNulls
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.stringFlags
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.workspace.ActionFailure
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 変更の命令: action（操作の一覧・計画・適用。ActionPlanner → 変更セット → Workspace.apply）・apply（変更セットの適用）。計画と適用の規則はカーネルにあり、ここは引数を写すだけ。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun actionCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val name = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
    if (name == null) {
        // 使える操作の一覧（横断の操作と、型の操作）
        asData(
            linkedMapOf(
                "schemaErrors" to ws.schemaErrors,
                "actions" to ActionPlanner.catalog(ws.mirror.schemas).map { e ->
                    pruneNulls(
                        linkedMapOf(
                            "name" to e.name,
                            "kind" to e.kind,
                            "label" to e.label,
                            "description" to e.description,
                            "target" to e.target,
                            "params" to e.params.map { p ->
                                pruneNulls(linkedMapOf("key" to p.key, "label" to p.label, "type" to (p.typeName ?: p.type.id), "required" to p.required.takeIf { it }))
                            },
                            "definedIn" to e.definedIn,
                        )
                    )
                },
            )
        )
    } else {
        val params = stringFlags(rest, "--param").associate { raw ->
            val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
            raw.substring(0, at) to YamlIO.parseToRaw(raw.substring(at + 1))
        }
        val plan = try {
            ActionPlanner.plan(ws.mirror, ActionRequest(name, stringFlag(rest, "--on"), params), java.time.LocalDateTime.now())
        } catch (e: ActionFailure) {
            error(e.message ?: "操作を計画できません")
        }
        val apply = "--apply" in rest
        val result = if (plan.isEmpty) null
        else ws.apply(plan.changeSet, dryRun = !apply, requireValid = apply && "--allow-errors" !in rest)
        asData(
            linkedMapOf<String, Any?>(
                "action" to plan.title,
                "summary" to plan.summary,
                "changes" to (YamlIO.parseToRaw(changeSetToYaml(plan.changeSet)) as? Map<*, *>)?.get("changes"),
                "ok" to (result?.ok ?: true),
                "applied" to (result?.let { apply && it.ok } ?: false),
                "results" to result?.results?.map { r ->
                    linkedMapOf<String, Any?>(
                        "file" to r.file,
                        "applied" to r.applied,
                        "message" to r.message,
                        "issues" to r.issues.map { issueMap(it) },
                    ).filterValues { it != null }
                }.orEmpty(),
            )
        )
    }
}

internal fun applyCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val changeSetFile = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: error("変更セットのYAMLファイルを指定してください")
    val changeSet = ChangeSets.parse(Files.readString(Path.of(changeSetFile)))
    val result = ws.apply(
        changeSet,
        dryRun = "--dry-run" in rest,
        requireValid = "--require-valid" in rest,
    )
    asData(
        linkedMapOf(
            "ok" to result.ok,
            "dryRun" to result.dryRun,
            "changes" to result.results.map { r ->
                linkedMapOf<String, Any?>(
                    "file" to r.file,
                    "applied" to r.applied,
                    "message" to r.message,
                    "issues" to r.issues.map { issueMap(it) },
                ).filterValues { it != null }
            },
        )
    )
}
