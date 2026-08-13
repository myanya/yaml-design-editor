package schema

import model.YNode

/**
 * 参照認識の命名規約（I-02）。スキーマなし文書の参照検出（index.DocScanner）と
 * スキーマ推測（R11-01）が同じ規約を共有する（独自の規約を増やさない。ARCHITECTURE §4.23）。
 * index は schema に依存するため、共有の述語は下層のこのファイルに置き、DocScanner が委譲する。
 */
object RefNaming {
    fun isRefKey(k: String): Boolean = k == "ref" || k.endsWith("_ref")

    fun isRefListKey(k: String): Boolean =
        k == "refs" || k.endsWith("_refs") || k == "depends_on" || k == "relates_to"
}

/**
 * スキーマ推測（R11-01）: 既存YAML文書群から `_schemas` 文書のドラフトを推測する純ロジック。
 * - 型推測: boolean / int / number / date / 長文 text（値の実例から合議）
 * - 参照認識: RefNaming の命名規約を流用（`ref` / `*_ref` はスカラー参照、`refs` / `depends_on` 等は参照配列）。
 *   加えて値ベース＝全値がサンプル中の論理ID（`id` キーの値）と一致するフィールドも参照とする（UX-27 追補）
 * - enum 候補: 小さく反復する文字列値の集合（IDらしい値＝既存ID一致・ID形式は除外——開いた値集合を閉じない）
 * - required 候補: 全出現に存在するキー（出現1件のみのキーは `id` を除き必須にしない）
 * 生成物は SchemaLoader.parseSchemaText でパースできるスキーマ文書ツリー（メタスキーマ検証 S-04 →
 * スキーマ設計UI S-05 で仕上げ、サンプル生成 S-06 を逆方向の検算に使う）。
 */
object SchemaInference {

    /** 推測の入力1件（ワークスペース相対パスとパース済みルート。パース不能は null で無視される）。 */
    data class Sample(val rel: String, val root: YNode?)

    /** enum 候補と見なす条件: 異なり数の上限・最小サンプル数（反復していること）。 */
    private const val ENUM_MAX_VALUES = 6
    // 3文書程度の小さなサンプルでも status / priority 等が enum になるよう 3 とする（UX-27。
    // ノイズガードは「反復していること」＝異なり数 < サンプル数の条件が担う）
    private const val ENUM_MIN_SAMPLES = 3
    private const val ENUM_MAX_VALUE_LENGTH = 40

    /** text（複数行テキスト）と推測する最小の文字数（改行を含む場合は長さに関係なく text）。 */
    private const val TEXT_MIN_LENGTH = 80

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** IDらしい値の形（英大文字始まりの英数セグメント＋末尾が数字。例 SCR-001 / TODO-12）。 */
    private val ID_LIKE_RE = Regex("""[A-Z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*-\d+""")

    /** フィールド位置ごとの値の観測結果。複数文書・複数出現をマージする。 */
    private class Stats {
        var seqs = 0
        var mapsCount = 0
        var scalarSamples = 0     // 非空のスカラー値の数
        var booleans = 0
        var ints = 0
        var numbers = 0
        var dates = 0
        var longTexts = 0
        var plainStrings = 0
        val values = LinkedHashMap<String, Int>()          // 文字列値 → 出現数（enum 候補）
        val children = LinkedHashMap<String, Stats>()      // object の子キー（出現順）
        val childPresence = LinkedHashMap<String, Int>()   // 子キー → 存在した出現数
        var items: Stats? = null                           // 配列要素

        fun itemStats(): Stats = items ?: Stats().also { items = it }
    }

    /**
     * サンプル群からスキーマ文書のドラフトツリーを推測する。
     * 返り値は `_schemas` 配下のスキーマ文書としてそのまま保存できる YNode ツリー（コメント付き）。
     */
    fun infer(samples: List<Sample>, schemaId: String, match: String, name: String = schemaId): YNode.Map {
        val roots = samples.mapNotNull { it.root }
        require(roots.isNotEmpty()) { "推測に使える文書がありません（glob に一致する YAML が必要です）" }
        val rootStats = Stats()
        roots.forEach { observe(rootStats, it, atRoot = true) }
        // サンプル中の論理ID（id キーの値）。ID値のフィールドを enum にしない・参照として推測する材料（UX-27 追補）
        val idValues = mutableSetOf<String>()
        roots.forEach { collectIdValues(it, idValues) }

        val schemaMap = YNode.Map(
            linkedMapOf<String, YNode>(
                "id" to YNode.Scalar(schemaId),
                "name" to YNode.Scalar(name),
                "match" to YNode.Scalar(match),
                "root" to buildField(rootStats, key = null, idValues = idValues),
            )
        )
        schemaMap.ensureComments().before += listOf(
            " ${roots.size} 件の文書から推測したスキーマドラフト（R11-01）",
            " 型・必須・enum の候補は実例からの推測のため、スキーマ設計画面で確認・調整してください",
        )
        return schemaMap
    }

