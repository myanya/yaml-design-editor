package dev.toros.yde.yaml.structure

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.structure.UnknownFieldsPolicy

/*
 * 最小の構造のスキーマ（P27 段C の C-1。ROADMAP の SC-01〜04）。
 * 利用者が書くスキーマ（意味のスキーマ）から導出され、yaml-core はこれだけを使って文書の構造を検証する。
 * 持つのは、値の形・必須・値の形式的なチェック（選択肢・範囲・文字数・形式）・選択肢の値ごとの項目（cases）・
 * 未定義の項目の扱い・定義への参照（再帰）・値の形ごとの決まり、だけ。
 * ID・参照・関係・参照先の範囲・フェーズ・表示のヒントを知らない（導出のときに、意味の側が畳むか落とす）。
 * 指摘の文面のために、項目の表示名（label）だけは持つ（SC-04）。
 */

/** 値の型。ID と参照は、構造の上では文字列（意味は、意味のスキーマだけが持つ）。 */
enum class ValueType(val id: String) {
    STRING("string"),
    TEXT("text"),
    INT("int"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ENUM("enum"),
    DATE("date"),
    ARRAY("array"),
    MAP("map"),
    OBJECT("object"),
    ANY("any"),
}

/**
 * 値の形ごとの決まり（SC-03）: スカラーならこの決まり・オブジェクトならこの決まり・配列ならこの決まり。
 * 値の形を見れば決まりが1つに決まるので、試して戻る処理は要らない（属性付き参照＝スカラーでも {to, 属性…} でも書ける、の形）。
 */
data class ValueShapes(
    val scalar: StructureField? = null,
    val obj: StructureField? = null,
    val array: StructureField? = null,
)

/** 構造のスキーマの項目1つ。 */
data class StructureField(
    val key: String? = null,
    /** 指摘の文面に使う表示名（未指定なら key）。 */
    val label: String? = null,
    val type: ValueType = ValueType.STRING,
    val required: Boolean = false,
    /** ENUM の選択肢。 */
    val values: List<String> = emptyList(),
    /** ENUM の値ごとに、親のオブジェクトへ足される項目。 */
    val cases: Map<String, List<StructureField>> = emptyMap(),
    val item: StructureField? = null,
    val value: StructureField? = null,
    val fields: List<StructureField> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val unknownFields: UnknownFieldsPolicy? = null,
    /** 定義への参照（`名前` か `モジュール#名前`）。 */
    val ref: String? = null,
    /** 非修飾の参照を引くモジュール（読み取りが付ける）。 */
    val module: String? = null,
    val shapes: ValueShapes? = null,
) {
    val displayLabel: String get() = label ?: key ?: "(root)"
}

/** 構造のスキーマ1件: ルートと、名前の付いた定義。catalog は、他のモジュールの定義も引ける一覧（モジュール → 名前 → 定義）。 */
data class StructureSchema(
    val id: String,
    val root: StructureField,
    val definitions: Map<String, StructureField> = emptyMap(),
    val catalog: Map<String, Map<String, StructureField>> = mapOf(id to definitions),
) {
    /** 定義への参照を1段ずつ解決する（使う側に書いた必須・表示名・値のチェックは、定義の側より優先）。循環は打ち切る。 */
    fun resolve(field: StructureField?): StructureField? {
        var current = field ?: return null
        val seen = mutableSetOf<Pair<String, String>>()
        while (current.ref != null) {
            val ref = current.ref!!
            val hash = ref.indexOf('#')
            val moduleId = if (hash >= 0) ref.substring(0, hash) else (current.module ?: id)
            val name = if (hash >= 0) ref.substring(hash + 1) else ref
            val target = catalog[moduleId]?.get(name) ?: return current
            if (!seen.add(moduleId to name)) return current
            current = target.copy(
                key = current.key ?: target.key,
                label = current.label ?: target.label,
                required = current.required || target.required,
                unknownFields = current.unknownFields ?: target.unknownFields,
                cases = current.cases.ifEmpty { target.cases },
                minLength = current.minLength ?: target.minLength,
                maxLength = current.maxLength ?: target.maxLength,
                pattern = current.pattern ?: target.pattern,
                min = current.min ?: target.min,
                max = current.max ?: target.max,
            )
        }
        return current
    }

    /**
     * オブジェクトの子の項目に、データの選択肢の値に応じた項目（cases）を足して返す。足した項目は、選択肢の項目の直後に入る。
     * inactive は、いまの値では使えない項目のキー → それを使えるようにする選択肢の項目と値。
     */
    fun effectiveFields(fields: List<StructureField>, node: YNode.Map?): EffectiveStructure {
        val result = mutableListOf<StructureField>()
        val inactive = LinkedHashMap<String, InactiveKey>()
        fun append(child: StructureField, depth: Int) {
            result += child
            if (depth > 32) return
            val eff = resolve(child) ?: child
            if (eff.type != ValueType.ENUM || eff.cases.isEmpty()) return
            val current = child.key?.let { k -> scalarText(node?.entries?.get(k)) }
            if (current != null) eff.cases[current].orEmpty().forEach { append(it, depth + 1) }
            for ((value, caseFields) in eff.cases) {
                if (value == current) continue
                for (caseField in caseFields) {
                    val key = caseField.key ?: continue
                    val enabling = eff.values.filter { v -> eff.cases[v].orEmpty().any { it.key == key } }
                        .ifEmpty { eff.cases.filterValues { list -> list.any { it.key == key } }.keys.toList() }
                    inactive.putIfAbsent(key, InactiveKey(eff, enabling))
                }
            }
        }
        fields.forEach { append(it, 0) }
        result.forEach { it.key?.let(inactive::remove) }
        return EffectiveStructure(result, inactive)
    }
}

/** いまの選択肢の値では使えない項目の案内（選択肢の項目と、その項目を使えるようにする値）。 */
data class InactiveKey(val discriminator: StructureField, val values: List<String>)

data class EffectiveStructure(val fields: List<StructureField>, val inactive: Map<String, InactiveKey>)

/** 構造のスキーマの読み取り（YAML を読んだままの値から）。知らないキーは誤りにする（導出の出力は、この形に閉じている）。 */
object StructureParser {
    private val TOP_KEYS = setOf("id", "root", "definitions")
    private val FIELD_KEYS = setOf(
        "key", "label", "type", "required", "values", "cases", "item", "value", "fields",
        "minLength", "maxLength", "pattern", "min", "max", "unknownFields", "\$ref", "shapes",
    )
    private val SHAPE_KEYS = setOf("scalar", "object", "array")

