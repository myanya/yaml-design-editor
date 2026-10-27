package dev.toros.yde.app.tools

import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefTarget
import kotlinx.serialization.json.*

/*
 * スキーマ（FieldDef）から JSON Schema へ。ツールの引数の形（操作の受け取る値）と、LLM の構造化出力（agent.llm）が同じ変換を使う。
 * 参照の項目には候補の ID を enum 制約として埋め込める（REF_ENUM_LIMIT 件まで）。
 */

/** 参照候補1件。ID索引の1出現に対応し、REFフィールドのスコープ絞り込みに使う。 */
data class RefCandidate(
    val id: String,
    val schemaId: String?,
    val documentId: String?,
    /** 参照候補の名前空間（R21-03）: エンティティ（既定）／文書ノード（documentId）。 */
    val kind: RefTarget = RefTarget.ENTITY,
)

/** REFフィールドの idPatterns / scope 条件を満たす候補IDを返す（文書参照は文書候補の名前空間から。R21-03）。 */
fun refCandidateIds(field: FieldDef, candidates: List<RefCandidate>): List<String> =
    candidates.asSequence()
        .filter { it.kind == field.refTarget }
        .filter { c ->
            field.refTarget == RefTarget.DOCUMENT ||
                (dev.toros.yde.semantic.idMatchesLayers(c.id, field.idPatternLayers) &&
                    (field.scope.schemaIds.isEmpty() || c.schemaId in field.scope.schemaIds) &&
                    (field.scope.documentIds.isEmpty() || c.documentId in field.scope.documentIds))
        }
        .map { it.id }
        .distinct()
        .sorted()
        .toList()

/** REF候補がこの件数以下のときだけ enum 制約として埋め込む（超える場合はプロンプト内の一覧に任せる）。 */
const val REF_ENUM_LIMIT = 200

