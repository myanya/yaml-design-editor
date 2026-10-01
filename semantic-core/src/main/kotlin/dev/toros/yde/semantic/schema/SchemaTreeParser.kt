package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.structure.UnknownFieldsPolicy

/**
 * スキーマ文書の構造部分のパース（3層コア移行 Phase 3・YM-004c で SchemaLoader から分離）。
 * フィールド木（fields / item / value / cases / 制約語彙）・definitions・root・phases だけを扱い、
 * 意味宣言（entity / relations / coverage / consistency / constraints / derive）・match・
 * ワークスペース探索・`Path` はルート側 SchemaLoader が担う（ADR-0002）。
 */
object SchemaTreeParser {

    /**
     * スキーマYAMLテキストから構造コアを組み立てる（yaml-core 単体利用・テスト用の入口）。
     * fallbackId は `id:` 未宣言時の補完（通常はファイル名由来。上位層が与える）。
     * derive 宣言スキーマの root 省略は原文に `derive:` があるかどうかだけで判定し、derive の内容は解釈しない。
     */
    fun parse(text: String, fallbackId: String): SchemaTree {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
        val id = raw["id"]?.toString() ?: fallbackId
        val kind = SchemaKind.parse(raw["kind"]?.toString())
        val definitions = parseDefinitions(raw["definitions"], id)
        val root = parseRoot(raw["root"], kind, id, allowMissingRoot = raw["derive"] != null)
        return SchemaTree(id = id, root = root, definitions = definitions, phases = parsePhases(raw["phases"]))
    }

    /** definitions ブロックのパース。 */
    fun parseDefinitions(raw: Any?, moduleId: String): Map<String, FieldDef> =
        (raw as? Map<*, *>)?.entries?.associate { (name, value) ->
            val definition = value as? Map<*, *> ?: error("definitions.${name}: objectである必要があります")
            name.toString() to parseField(definition, key = null, definitionScope = moduleId)
        } ?: emptyMap()

    /** root ブロックのパース。allowMissingRoot は derive 宣言スキーマの root 省略（共通フィールドがなければ空の object）。 */
    fun parseRoot(raw: Any?, kind: SchemaKind, moduleId: String, allowMissingRoot: Boolean): FieldDef {
        if (kind != SchemaKind.DOCUMENT) {
            require(raw == null) { "${kind.id} スキーマに root は定義できません" }
            return FieldDef(type = FieldType.OBJECT, definitionScope = moduleId)
        }
        if (allowMissingRoot && raw == null) return FieldDef(type = FieldType.OBJECT, definitionScope = moduleId)
        val rootMap = raw as? Map<*, *> ?: error("root定義が必要です")
        return parseField(rootMap, key = null, definitionScope = moduleId)
    }

    /** phases 宣言（P7）のパース。 */
    fun parsePhases(raw: Any?): PhasesDef? = when (raw) {
        null -> null
        is List<*> -> PhasesDef(order = raw.map { it.toString() })
        is Map<*, *> -> PhasesDef(
            key = raw["key"]?.toString() ?: "phase",
            order = (raw["order"] as? List<*>)?.map { it.toString() } ?: error("phases.order（フェーズの順序リスト）が必要です"),
        )
        else -> error("phases はフェーズのリスト、または key / order を持つ object である必要があります")
    }

