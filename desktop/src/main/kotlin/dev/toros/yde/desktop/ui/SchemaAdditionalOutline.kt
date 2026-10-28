package dev.toros.yde.desktop.ui

import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldHints
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** 追加区画はメタ定義と実データから導出し、語彙ごとのツリー実装を持たない。 */
internal fun schemaAdditionalOutline(root: YNode.Map, meta: SchemaDef?, library: Boolean): List<FormOutlineItem> =
    schemaAdditionalFields(meta?.tree, root, library).flatMap { field ->
        val key = field.key!!
        formValueOutline(root.entries[key], meta, field, listOf(key), field.displayLabel)
    }.map { item ->
        val map = item.node as? YNode.Map
        fun text(key: String) = (map?.entries?.get(key) as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }
        // スキーマの操作ID・入力キーはデータのID型ではない。Undo用の表示同一性としてのみ使う。
        val identity = listOf("id", "key").firstNotNullOfOrNull { key -> text(key)?.let { "$key:$it" } }
        val title = if (item.path.lastOrNull() is Int) text("label") ?: text("id") ?: text("key") else null
        item.copy(title = title ?: if (item.field == null) SchemaValueForms.label(item.title) else item.title, identity = identity,
            field = item.field ?: FieldDef(type = FieldType.ANY, hints = FieldHints(label = SchemaValueForms.label(item.title))))
    }

/** 移動した配列の祖先を追い、削除時は別の要素を選ばず残っている親へ戻す。 */
internal fun schemaAdditionalSelection(
    items: List<FormOutlineItem>, path: YPath?, previous: List<FormOutlineItem>,
): YPath? {
    if (path == null) return items.firstOrNull()?.path
    var mapped: YPath = emptyList()
    path.forEachIndexed { index, segment ->
        val old = previous.firstOrNull { it.path == path.take(index + 1) }
        val siblings = items.filter { it.parent == mapped }
        val oldSiblings = previous.filter { it.parent == path.take(index) }
        val identity = old?.identity?.takeIf { id -> oldSiblings.count { it.identity == id } == 1 }
        val match = if (segment is Int && old != null) {
            siblings.singleOrNull { old.node != null && it.node === old.node }
                ?: identity?.let { id -> siblings.singleOrNull { it.identity == id } }
        } else null
        if (segment is Int && old != null && match == null) {
            val oldParent = previous.firstOrNull { it.path == path.take(index) }?.node
            val sameParent = oldParent != null && items.any { it.path == mapped && it.node === oldParent }
            if (sameParent && siblings.size < oldSiblings.size || identity != null && siblings.none { it.identity == identity })
                return nearestFormPath(items.map { it.path }, mapped)
        }
        mapped = match?.path ?: (mapped + segment)
    }
    return nearestFormPath(items.map { it.path }, mapped)
}
