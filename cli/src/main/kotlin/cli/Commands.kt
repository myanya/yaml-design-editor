package cli

import model.DocumentId
import model.pretty
import semantic.RefactoringRequest
import validate.Severity
import workspace.FileSystemDocumentStore
import workspace.WorkspaceReader
import workspace.WorkspaceSession
import java.nio.file.Files
import java.nio.file.Path

/**
 * workspace 系コマンドの本体（Phase 10）。dispatcher（Main.kt）から呼ばれ、JUnit から直接テストできる。
 * 業務ロジックは持たない——引数を core API の入力へ写し、結果を表示形式（text / yaml / json）へ写すだけ。
 */

internal fun requireDir(root: String?): Path {
    val dir = Path.of(root ?: error("ワークスペースのルートを指定してください"))
    require(Files.isDirectory(dir)) { "ディレクトリではありません: $dir" }
    return dir
}

/**
 * 組込みスキーマ（メタスキーマ・定義ゾーンの構造検証）を注入して読取ワークスペースを開く
 * （C-4・RC-07。`_schemas` / 定義文書の検証面がアプリと揃う＝意図的差分 D-14）。
 */
internal fun loadWorkspace(root: String?): workspace.LoadedWorkspace {
    val dir = requireDir(root)
    return WorkspaceReader.load(
        FileSystemDocumentStore(dir),
        builtins = workspace.BuiltinSchemaResources.kernelSchemas(schema.WorkspaceLayout.of(dir)),
    )
}

/** 組込みスキーマを注入してセッションを開く（refactor / shell 用。C-4・RC-07）。 */
internal fun openSession(root: String?): WorkspaceSession {
    val dir = requireDir(root)
    return WorkspaceSession(
        FileSystemDocumentStore(dir),
        builtins = workspace.BuiltinSchemaResources.kernelSchemas(schema.WorkspaceLayout.of(dir)),
    )
}

// ---------------------------------------------------------------- validate

fun workspaceValidate(root: String?, args: List<String>, out: (String) -> Unit): Int {
    val format = CliArgs.format(args)
    val ws = loadWorkspace(root)

    val structural = ws.derived.structuralIssues.entries.flatMap { (id, issues) -> issues.map { id to it } }
    val semantic = ws.derived.semantic.issues
    val structuralErrors = structural.count { it.second.severity == Severity.ERROR }
    val structuralWarnings = structural.count { it.second.severity == Severity.WARNING }
    val semanticErrors = semantic.count { it.severity == Severity.ERROR }
    val semanticWarnings = semantic.count { it.severity == Severity.WARNING }

    if (format == OutputFormat.TEXT) {
        out("スキーマ: ${ws.schemas.size} 件（${ws.schemas.joinToString(", ") { it.id }}）")
        out("文書: ${ws.snapshot.documents.size} 件（台帳内 ${ws.snapshot.documents.values.count { it.inLedger }} 件）")
        ws.schemaErrors.forEach { out("スキーマエラー: $it") }
        ws.parseErrors.forEach { (id, message) -> out("パースエラー: ${id.value}: $message") }
        structural.forEach { (id, issue) ->
            out("  [構造:${issue.severity}] ${id.value} ${issue.path.pretty()}: ${issue.message}")
        }
        semantic.forEach { issue ->
            out("  [意味:${issue.severity}] ${issue.document.value} ${issue.path.pretty()}: ${issue.message}")
        }
        out("構造検証: エラー $structuralErrors 件 / 警告 $structuralWarnings 件")
        out("意味検証: エラー $semanticErrors 件 / 警告 $semanticWarnings 件")
    } else {
        CliOutput.emit(
            linkedMapOf(
                "schemas" to ws.schemas.map { it.id },
                "documents" to ws.snapshot.documents.size,
                "ledger" to ws.snapshot.documents.values.count { it.inLedger },
                "schemaErrors" to ws.schemaErrors,
                "parseErrors" to ws.parseErrors.map { (id, message) ->
                    linkedMapOf("document" to id.value, "message" to message)
                },
                "structural" to structural.map { (id, issue) ->
                    linkedMapOf(
                        "severity" to issue.severity.name,
                        "document" to id.value,
                        "path" to issue.path.pretty(),
                        "message" to issue.message,
                    )
                },
                "semantic" to semantic.map { issue ->
                    linkedMapOf(
                        "severity" to issue.severity.name,
                        "document" to issue.document.value,
                        "path" to issue.path.pretty(),
                        "message" to issue.message,
                    )
                },
                "counts" to linkedMapOf(
                    "structuralErrors" to structuralErrors,
                    "structuralWarnings" to structuralWarnings,
                    "semanticErrors" to semanticErrors,
                    "semanticWarnings" to semanticWarnings,
                ),
            ),
            format, out,
        )
    }
    return when {
        ws.schemaErrors.isNotEmpty() || ws.parseErrors.isNotEmpty() || structuralErrors > 0 -> 2
        semanticErrors > 0 -> 3
        else -> 0
    }
}

