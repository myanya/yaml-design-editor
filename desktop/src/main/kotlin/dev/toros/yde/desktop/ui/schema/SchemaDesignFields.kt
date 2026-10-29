package dev.toros.yde.desktop.ui.schema

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldHints
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.yaml.model.YNode

/** 専用画面が扱う項目以外はメタスキーマから補う。未知の既存キーも隠さない。 */
internal val schemaBasicKeys = setOf("id", "name", "kind", "description", "match", "phases", "coverage", "entity", "root", "definitions")
private val documentOnlyKeys = setOf("match", "root", "phases", "coverage", "entity", "filePattern", "scaffold", "derive")

internal fun schemaAdditionalFields(tree: SchemaTree?, root: YNode.Map, library: Boolean): List<FieldDef> =
    schemaRemainingFields(tree?.root?.let { tree.resolve(it) }?.fields.orEmpty(), root, schemaBasicKeys)
        .filter { !library || it.key !in documentOnlyKeys || it.key in root.entries }

internal fun schemaRemainingFields(fields: List<FieldDef>, node: YNode.Map, handled: Set<String>): List<FieldDef> {
    val known = fields.mapNotNull { it.key }.toSet()
    return fields.filter { it.key !in handled } + node.entries.keys.filter { it !in known && it !in handled }.map {
        FieldDef(key = it, type = FieldType.ANY, hints = FieldHints(label = it))
    }
}

/** 型別の専用欄が担当するキー。残りは型の cases を含むメタ定義から編集する。 */
internal fun schemaFieldHandledKeys(type: String, allowKey: Boolean, referenced: Boolean): Set<String> {
    val common = setOf("key", "label", "type", "\$ref", "ref", "description") +
        if (allowKey) setOf("required", "requiredFrom", "role") else emptySet()
    if (referenced) return common
    val specific = when (type) {
        "object" -> setOf("fields", "entity", "unknownFields")
        "array" -> setOf("item", "display")
        "map" -> setOf("value", "display")
        "enum" -> setOf("values", "cases", "default")
        "ref" -> setOf("idPattern", "idPatterns", "scope", "relation", "target", "fields")
        "id" -> setOf("pattern")
        "date", "time", "datetime" -> setOf("pattern", "format", "auto")
        "string" -> setOf("pattern", "minLength", "maxLength", "display", "rows")
        "int" -> setOf("min", "max", "format", "auto")
        "number" -> setOf("min", "max", "format")
        else -> emptySet()
    }
    val scalar = if (type !in setOf("object", "array", "map", "enum")) setOf("default") else emptySet()
    val hints = if (type in setOf("string", "int", "number", "date", "time", "datetime", "id", "ref", "file"))
        setOf("placeholder", "sample") else emptySet()
    return common + specific + scalar + hints
}

internal fun schemaFieldAdditionalFields(tree: SchemaTree?, node: YNode.Map, type: String, allowKey: Boolean, referenced: Boolean): List<FieldDef> {
    val definition = tree?.definitions?.get("field")?.let { tree.resolve(it) }
    // 未保存の名前付き型にも基底の入力欄を出す。表示用の写しだけを使い、原本の type は変えない。
    val shape = YNode.Map(LinkedHashMap(node.entries).apply { put("type", YNode.Scalar(type)) })
    val fields = definition?.let { tree.effectiveFields(it.fields, shape).fields }.orEmpty()
    val handled = schemaFieldHandledKeys(type, allowKey, referenced).toMutableSet()
    // 複合値はスカラー専用の欄では表せないため、共通フォームへ回す。
    listOf("default", "sample").forEach { if (node.entries[it] is YNode.Map || node.entries[it] is YNode.Seq) handled.remove(it) }
    return schemaRemainingFields(fields, node, handled)
}
