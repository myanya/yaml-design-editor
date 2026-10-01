package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.YamlIO

/**
 * スキーマの項目の木（FieldDef）を、利用者が書く形の YAML の項へ書き出す（SchemaTreeParser の逆。P27 段C の C-1）。
 * 構造のスキーマの導出（YAML から YAML への変換）の入力を作るのに使う——ファイルに書かれたスキーマだけでなく、
 * 読み込みの後でプログラムが組み立てたスキーマ（派生スキーマ `derive:`・語彙のキーを足したメタスキーマ）も、同じ導出を通せるようにする。
 * 書き出すのは、既定値と違う項目だけ。別のモジュールで定義された項目の非修飾の `$ref` は、`モジュール#名前` に直して書き出す
 * （導出した構造のスキーマは、項目ごとの「定義元のモジュール」を持たないため）。
 */
object FieldYaml {

    /** スキーマ1件（id・root・definitions）の項。root が null なら root を書かない（定義だけのモジュール）。 */
    fun schemaTerm(id: String, root: FieldDef?, definitions: Map<String, FieldDef>): YNode {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = id
        root?.let { m["root"] = fieldRaw(it, id) }
        if (definitions.isNotEmpty()) m["definitions"] = definitions.mapValues { (_, d) -> fieldRaw(d, id) }
        return YamlIO.fromAny(m)
    }

    /** 項目1つ（子の項目を含む）。moduleId は、書き出し先のモジュール（非修飾の `$ref` をそのまま書いてよいかの判定に使う）。 */
    fun fieldRaw(f: FieldDef, moduleId: String): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        f.key?.let { m["key"] = it }
        f.label?.let { m["label"] = it }
        // 定義への参照では、型は定義の側で決まる。型の名前（type: 名前）は、書かれたとおりに書き出す
        val typeName = f.typeName
        if (typeName != null) m["type"] = qualify(typeName, f.definitionScope, moduleId)
        else if (f.ref == null) m["type"] = f.type.id
        if (f.required) m["required"] = true
        if (f.expression) m["expression"] = true
        f.requiredFrom?.let { m["requiredFrom"] = it }
        if (f.values.isNotEmpty()) {
            m["values"] = f.enumOptions().map { o ->
                if (o.label == null && o.description == null) o.value
                else linkedMapOf<String, Any?>("value" to o.value, "label" to o.label, "description" to o.description).filterValues { it != null }
            }
        }
        f.role?.let { m["role"] = it.id }
        f.display?.let { m["display"] = it.id }
        if (f.cases.isNotEmpty()) m["cases"] = f.cases.mapValues { (_, list) -> list.map { fieldRaw(it, moduleId) } }
        f.item?.let { m["item"] = fieldRaw(it, moduleId) }
        f.value?.let { m["value"] = fieldRaw(it, moduleId) }
        if (f.fields.isNotEmpty()) m["fields"] = f.fields.map { fieldRaw(it, moduleId) }
        if (f.idPatterns.isNotEmpty()) m["idPatterns"] = f.idPatterns
        if (f.scope != RefScope()) {
            m["scope"] = linkedMapOf<String, Any?>(
                "schemaIds" to f.scope.schemaIds.takeIf { it.isNotEmpty() },
                "documentIds" to f.scope.documentIds.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        }
        f.relation?.let { m["relation"] = it }
        if (f.refTarget != RefTarget.ENTITY) m["target"] = f.refTarget.id
        f.minLength?.let { m["minLength"] = it }
        f.maxLength?.let { m["maxLength"] = it }
        // 形式: 型の連鎖から引き継いだものがあれば一覧（すべてに一致）、無ければ書かれたとおり
        val patterns = f.patterns
        if (patterns.size > 1) m["pattern"] = patterns else f.pattern?.let { m["pattern"] = it }
        f.min?.let { m["min"] = it }
        f.max?.let { m["max"] = it }
        f.default?.let { m["default"] = it }
        f.description?.let { m["description"] = it }
        if (typeName == null) f.ref?.let { ref -> m["\$ref"] = qualify(ref, f.definitionScope, moduleId) }
        f.unknownFields?.let { m["unknownFields"] = it.id }
        return m
    }

    /** 別のモジュールで定義された項目の非修飾の名前は、`モジュール#名前` に直す。 */
    private fun qualify(ref: String, scope: String?, moduleId: String): String =
        if ('#' !in ref && scope != null && scope != moduleId) "$scope#$ref" else ref
}