// ---------------------------------------------------------------- catalog

/** ワークスペースの棚卸し（スキーマと文書の一覧・割当）。検証は validate の仕事のため exit は 0 / 1 のみ。 */
fun workspaceCatalog(root: String?, args: List<String>, out: (String) -> Unit): Int {
    val format = CliArgs.format(args)
    val ws = loadWorkspace(root)
    if (format == OutputFormat.TEXT) {
        out("スキーマ ${ws.schemas.size} 件:")
        ws.schemas.forEach { s ->
            val note = if (s.isTemplate) "・derive テンプレート" else ""
            out("  ${s.id}  [${s.kind.id}]  match: ${s.match.ifEmpty { "-" }}  （${s.source.value}$note）")
        }
        out("文書 ${ws.snapshot.documents.size} 件（台帳内 ${ws.snapshot.documents.values.count { it.inLedger }} 件）:")
        ws.snapshot.documents.forEach { (id, d) ->
            out("  ${id.value}  schema: ${d.schemaId ?: "-"}${if (d.inLedger) "" else "  [台帳外]"}")
        }
        ws.schemaErrors.forEach { out("スキーマエラー: $it") }
    } else {
        CliOutput.emit(
            linkedMapOf(
                "schemas" to ws.schemas.map { s ->
                    linkedMapOf(
                        "id" to s.id,
                        "kind" to s.kind.id,
                        "match" to s.match,
                        "source" to s.source.value,
                        "template" to s.isTemplate,
                    )
                },
                "documents" to ws.snapshot.documents.map { (id, d) ->
                    linkedMapOf("document" to id.value, "schema" to d.schemaId, "inLedger" to d.inLedger)
                },
                "schemaErrors" to ws.schemaErrors,
            ),
            format, out,
        )
    }
    return 0
}

// ---------------------------------------------------------------- entity inspect

/** エンティティ1件の検分（出現・被参照・出参照）。見つからなければ exit 1。 */
fun workspaceEntity(root: String?, id: String?, args: List<String>, out: (String) -> Unit): Int {
    require(!id.isNullOrBlank()) { "エンティティのIDを指定してください" }
    val format = CliArgs.format(args)
    val snapshot = loadWorkspace(root).derived.semantic
    val node = snapshot.graph.entities[id]
    if (node == null) {
        out("ID「$id」の出現が見つかりません")
        return 1
    }
    val inbound = snapshot.backlinksTo(setOf(id))
    val outgoing = snapshot.graph.outgoingRefs(id)
    if (format == OutputFormat.TEXT) {
        out("ID: $id" + (node.entityType?.let { "  type: $it" } ?: "") + (node.title?.let { "  title: $it" } ?: ""))
        out("出現 ${node.occurrences.size} 件:")
        node.occurrences.forEach { e ->
            val role = e.role?.let { "・${it.name}" } ?: ""
            out("  ${e.document.value} ${e.path.pretty().ifEmpty { "（ルート）" }}（schema: ${e.schemaId ?: "-"}$role）")
        }
        out("被参照 ${inbound.size} 件:")
        inbound.forEach { r -> out("  ${r.document.value} ${r.path.pretty()}${r.relation?.let { "（relation: $it）" } ?: ""}") }
        out("出参照 ${outgoing.size} 件:")
        outgoing.forEach { r -> out("  → ${r.targetId}（${r.document.value} ${r.path.pretty()}）") }
    } else {
        CliOutput.emit(
            linkedMapOf(
                "id" to id,
                "entityType" to node.entityType,
                "title" to node.title,
                "occurrences" to node.occurrences.map { e ->
                    linkedMapOf(
                        "document" to e.document.value,
                        "path" to e.path.pretty(),
                        "schema" to e.schemaId,
                        "role" to e.role?.name,
                    )
                },
                "inbound" to inbound.map { r ->
                    linkedMapOf("document" to r.document.value, "path" to r.path.pretty(), "relation" to r.relation)
                },
                "outgoing" to outgoing.map { r ->
                    linkedMapOf("target" to r.targetId, "document" to r.document.value, "path" to r.path.pretty())
                },
            ),
            format, out,
        )
    }
    return 0
}

