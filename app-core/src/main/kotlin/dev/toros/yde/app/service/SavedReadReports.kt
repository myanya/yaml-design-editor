package dev.toros.yde.app.service

import dev.toros.yde.app.core.*
import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.*
import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.*
import java.nio.file.Path
import java.time.LocalDate

/** 既存の評価器の結果を値と原本座標へ写す。評価・検証・集計の規則はここに追加しない。 */
internal class SavedReadReports(private val root: Path, private val texts: Map<DocumentId, String>, private val layout: WorkspaceLayout) {
    private val workspace by lazy { Workspace.fromReadSnapshot(root, texts, layout) }
    private val parsed = mutableMapOf<DocumentId, Result<YNode?>>()
    private fun parsed(id: DocumentId): Result<YNode?> = parsed.getOrPut(id) { runCatching { YamlIO.parseFirst(texts.getValue(id)) } }
    private fun tree(id: DocumentId): YNode? = if (id in texts) parsed(id).getOrNull() else null
    private fun schema(id: DocumentId): SchemaDef? = workspace.mirror.snapshot.documents[id]?.schemaId?.let { schemaId ->
        workspace.schemas.firstOrNull { it.id == schemaId }
    }
    private val catalog by lazy {
        val source = texts.filterKeys { id ->
            val prefix = layout.viewsDir + "/"
            id.value.startsWith(prefix) && '/' !in id.value.removePrefix(prefix)
        }.toList()
        val loaded = ViewDefinitions.load(source, workspace.schemas.map { it.kernel }, Extensions.viewKinds)
        readRequire(loaded.errors.isEmpty(), ReadFailureCode.INVALID_DEFINITIONS, "ビュー定義に誤りがあります。デスクトップで確認してください")
        Views.LoadResult(loaded.views, loaded.docViews, loaded.errors, loaded.lensViews, loaded.dashboards, loaded.extensionViews)
    }

    fun read(query: ReadQuery, visible: Map<DocumentId, String>, date: LocalDate): Map<String, Any?> {
        if (query.resource in setOf(ReadResource.ENTITY, ReadResource.VIEWS, ReadResource.VIEW, ReadResource.VALIDATION, ReadResource.ACTIONS)) {
            readRequire(workspace.schemaErrors.isEmpty(), ReadFailureCode.INVALID_DEFINITIONS, "スキーマ定義に誤りがあります。デスクトップで確認してください")
        }
        return when (query.resource) {
            ReadResource.ACTIONS -> page(actions().map { action -> mapOf(
                "name" to action.name, "kind" to action.kind, "label" to action.label, "description" to action.description,
                "target" to action.target, "definedIn" to action.definedIn,
                "params" to action.params.map { param ->
                    val resolved = workspace.schemas.firstOrNull { it.id == action.definedIn }?.resolve(param) ?: param
                    mapOf("key" to param.key, "type" to resolved.type.name.lowercase(), "required" to param.required)
                },
            ) }, query)
            ReadResource.WORKSPACE -> mapOf("documents" to visible.size, "state" to "saved")
            ReadResource.DOCUMENTS -> page(visible.entries.map { (id, text) -> mapOf("document" to id.value, "hash" to ChangeSets.contentHash(text)) }, query)
            ReadResource.DOCUMENT -> {
                val id = requireNotNull(query.document)
                val text = visible[id] ?: throw ReadFailure(ReadFailureCode.NOT_FOUND, "文書が見つかりません")
                mapOf("document" to id.value, "hash" to ChangeSets.contentHash(text), "text" to text)
            }
            ReadResource.SEARCH -> {
                val hits = Search.run(workspace, SearchQuery(text = query.text, limit = query.offset + query.limit + 1),
                    files = visible.keys.map { root.resolve(it.value) }, loader = { tree(workspace.documentOf(it)) })
                page(hits.map { mapOf("origin" to mapOf("document" to it.file, "path" to it.path), "kind" to it.kind, "excerpt" to it.excerpt) }, query)
            }
            ReadResource.ENTITY -> entity(requireNotNull(query.id))
            ReadResource.VIEWS -> page(definitions().sortedBy { it.id }.map { definition ->
                val supported = definition.viewKind in setOf("grid", "matrix", "dashboard", "chart")
                mapOf("id" to definition.id, "name" to definition.name, "kind" to definition.viewKind,
                    "origin" to origin(definition.source, emptyList()), "supported" to supported,
                    "reason" to if (supported) null else "この種類のビューは API の対象外です")
            }, query)
            ReadResource.VIEW -> view(query, query.date ?: date)
            ReadResource.VALIDATION -> {
                val ids = query.document?.let { id ->
                    readRequire(id in texts, ReadFailureCode.NOT_FOUND, "文書が見つかりません")
                    listOf(id)
                } ?: texts.keys.filter { layout.inLedger(it.value) || layout.isReserved(it.value) }
                // ページに載る文書だけを検証しても、索引と loader は同じ全入力を参照する。
                val selected = ids.drop(query.offset).take(query.limit)
                mapOf("items" to selected.map(::validation), "offset" to query.offset,
                    "nextOffset" to nextOffset(ids.size, query), "truncated" to truncated(ids.size, query), "fileExistence" to "not_checked")
            }
        }
    }

