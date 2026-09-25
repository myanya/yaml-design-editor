package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText

/**
 * 利用者が書くスキーマ1件の、項目の木と、その読み方（`$ref` 解決・cases 合成・フェーズに応じた必須・パス→定義解決）。
 * 走査（ID と参照）・フォーム・AI 生成・リファクタリングなどが、この木を読む。
 * 名前は 3層コア移行（YM-004b。当時は yaml-core の構造コア）のままだが、P27 段C の C-2（2026-09-21）で semantic-core へ移した
 * ——ID・参照・フェーズといった意味を持つ木だからである。**構造の検証には、この木をそのまま使わない**: この木から導出した
 * 最小の構造のスキーマ（yaml-core の structure パッケージ。入口は workspace-core の DerivedStructures）で行う（architecture/schema.md「構造スキーマの導出」）。
 * 意味宣言（entity / relation / 検査）・match・ワークスペース・`Path` は持たない（ADR-0002）。
 * ルート側の `SchemaDef` は `SchemaDef.tree` でこの型へ委譲する。
 */
data class SchemaTree(
    val id: String,
    val root: FieldDef,
    val definitions: Map<String, FieldDef> = emptyMap(),
    val phases: PhasesDef? = null,
    val definitionCatalog: SchemaCatalog = SchemaCatalog.local(id, definitions),
) {
    /** `$ref` の参照先。非修飾名はフィールドの定義元、修飾名は `module#name` から引く。 */
    fun definitionTarget(field: FieldDef): Pair<DefinitionKey, FieldDef>? {
        val ref = field.ref ?: return null
        return definitionCatalog.find(ref, field.definitionScope ?: id)
    }

    /**
     * フィールドが参照する named definition の修飾名（`module#name`）。`$ref` でなければ null。
     * ビュー定義の一級参照・リント（R14-02）が「このフィールドはどの定義か」を外部から判定するのに使う。
     */
    fun definitionRefOf(field: FieldDef?): String? = field?.let { f -> definitionTarget(f)?.first?.toString() }

    /** $ref を1段ずつ解決する。循環はデータ走査側が有限なので許容する。 */
    fun resolve(field: FieldDef?): FieldDef? {
        var current = field ?: return null
        val seen = mutableSetOf<DefinitionKey>()
        while (current.ref != null) {
            val (key, target) = definitionTarget(current) ?: return current
            if (!seen.add(key)) return current
            current = target.copy(
                key = current.key ?: target.key,
                required = current.required || target.required,
                requiredFrom = current.requiredFrom ?: target.requiredFrom,
                idPatterns = current.idPatterns.ifEmpty { target.idPatterns },
                scope = if (current.scope == RefScope()) target.scope else current.scope,
                relation = current.relation ?: target.relation,
                refTarget = if (current.refTarget == RefTarget.ENTITY) target.refTarget else current.refTarget,
                unknownFields = current.unknownFields ?: target.unknownFields,
                cases = current.cases.ifEmpty { target.cases },
                role = current.role ?: target.role,
                expression = current.expression || target.expression,
                hints = current.hints.over(target.hints),
                minLength = current.minLength ?: target.minLength,
                maxLength = current.maxLength ?: target.maxLength,
                pattern = current.pattern ?: target.pattern,
                min = current.min ?: target.min,
                max = current.max ?: target.max,
            )
        }
        return current
    }

    /** 文書ルートから現在フェーズの申告値を読む（phases 未宣言なら null）。 */
    fun currentPhaseValue(root: YNode?): String? =
        phases?.let { p -> scalarText((root as? YNode.Map)?.entries?.get(p.key)) }

    /** 現在フェーズの順序位置。未申告・未知の値は先頭フェーズ（最も緩い判定）。phases 未宣言なら null。 */
    fun currentPhaseIndex(root: YNode?): Int? =
        phases?.let { p -> p.order.indexOf(currentPhaseValue(root)).coerceAtLeast(0) }

    /**
     * フェーズを考慮した実効 required。requiredFrom は「そのフェーズ以降で必須」（累積的）。
     * 検証・進捗計算・フォーム表示はこの判定を共有する（architecture/schema.md「フェーズ」）。
     */
    fun requiredAt(field: FieldDef, phaseIndex: Int?): Boolean {
        val from = field.requiredFrom ?: return field.required
        val p = phases ?: return field.required
        if (phaseIndex == null) return field.required
        val idx = p.order.indexOf(from)
        return idx in 0..phaseIndex
    }

    /**
     * object の子フィールド一覧に、データノードの enum 値に応じた case フィールドを合成して返す。
     * object のフィールドを列挙する側（検証・フォーム・索引・AI変換・初期値生成）は必ずこの関数を通すこと。
     * case フィールドは判別フィールドの直後に挿入される。追加されたフィールド自身が cases つき enum の場合も、
     * 同じ親 object 上で続けて展開する。
     */
    fun effectiveFields(fields: List<FieldDef>, node: YNode.Map?): EffectiveFields {
        val result = mutableListOf<FieldDef>()
        val inactive = LinkedHashMap<String, InactiveCase>()
        fun append(child: FieldDef, depth: Int) {
            result += child
            // $ref を介した自己参照で不正な無限展開にならないよう、スキーマ走査と同程度の上限を設ける。
            if (depth > 32) return
            val eff = resolve(child) ?: child
            if (eff.type != FieldType.ENUM || eff.cases.isEmpty()) return
            val current = child.key?.let { k -> scalarText(node?.entries?.get(k)) }
            if (current != null) eff.cases[current].orEmpty().forEach { append(it, depth + 1) }
            for ((value, caseFields) in eff.cases) {
                if (value == current) continue
                for (caseField in caseFields) {
                    val key = caseField.key ?: continue
                    val enabling = eff.values.filter { v -> eff.cases[v].orEmpty().any { it.key == key } }
                        .ifEmpty { eff.cases.filterValues { list -> list.any { it.key == key } }.keys.toList() }
                    inactive.putIfAbsent(key, InactiveCase(eff, enabling))
                }
            }
        }
        fields.forEach { append(it, 0) }
        result.forEach { it.key?.let(inactive::remove) }
        return EffectiveFields(result, inactive)
    }

    /**
     * データパスに対応するフィールド定義をスキーマを辿って解決する（object の子は effectiveFields＝
     * enum の cases 合成、$ref は resolve を通す）。ビューのセル型解決・ID横ぐし・エンティティ合成・
     * 重複所有の検証が共有する1実装（R23-01。architecture/graph-query.md「エンティティページ」）。
     */
    fun fieldAt(root: YNode, path: YPath): FieldDef? {
        var def: FieldDef = resolve(this.root) ?: return null
        var node: YNode? = root
        for (seg in path) {
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String ->
                    effectiveFields(def.fields, node as? YNode.Map).fields.firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && seg is Int -> def.item
                else -> null
            }
            def = resolve(next) ?: return null
            node = node?.let { getAt(it, listOf(seg)) }
        }
        return def
    }
}

/** 非アクティブな case 由来キーの情報（判別フィールドと、そのキーが有効になる enum 値）。 */
data class InactiveCase(val discriminator: FieldDef, val values: List<String>)

/** object の実効フィールド合成の結果。 */
data class EffectiveFields(
    val fields: List<FieldDef>,
    /** 実効フィールドに含まれないが、いずれかの case で定義されているキー → その案内情報 */
    val inactive: Map<String, InactiveCase>,
)
