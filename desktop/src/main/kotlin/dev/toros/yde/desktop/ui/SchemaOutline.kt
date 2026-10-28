package dev.toros.yde.desktop.ui

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** ナビゲーションは既存の区画・定義・項目選択へ変換するだけで、編集内容に影響しない。 */
internal data class SchemaNavigationTarget(val section: Int, val definition: String? = null, val base: YPath? = null)

internal fun schemaNavigationTarget(path: YPath): SchemaNavigationTarget = when (path.firstOrNull()) {
    "root" -> SchemaNavigationTarget(0, base = listOf("root"))
    "definitions" -> (path.getOrNull(1) as? String)?.let {
        SchemaNavigationTarget(1, it, listOf("definitions", it))
    } ?: SchemaNavigationTarget(1)
    "@settings", "id", "name", "kind", "description", "match", "phases", "coverage", "entity" -> SchemaNavigationTarget(2)
    else -> SchemaNavigationTarget(3, definition = path.firstOrNull() as? String, base = path.take(1))
}

/** 宣言した構造だけの表示用射影。参照先の展開や型の意味解釈は既存の型解決に任せる。 */
internal data class SchemaOutlineEntry(
    val path: YPath,
    val parent: YPath?,
    val node: YNode?,
    val title: String,
    val type: String,
    val depth: Int,
    val fieldPath: List<String>?,
    val allowKey: Boolean = false,
    val caseGroup: Boolean = false,
)

internal fun schemaOutline(
    node: YNode?,
    path: YPath,
    title: String,
    baseKind: (String) -> String,
    fieldPath: List<String>? = null,
): List<SchemaOutlineEntry> = buildList {
    fun visit(value: YNode?, at: YPath, parent: YPath?, name: String, depth: Int,
              dataPath: List<String>?, allowKey: Boolean = false, group: Boolean = false) {
        val map = value as? YNode.Map
        fun text(key: String) = (map?.entries?.get(key) as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }
        val ref = text("\$ref") ?: text("ref")
        val type = text("type") ?: "string"
        add(SchemaOutlineEntry(at, parent, value, text("label") ?: text("key") ?: name,
            if (group) "条件付き項目" else if (value == null) "未定義" else if (map == null) "形式を確認" else ref?.let { "参照: $it" } ?: type,
            depth, dataPath, allowKey, group))
        if (depth >= 64) return
        fun fields(seq: YNode.Seq?, prefix: YPath, owner: YPath, ownerDepth: Int, ownerData: List<String>?) {
            seq?.items?.forEachIndexed { index, child ->
                val key = ((child as? YNode.Map)?.entries?.get("key") as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }
                visit(child, prefix + index, owner, "未命名の項目", ownerDepth + 1,
                    if (key != null && ownerData != null) ownerData + key else null, allowKey = true)
            }
        }
        if (group) {
            fields(value as? YNode.Seq, at, at, depth, null)
        } else if (map != null) {
            when (baseKind(ref ?: type)) {
                "object", "ref" -> fields(map.entries["fields"] as? YNode.Seq, at + "fields", at, depth, dataPath)
                "array" -> visit(map.entries["item"], at + "item", at, "配列の要素", depth + 1, dataPath)
                "map" -> visit(map.entries["value"], at + "value", at, "マップの値", depth + 1, dataPath)
                "enum" -> (map.entries["cases"] as? YNode.Map)?.entries?.forEach { (key, seq) ->
                    visit(seq, at + listOf("cases", key), at, "「$key」のとき", depth + 1, null, group = true)
                }
            }
        }
    }
    visit(node, path, null, title, 0, fieldPath)
}

/** ソース編集・Undo で選択先が消えた場合は、存在する最も近い親へ戻る。 */
internal fun schemaOutlineSelection(entries: List<SchemaOutlineEntry>, selected: YPath?): SchemaOutlineEntry =
    entries.filter { selected != null && selected.take(it.path.size) == it.path }.maxByOrNull { it.path.size }
        ?: entries.first()

/** Undo・ソースで配列の順序が変わっても、一意なキーを持つ項目の選択を保つ。 */
internal fun schemaSelectionAfterEdit(entries: List<SchemaOutlineEntry>, selected: YPath?, previousKey: String?): SchemaOutlineEntry {
    if (selected?.lastOrNull() is Int && !previousKey.isNullOrBlank()) {
        entries.singleOrNull {
            it.path.dropLast(1) == selected.dropLast(1) &&
                ((it.node as? YNode.Map)?.entries?.get("key") as? YNode.Scalar)?.value?.toString() == previousKey
        }?.let { return it }
    }
    return schemaOutlineSelection(entries, selected)
}

/** 並べ替え時に子孫を含めて選択を同じ項目へ追随させる。 */
internal fun movedSchemaSelection(selected: YPath, sequence: YPath, from: Int, to: Int): YPath {
    if (selected.take(sequence.size) != sequence) return selected
    val index = selected.getOrNull(sequence.size) as? Int ?: return selected
    val moved = when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        to < from && index in to until from -> index + 1
        else -> index
    }
    return sequence + moved + selected.drop(sequence.size + 1)
}
