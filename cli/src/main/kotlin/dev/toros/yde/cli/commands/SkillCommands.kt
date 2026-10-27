package dev.toros.yde.cli.commands

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.changeSetToYaml
import dev.toros.yde.app.skill.Exporter
import dev.toros.yde.app.skill.Importer
import dev.toros.yde.app.skill.Runner
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path

/**
 * skill の命令: skills（一覧と検証）・import（表形式の取り込み。既定はプレビュー）・export（派生成果物の再生成）・run-skill（コード実行 skill。--yes の明示許可制）。形式は入口の登録簿（CliExtensions）から。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun skillsCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val loaded = Skills.load(ws.root)
    dumpYaml(
        linkedMapOf(
            "skills" to loaded.skills.map { s ->
                linkedMapOf<String, Any?>(
                    "id" to s.id, "name" to s.name, "kind" to s.kind.id,
                    "target" to s.import?.targetFile,
                    "output" to s.export?.outputFile,
                ).filterValues { it != null }
            },
            "errors" to loaded.errors,
        )
    )
}

internal fun exportCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
    val loaded = Skills.load(ws.root)
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
    val result = Exporter.run(ws, def)
    dumpYaml(linkedMapOf("file" to result.rel, "rows" to result.rows))
}

internal fun runSkillCommand(rest: List<String>): String = run {
    // コード実行skill（R9-06）。明示許可制: --yes なしでは内容の表示だけを行い、実行しない
    val ws = workspaceOf(rest)
    val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
    val loaded = Skills.load(ws.root)
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
    val runDef = def.run ?: error("skill「$skillId」は run（コード実行）ではありません")
    val summary = linkedMapOf<String, Any?>(
        "skill" to def.id,
        "command" to runDef.command,
        "workdir" to runDef.workdir,
        "timeoutSeconds" to runDef.timeoutSeconds,
        "writes" to runDef.writes,
    )
    if ("--yes" !in rest) {
        dumpYaml(summary) +
            "# 上記のコマンドがワークスペースの外にも作用しうることを確認し、実行するには --yes を付けてください\n"
    } else {
        val outcome = Runner.run(ws, def, Runner.Approval.approve(runDef))
        dumpYaml(
            linkedMapOf<String, Any?>(
                "ok" to outcome.ok,
                "exitCode" to outcome.exitCode,
                "timedOut" to outcome.timedOut.takeIf { it },
                "changedFiles" to outcome.changedFiles.ifEmpty { null },
                "outOfScope" to outcome.outOfScope.ifEmpty { null },
                "reservedTouched" to outcome.reservedTouched.ifEmpty { null },
                "stdout" to outcome.stdout.takeIf { it.isNotBlank() },
                "stderr" to outcome.stderr.takeIf { it.isNotBlank() },
            ).filterValues { it != null }
        )
    }
}

/** 表形式ファイルを変更セットへ変換し、プレビューまたは適用する（R5-02）。 */
internal fun importCommand(rest: List<String>): String {
    val ws = Workspace.open(Path.of(rest.firstOrNull() ?: error("ワークスペースのパスを指定してください")))
    val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
    val sourceFile = rest.getOrNull(2)?.takeIf { !it.startsWith("--") }
        ?.let { Path.of(it) } ?: error("取り込むファイルを指定してください")
    require(Files.isRegularFile(sourceFile)) { "ファイルが見つかりません: $sourceFile" }
    val loaded = Skills.load(ws.root)
    if (loaded.errors.isNotEmpty()) System.err.println("定義の警告:\n" + loaded.errors.joinToString("\n") { "- $it" })
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")

    val preview = Importer.build(ws, def, sourceFile)
    if (!preview.ok) {
        return dumpYaml(linkedMapOf("ok" to false, "errors" to preview.errors))
    }
    val summary = linkedMapOf<String, Any?>(
        "ok" to true,
        "created" to preview.created,
        "updated" to preview.updated,
        "unchanged" to preview.unchanged,
    )
    if (preview.details.isNotEmpty()) summary["details"] = preview.details
    if (preview.skippedRows > 0) summary["skippedRows"] = preview.skippedRows
    if ("--apply" !in rest) {
        return dumpYaml(summary) +
            (if (preview.changeSet.changes.isEmpty()) "" else "---\n" + changeSetToYaml(preview.changeSet))
    }
    if (preview.changeSet.changes.isEmpty()) {
        summary["applied"] = "変更なし"
        return dumpYaml(summary)
    }
    val result = ws.apply(preview.changeSet, requireValid = "--require-valid" in rest)
    summary["applied"] = result.ok
    if (!result.ok) {
        summary["applyErrors"] = result.results.mapNotNull { r ->
            val issues = r.issues.map { "${it.severity} ${it.path.pretty()}: ${it.message}" }
            val parts = listOfNotNull(r.message?.takeIf { it.isNotBlank() }) + issues
            if (parts.isEmpty()) null else "${r.file}: ${parts.joinToString("; ")}"
        }
    }
    return dumpYaml(summary)
}