    /** フィールド定義1件のパース（再帰）。 */
    fun parseField(m: Map<*, *>?, key: String?, definitionScope: String): FieldDef {
        if (m == null) return FieldDef(key = key, definitionScope = definitionScope)
        val typeText = m["type"]?.toString()
        // 型の名前（P33）: 基底の種類でなければ定義の名前として扱う（解決は $ref と同じ。見つからなければ定義の点検が「未知の型」にする）
        val baseType = typeText?.let { t -> FieldType.entries.firstOrNull { it.id == t } }
        val namedType = if (typeText != null && baseType == null) typeText else null
        val explicitRef = (m["\$ref"] ?: m["ref"])?.toString()
        require(namedType == null || explicitRef == null) { "type に型の名前（$namedType）を書くときは \$ref と併用できません" }
        require(namedType == null || !namedType.startsWith("$") && namedType.isNotBlank()) { "型の名前が不正です: $namedType" }
        val type = baseType ?: FieldType.STRING
        val fields = (m["fields"] as? List<*>)?.mapIndexed { index, f ->
            val child = f as? Map<*, *> ?: error("fields[$index]はobjectである必要があります")
            parseField(child, child["key"]?.toString(), definitionScope)
        } ?: emptyList()
        val item = m["item"]?.let {
            parseField(it as? Map<*, *> ?: error("itemはobjectである必要があります"), null, definitionScope)
        }
        val value = m["value"]?.let {
            parseField(it as? Map<*, *> ?: error("valueはobjectである必要があります"), null, definitionScope)
        }
        // enum の選択肢: 文字列、または {value, label, description} の object（リッチ選択肢。R10-03）
        val options = (m["values"] as? List<*>)?.mapIndexed { index, entry ->
            when (entry) {
                is Map<*, *> -> EnumOption(
                    value = entry["value"]?.toString() ?: error("values[$index] には value が必要です"),
                    label = entry["label"]?.toString(),
                    description = entry["description"]?.toString(),
                )
                else -> EnumOption(entry.toString())
            }
        } ?: emptyList()
        val cases = when (val rawCases = m["cases"]) {
            null -> emptyMap()
            is Map<*, *> -> rawCases.entries.associate { (caseValue, defs) ->
                val list = defs as? List<*> ?: error("cases.${caseValue}はフィールド定義のリストである必要があります")
                caseValue.toString() to list.mapIndexed { index, f ->
                    val child = f as? Map<*, *> ?: error("cases.${caseValue}[$index]はobjectである必要があります")
                    parseField(child, child["key"]?.toString(), definitionScope)
                }
            }
            else -> error("casesはobject（enum値 → フィールド定義リスト）である必要があります")
        }
        val idPatterns = when (val rawPatterns = m["idPatterns"] ?: m["idPattern"]) {
            is List<*> -> rawPatterns.map { it.toString() }
            null -> emptyList()
            else -> listOf(rawPatterns.toString())
        }
        val scopeMap = m["scope"] as? Map<*, *>
        fun stringList(key: String): List<String> = when (val value = scopeMap?.get(key)) {
            is List<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
        return FieldDef(
            key = key,
            type = type,
            required = (m["required"] as? Boolean) ?: false,
            requiredFrom = m["requiredFrom"]?.toString(),
            expression = (m["expression"] as? Boolean) ?: false,
            values = options.map { it.value },
            role = FieldRole.parse(m["role"]?.toString()),
            cases = cases,
            item = item,
            value = value,
            fields = fields,
            idPatterns = idPatterns,
            scope = RefScope(
                schemaIds = stringList("schemaIds"),
                documentIds = stringList("documentIds"),
            ),
            relation = m["relation"]?.toString(),
            refTarget = m["target"]?.let {
                // 型の名前を書いた項目は、基底の種類が解決まで分からない（ref でなければ定義の点検が指摘する）
                require(type == FieldType.REF || namedType != null) { "target（参照対象種別）は type: ref のフィールドでのみ定義できます" }
                RefTarget.parse(it.toString())
            } ?: RefTarget.ENTITY,
            minLength = (m["minLength"] as? Number)?.toInt(),
            maxLength = (m["maxLength"] as? Number)?.toInt(),
            pattern = m["pattern"]?.toString(),
            min = (m["min"] as? Number)?.toDouble(),
            max = (m["max"] as? Number)?.toDouble(),
            ref = explicitRef ?: namedType,
            unknownFields = m["unknownFields"]?.toString()?.let { raw ->
                UnknownFieldsPolicy.parse(raw) ?: error("unknownFields は allow / warning / error のいずれかである必要があります: $raw")
            },
            definitionScope = definitionScope,
            typeName = namedType,
            hints = displayHints(m, type, namedType, options),
        )
    }

    /**
     * 表示のヒント。display は型で意味が変わる: array / map は一覧の見せ方（table / list）、int / number は桁区切り（grouped）。
     * 型の名前を書いた項目は基底の種類が解決まで分からないので、書かれた値で見分ける（grouped なら桁区切り）。
     */
    private fun displayHints(m: Map<*, *>, type: FieldType, namedType: String?, options: List<EnumOption>): FieldHints {
        val displayRaw = m["display"]?.toString()
        val numeric = type == FieldType.INT || type == FieldType.NUMBER
        val numberDisplay = when {
            numeric -> NumberDisplay.parse(displayRaw)
            namedType != null && displayRaw == NumberDisplay.GROUPED.id -> NumberDisplay.GROUPED
            else -> null
        }
        return FieldHints(
            label = m["label"]?.toString(),
            description = m["description"]?.toString(),
            options = options,
            display = if (numeric || numberDisplay != null) null else CollectionDisplay.parse(displayRaw),
            numberDisplay = numberDisplay,
            default = m["default"],
        )
    }
}
