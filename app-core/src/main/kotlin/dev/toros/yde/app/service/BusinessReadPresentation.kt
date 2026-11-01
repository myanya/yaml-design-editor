package dev.toros.yde.app.service

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.ValueFormat
import dev.toros.yde.yaml.model.*

/** 保存版の業務表示に添える情報。値の評価と参照の意味は既存のスキーマ・索引から受け取る。 */
internal class BusinessReadPresentation(
    private val workspace: Workspace, private val tree: (DocumentId) -> YNode?, private val schema: (DocumentId) -> SchemaDef?,
) {
    private val refsByDocument by lazy { workspace.index.refs.groupBy { it.document } }
    private fun field(document: DocumentId?, path: YPath?) = document?.let { d ->
        path?.let { p -> tree(d)?.let { schema(d)?.fieldAt(it, p) } }
    }

    fun value(value: YNode?, document: DocumentId?, path: YPath?): Map<String, Any?> {
        val field = field(document, path)
        val raw = scalarText(value)
        val text = raw?.let { field?.optionFor(it)?.label ?: field?.let { f -> ValueFormat.display(f, it) } ?: it }
        return mapOf("display" to text, "references" to references(document, path))
    }

    fun cell(cell: VirtualCell): Map<String, Any?> {
        val hints = value(cell.value, cell.document, cell.path).toMutableMap()
        val raw = scalarText(cell.value)
        if (raw != null) {
            val format = cell.column.format
            if (format != null) hints["display"] = ValueFormat.display(field(cell.document, cell.path)?.type ?: FieldType.ANY, format, raw)
            else cell.column.display?.let { hints["display"] = it.format(raw) }
        }
        return hints
    }

    private fun references(document: DocumentId?, path: YPath?): List<Map<String, Any?>> {
        if (document == null || path == null) return emptyList()
        return refsByDocument[document].orEmpty().filter { it.path.take(path.size) == path }.map { ref ->
            val entity = ref.targetKind == RefTarget.ENTITY
            val targets = if (entity) workspace.index.resolve(ref) else emptyList()
            mapOf("id" to ref.targetId, "title" to targets.firstOrNull()?.title, "kind" to if (entity) "entity" else "document",
                "resolved" to if (entity) targets.isNotEmpty() else null)
        }.distinct()
    }

    fun backlinks(id: String): List<Map<String, Any?>> = workspace.index.backlinksTo(setOf(id)).mapNotNull { ref ->
        workspace.index.sourceEntryOf(ref)?.let { source -> mapOf("id" to source.id, "title" to source.title, "relation" to ref.relation) }
    }.distinct()
}
