package dev.toros.yde.app.ui

import dev.toros.yde.app.schema.Display
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** 編集対象の表示用射影。パスが原本位置で、型と要約の意味は既存の解決器へ委ねる。 */
internal data class FormOutlineItem(
    val path: YPath, val parent: YPath?, val title: String, val subtitle: String = "",
    val depth: Int = 0, val badge: String? = null, val node: YNode? = null,
    val field: FieldDef? = null, val identity: String? = null,
)

internal fun documentOutline(root: YNode, schema: SchemaDef?): List<FormOutlineItem> =
    formValueOutline(root, schema, schema?.root, emptyList(), "文書全体")

/** 部分木も文書全体と同じ型解決・実データの走査で表示する。 */
internal fun formValueOutline(
    root: YNode?, schema: SchemaDef?, field: FieldDef?, path: YPath, title: String,
): List<FormOutlineItem> = buildList {
    fun visit(node: YNode?, field: FieldDef?, path: YPath, parent: YPath?, title: String, depth: Int) {
        val effective = field?.let { schema?.resolve(it) ?: it }
        add(FormOutlineItem(path, parent, title, Display.inline(node, schema, effective), depth,
            if (effective?.required == true) "必須" else null, node, effective, Display.summarize(node, schema, effective).id))
        if (depth >= 64) return
        when (node) {
            is YNode.Map -> {
                val fields = if (effective?.type == FieldType.OBJECT)
                    schema?.effectiveFields(effective.fields, node)?.fields ?: effective.fields else emptyList()
                fields.forEach { child -> child.key?.let { key ->
                    visit(node.entries[key], child, path + key, path, child.displayLabel, depth + 1)
                } }
                val defined = fields.mapNotNull { it.key }.toSet()
                node.entries.filterKeys { it !in defined }.forEach { (key, value) ->
                    val child = if (effective?.type == FieldType.MAP) effective.value else null
                    visit(value, child, path + key, path, if (path.isEmpty() && key == "_document") "文書情報" else key, depth + 1)
                }
            }
            is YNode.Seq -> node.items.forEachIndexed { i, value ->
                val child = effective?.item
                val summary = Display.titleOf(value, schema, child)
                visit(value, child, path + i, path, "${i + 1}." + (summary?.let { " $it" } ?: " 要素"), depth + 1)
            }
            else -> Unit // 未作成の複合値を表示のために生成・再帰展開しない。
        }
    }
    visit(root, field, path, null, title, 0)
}

internal fun nearestFormPath(paths: Collection<YPath>, target: YPath?): YPath? =
    paths.filter { target != null && target.take(it.size) == it }.maxByOrNull { it.size } ?: paths.firstOrNull()

/** 上端の基準線を含む最も深い項目。区画間の空白では直前の項目へ追従する。 */
internal fun activeFormPath(bounds: Map<YPath, Pair<Float, Float>>, top: Float): YPath? =
    bounds.filterValues { (start, end) -> start <= top && end > top }.keys.maxByOrNull { it.size }
        ?: bounds.filterValues { it.first <= top }.maxByOrNull { it.value.first }?.key
        ?: bounds.minByOrNull { it.value.first }?.key

/** 配列を含む祖先も追随する。ノード同一性を優先し、Undo は表示解決済みの一意な ID を使う。 */
internal fun retainedFormSelection(items: List<FormOutlineItem>, path: YPath?, previous: List<FormOutlineItem>): YPath? {
    if (path == null) return items.firstOrNull()?.path
    var mapped: YPath = emptyList()
    path.forEachIndexed { index, segment ->
        val old = previous.firstOrNull { it.path == path.take(index + 1) }
        val siblings = items.filter { it.parent == mapped }
        val match = if (segment is Int && old != null) {
            siblings.singleOrNull { old.node != null && it.node === old.node }
                ?: old.identity?.let { id -> siblings.singleOrNull { it.identity == id } }
        } else null
        mapped = match?.path ?: (mapped + segment)
    }
    return nearestFormPath(items.map { it.path }, mapped)
}