// ---------------------------------------------------------------- bench

/**
 * 意味解析の「全再構築 vs 1文書の増分更新」を計測する（Phase 11・PF-003。root core/Bench.kt の
 * 計測部の移設）。増分更新のコストが総文書数でなく変更文書数に比例することの確認に使う。
 */
fun workspaceBench(root: String?, args: List<String>, out: (String) -> Unit): Int {
    val format = CliArgs.format(args)
    val iterations = CliArgs.stringFlag(args, "--iterations")?.toIntOrNull() ?: 3
    require(iterations > 0) { "--iterations は 1 以上を指定してください" }
    val ws = loadWorkspace(root)
    val input = workspace.WorkspaceReader.semanticInput(
        ws.snapshot.documents, ws.schemas, ws.declarations, ws.layout, version = 0,
    )
    require(input.documents.isNotEmpty()) { "計測対象の文書がありません" }

    fun best(block: () -> Unit): Double {
        var min = Long.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            min = minOf(min, System.nanoTime() - start)
        }
        return min / 1_000_000.0
    }

    val snapshot = semantic.SemanticEngine.build(input)
    val target = input.documents.first()
    val delta = semantic.SemanticDelta(
        workspaceVersion = 1,
        changed = listOf(target),
        removed = setOf(target.id),
        declarations = input.declarations,
    )
    val full = best { semantic.SemanticEngine.build(input) }
    val incremental = best { semantic.SemanticEngine.update(snapshot, delta) }
    val speedup = if (incremental > 0) full / incremental else 0.0

    if (format == OutputFormat.TEXT) {
        out("文書: ${input.documents.size} 件・ID: ${snapshot.ids.size} 件・参照: ${snapshot.refs.size} 件")
        out("全再構築: %.2f ms".format(full))
        out("増分更新（1文書・${target.id.value}）: %.2f ms".format(incremental))
        out("speedup: %.1f 倍（iterations=$iterations の最小値）".format(speedup))
    } else {
        CliOutput.emit(
            linkedMapOf(
                "documents" to input.documents.size,
                "ids" to snapshot.ids.size,
                "refs" to snapshot.refs.size,
                "fullBuildMillis" to full,
                "incrementalUpdateMillis" to incremental,
                "speedup" to speedup,
                "iterations" to iterations,
            ),
            format, out,
        )
    }
    return 0
}

// ---------------------------------------------------------------- refactor

private const val REFACTOR_OPS = "rename-entity / merge-entity / delete-entity / move-document / " +
    "rename-field / move-field / align-order / rename-enum-value / delete-field / require-field / " +
    "convert-type / rename-definition / extract-definition / inline-definition / rename-relation / rename-schema-id"

