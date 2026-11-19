package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/**
 * 型への参照を保ったまま、利用箇所で狭める指定を書ける子の場所を用意する。
 * 継承値は書き込まない。欠けた fields / item / value はキーと入れ物だけで、型解決は SchemaTree のまま。
 * 設計 ID・補正・採用の規則は呼び出す変換の責務。ここは元の項を変えない Term の射影である。
 */
object SchemaUses {
    fun prepare(input: YNode): YNode {
        val loaded = SchemaModels.load(input, "\$schemaUses")
        val byId = loaded.schemas.associateBy { it.id }
        val uses = mutableListOf<Map<String, Any?>>()
        val prepared = (input as YNode.Seq).items.map { node ->
            val raw = YamlIO.toAny(node) as Map<*, *>
            val schema = byId.getValue(raw["id"].toString())
            val result = raw.entries.associateTo(linkedMapOf()) { it.key.toString() to it.value }
            val tree = schema.tree
            data class Owner(val value: Map<String, String>, val path: List<String>)

            fun walk(
                written: FieldDef?, effective: FieldDef, source: Map<*, *>?, at: YPath,
                traversal: BusinessModelTraversal, owner: Owner?, field: Boolean = false,
                writable: Boolean = true, definition: String? = null, schemaEntity: Boolean = false,
            ): Map<String, Any?> {
                val own = written ?: FieldDef(key = effective.key, typeGiven = false)
                val expanded = traversal.resolve(BusinessModelTraversal.Use(own, effective), definition)
                val resolved = expanded.resolved
                val out = source.orEmpty().entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
                if (field) effective.key?.let { out["key"] = it }
                if (field && owner != null) uses += linkedMapOf(
                    "owner" to owner.value, "path" to owner.path, "schema" to schema.id,
                    "at" to at, "writable" to writable,
                )
                val entity = resolved.entity?.type ?: schema.entities.firstOrNull()?.type?.takeIf {
                    schemaEntity && resolved.type == FieldType.OBJECT && resolved.fields.any { f -> tree.resolve(f)?.type == FieldType.ID }
                }
                val childOwner = entity?.let { Owner(mapOf("entity" to it), emptyList()) } ?: owner
                fun ownerAt(vararg path: String) = childOwner?.let { it.copy(path = it.path + path) }
                val rawFields = (source?.get("fields") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().associateBy { it["key"] }
                if (expanded.fields.isNotEmpty()) out["fields"] = expanded.fields.mapIndexed { index, child ->
                    val key = child.effective.key
                    walk(own.fields.firstOrNull { it.key == key }, child.effective, rawFields[key], at + listOf("fields", index),
                        expanded.next, key?.let { ownerAt("fields", it) }, field = true, writable = writable, schemaEntity = schemaEntity)
                }
                expanded.item?.let { child ->
                    out["item"] = walk(own.item, child.effective, source?.get("item") as? Map<*, *>, at + "item",
                        expanded.next, ownerAt("item"), writable = writable, schemaEntity = schemaEntity)
                }
                expanded.value?.let { child ->
                    out["value"] = walk(own.value, child.effective, source?.get("value") as? Map<*, *>, at + "value",
                        expanded.next, ownerAt("value"), writable = writable, schemaEntity = schemaEntity)
                }
                // 現行の cases は継承元を優先する。効かない場所へ補正を書いて成功と見せない。
                // enum 型の参照だけでなく、親の object から継承した cases も利用箇所では置き換えられない。
                val inheritedCases = resolved.cases.isNotEmpty() && resolved.cases != own.cases
                if (expanded.cases.isNotEmpty()) {
                    val rawCases = source?.get("cases") as? Map<*, *>
                    val cases = expanded.cases.mapValues { (value, children) ->
                        val rawChildren = (rawCases?.get(value) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().associateBy { it["key"] }
                        children.mapIndexed { index, child ->
                            val key = child.effective.key
                            walk(own.cases[value]?.firstOrNull { it.key == key }, child.effective, rawChildren[key], at + listOf("cases", value, index),
                                expanded.next, key?.let { ownerAt("cases", value, "fields", it) }, field = true,
                                writable = writable && !inheritedCases, schemaEntity = schemaEntity)
                        }
                    }
                    if (!inheritedCases) out["cases"] = cases
                }
                return out
            }

            (raw["root"] as? Map<*, *>)?.let { root ->
                result["root"] = walk(tree.root, tree.root, root, listOf("root"), BusinessModelTraversal(tree), null,
                    schemaEntity = schema.kind == SchemaKind.DOCUMENT)
            }
            (raw["definitions"] as? Map<*, *>)?.let { definitions ->
                result["definitions"] = definitions.mapValues { (name, source) ->
                    val def = tree.definitions.getValue(name.toString())
                    val qualified = "${schema.id}#$name"
                    walk(def, def, source as? Map<*, *>, listOf("definitions", name.toString()), BusinessModelTraversal(tree),
                        Owner(mapOf("type" to qualified), emptyList()), definition = qualified)
                }
            }
            result
        }
        return YamlIO.fromAny(linkedMapOf("schemas" to prepared, "uses" to uses))
    }
}