/** 項目の定義から JSON Schema を作る。`$ref` は解決し、制約語彙（長さ・パターン・選択肢・必須）を伝える。深さ 16 で打ち切る。 */
fun fieldToJsonSchema(original: FieldDef, schema: SchemaDef, depth: Int = 0, candidates: List<RefCandidate> = emptyList()): JsonObject {
    if (depth > 16) return buildJsonObject { put("type", "object"); putJsonObject("properties") {}; putJsonArray("required") {}; put("additionalProperties", false) }
    val field = schema.resolve(original) ?: original
    return buildJsonObject {
        field.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        when (field.type) {
            FieldType.STRING -> {
                put("type", "string")
                // 制約語彙（R16-01）を Structured Outputs へ伝搬する（生成値が検証を通りやすくなる）
                field.minLength?.let { put("minLength", it) }
                field.maxLength?.let { put("maxLength", it) }
                field.pattern?.let { put("pattern", it) }
            }
            // 意味の基底型（日付・時刻・日時）は文字列＋ JSON Schema の format（date / time / date-time）
            FieldType.DATE, FieldType.TIME, FieldType.DATETIME -> {
                put("type", "string")
                field.type.value?.let { put("format", it.jsonFormat) }
                field.pattern?.let { put("pattern", it) }
            }
            FieldType.ID -> put("type", "string")
            // ファイルの場所: 文字列。拡張子の制限があれば形式として伝える（書き方の案内は項目の説明が担う）
            FieldType.FILE -> {
                put("type", "string")
                if (field.extensions.isNotEmpty()) put("pattern", "\\.(" + field.extensions.joinToString("|") + ")$")
                else field.pattern?.let { put("pattern", it) }
            }
            FieldType.REF -> {
                if (field.fields.isNotEmpty()) {
                    // 属性付き参照（R20-03。architecture/graph-query.md「エンティティ型と関係レジストリ」）: {to: enum制約, 属性...} の object へ変換する
                    put("type", "object")
                    putJsonObject("properties") {
                        put("to", buildJsonObject {
                            put("type", "string")
                            val ids = refCandidateIds(field, candidates)
                            if (ids.isNotEmpty() && ids.size <= REF_ENUM_LIMIT) {
                                putJsonArray("enum") { ids.forEach(::add) }
                            }
                        })
                        field.fields.forEach { child ->
                            child.key?.let { key -> put(key, fieldToJsonSchema(child, schema, depth + 1, candidates)) }
                        }
                    }
                    putJsonArray("required") {
                        (listOf("to") + field.fields.mapNotNull { it.key }).distinct().forEach(::add)
                    }
                    put("additionalProperties", false)
                } else {
                    put("type", "string")
                    val ids = refCandidateIds(field, candidates)
                    if (ids.isNotEmpty() && ids.size <= REF_ENUM_LIMIT) {
                        putJsonArray("enum") { ids.forEach(::add) }
                    }
                }
            }
            FieldType.INT -> {
                put("type", "integer")
                field.min?.let { put("minimum", it.toLong()) }
                field.max?.let { put("maximum", it.toLong()) }
            }
            FieldType.NUMBER -> {
                put("type", "number")
                field.min?.let { put("minimum", it) }
                field.max?.let { put("maximum", it) }
            }
            FieldType.BOOLEAN -> put("type", "boolean")
            FieldType.ENUM -> {
                put("type", "string")
                putJsonArray("enum") { field.values.forEach(::add) }
            }
            FieldType.ARRAY -> {
                put("type", "array")
                put("items", field.item?.let { fieldToJsonSchema(it, schema, depth + 1, candidates) }
                    ?: buildJsonObject { put("type", "string") })
            }
            FieldType.OBJECT -> {
                val discriminators = field.fields.mapNotNull { child ->
                    val eff = schema.resolve(child) ?: child
                    if (child.key != null && eff.type == FieldType.ENUM && eff.cases.isNotEmpty()) child.key to eff else null
                }
                val single = discriminators.singleOrNull()?.takeIf { it.second.values.isNotEmpty() }
                if (single != null) {
                    // 判別 enum が1つの object は、値ごとの分岐（判別子を固定し case フィールドを加えた object）の anyOf にする
                    val (discKey, disc) = single
                    putJsonArray("anyOf") {
                        disc.values.forEach { discValue ->
                            add(buildJsonObject {
                                put("type", "object")
                                putJsonObject("properties") {
                                    field.fields.forEach { child ->
                                        child.key?.let { key ->
                                            if (key == discKey) put(key, buildJsonObject {
                                                put("type", "string")
                                                putJsonArray("enum") { add(discValue) }
                                            })
                                            else put(key, fieldToJsonSchema(child, schema, depth + 1, candidates))
                                        }
                                    }
                                    disc.cases[discValue].orEmpty().forEach { caseField ->
                                        caseField.key?.let { key -> put(key, fieldToJsonSchema(caseField, schema, depth + 1, candidates)) }
                                    }
                                }
                                putJsonArray("required") {
                                    (field.fields.mapNotNull { it.key } + disc.cases[discValue].orEmpty().mapNotNull { it.key })
                                        .distinct().forEach(::add)
                                }
                                put("additionalProperties", false)
                            })
                        }
                    }
                } else {
                    // 判別 enum が複数の object は厳密な分岐を諦め、全 case フィールドの和集合で受ける
                    put("type", "object")
                    val union = LinkedHashMap<String, FieldDef>()
                    field.fields.forEach { child -> child.key?.let { union[it] = child } }
                    discriminators.forEach { (_, disc) ->
                        disc.cases.values.flatten().forEach { caseField -> caseField.key?.let { union.putIfAbsent(it, caseField) } }
                    }
                    putJsonObject("properties") {
                        union.forEach { (key, child) -> put(key, fieldToJsonSchema(child, schema, depth + 1, candidates)) }
                    }
                    putJsonArray("required") { union.keys.forEach(::add) }
                    put("additionalProperties", false)
                }
            }
            FieldType.MAP -> {
                put("type", "object")
                put("additionalProperties", field.value?.let { fieldToJsonSchema(it, schema, depth + 1, candidates) }
                    ?: buildJsonObject { put("type", "string") })
            }
            // any を含むスキーマは containsMap で構造化出力が無効化されるため、ここは保険の文字列扱い
            FieldType.ANY -> put("type", "string")
        }
    }
}

/** 構造化出力（Structured Outputs）で表現できない型（map / any）を含むか。含む場合はプロンプト指示へフォールバックする。 */
fun containsMap(original: FieldDef, schema: SchemaDef, depth: Int = 0): Boolean {
    if (depth > 16) return false
    val field = schema.resolve(original) ?: original
    return when (field.type) {
        FieldType.MAP, FieldType.ANY -> true
        FieldType.ARRAY -> field.item?.let { containsMap(it, schema, depth + 1) } == true
        FieldType.OBJECT -> field.fields.any { containsMap(it, schema, depth + 1) }
        FieldType.ENUM -> field.cases.values.flatten().any { containsMap(it, schema, depth + 1) }
        // 属性付き参照（R20-03）: 属性に map / any を含む場合も構造化出力を無効化する
        FieldType.REF -> field.fields.any { containsMap(it, schema, depth + 1) }
        else -> false
    }
}