    fun parse(raw: Any?, fallbackId: String): StructureSchema {
        val m = raw as? Map<*, *> ?: error("構造のスキーマのルートはobjectである必要があります")
        m.keys.map { it.toString() }.firstOrNull { it !in TOP_KEYS }?.let { error("構造のスキーマ: 未知のキーです: $it") }
        val id = m["id"]?.toString() ?: fallbackId
        val definitions = parseDefinitions(m["definitions"], id)
        val root = (m["root"] as? Map<*, *>)?.let { parseField(it, null, id, "root") } ?: StructureField(type = ValueType.OBJECT, module = id)
        return StructureSchema(id, root, definitions)
    }

    fun parseDefinitions(raw: Any?, moduleId: String): Map<String, StructureField> =
        (raw as? Map<*, *>)?.entries?.associate { (name, value) ->
            val definition = value as? Map<*, *> ?: error("definitions.$name: objectである必要があります")
            name.toString() to parseField(definition, null, moduleId, "definitions.$name")
        } ?: emptyMap()

    private fun parseField(m: Map<*, *>, key: String?, moduleId: String, where: String): StructureField {
        m.keys.map { it.toString() }.firstOrNull { it !in FIELD_KEYS }?.let { error("$where: 構造のスキーマに書けないキーです: $it") }
        fun child(raw: Any?, at: String): StructureField =
            parseField(raw as? Map<*, *> ?: error("$where.$at はobjectである必要があります"), null, moduleId, "$where.$at")
        fun children(raw: Any?, at: String): List<StructureField> = (raw as? List<*>).orEmpty().mapIndexed { i, f ->
            val fm = f as? Map<*, *> ?: error("$where.$at[$i] はobjectである必要があります")
            parseField(fm, fm["key"]?.toString(), moduleId, "$where.$at[$i]")
        }
        val typeName = m["type"]?.toString()
        val shapes = (m["shapes"] as? Map<*, *>)?.let { sm ->
            sm.keys.map { it.toString() }.firstOrNull { it !in SHAPE_KEYS }?.let { error("$where.shapes: 未知の形です: $it（${SHAPE_KEYS.joinToString(" / ")}）") }
            ValueShapes(
                scalar = sm["scalar"]?.let { child(it, "shapes.scalar") },
                obj = sm["object"]?.let { child(it, "shapes.object") },
                array = sm["array"]?.let { child(it, "shapes.array") },
            )
        }
        return StructureField(
            key = key,
            label = m["label"]?.toString(),
            type = if (typeName == null) ValueType.STRING
            else ValueType.entries.firstOrNull { it.id == typeName } ?: error("$where: 構造のスキーマに無い型です: $typeName"),
            required = (m["required"] as? Boolean) ?: false,
            values = (m["values"] as? List<*>).orEmpty().map { it.toString() },
            cases = (m["cases"] as? Map<*, *>)?.entries?.associate { (v, defs) -> v.toString() to children(defs, "cases.$v") } ?: emptyMap(),
            item = m["item"]?.let { child(it, "item") },
            value = m["value"]?.let { child(it, "value") },
            fields = children(m["fields"], "fields"),
            minLength = (m["minLength"] as? Number)?.toInt(),
            maxLength = (m["maxLength"] as? Number)?.toInt(),
            pattern = m["pattern"]?.toString(),
            min = (m["min"] as? Number)?.toDouble(),
            max = (m["max"] as? Number)?.toDouble(),
            unknownFields = m["unknownFields"]?.toString()?.let { raw ->
                UnknownFieldsPolicy.parse(raw) ?: error("$where.unknownFields は allow / warning / error のいずれかである必要があります: $raw")
            },
            ref = m["\$ref"]?.toString(),
            module = moduleId,
            shapes = shapes,
        )
    }
}