    /** サンプル中の論理ID値を集める（`id` キーのスカラー値。索引規約 I-01 と同じ）。 */
    private fun collectIdValues(node: YNode, into: MutableSet<String>) {
        when (node) {
            is YNode.Seq -> node.items.forEach { collectIdValues(it, into) }
            is YNode.Map -> {
                (node.entries["id"] as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
                node.entries.values.forEach { collectIdValues(it, into) }
            }
            is YNode.Scalar -> Unit
        }
    }

    /** 1ノードの観測を stats へ加算する。 */
    private fun observe(stats: Stats, node: YNode, atRoot: Boolean = false) {
        when (node) {
            is YNode.Seq -> {
                stats.seqs++
                node.items.forEach { observe(stats.itemStats(), it) }
            }
            is YNode.Map -> {
                stats.mapsCount++
                for ((k, child) in node.entries) {
                    if (atRoot && k == "_document") continue  // 予約メタデータは推測対象外
                    val childStats = stats.children.getOrPut(k) { Stats() }
                    stats.childPresence[k] = (stats.childPresence[k] ?: 0) + 1
                    observe(childStats, child)
                }
            }
            is YNode.Scalar -> {
                val v = node.value
                val text = v?.toString()
                if (text.isNullOrBlank()) return
                stats.scalarSamples++
                when {
                    v is Boolean -> stats.booleans++
                    v is Int || v is Long -> stats.ints++
                    v is Number -> stats.numbers++
                    // SnakeYAML は非引用の日付を java.util.Date にパースする（YNode.Scalar の値仕様）
                    v is java.util.Date -> stats.dates++
                    DATE_RE.matches(text) -> stats.dates++
                    text.contains('\n') || text.length >= TEXT_MIN_LENGTH -> stats.longTexts++
                    else -> {
                        stats.plainStrings++
                        if (text.length <= ENUM_MAX_VALUE_LENGTH) {
                            stats.values[text] = (stats.values[text] ?: 0) + 1
                        }
                    }
                }
            }
        }
    }

    /** 観測結果からフィールド定義のツリー（スキーマ文書の1ノード）を作る。 */
    private fun buildField(stats: Stats, key: String?, idValues: Set<String>): YNode.Map {
        val field = YNode.Map()
        key?.let { field.entries["key"] = YNode.Scalar(it) }
        val structures = listOf(stats.mapsCount > 0, stats.seqs > 0, stats.scalarSamples > 0).count { it }
        when {
            // 構造が揺れている場合は any（検証しないことの明示。手で仕上げる前提のドラフト）
            structures > 1 -> field.entries["type"] = YNode.Scalar(FieldType.ANY.id)
            stats.mapsCount > 0 -> {
                field.entries["type"] = YNode.Scalar(FieldType.OBJECT.id)
                val fields = YNode.Seq()
                for ((childKey, childStats) in stats.children) {
                    fields.items += buildChildField(
                        childKey, childStats,
                        presentCount = stats.childPresence[childKey] ?: 0,
                        parentOccurrences = stats.mapsCount,
                        idValues = idValues,
                    )
                }
                field.entries["fields"] = fields
            }
            stats.seqs > 0 -> {
                field.entries["type"] = YNode.Scalar(FieldType.ARRAY.id)
                val itemStats = stats.items
                field.entries["item"] =
                    if (itemStats == null) YNode.Map(linkedMapOf<String, YNode>("type" to YNode.Scalar(FieldType.STRING.id)))
                    else buildField(itemStats, key = null, idValues = idValues)
            }
            else -> applyScalarType(field, stats, idValues)
        }
        return field
    }

    /** object の子キー1件のフィールド定義（id・参照の命名規約と required 候補はここで判定する）。 */
    private fun buildChildField(key: String, stats: Stats, presentCount: Int, parentOccurrences: Int, idValues: Set<String>): YNode.Map {
        // required 候補: 親 object の全出現に存在するキー（出現1件だけの合議は根拠が弱いため id 以外は必須にしない）
        val alwaysPresent = presentCount >= parentOccurrences
        // id キー（スカラーのみ）は論理IDとして扱う（スキーマなし文書の索引規約 I-01 と同じ）
        if (key == "id" && stats.mapsCount == 0 && stats.seqs == 0) {
            val field = YNode.Map(
                linkedMapOf<String, YNode>("key" to YNode.Scalar(key), "type" to YNode.Scalar(FieldType.ID.id))
            )
            if (alwaysPresent) field.entries["required"] = YNode.Scalar(true)
            return field
        }
        // 参照の命名規約（I-02 の流用）: ref / *_ref はスカラー参照、refs / depends_on 等は参照配列
        if (RefNaming.isRefKey(key) && stats.mapsCount == 0 && stats.seqs == 0 && stats.scalarSamples > 0) {
            val field = YNode.Map(
                linkedMapOf<String, YNode>("key" to YNode.Scalar(key), "type" to YNode.Scalar(FieldType.REF.id))
            )
            if (alwaysPresent && parentOccurrences >= 2) field.entries["required"] = YNode.Scalar(true)
            return field
        }
        if (RefNaming.isRefListKey(key) && stats.mapsCount == 0 && stats.scalarSamples == 0 && stats.seqs > 0 &&
            (stats.items?.let { it.mapsCount == 0 && it.seqs == 0 } != false)
        ) {
            return YNode.Map(
                linkedMapOf<String, YNode>(
                    "key" to YNode.Scalar(key),
                    "type" to YNode.Scalar(FieldType.ARRAY.id),
                    "item" to YNode.Map(linkedMapOf<String, YNode>("type" to YNode.Scalar(FieldType.REF.id))),
                )
            )
        }
        val field = buildField(stats, key, idValues)
        if (alwaysPresent && parentOccurrences >= 2) {
            field.entries["required"] = YNode.Scalar(true)
        }
        return field
    }

    /** スカラー値の観測からの型推測（boolean / int / number / date / text / ref / enum / string）。 */
    private fun applyScalarType(field: YNode.Map, stats: Stats, idValues: Set<String>) {
        val total = stats.scalarSamples
        val type = when {
            total == 0 -> FieldType.STRING
            stats.booleans == total -> FieldType.BOOLEAN
            stats.ints == total -> FieldType.INT
            stats.ints + stats.numbers == total -> FieldType.NUMBER
            stats.dates == total -> FieldType.DATE
            stats.longTexts > 0 && stats.longTexts + stats.plainStrings == total -> FieldType.TEXT
            else -> FieldType.STRING
        }
        // 値ベースの参照認識（UX-27 追補）: 全値がサンプル中の論理IDと一致するなら参照（命名規約 I-02 の補完）
        if (type == FieldType.STRING && stats.plainStrings == total && total > 0 &&
            stats.values.values.sum() == total && stats.values.keys.all { it in idValues }
        ) {
            field.entries["type"] = YNode.Scalar(FieldType.REF.id)
            return
        }
        // enum 候補: 純粋な短い文字列値が、小さい値集合の中で反復しているとき。
        // IDらしい値（既存IDと一致 or ID形式）は enum にしない——参照の開いた値集合を閉じてしまうため（UX-27 追補）
        if (type == FieldType.STRING && stats.plainStrings == total && total >= ENUM_MIN_SAMPLES) {
            val enumTotal = stats.values.values.sum()
            val idLike = stats.values.keys.any { it in idValues || ID_LIKE_RE.matches(it) }
            if (!idLike && stats.values.size in 1..ENUM_MAX_VALUES && enumTotal >= ENUM_MIN_SAMPLES && stats.values.size < enumTotal) {
                field.entries["type"] = YNode.Scalar(FieldType.ENUM.id)
                field.entries["values"] = YNode.Seq(stats.values.keys.map { YNode.Scalar(it) as YNode }.toMutableList())
                return
            }
        }
        field.entries["type"] = YNode.Scalar(type.id)
    }
}
