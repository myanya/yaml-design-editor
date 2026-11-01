package dev.toros.yde.cli.commands

import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.skill.Exporter
import dev.toros.yde.app.skill.Importer
import dev.toros.yde.app.skill.Runner
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path

/**
 * skill の命令: skills（一覧と検証）・import（表形式の取り込み。既定はプレビュー）・export（派生成果物の再生成）・run-skill（コード実行 skill。--yes の明示許可制）。形式は入口の登録簿（CliExtensions）から。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun skillsCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val loaded = Skills.load(ws.root)
    asData(
        linkedMapOf(
            "skills" to loaded.skills.map { s ->
                linkedMapOf<String, Any?>(
                    "id" to s.id, "name" to s.name, "kind" to s.kind.id,
                    "target" to s.import?.targetFile,
                    "output" to s.export?.outputFile?.takeIf { it.isNotEmpty() },
                    "include" to s.export?.include?.takeIf { it.isNotEmpty() },
                ).filterValues { it != null }
            },
            "errors" to loaded.errors,
        )
    )
}

/**
 * 派生成果物を書き出す。`--out <フォルダ>` は書き出し先のフォルダ（output.file のファイル名で書く。ワークスペースの外も可）。
 * `--dry-run` は書かずに、今あるファイルとの違い（変わる行の範囲）だけを返す。
 * まとめて書き出す定義（include）は、既存のファイルを上書きするなら `--yes` が要る（無ければ一覧だけ返して書かない）。
 */
internal fun exportCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val skillId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("skill IDを指定してください")
    val loaded = Skills.load(ws.root)
    val def = loaded.skill(skillId) ?: error("skill「$skillId」が見つかりません（skills コマンドで確認できます）")
    val outDir = rest.indexOf("--out").takeIf { it >= 0 }?.let { i ->
        val dir = rest.getOrNull(i + 1)?.takeIf { !it.startsWith("--") } ?: error("--out に書き出し先のフォルダを指定してください")
        Path.of(dir).also { require(Files.isDirectory(it)) { "書き出し先のフォルダが見つかりません: $dir" } }
    }
    if (def.export?.isBundle == true) {
        val plans = Exporter.plans(ws, def, outDir)
        fun state(p: Exporter.Plan) = when {
            p.previous == null -> "created"
            p.changed -> "overwritten"
            else -> "unchanged"
        }
        val summary = linkedMapOf<String, Any?>(
            "files" to plans.map { p -> linkedMapOf("file" to p.rel, "rows" to p.rows, "state" to state(p)) },
            "overwritten" to plans.count { state(it) == "overwritten" },
            "created" to plans.count { state(it) == "created" },
            "unchanged" to plans.count { state(it) == "unchanged" },
        )
        val overwriting = plans.any { state(it) == "overwritten" }
        return@run when {
            "--dry-run" in rest -> asData(summary)
            overwriting && "--yes" !in rest ->
                asData(summary, note = "既存のファイルを上書きします。内容を確かめ、上書きするには --yes を付けてください（何も書いていません）")
            else -> {
                Exporter.write(plans)
                asData(summary.also { it["written"] = true })
            }
        }
    }
    if ("--dry-run" in rest) {
        val plan = Exporter.plan(ws, def, outDir)
        val summary = linkedMapOf<String, Any?>("file" to plan.rel, "rows" to plan.rows, "changed" to plan.changed)
        if (plan.previous == null) summary["created"] = true
        plan.lineDiff()?.takeIf { it.isNotEmpty() }?.let { hunks ->
            // 変わる行の範囲（今あるファイルの行番号。1 始まり）と、置き換わる行の数
            summary["diff"] = hunks.take(50).map { h ->
                linkedMapOf("line" to h.start + 1, "removed" to h.end - h.start, "added" to h.lines.size)
            }
            if (hunks.size > 50) summary["diffOmitted"] = hunks.size - 50
        }
        return@run asData(summary)
    }
    val result = Exporter.run(ws, def, outDir)
    asData(linkedMapOf("file" to result.rel, "rows" to result.rows, "changed" to result.changed))
}

internal fun runSkillCommand(rest: List<String>): CliResult = run {
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
        asData(summary, note = "上記のコマンドがワークスペースの外にも作用しうることを確認し、実行するには --yes を付けてください")
    } else {
        val outcome = Runner.run(ws, def, Runner.Approval.approve(runDef))
        asData(
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
internal fun importCommand(rest: List<String>): CliResult {
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
        return asData(linkedMapOf("ok" to false, "errors" to preview.errors))
    }
    val summary = linkedMapOf<String, Any?>(
        "ok" to true,
        "created" to preview.created,
        "updated" to preview.updated,
        "unchanged" to preview.unchanged,
    )
    if (preview.details.isNotEmpty()) summary["details"] = preview.details
    if (preview.skippedRows > 0) summary["skippedRows"] = preview.skippedRows
    if (preview.skippedNulls > 0) summary["skippedNulls"] = preview.skippedNulls
    if ("--apply" !in rest) {
        return asData(summary, changeSet = preview.changeSet)
    }
    if (preview.changeSet.changes.isEmpty()) {
        summary["applied"] = "変更なし"
        return asData(summary)
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
    return asData(summary)
}
