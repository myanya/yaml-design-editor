package dev.toros.yde.app.ui

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

