package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.ActionRequire
import dev.toros.yde.semantic.schema.RefScope
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.time.LocalDate

/** 区画のタイルの並べ方（`placement`）。auto は画面の幅と区画の数から画面が決める。 */
enum class DashboardPlacement(val id: String, val label: String) {
    AUTO("auto", "自動"),
    HORIZONTAL("horizontal", "横（折り返し）"),
    VERTICAL("vertical", "縦（左の一覧）"),
    ;

    companion object {
        fun parse(text: String?, where: String): DashboardPlacement =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 共有の入力の種類。 */
enum class DashboardInputType(val id: String, val label: String) {
    DATE("date", "日付"),
    ENUM("enum", "選択"),
    STRING("string", "文字"),
    /** 参照（候補は索引の ID と表示名。参照の項目と同じ絞り込み）。 */
    REF("ref", "参照"),
    ;

    companion object {
        fun parse(text: String?, where: String): DashboardInputType =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/**
 * 全区画に共有する入力（`inputs`。P44）。値は区画の filter と、区画が参照する表の params に `$key` で渡る。
 * 空の値は「絞らない」（その値を使う条件を外す）。default は初期値で、`$today` / `$monthStart` / `$monthEnd` は基準日から決める。
 */
data class DashboardInput(
    val key: String,
    val label: String,
    val type: DashboardInputType = DashboardInputType.STRING,
    /** 選択の候補（値と見出し。enum のとき）。 */
    val values: List<Pair<String, String?>> = emptyList(),
    val default: String? = null,
    /** 参照の候補の ID の絞り込み（type: ref。参照の項目と同じ層＝すべての層のどれかの glob に合う ID）。 */
    val refPatterns: List<List<String>> = emptyList(),
    /** 参照の候補の範囲（type: ref。参照の項目の scope と同じ）。 */
    val refScope: RefScope = RefScope(),
    /** 複数を選べる（enum / ref）。値は選んだものの並びで、受け渡しはカンマ区切りの文字（split / join）。 */
    val multiple: Boolean = false,
    /** 型の名前（`type: common#タグ参照` と書いたとき。候補と種類はその型から）。 */
    val typeName: String? = null,
) {
    /** 選択の候補を持つ種類か（enum・ref）。 */
    val choice: Boolean get() = type == DashboardInputType.ENUM || type == DashboardInputType.REF

    /** 条件へ差し込む値（複数を選べる入力は並び。空は null＝絞らない）。 */
    fun bound(text: String?): Any? = when {
        text.isNullOrBlank() -> null
        multiple -> split(text).takeIf { it.isNotEmpty() }
        else -> text
    }

    /** 基準日から決めた初期値（無ければ null＝絞らない）。 */
    fun defaultFor(date: LocalDate): String? = when (default) {
        null -> null
        "\$today" -> date.toString()
        "\$monthStart" -> date.withDayOfMonth(1).toString()
        "\$monthEnd" -> date.withDayOfMonth(date.lengthOfMonth()).toString()
        else -> default
    }

    companion object {
        /** 複数の値の受け渡しの形（カンマ区切り）を並びへ。空の要素は落とし、同じ値は 1 つにする。 */
        fun split(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun join(values: List<String>): String = values.distinct().joinToString(",")
    }
}

/** 共有の入力の候補と値の点検（画面・業務 Web・CLI・保存版の読み出しが共有する）。 */
object DashboardInputs {
    /** 選択の候補（値と見出し）。enum は定義の値、ref は索引の ID（ID の順）と表示名。その他は無し。 */
    fun options(input: DashboardInput, ids: Map<String, List<ScannedId>>): List<Pair<String, String?>> = when (input.type) {
        DashboardInputType.ENUM -> input.values
        DashboardInputType.REF -> ids.entries.asSequence()
            .filter { (id, occurrences) -> idMatchesLayers(id, input.refPatterns) && occurrences.any { inScope(it, input.refScope) } }
            .map { (id, occurrences) -> id to SemanticExpression.preferDefining(occurrences).firstNotNullOfOrNull { it.title } }
            .sortedBy { it.first }.toList()
        else -> emptyList()
    }

    private fun inScope(occurrence: ScannedId, scope: RefScope): Boolean =
        (scope.schemaIds.isEmpty() || occurrence.schemaId in scope.schemaIds) &&
            (scope.documentIds.isEmpty() || occurrence.documentId in scope.documentIds)

    /** 入力の値の誤り（日付として読めない・候補に無い）。誤りが無ければ null。空は「絞らない」で誤りではない。 */
    fun invalid(input: DashboardInput, text: String?, ids: Map<String, List<ScannedId>>): String? {
        if (text.isNullOrBlank()) return null
        val values = if (input.multiple) DashboardInput.split(text) else listOf(text)
        return when (input.type) {
            DashboardInputType.DATE -> values.firstOrNull { runCatching { LocalDate.parse(it) }.isFailure }
                ?.let { "共有の入力「${input.label}」の値「$it」は日付（YYYY-MM-DD）ではありません" }
            DashboardInputType.STRING -> null
            DashboardInputType.ENUM, DashboardInputType.REF -> {
                val known = options(input, ids).map { it.first }.toSet()
                values.firstOrNull { it !in known }?.let { "共有の入力「${input.label}」に「$it」はありません（候補から選びます）" }
            }
        }
    }
}

/** 業務の入口。原本を持たず、既存グリッドと操作の参照だけを合成する。 */
data class VirtualDashboardDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val sections: List<DashboardSection>,
    val actions: List<String> = emptyList(),
    val default: Boolean = false,
    /** 区画のタイルの並べ方。 */
    val placement: DashboardPlacement = DashboardPlacement.AUTO,
    /** 全区画に共有する入力。 */
    val inputs: List<DashboardInput> = emptyList(),
    /** 区画を段、操作を工程として並べる流れ（DashboardFlows）。 */
    val flow: DashboardFlow? = null,
) : VirtualViewDefinition

data class DashboardSection(
    val id: String,
    val label: String,
    val view: String,
    /** 操作の前提と同じ、パラメータを受け取る Query / Predicate の宣言を再利用する。 */
    val filter: ActionRequire? = null,
    /** 群の名前（同じ名前の区画を見出し付きでまとめ、畳める。null＝無名の群で先頭に並ぶ）。 */
    val group: String? = null,
)

/** 区画を群ごとに分ける。無名の群（group なし）が先頭で、名前のある群は最初に現れた順。区画の順は定義のまま。 */
fun VirtualDashboardDef.sectionGroups(): List<Pair<String?, List<DashboardSection>>> {
    val grouped = sections.groupBy { it.group }
    return listOfNotNull(grouped[null]?.let { null to it }) + grouped.entries.filter { it.key != null }.map { it.key to it.value }
}

data class DashboardPending(val row: VirtualRow, val reason: String)

data class DashboardSectionResult(
    val definition: DashboardSection,
    val view: VirtualGridDef?,
    val rows: List<VirtualRow>,
    val pending: List<DashboardPending> = emptyList(),
    val error: String? = null,
    /** 拡張の種類の区画（チャートなど。ADR-0014）: 解決した結果。view は null。 */
    val embedded: ExtensionViewResult? = null,
    /** 元の表のクエリ行が `limit` で切れていたとき（件数は切れた後の数。黙らずに示す）。 */
    val truncation: ResolvedRows? = null,
) {
    /** 件数とドリルダウンの母集団は同じ。合計行は原本を持たないため含めない（値の軸の行は数える）。拡張の種類の区画は、その結果の件数。 */
    val count: String get() = embedded?.count
        ?: SemanticExpression.aggregate(ValueExpr.AggregateFn.COUNT, rows.filter { it.occurrences.isNotEmpty() || it.valueRow }.map { it.key })!!

    fun selected(key: String?): VirtualRow? = rows.singleOrNull { it.key == key && it.occurrences.isNotEmpty() }
}

/**
 * 解決の結果。inputs は効かせた入力の値（鍵＝入力の名前。null＝絞っていない）。options は選択の入力の候補（値と見出し。
 * 参照は索引から作る）で、画面・業務 Web・CLI が同じ候補を見せる。
 */
data class DashboardResolution(
    val date: LocalDate,
    val sections: List<DashboardSectionResult>,
    val inputs: Map<String, String?> = emptyMap(),
    val options: Map<String, List<Pair<String, String?>>> = emptyMap(),
)

/** 演算は既存評価器へ委譲し、画面・CLI・エージェントに同じ行と判定理由を返す。 */
object DashboardViews {
    fun resolve(
        dashboard: VirtualDashboardDef,
        date: LocalDate,
        view: (String) -> VirtualGridDef?,
        /** 表の行の解決（受け取る値つき。表の params に無い値は渡らない）。 */
        rows: (VirtualGridDef, Map<String, Any?>) -> ResolvedRows,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        loader: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
        /** 拡張の種類の区画（チャートなど。ADR-0014）: ID から定義を引く（無ければ null）。 */
        embedded: (String) -> ExtensionViewDef? = { null },
        /** 共有の入力の値（鍵＝入力の名前。空か null＝絞らない）。 */
        inputs: Map<String, String?> = emptyMap(),
    ): DashboardResolution {
        // 複数を選べる入力は並びにして差し込む（受け渡しはカンマ区切り）。空は外す（絞らない）
        val given: Map<String, Any?> = inputs.mapNotNull { (key, text) ->
            val input = dashboard.inputs.firstOrNull { it.key == key }
            (input?.bound(text) ?: text?.takeIf { input == null && it.isNotBlank() })?.let { key to it }
        }.toMap()
        val optional = dashboard.inputs.map { it.key }.toSet()
        // 同じ表は同じ値で一度だけ解決する（表の params に無い値は渡さないので、鍵は表の ID と渡す値）
        val cache = mutableMapOf<Pair<String, Map<String, Any?>>, ResolvedRows>()
        fun rowsOf(grid: VirtualGridDef): ResolvedRows {
            val params = given.filterKeys { it in grid.params }
            return cache.getOrPut(grid.id to params) { rows(grid, params) }
        }
        return DashboardResolution(date, dashboard.sections.map { section ->
            embedded(section.view)?.let { def ->
                return@map runCatching {
                    require(section.filter == null) { "${def.kindLabel}の区画にはfilterを指定できません（絞り込みは元の表で）" }
                    // 元の表は区画と同じ受け取る値で、一度だけ解決する
                    val result = def.resolve(object : ViewContext {
                        override fun grid(id: String): VirtualGridDef? = view(id)
                        override fun rows(grid: VirtualGridDef): List<VirtualRow> = rowsOf(grid).rows
                    })
                    DashboardSectionResult(section, null, emptyList(), embedded = result)
                }.getOrElse { DashboardSectionResult(section, null, emptyList(), error = it.message ?: "${def.kindLabel}を解決できません") }
            }
            val grid = view(section.view)
            if (grid == null) return@map DashboardSectionResult(section, null, emptyList(),
                error = "ビュー「${section.view}」は一意なグリッド・クロス集計か、区画に置ける種類のビューを参照してください")
            runCatching {
                require(grid.matrix == null || section.filter == null) { "クロス集計の区画にはfilterを指定できません" }
                val resolved = rowsOf(grid)
                val sourceRows = resolved.rows
                // 区画の条件: 基準日と共有の入力を差し込む。空の入力を使う条件は外れる（空＝絞らない）
                val rule = section.filter?.ruleOrNull(mapOf("today" to date.toString()) + given, optional)
                val pending = mutableListOf<DashboardPending>()
                val outcomes = mutableMapOf<String, SemanticRuleResult>()
                val selected = if (rule == null) sourceRows else sourceRows.filter { row ->
                    if (row.occurrences.isEmpty()) return@filter false
                    val result = outcomes.getOrPut(row.id) {
                        SemanticRules.evaluate(ids, refs, graph, rule, row.id, loader = loader, fieldAt = fieldAt)
                    }
                    if (result.outcome == CheckOutcome.WITHHELD || result.reason == CheckReason.NO_VALUE) {
                        pending += DashboardPending(row, result.reason?.text ?: "判定できません")
                    }
                    result.outcome == CheckOutcome.SATISFIED
                }
                DashboardSectionResult(section, grid, selected, pending, truncation = resolved.takeIf { it.truncated })
            }.getOrElse { DashboardSectionResult(section, grid, emptyList(), error = it.message ?: "表示を解決できません") }
        }, inputs, dashboard.inputs.filter { it.choice }.associate { it.key to DashboardInputs.options(it, ids) })
    }
}