    private fun definitions(): List<VirtualViewDefinition> = with(catalog) { views + docViews + lensViews + dashboards + extensionViews }

    fun actions(): List<dev.toros.yde.workspace.ActionEntry> {
        readRequire(workspace.schemaErrors.isEmpty(), ReadFailureCode.INVALID_DEFINITIONS, "スキーマ定義に誤りがあります。デスクトップで確認してください")
        return ActionPlanner.catalog(workspace.mirror.schemas).filter { it.kind != "create" }.sortedBy { it.name }
    }

    private val VirtualViewDefinition.viewKind: String get() = when (this) {
        is VirtualGridDef -> if (matrix == null) "grid" else "matrix"
        is VirtualDashboardDef -> "dashboard"
        is VirtualDocViewDef -> "document"
        is VirtualLensDef -> "lens"
        is ExtensionViewDef -> viewKind
    }

    private fun entity(id: String): Map<String, Any?> {
        val value = Aspects.compose(id, workspace.index, workspace.schemas, workspace.entityTypes, ::tree)
            ?: throw ReadFailure(ReadFailureCode.NOT_FOUND, "エンティティが見つかりません")
        return mapOf("id" to value.id, "type" to value.entityType, "title" to value.title,
            "sections" to value.sections.map { section -> mapOf(
                "aspect" to section.aspect, "label" to section.label, "origin" to origin(section.entry.document, section.entry.path),
                "fields" to section.fields.map { field -> mapOf(
                    "key" to field.key, "label" to field.label, "value" to field.value?.let(YamlIO::toAny),
                    "origin" to origin(field.document, field.path),
                    "duplicateOf" to field.duplicateOf?.let { origin(it.first, it.second) },
                ) },
            ) }, "unassigned" to value.unassigned.map { origin(it.document, it.path) })
    }

