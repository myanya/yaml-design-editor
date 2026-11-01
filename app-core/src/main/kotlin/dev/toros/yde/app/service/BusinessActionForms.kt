package dev.toros.yde.app.service

import dev.toros.yde.semantic.schema.*
import dev.toros.yde.workspace.*

/** 操作定義の表示用射影。初期値・前提・送信値の意味は既存の計画器と ActionInput が所有する。 */
internal object BusinessActionForms {
    fun entries(session: WorkspaceSession, entity: String?, allowed: Set<String>): List<Map<String, Any?>> {
        val entries = if (entity == null) ActionPlanner.forWorkspace(session.schemas).map { it to null }
        else ActionPlanner.forEntity(session, entity).map { it to entity } +
            session.derived.semantic.graph.entities[entity]?.occurrences.orEmpty().map { it.document.value }.distinct()
                .flatMap { document -> ActionPlanner.forDocument(session, document).map { it to document } }
        return entries.filter { it.first.name in allowed }.distinctBy { it.first.name to it.second }.map { (entry, target) ->
            val availability = ActionPlanner.availability(session, ActionRequest(entry.name, target))
            val form = runCatching { fields(session, entry) }
            mapOf("name" to entry.name, "label" to (entry.label ?: entry.name), "description" to entry.description,
                "target" to target, "enabled" to (availability.enabled && form.isSuccess),
                "reason" to if (form.isFailure) "この入力形式は Web では扱えません。デスクトップで操作してください" else availability.reason)
        }
    }

    fun fields(session: WorkspaceSession, entry: ActionEntry): List<Map<String, Any?>> {
        val tree = session.schemas.single { it.id == entry.definedIn }.tree
        var count = 0
        fun field(raw: FieldDef, depth: Int): Map<String, Any?> {
            require(depth <= 8 && ++count <= 256) { "入力定義が大きすぎます" }
            val f = requireNotNull(tree.resolve(raw)) { "入力の型を解決できません" }
            require(f.cases.isEmpty() && f.type !in setOf(FieldType.ANY, FieldType.MAP)) { "Web では扱えない入力形式です" }
            return mapOf("key" to f.key, "label" to (f.label ?: f.key.orEmpty()), "type" to f.type.id,
                "required" to tree.requiredAt(f, null), "description" to f.description, "placeholder" to f.placeholder,
                "multiline" to f.multiline, "default" to f.default,
                "options" to f.values.map { mapOf("value" to it, "label" to (f.optionFor(it)?.label ?: it)) },
                "fields" to if (f.type == FieldType.OBJECT) tree.effectiveFields(f.fields, null).fields.map { field(it, depth + 1) } else emptyList(),
                "item" to if (f.type == FieldType.ARRAY) field(requireNotNull(f.item), depth + 1) else null)
        }
        return tree.effectiveFields(entry.params, null).fields.map { field(it, 0) }
    }
}
