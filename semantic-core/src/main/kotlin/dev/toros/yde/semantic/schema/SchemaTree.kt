package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.structure.UnknownFieldsPolicy

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

    /**
     * 型の名前（`名前` / `module#名前`。組込みの型を含む）を、項目に `type: 名前` と書いたのと同じ規則で解決する。
     * 見つからない・連鎖が切れる名前は null。エンティティ型の宣言（idType）など、項目の外から型を引くときに使う。
     */
    fun resolveTypeName(name: String): FieldDef? =
        resolve(FieldDef(ref = name, typeName = name))?.takeIf { it.ref == null }

    /**
     * `$ref` と型の名前（`type: 名前`）を1段ずつ解決する。循環は打ち切る（型の連鎖の循環は、定義の点検が誤りにする。
     * object の再帰は fields / item / value を通るので、ここでは循環にならない）。
     * 合成は「狭めるだけ」（P33 の規則）: 使う側（型から型へ、も同じ）の決まりは定義の決まりと合わせ、緩めない。
     * 緩める書き方は定義の点検（SchemaDefinitionChecks）が誤りにする。ここでの合成は、誤りがあっても広がらない向きに倒す。
     * 解決した項目は型の連鎖（lineage）を持つ。
     */
    fun resolve(field: FieldDef?): FieldDef? {
        var current = field ?: return null
        val seen = mutableSetOf<DefinitionKey>()
        while (current.ref != null) {
            val (key, target) = definitionTarget(current) ?: return current
            if (!seen.add(key)) return current
            current = narrow(current, target, key)
        }
        return current
    }

    /** 使う側 use を、定義 def の上に「狭めるだけ」で合成する（1段）。 */
    private fun narrow(use: FieldDef, def: FieldDef, key: DefinitionKey): FieldDef =
        narrowOnto(use, def, typeName = use.typeName, lineage = use.lineage + key.toString())

    /**
     * object の子の合成（オブジェクト型。P33 第三弾）: 定義に無いキーは末尾に足し、定義にあるキーは「同じ項目を狭める」
     * （熟練プログラマの直感＝サブインターフェースで同じ項目を書き直すと、狭い型しか許されない）。位置は定義の場所のまま。
     */
    private fun mergeFields(use: List<FieldDef>, def: List<FieldDef>): List<FieldDef> {
        if (use.isEmpty()) return def
        val byKey = use.filter { it.key != null }.associateBy { it.key!! }
        val defKeys = def.mapNotNull { it.key }.toSet()
        val merged = def.map { d -> d.key?.let { byKey[it] }?.let { u -> mergeChild(u, d) } ?: d }
        return merged + use.filter { it.key == null || it.key !in defKeys }
    }

    /**
     * 同じキーの子の合成。型を省いた書き直し（typeGiven でない）は定義の型のまま決まりを狭める。型を書いた書き直しは、
     * 使う側の型（定義側の型を狭めた型であることは定義の点検が確かめる）に、定義側の決まりを重ねる。
     */
    private fun mergeChild(u: FieldDef, d: FieldDef): FieldDef {
        if (!u.typeGiven) {
            val use = u.copy(type = d.type, ref = d.ref, typeName = d.typeName, definitionScope = d.definitionScope)
            return narrowOnto(use, d, typeName = d.typeName, lineage = d.lineage)
        }
        val base = d.copy(type = u.type, ref = u.ref, typeName = u.typeName, definitionScope = u.definitionScope, lineage = emptyList())
        return narrowOnto(u, base, typeName = u.typeName, lineage = u.lineage)
    }

    /** 使う側 use を、定義 def の上に「狭めるだけ」で合成する。typeName と lineage は合成した項目のもの。 */
    private fun narrowOnto(use: FieldDef, def: FieldDef, typeName: String?, lineage: List<String>): FieldDef {
        // 選択肢は部分集合（定義に無い値は落とす。誤りは定義の点検が報告する）
        val values = when {
            use.values.isEmpty() -> def.values
            def.values.isEmpty() -> use.values
            else -> use.values.filter { it in def.values }
        }
        val hints = use.hints.over(def.hints).let { h ->
            val options = use.options.ifEmpty { def.options }.filter { values.isEmpty() || it.value in values }
            h.copy(options = options, default = use.default ?: def.default)
        }
        // 参照先の絞り込み: 層を重ねる（すべての層に合う ID だけ）。範囲は共通部分（空になる組合せは定義の点検が誤りにし、使う側の範囲を採る）
        val layers = def.idPatternLayers + use.idPatternLayers
        return def.copy(
            key = use.key ?: def.key,
            typeName = typeName,
            required = use.required || def.required,
            requiredFrom = use.requiredFrom ?: def.requiredFrom,
            values = values,
            idPatterns = layers.lastOrNull().orEmpty(),
            outerIdPatterns = layers.dropLast(1),
            scope = RefScope(
                schemaIds = intersectScope(use.scope.schemaIds, def.scope.schemaIds),
                documentIds = intersectScope(use.scope.documentIds, def.scope.documentIds),
            ),
            relation = use.relation ?: def.relation,
            refTarget = if (use.refTarget == RefTarget.ENTITY) def.refTarget else use.refTarget,
            unknownFields = stricter(use.unknownFields, def.unknownFields),
            cases = def.cases.ifEmpty { use.cases },
            role = use.role ?: def.role,
            expression = use.expression || def.expression,
            hints = hints,
            minLength = maxOfNullable(use.minLength, def.minLength),
            maxLength = minOfNullable(use.maxLength, def.maxLength),
            pattern = use.pattern ?: def.pattern,
            inheritedPatterns = (def.patterns + use.inheritedPatterns).distinct(),
            min = maxOfNullable(use.min, def.min),
            max = minOfNullable(use.max, def.max),
            lineage = lineage,
            // オブジェクト型（P33 第三弾）: 項目を足す・同じ項目を狭める（object の子・配列の要素・map の値に再帰的に）。同一性は使う側が足せる（型が持てば変えられない）
            fields = mergeFields(use.fields, def.fields),
            item = use.item?.let { ui -> def.item?.let { di -> mergeChild(ui, di) } ?: ui } ?: def.item,
            value = use.value?.let { uv -> def.value?.let { dv -> mergeChild(uv, dv) } ?: uv } ?: def.value,
            entity = use.entity ?: def.entity,
            typeGiven = true,
        )
    }

    private fun intersectScope(use: List<String>, def: List<String>): List<String> = when {
        use.isEmpty() -> def
        def.isEmpty() -> use
        else -> use.filter { it in def }.ifEmpty { use }
    }

    /** 未定義の項目の扱いは厳しい方（allow < warning < error。null は warning）。両方 null なら null。 */
    private fun stricter(a: UnknownFieldsPolicy?, b: UnknownFieldsPolicy?): UnknownFieldsPolicy? {
        if (a == null && b == null) return null
        fun rank(p: UnknownFieldsPolicy?) = when (p ?: UnknownFieldsPolicy.WARNING) {
            UnknownFieldsPolicy.ALLOW -> 0
            UnknownFieldsPolicy.WARNING -> 1
            UnknownFieldsPolicy.ERROR -> 2
        }
        return if (rank(a) >= rank(b)) a ?: UnknownFieldsPolicy.WARNING else b ?: UnknownFieldsPolicy.WARNING
    }

    private fun <T : Comparable<T>> maxOfNullable(a: T?, b: T?): T? = if (a == null) b else if (b == null) a else maxOf(a, b)
    private fun <T : Comparable<T>> minOfNullable(a: T?, b: T?): T? = if (a == null) b else if (b == null) a else minOf(a, b)

    /**
     * 定義の型の連鎖（定義自身の ref を辿る）。循環していれば、通った修飾名の並び（循環の始点を末尾にもう一度）を返す。
     * 定義の点検が「型の定義が循環しています」の誤りにするのに使う。
     */
    fun typeChainCycle(definition: FieldDef): List<String>? {
        val seen = LinkedHashSet<String>()
        var current = definition
        while (current.ref != null) {
            val (key, target) = definitionTarget(current) ?: return null
            if (!seen.add(key.toString())) return seen.toList() + key.toString()
            current = target
        }
        return null
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