    private fun view(query: ReadQuery, date: LocalDate): Map<String, Any?> {
        val definition = definitions().singleOrNull { it.id == query.id }
            ?: throw ReadFailure(ReadFailureCode.NOT_FOUND, "一意なビューが見つかりません")
        val report: Map<String, Any?> = when (definition.viewKind) {
            "grid", "matrix" -> {
                val view = requireNotNull(catalog.view(definition.id))
                val resolved = Views.resolveDetailed(view, workspace.index, ::tree, ::schema)
                mapOf("columns" to Views.columnsOf(view, resolved.rows).map { it.label },
                    "rows" to resolved.rows.drop(query.offset).take(query.limit).map(::row),
                    "total" to resolved.total, "definitionLimit" to resolved.limit,
                    "offset" to query.offset, "nextOffset" to nextOffset(resolved.rows.size, query), "truncated" to truncated(resolved.rows.size, query))
            }
            "dashboard" -> {
                readRequire(query.offset == 0 && query.limit == 50, ReadFailureCode.INVALID_REQUEST, "ダッシュボードにはページ範囲を指定できません")
                val view = requireNotNull(catalog.dashboard(definition.id))
                Dashboards.report(view, Dashboards.resolve(view, date, catalog, workspace.index, ::schema, ::tree))
            }
            "chart" -> {
                readRequire(query.offset == 0 && query.limit == 50, ReadFailureCode.INVALID_REQUEST, "チャートにはページ範囲を指定できません")
                Views.resolveExtension(requireNotNull(catalog.extensionView(definition.id)), catalog, workspace.index, ::tree, ::schema).report()
            }
            else -> throw ReadFailure(ReadFailureCode.UNSUPPORTED_VIEW, "この種類のビューは API の対象外です")
        }
        return mapOf("id" to definition.id, "kind" to definition.viewKind, "origin" to origin(definition.source, emptyList()), "report" to report)
    }

    private fun row(row: VirtualRow): Map<String, Any?> = mapOf(
        "key" to row.key, "id" to row.id, "title" to row.title,
        "origins" to row.occurrences.map { origin(it.document, it.path) },
        "cells" to row.cells.map { cell -> mapOf(
            "label" to cell.column.label, "value" to cell.value?.let(YamlIO::toAny),
            "origin" to cell.document?.let { origin(it, cell.path ?: emptyList()) }, "computed" to cell.computed,
            "trace" to cell.trace?.let { trace -> mapOf("raw" to trace.raw, "inputs" to trace.inputs.map { input -> mapOf(
                "placeholder" to input.placeholder, "value" to input.value,
                "origin" to input.document?.let { origin(it, input.path ?: emptyList()) },
            ) }) },
            "backlinks" to cell.backlinks?.map { mapOf("id" to it.sourceId, "origin" to origin(it.document, it.path)) },
        ) },
    )

    private fun validation(id: DocumentId): Map<String, Any?> {
        val result = Validator.validateDetailed(tree(id), parsed(id).exceptionOrNull()?.message, id, schema(id), workspace.index,
            indexed = !layout.indexExcluded(id.value), loader = ::tree, allSchemas = workspace.schemas,
            relations = workspace.relations, enforcement = workspace.enforcement, shareEvaluations = true, files = layout.files)
        // file 型の実ファイルの存在は YAML のスナップショットに含まれないので調べず、応答に明示する。
        return mapOf("document" to id.value,
            "issues" to result.issues.map { issue -> mapOf(
                "severity" to issue.severity.name.lowercase(), "message" to issue.message, "origin" to origin(id, issue.path),
                "source" to issue.source.let { source -> mapOf("identity" to source.identity,
                    "rule" to source.rule?.key, "ruleId" to source.ruleId, "kind" to source.kind,
                    "subjectId" to source.subjectId, "within" to source.within, "delivery" to source.delivery.name.lowercase()) },
            ) },
            "withheld" to result.withheld.map { mapOf("message" to it.text, "origin" to origin(id, it.path),
                "rule" to it.rule?.key, "ruleId" to it.ruleId, "kind" to it.kind, "subjectId" to it.subjectId) },
        )
    }

    private fun origin(document: DocumentId, path: YPath) = mapOf("document" to document.value, "path" to path.pretty())
    private fun nextOffset(size: Int, query: ReadQuery): Int? = (query.offset + query.limit).takeIf { it < size && it <= 10_000 }
    private fun truncated(size: Int, query: ReadQuery): Boolean = size > query.offset + query.limit && query.offset + query.limit > 10_000
    private fun page(items: List<Any?>, query: ReadQuery): Map<String, Any?> = mapOf(
        "items" to items.drop(query.offset).take(query.limit), "offset" to query.offset, "nextOffset" to nextOffset(items.size, query), "truncated" to truncated(items.size, query),
    )
}