/** 位置引数（フラグより前）とフラグから RefactoringRequest を組み立てる（shell と共用）。 */
internal fun refactorRequest(params: List<String>): RefactoringRequest {
    val op = params.firstOrNull() ?: error("操作を指定してください（$REFACTOR_OPS）")
    val positionals = params.drop(1).takeWhile { !it.startsWith("--") }
    fun arg(i: Int, label: String): String = positionals.getOrNull(i) ?: error("${label}を指定してください")
    return when (op) {
        "rename-entity" -> RefactoringRequest.RenameEntity(arg(0, "改名元のID"), arg(1, "改名先のID"))
        "merge-entity" -> RefactoringRequest.MergeEntity(
            arg(0, "統合元のID"), arg(1, "統合先のID"),
            deleteOccurrences = "--delete-occurrences" in params,
        )
        "delete-entity" -> RefactoringRequest.DeleteEntity(arg(0, "削除するID"))
        "move-document" -> RefactoringRequest.MoveDocument(
            DocumentId(arg(0, "移動元のパス")), DocumentId(arg(1, "移動先のパス")),
        )
        "rename-field" -> RefactoringRequest.RenameField(
            arg(0, "スキーマID"), arg(1, "フィールドパス（ドット区切り）"), arg(2, "改名先のフィールド名"),
        )
        "move-field" -> RefactoringRequest.MoveField(
            arg(0, "スキーマID"), arg(1, "フィールドパス（ドット区切り）"), arg(2, "移動先の object パス（ルートは .）"),
            deleteEmptyParents = "--keep-empty" !in params,
        )
        "align-order" -> RefactoringRequest.AlignOrder(arg(0, "スキーマID"))
        "rename-enum-value" -> RefactoringRequest.RenameEnumValue(
            arg(0, "スキーマID"), arg(1, "フィールドパス"), arg(2, "現在の値"), arg(3, "改名先の値"),
        )
        "delete-field" -> RefactoringRequest.DeleteField(arg(0, "スキーマID"), arg(1, "フィールドパス"))
        "require-field" -> RefactoringRequest.RequireField(
            arg(0, "スキーマID"), arg(1, "フィールドパス"),
            default = CliArgs.stringFlag(params, "--default") ?: error("--default（充填する既定値）を指定してください"),
            fromPhase = CliArgs.stringFlag(params, "--from"),
        )
        "convert-type" -> RefactoringRequest.ConvertFieldType(
            arg(0, "スキーマID"), arg(1, "フィールドパス"), arg(2, "変換先の型"),
            enumValues = CliArgs.stringFlag(params, "--values")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
        "rename-definition" -> RefactoringRequest.RenameDefinition(
            arg(0, "モジュール（スキーマ）ID"), arg(1, "定義名"), arg(2, "改名先の定義名"),
        )
        "extract-definition" -> RefactoringRequest.ExtractDefinition(
            arg(0, "スキーマID"), arg(1, "フィールドパス"), arg(2, "定義名"),
        )
        "inline-definition" -> RefactoringRequest.InlineDefinition(arg(0, "スキーマID"), arg(1, "フィールドパス"))
        "rename-relation" -> RefactoringRequest.RenameRelation(arg(0, "現在の関係名"), arg(1, "改名先の関係名"))
        "rename-schema-id" -> RefactoringRequest.RenameSchemaId(arg(0, "現在のスキーマID"), arg(1, "改名先のスキーマID"))
        else -> error("refactor の操作は $REFACTOR_OPS のいずれかです: $op")
    }
}

/**
 * リファクタリングの plan 表示と適用（Phase 9/10: plan は semantic-core・apply は WorkspaceSession）。
 * exit: 0 = 成功／1 = 入力・計画エラー／4 = baseVersion の拒否／5 = 保存失敗。
 */
fun workspaceRefactor(root: String?, params: List<String>, out: (String) -> Unit): Int {
    val format = CliArgs.format(params)
    val session = openSession(root)
    val plan = session.planRefactoring(refactorRequest(params))
    val apply = "--apply" in params

    if (format == OutputFormat.TEXT) {
        plan.summary.forEach(out)
        plan.warnings.forEach { out("注意: $it") }
        plan.moves.forEach { (from, to) -> out("  移動: ${from.value} → ${to.value}") }
        plan.changes.forEach { change -> out("  ${change.document.value}: ${change.operations.size} 操作") }
    }

    val structured = linkedMapOf<String, Any?>(
        "summary" to plan.summary,
        "warnings" to plan.warnings,
        "moves" to plan.moves.map { (from, to) -> linkedMapOf("from" to from.value, "to" to to.value) },
        "changes" to plan.changes.map { linkedMapOf("document" to it.document.value, "operations" to it.operations.size) },
        "applied" to false,
    )
    if (!apply) {
        if (format == OutputFormat.TEXT) out("（プレビューのみ。適用するには --apply を付けてください）")
        else CliOutput.emit(structured, format, out)
        return 0
    }

    val result = session.applyRefactoring(plan)
    if (!result.ok) {
        if (format == OutputFormat.TEXT) out("適用できません: ${result.message}")
        else CliOutput.emit(structured.also { it["error"] = result.message }, format, out)
        return if (result.staleBase) 4 else 1
    }
    val save = session.save()
    structured["applied"] = true
    structured["written"] = save.written.map { it.value }
    structured["deleted"] = save.deleted.map { it.value }
    return if (save.ok) {
        if (format == OutputFormat.TEXT) out("適用して保存しました（${save.written.size + save.deleted.size} 文書）")
        else CliOutput.emit(structured, format, out)
        0
    } else {
        structured["failures"] = save.failures.map { (id, message) -> linkedMapOf("document" to id.value, "message" to message) }
        if (format == OutputFormat.TEXT) out("保存に失敗しました: ${save.failures}")
        else CliOutput.emit(structured, format, out)
        5
    }
}
