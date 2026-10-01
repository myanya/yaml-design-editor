package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.EnumOption
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.semantic.schema.SchemaTree

/**
 * メタスキーマへの型の名前の合成（P33）。スキーマ文書の項目の `type:` には、基底の種類のほかに型の名前
 * （組込みの型 `text`・利用者の型 `金額` / `common#金額`）を書ける。メタスキーマ（builtin-schema）はそれを知らないので、
 * 読み込み時に、カタログにある型の名前を `type` の選択肢へ足し、その名前で書けるキー（`cases`）に基底の種類の分を写す
 * （語彙のキーを足す CheckCatalog.withVocabularyKeys と同じ流儀）。
 */
object MetaSchemaTypes {

    /**
     * 型の名前 → 基底の種類（連鎖の末端）。カタログの全モジュールの、入れ物（object / array / map）でない定義。
     * 名前は `モジュール#名前` と非修飾の `名前` の両方（非修飾は、自分のモジュールと組込みの型の書き方。同じ名前が複数あれば先勝ち）。
     */
    fun typeNames(catalog: SchemaCatalog): Map<String, FieldType> {
        val result = LinkedHashMap<String, FieldType>()
        for ((moduleId, definitions) in catalog.modules()) {
            if (definitions.isEmpty()) continue
            val tree = SchemaTree(moduleId, FieldDef(type = FieldType.OBJECT), definitions, definitionCatalog = catalog)
            for ((name, definition) in definitions) {
                val base = tree.resolve(definition) ?: definition
                if (base.type.isContainer) continue
                result.putIfAbsent("$moduleId#$name", base.type)
                result.putIfAbsent(name, base.type)
            }
        }
        return result
    }

    /**
     * メタスキーマの項目の定義（definitions.field）の `type` に、型の名前を足す（選択肢・表示の選択肢・`cases`）。
     * 既にある名前は足さない。形が想定と違えば（利用者が置き換えたメタスキーマなど）そのまま返す。
     */
    fun withTypeNames(meta: SchemaTree, names: Map<String, FieldType>): SchemaTree {
        if (names.isEmpty()) return meta
        val fieldDef = meta.definitions["field"] ?: return meta
        val typeField = fieldDef.fields.firstOrNull { it.key == "type" && it.type == FieldType.ENUM } ?: return meta
        val existing = typeField.values.toSet()
        val added = names.filterKeys { it !in existing }
        if (added.isEmpty()) return meta
        val cases = LinkedHashMap(typeField.cases)
        added.forEach { (name, base) -> typeField.cases[base.id]?.let { cases[name] = it } }
        val composed = typeField.copy(
            values = typeField.values + added.keys,
            cases = cases,
            hints = typeField.hints.copy(
                options = typeField.enumOptions() + added.map { (name, base) -> EnumOption(name, description = "型の名前（基底の種類は ${base.id}）") },
            ),
        )
        val newFields = fieldDef.fields.map { if (it === typeField) composed else it }
        return meta.copy(definitions = meta.definitions + ("field" to fieldDef.copy(fields = newFields)))
    }
}
