package dev.toros.yde.cli.commands

import dev.toros.yde.app.validate.Readiness
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.issueMap
import dev.toros.yde.cli.pruneNulls
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity

/**
 * 検証の命令: validate（文書ごとの指摘と判定を保留した検査）・readiness（準備完了レポート）・checks（展開後の検査の表示）。検証の本体はカーネルと app-core の Validator。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun validateCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val target = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
    // `_schemas` 配下もメタスキーマ（R11-02）で検証対象になる（定義のパースエラーは schemaErrors 側）
    val files = if (target != null) listOf(ws.resolve(target)) else ws.files
    var errors = 0
    var warnings = 0
    var withheld = 0
    // --sources: 指摘の出どころ（どの検査の・どの要素についての指摘か）も出す（P27 段A）
    val withSources = "--sources" in rest
    val fileResults = files.mapNotNull { file ->
        val rel = ws.relPath(file)
        val result = ws.validateDetailed(rel)
        val issues = result.issues
        withheld += result.withheld.size
        errors += issues.count { it.severity == Severity.ERROR }
        warnings += issues.count { it.severity == Severity.WARNING }
        // phases 宣言のあるスキーマは記入進捗（未来フェーズの未記入はエラーではなくここに出る）も付与する
        val doc = ws.load(rel)
        val docSchema = doc.schema
        val progress = docSchema?.phases?.let { Validator.phaseProgress(doc.root, docSchema) }
            ?.filter { it.total > 0 }
        if (issues.isEmpty() && progress.isNullOrEmpty() && result.withheld.isEmpty()) return@mapNotNull null
        linkedMapOf(
            "file" to rel,
            "issues" to issues.map { issueMap(it, withSources) },
            // 判定を保留した検査（指摘ではない。データの不備で、充足とも違反とも決められなかった検査）
            "withheld" to result.withheld.map { linkedMapOf("path" to it.path.pretty(), "message" to it.text) },
            "phaseProgress" to progress?.map {
                linkedMapOf("phase" to it.phase, "filled" to it.filled, "total" to it.total)
            },
        ).filterValues { it != null && it != emptyList<Any>() }
    }
    dumpYaml(
        linkedMapOf(
            "schemaErrors" to ws.schemaErrors,
            "files" to fileResults,
            "errors" to errors,
            "warnings" to warnings,
            "withheld" to withheld.takeIf { it > 0 },
        ).filterValues { it != null }
    )
}

internal fun readinessCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val report = Readiness.report(ws)
    val listed = if ("--pending" in rest) report.entities.filterNot { it.ready } else report.entities
    fun groupMaps(rows: List<Readiness.GroupRow>, key: String) =
        rows.map { linkedMapOf(key to it.key, "total" to it.total, "ready" to it.ready) }
    dumpYaml(
        linkedMapOf(
            "schemaErrors" to ws.schemaErrors,
            "summary" to linkedMapOf(
                "entities" to report.entities.size,
                "ready" to report.entities.count { it.ready },
                "pending" to report.entities.count { !it.ready },
                // 判定を保留した検査（データの不備で確かめられなかった検査）の件数。0 なら出さない
                "withheld" to report.withheld.size.takeIf { it > 0 },
            ).filterValues { it != null },
            "byType" to groupMaps(report.byType, "type"),
            "byPhase" to groupMaps(report.byPhase, "phase"),
            "entities" to listed.map { row ->
                linkedMapOf(
                    "id" to row.id,
                    "type" to row.entityType,
                    "title" to row.title,
                    "file" to row.file,
                    "phase" to row.phase,
                    "errors" to row.errors,
                    "warnings" to row.warnings,
                    "withheld" to row.withheld.takeIf { it > 0 },
                    "ready" to row.ready,
                ).filterValues { it != null }
            },
            "unassigned" to report.unassigned.map {
                linkedMapOf("file" to it.file, "errors" to it.errors, "warnings" to it.warnings)
            },
            // 判定を保留した検査の内訳（--pending でも全件を出す: 「エラー0」の要素にも、確かめられなかった検査がありうる）
            "withheld" to report.withheld.map { (file, check) ->
                linkedMapOf("file" to file, "id" to check.subjectId, "message" to check.text)
            }.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    )
}

internal fun checksCommand(rest: List<String>): String = run {
    // 展開の透明性（P29 Phase 6・CALC-29）: 語彙（consistency など）と定義の使用（use）が、検証の前に
    // どんな基本の検査へ展開されたかを、由来つきで表示する。--definitions で使える定義と語彙の一覧も出す
    val ws = workspaceOf(rest)
    val only = rest.drop(1).firstOrNull { !it.startsWith("--") }
    // 型の検査（オブジェクト型の要素ごとの検査。P33 第三弾）は、書かれた場所（定義名か項目の場所）ごとに並べる
    fun typeChecks(s: dev.toros.yde.app.schema.SchemaDef) = s.tree.objectDeclarations()
        .filter { (_, f) -> f.checks.isNotEmpty() }
        .map { (at, f) ->
            linkedMapOf(
                "at" to at,
                "checks" to f.checks.map { r ->
                    pruneNulls(linkedMapOf("id" to r.id, "description" to r.description, "level" to r.level.id, "origin" to r.origin))
                },
            )
        }
    val targets = ws.schemas.filter { (it.expandedChecks.isNotEmpty() || typeChecks(it).isNotEmpty()) && (only == null || it.id == only) }
    val out = linkedMapOf<String, Any?>(
        "schemaErrors" to ws.schemaErrors,
        "schemas" to targets.map { s ->
            linkedMapOf<String, Any?>(
                "schema" to s.id,
                // 渡されなかった値（null）の項目は、読みやすさのため表示から省く
                "checks" to s.expandedChecks.map { linkedMapOf("origin" to it.origin, "check" to pruneNulls(it.raw)) },
            ).also { m -> typeChecks(s).takeIf { it.isNotEmpty() }?.let { m["typeChecks"] = it } }
        },
    )
    // 特定のスキーマに属さない検査のうち、組込み定義体に直接書かれたもの（エンティティの整合など）
    if (only == null) {
        out["builtinChecks"] = dev.toros.yde.app.schema.SchemaLoader.builtinChecks.map {
            linkedMapOf("origin" to it.origin, "check" to pruneNulls(it.raw))
        }
    }
    if ("--definitions" in rest) {
        val library = ws.schemas.fold(dev.toros.yde.app.schema.SchemaLoader.builtinLibrary) { acc, s ->
            runCatching { acc + s.checkLibrary }.getOrDefault(acc)
        }
        out["definitions"] = linkedMapOf(
            "functions" to library.functions.values.map {
                linkedMapOf("id" to it.id, "params" to it.params, "definedIn" to it.origin)
            },
            "vocabulary" to library.words.map {
                linkedMapOf("word" to it.word, "in" to it.location, "definedIn" to it.origin)
            },
        )
    }
    dumpYaml(out)
}
