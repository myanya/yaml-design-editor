package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.BuiltinTypes
import dev.toros.yde.semantic.schema.ExpressionFields
import dev.toros.yde.semantic.schema.DisplayCore
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.NumberDisplay
import dev.toros.yde.semantic.schema.ValueFormat
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.semantic.schema.AnyIndex
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.SemanticValues
import dev.toros.yde.semantic.VirtualBacklinksDef
import dev.toros.yde.semantic.VirtualDetailDef
import dev.toros.yde.semantic.VirtualDocAttachment
import dev.toros.yde.semantic.VirtualDocBacklinkColumn
import dev.toros.yde.semantic.VirtualDocBacklinksDef
import dev.toros.yde.semantic.VirtualDocSection
import dev.toros.yde.semantic.VirtualDocViewDef
import dev.toros.yde.semantic.VirtualEachDef
import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.semantic.LensArrow
import dev.toros.yde.semantic.VirtualLensDef
import dev.toros.yde.semantic.VirtualLensRelation
import dev.toros.yde.semantic.VirtualFlowDef
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.semantic.DashboardPlacement
import dev.toros.yde.semantic.DashboardInputType
import dev.toros.yde.semantic.DashboardInput
import dev.toros.yde.semantic.DashboardSection
import dev.toros.yde.semantic.schema.ActionRequire
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.VirtualMatrixCells
import dev.toros.yde.semantic.ChartDrill
import dev.toros.yde.semantic.ChartKind
import dev.toros.yde.semantic.ChartOrder
import dev.toros.yde.semantic.ChartScale
import dev.toros.yde.semantic.VirtualChartDef
import dev.toros.yde.semantic.VirtualMatrixDef
import dev.toros.yde.semantic.VirtualMatrixKey
import dev.toros.yde.semantic.VirtualNewRowDef
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.VirtualRowsDef
import dev.toros.yde.semantic.VirtualViewColumn
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.semantic.VirtualViewSource
import dev.toros.yde.yaml.YamlIO

/** 読み込んだビュー定義の一式（エラー収集つき）。 */
data class ViewCatalog(
    val views: List<VirtualGridDef>,
    val docViews: List<VirtualDocViewDef>,
    val errors: List<String>,
    val flowViews: List<VirtualFlowDef> = emptyList(),
    /** レンズの見え方（kind: lens）。レンズは全部の定義を合わせて読む。 */
    val lensViews: List<VirtualLensDef> = emptyList(),
    val dashboards: List<VirtualDashboardDef> = emptyList(),
    /** チャート（kind: chart。P46）。元の表（グリッドかクロス集計）を数にして描く部品。 */
    val chartViews: List<VirtualChartDef> = emptyList(),
) {
    fun view(id: String): VirtualGridDef? = views.firstOrNull { it.id == id }

    fun docView(id: String): VirtualDocViewDef? = docViews.firstOrNull { it.id == id }

    fun flowView(id: String): VirtualFlowDef? = flowViews.firstOrNull { it.id == id }

    fun chartView(id: String): VirtualChartDef? = chartViews.firstOrNull { it.id == id }

    /** 文書に適用する文書ビュー。スキーマの match と同じ「定義ファイル名順の先勝ち」。 */
    fun docViewFor(relPath: String, schemaId: String?): VirtualDocViewDef? =
        docViews.firstOrNull { it.appliesTo(relPath, schemaId) }
}

/**
 * ビュー定義（`_views` 配下の YAML）の読み取りとリント（P27 段D の D-c で、root の core/Views.kt から移した）。
 * 宣言の型と解決は semantic-core の semantic/VirtualViews.kt。ここに置くのは、リントが「作り先に合うスキーマ」を選ぶ
 * （WorkspaceSchema.matches）ため。root の core.Views は、ファイルを読んでここを呼ぶ窓口。
 */
object ViewDefinitions {

    /** ビュー定義文書のファイル名（エラーの文面の先頭に使う）。 */
    private fun fileName(source: DocumentId): String = source.value.substringAfterLast('/')

    /**
     * ビュー定義の一式を読み込む（エラー収集つき）。sources は（文書ID, 本文）の、定義ファイル名順。
     * schemas を渡すと、definition 参照の列展開（R14-02）と、ビュー→スキーマの整合検査（リント）が有効になる。
     */
    fun load(sources: List<Pair<DocumentId, String>>, schemas: List<WorkspaceSchema> = emptyList()): ViewCatalog {
        val all = mutableListOf<VirtualViewDefinition>()
        val errors = mutableListOf<String>()
        sources.forEach { (source, text) ->
            runCatching {
                val raw = YamlIO.parseToRaw(text) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseView(raw, source, schemas)
            }.onSuccess { all += it }
                .onFailure { errors += "${fileName(source)}: ${it.message ?: it::class.simpleName}" }
        }
        all.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "ビューID「$id」が重複しています（${duplicates.joinToString(", ") { fileName(it.source) }}）"
        }
        val views = all.filterIsInstance<VirtualGridDef>()
        val docViews = all.filterIsInstance<VirtualDocViewDef>()
        val flowViews = all.filterIsInstance<VirtualFlowDef>()
        val lensViews = all.filterIsInstance<VirtualLensDef>()
        val dashboards = all.filterIsInstance<VirtualDashboardDef>()
        val chartViews = all.filterIsInstance<VirtualChartDef>()
        if (dashboards.count { it.default } > 1) errors += "既定のダッシュボード（default: true）は1件だけ指定してください"
        dashboards.forEach { dashboard ->
            dashboard.sections.forEach { section ->
                val target = all.filter { it.id == section.view }
                val single = target.singleOrNull()
                when {
                    single is VirtualChartDef -> if (section.filter != null) {
                        errors += "${fileName(dashboard.source)}: チャートの区画にはfilterを指定できません（絞り込みは元の表で）"
                    }
                    single !is VirtualGridDef ->
                        errors += "${fileName(dashboard.source)}: sections.${section.id}.view「${section.view}」は一意なグリッド・クロス集計・チャートを参照してください"
                    single.matrix != null && section.filter != null ->
                        errors += "${fileName(dashboard.source)}: クロス集計の区画にはfilterを指定できません（合計との不一致を防ぐため）"
                }
            }
            if (schemas.isNotEmpty()) errors += lintDashboard(dashboard, schemas)
        }
        // チャート（P46）: 元の表が一意なグリッドかクロス集計であること。系列と横軸の列は、列が宣言で決まるグリッドだけ点検する
        chartViews.forEach { chart ->
            val source = all.filter { it.id == chart.view }.singleOrNull() as? VirtualGridDef
            if (source == null) {
                errors += "${fileName(chart.source)}: チャート「${chart.id}」の元の表（view）「${chart.view}」は一意なグリッドまたはクロス集計を参照してください"
            } else if (source.matrix == null && source.columns.none { it.each != null }) {
                (chart.series + listOfNotNull(chart.x, chart.label, chart.order?.series)).filterNot { label -> source.columns.any { it.label == label } }.forEach {
                    errors += "${fileName(chart.source)}: チャート「${chart.id}」の列「$it」は元の表「${source.id}」にありません"
                }
            }
        }
        if (schemas.isNotEmpty()) errors += lint(views, docViews, schemas)
        if (schemas.isNotEmpty()) errors += lintFlows(flowViews, schemas)
        if (schemas.isNotEmpty()) errors += lintLens(lensViews, schemas)
        docViews.forEach { doc -> doc.sections.forEachIndexed { i, section ->
            section.flowView?.let { target ->
                val matches = all.filter { it.id == target }
                if (matches.size != 1 || matches.singleOrNull() !is VirtualFlowDef) {
                    errors += "${fileName(doc.source)}: sections[$i].flow.view「$target」は一意なflowビューを参照してください"
                }
            }
            section.chartView?.let { target ->
                val matches = all.filter { it.id == target }
                if (matches.size != 1 || matches.singleOrNull() !is VirtualChartDef) {
                    errors += "${fileName(doc.source)}: sections[$i].chart.view「$target」は一意なチャート（kind: chart）を参照してください"
                }
            }
        } }
        return ViewCatalog(
            views = views, docViews = docViews, errors = errors, flowViews = flowViews, lensViews = lensViews, dashboards = dashboards,
            chartViews = chartViews,
        )
    }

    /**
     * ビュー定義1件（編集中の未保存ツリーでもよい）の検査（UX-43）。
     * パース不能はその内容を1件、パースできればリント（ビュー→スキーマの整合検査）の指摘を返す。
     * 検証パネルが `_views` の文書を開いている間、保存を待たずにその場で指摘を出すために使う。
     */
    fun lintDocument(root: YNode?, source: DocumentId, schemas: List<WorkspaceSchema>): List<String> {
        if (root == null) return emptyList()  // パースエラーは検証本体が報告する
        val raw = runCatching { YamlIO.parseToRaw(YamlIO.dumpPlain(root)) }.getOrNull() as? Map<*, *>
            ?: return listOf("ビュー定義のルートはobjectである必要があります")
        val def = runCatching { parseView(raw, source, schemas) }
            .getOrElse { return listOf(it.message ?: (it::class.simpleName ?: "定義を解析できません")) }
        return when (def) {
            is VirtualGridDef -> lint(listOf(def), emptyList(), schemas)
            is VirtualDocViewDef -> lint(emptyList(), listOf(def), schemas)
            is VirtualFlowDef -> lintFlows(listOf(def), schemas)
            is VirtualLensDef -> lintLens(listOf(def), schemas)
            is VirtualDashboardDef -> lintDashboard(def, schemas)
            // チャートの点検（元の表の有無・列）はカタログ全体でしか決まらない（load で行う）
            is VirtualChartDef -> emptyList()
        }
    }

    /**
     * ビュー定義1件のパース（kind で分岐）。ステージングの定義ゲート（Staging）からも使う。
     * schemas は definition 参照の解決に使う（未指定時は definition を使う定義がパースエラーになる）。
     */
    fun parseView(raw: Map<*, *>, source: DocumentId, schemas: List<WorkspaceSchema> = emptyList()): VirtualViewDefinition =
        when (val kind = raw["kind"]?.toString()) {
            null, "grid" -> parseGridView(raw, source, schemas)
            "document" -> parseDocView(raw, source, schemas)
            "matrix" -> parseMatrixView(raw, source)
            "flow" -> parseFlowView(raw, source)
            "lens" -> parseLensView(raw, source)
            "dashboard" -> parseDashboard(raw, source)
            "chart" -> parseChartView(raw, source)
            else -> error("kind は grid / document / matrix / flow / lens / dashboard / chart のいずれかである必要があります: $kind")
        }

    /**
     * チャート（kind: chart。P46）のパース: 図の種類（chart）・元の表（view）・系列にする列（series）・横軸の列（x。散布図では数の列）・点の名前の列（label。散布図）・縦軸の見出しと形（y）。
     * 元の表との整合（存在・列の有無）はカタログの読み込みで点検する。
     */
    private fun parseChartView(raw: Map<*, *>, source: DocumentId): VirtualChartDef {
        val keys = setOf("id", "name", "description", "kind", "chart", "view", "series", "x", "y", "drill", "order", "label", "scale")
        raw.keys.firstOrNull { it !in keys }?.let { error("kind: chart では使えないキーです: $it（使えるキー: ${keys.joinToString(", ")}）") }
        fun text(key: String): String? = if (!raw.containsKey(key)) null else
            (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key は空でない文字列で書きます")
        val id = text("id") ?: error("id が必要です")
        val kind = ChartKind.parse(text("chart") ?: error("chart（図の種類: ${ChartKind.entries.joinToString(" / ") { it.id }}）が必要です"), "chart")
        val view = text("view") ?: error("view（元の表＝グリッドかクロス集計のビュー ID）が必要です")
        val x = text("x")
        // 散布図: x は横軸の数の列（必須）、label は点の名前の列。他の図では x が項目の文字で、label は書けない
        val label = text("label")
        if (kind == ChartKind.SCATTER && x == null) error("chart: scatter には x（横軸にする数の列）が必要です")
        if (label != null && kind != ChartKind.SCATTER) error("label（点の名前の列）は chart: scatter で使います（他の図では x が項目の文字です）")
        // 軸の種類（散布図だけ）: {x: linear | log, y: linear | log}
        val scale = raw["scale"]?.let { s ->
            val sm = s as? Map<*, *> ?: error("scale は {x: linear | log, y: linear | log} で書きます")
            if (kind != ChartKind.SCATTER) error("scale（軸の種類）は chart: scatter で使います")
            sm.keys.firstOrNull { it != "x" && it != "y" }?.let { error("scale に書けるキーは x / y です: $it") }
            ChartScale.parse(sm["x"]?.toString() ?: "linear", "scale.x") to ChartScale.parse(sm["y"]?.toString() ?: "linear", "scale.y")
        }
        val series = when (val s = raw["series"]) {
            null -> emptyList()
            is List<*> -> s.map { it?.toString()?.takeIf { t -> t.isNotBlank() } ?: error("series は列の見出しの配列で書きます") }
            else -> error("series は列の見出しの配列で書きます")
        }
        val y = raw["y"]?.let { it as? Map<*, *> ?: error("y は {label, format} で書きます") }
        y?.keys?.firstOrNull { it != "label" && it != "format" }?.let { error("y に書けるキーは label / format です: $it") }
        val format = y?.get("format")?.toString()?.takeIf { it.isNotBlank() }?.also { f ->
            require(f.any { it == '0' || it == '#' } && runCatching { java.text.DecimalFormat(f) }.isSuccess) { "y.format は数の形（DecimalFormat のパターン。例 #,##0）で書きます: $f" }
        }
        // 押したときの材料の見せ方（省略時は浮いた一覧）
        val drill = if (!raw.containsKey("drill")) ChartDrill.POPUP else ChartDrill.parse(raw["drill"]?.toString(), "drill")
        // 項目の並び: {series: 列の見出し（省略＝最初の系列）, direction: asc | desc}
        val order = raw["order"]?.let { o ->
            val om = o as? Map<*, *> ?: error("order は {series, direction} で書きます（例 {direction: desc}）")
            om.keys.firstOrNull { it != "series" && it != "direction" }?.let { error("order に書けるキーは series / direction です: $it") }
            val direction = om["direction"]?.toString() ?: "asc"
            require(direction == "asc" || direction == "desc") { "order.direction は asc / desc で書きます: $direction" }
            ChartOrder(series = om["series"]?.toString()?.takeIf { it.isNotBlank() }, descending = direction == "desc")
        }
        return VirtualChartDef(
            id = id, name = text("name") ?: id, description = text("description"), source = source,
            kind = kind, view = view, series = series, x = x, label = label,
            xScale = scale?.first ?: ChartScale.LINEAR, yScale = scale?.second ?: ChartScale.LINEAR,
            yLabel = y?.get("label")?.toString()?.takeIf { it.isNotBlank() }, format = format, drill = drill, order = order,
        )
    }

    private val PARAM_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")

    /** 受け取る値（params）の宣言と、原文（rows / cells）で使っている `$名前` の点検。 */
    private fun parseParams(raw: Map<*, *>, used: Any?, where: String): List<String> {
        val params = when (val p = raw["params"]) {
            null -> emptyList()
            is List<*> -> p.map { it?.toString()?.takeIf { t -> PARAM_NAME.matches(t) } ?: error("$where.params は英字で始まる英数字の名前の配列で書きます") }
            else -> error("$where.params は名前の配列で書きます")
        }
        require(params.distinct().size == params.size) { "$where.params に同じ名前があります" }
        ActionRequire.referencedNames(used).firstOrNull { it !in params }
            ?.let { error("$where: 受け取る値「\$$it」が params に宣言されていません（rows / cells の \$名前は params に書いた名前だけ）") }
        return params
    }

    /** ダッシュボードの共有の入力（inputs）。 */
    private fun parseInputs(raw: Any?): List<DashboardInput> {
        val list = when (raw) {
            null -> return emptyList()
            is List<*> -> raw
            else -> error("dashboard.inputs は配列で書きます")
        }
        val inputs = list.mapIndexed { i, item ->
            val at = "dashboard.inputs[$i]"
            val m = item as? Map<*, *> ?: error("$at はobjectで指定してください")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("key", "label", "type", "values", "default") }?.let { error("$at: 未知のキーです: $it") }
            val key = m["key"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.key（名前）が必要です")
            require(PARAM_NAME.matches(key)) { "$at.key は英字で始まる英数字で書きます: $key" }
            require(key != "today") { "$at.key に today は使えません（基準日の名前）" }
            val type = if (m["type"] == null) DashboardInputType.STRING else DashboardInputType.parse(m["type"]?.toString(), "$at.type")
            val values = (m["values"] as? List<*>)?.map { v ->
                when (v) {
                    is Map<*, *> -> (v["value"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$at.values の各要素は値か {value, label} で書きます")) to v["label"]?.toString()
                    else -> v.toString() to null
                }
            }.orEmpty()
            if (type == DashboardInputType.ENUM) require(values.isNotEmpty()) { "$at: type: enum には values（選択の候補）が必要です" }
            if (type != DashboardInputType.ENUM) require(values.isEmpty()) { "$at: values は type: enum のときだけ書きます" }
            DashboardInput(key, m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: key, type, values, m["default"]?.toString()?.takeIf { it.isNotBlank() })
        }
        require(inputs.map { it.key }.distinct().size == inputs.size) { "dashboard.inputs の key が重複しています" }
        return inputs
    }

    private fun parseDashboard(raw: Map<*, *>, source: DocumentId): VirtualDashboardDef {
        fun keys(map: Map<*, *>, allowed: Set<String>, at: String) {
            map.keys.firstOrNull { it !in allowed }?.let { error("$at: 未知のキーです: $it") }
        }
        fun text(map: Map<*, *>, key: String, at: String): String =
            (map[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$at.$key は空でない文字列で指定してください")
        keys(raw, setOf("id", "name", "description", "kind", "sections", "actions", "default", "placement", "inputs"), "dashboard")
        require(!raw.containsKey("default") || raw["default"] is Boolean) { "dashboard.default は真偽値で指定してください" }
        val id = text(raw, "id", "dashboard")
        val inputs = parseInputs(raw["inputs"])
        val sections = (raw["sections"] as? List<*>)?.takeIf { it.isNotEmpty() }
            ?: error("dashboard.sections は1件以上の配列で指定してください")
        val parsed = sections.mapIndexed { i, item ->
            val at = "dashboard.sections[$i]"
            val map = item as? Map<*, *> ?: error("$at はobjectで指定してください")
            keys(map, setOf("id", "label", "view", "filter", "group"), at)
            val key = text(map, "id", at)
            DashboardSection(key, map["label"]?.let { text(map, "label", at) } ?: key, text(map, "view", at),
                map["filter"]?.let { ActionRequire.parse(it, "$at.filter", setOf("today") + inputs.map { i -> i.key }, hasStart = true) },
                group = map["group"]?.let { text(map, "group", at) })
        }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "dashboard.sections のidが重複しています" }
        val actions = when (val value = raw["actions"]) {
            null -> emptyList()
            is List<*> -> value.map { (it as? String)?.takeIf(String::isNotBlank) ?: error("dashboard.actions は操作IDの配列です") }
            else -> error("dashboard.actions は操作IDの配列です")
        }
        require(actions.distinct().size == actions.size) { "dashboard.actions の操作IDが重複しています" }
        return VirtualDashboardDef(id, raw["name"]?.let { text(raw, "name", "dashboard") } ?: id,
            raw["description"]?.let { text(raw, "description", "dashboard") }, source, parsed, actions, raw["default"] == true,
            placement = raw["placement"]?.let { DashboardPlacement.parse(it as? String, "dashboard.placement") } ?: DashboardPlacement.AUTO,
            inputs = inputs)
    }

    private fun lintDashboard(view: VirtualDashboardDef, schemas: List<WorkspaceSchema>): List<String> =
        view.actions.mapNotNull { id ->
            val actions = schemas.flatMap { it.actions }.filter { it.id == id }
            if (actions.size == 1 && actions.single().target == null) null
            else "${fileName(view.source)}: actions「$id」は対象を持たない一意なアクションを参照してください"
        }

    /**
     * レンズの見え方（kind: lens）のパース: 関係ごとの矢印の向き（architecture/views.md「レンズの見え方」）。
     * 表示だけの定義なので、ここで読むのは関係名と向きだけ。節点の選択は画面の操作のまま。
     */
    private fun parseLensView(raw: Map<*, *>, source: DocumentId): VirtualLensDef {
        val keys = setOf("id", "name", "description", "kind", "relations")
        raw.keys.firstOrNull { it !in keys }?.let { error("kind: lens では使えないキーです: $it") }
        fun text(key: String): String? = if (!raw.containsKey(key)) null else
            (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key は空でない文字列で書きます")
        val id = text("id") ?: error("id が必要です")
        val list = if (!raw.containsKey("relations")) emptyList<Any?>() else raw["relations"] as? List<*> ?: error("relations はリストで書きます")
        val relations = list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("relations[$i] は object で書きます")
            m.keys.firstOrNull { it != "name" && it != "arrow" }?.let { error("relations[$i] で使えないキーです: $it") }
            val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: error("relations[$i].name（関係名）が必要です")
            val arrow = m["arrow"]?.let { v -> LensArrow.parse(v.toString()) ?: error("relations[$i].arrow は forward / reverse で書きます: $v") }
                ?: LensArrow.FORWARD
            VirtualLensRelation(name, arrow)
        }
        relations.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("relations に関係「$it」が重複しています") }
        return VirtualLensDef(id, text("name") ?: id, text("description"), source, relations)
    }

    /** レンズの定義のリント: 関係レジストリがあれば、関係名が宣言されているか（レジストリが無ければ判定しない）。 */
    private fun lintLens(views: List<VirtualLensDef>, schemas: List<WorkspaceSchema>): List<String> {
        val declared = schemas.filter { it.kind == dev.toros.yde.semantic.schema.SchemaKind.RELATIONS }
            .flatMap { s -> s.relations.map { it.name } }.toSet()
        if (declared.isEmpty()) return emptyList()
        return views.flatMap { view ->
            view.relations.filter { it.name !in declared }.map { "${fileName(view.source)}: 関係「${it.name}」は関係レジストリに宣言されていません" }
        }
    }

    private fun parseFlowView(raw: Map<*, *>, source: DocumentId): VirtualFlowDef {
        val keys = setOf("id", "name", "description", "kind", "match", "schemaId", "layout", "projection")
        raw.keys.firstOrNull { it !in keys }?.let { error("kind: flow では使えないキーです: $it") }
        fun text(key: String): String? = if (!raw.containsKey(key)) null else
            (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key は空でない文字列で書きます")
        val id = text("id") ?: error("id が必要です")
        val match = text("match")
        val schemaId = text("schemaId")
        require(match != null || schemaId != null) { "フロービューには match か schemaId が必要です" }
        match?.let(::globToRegex)
        val layout = if (raw.containsKey("layout")) raw["layout"] as? Map<*, *> ?: error("layout はobjectで書きます") else emptyMap<Any, Any>()
        require(layout.keys.all { it == "direction" }) { "layout に書けるキーは direction だけです" }
        val direction = if (layout.containsKey("direction")) layout["direction"] as? String else "down"
        require(direction in listOf("down", "right")) { "layout.direction は down / right で書きます" }
        val projection = TransformDef.parse(raw["projection"], "projection")
        require(projection.from == null && projection.params.isEmpty()) { "フローのprojectionには from・外部paramsを指定できません" }
        return VirtualFlowDef(id, text("name") ?: id, text("description"), source, match, schemaId, direction!!, projection)
    }

    private fun lintFlows(views: List<VirtualFlowDef>, schemas: List<WorkspaceSchema>): List<String> = views.mapNotNull { view ->
        view.schemaId?.takeIf { id -> schemas.none { it.id == id } }?.let {
            "${fileName(view.source)}: 適用先スキーマ「$it」が見つかりません"
        }
    }

    private val MATRIX_KEYS = listOf("cells", "totals", "unclassified")

    /**
     * クロス集計（kind: matrix）のパース。行の軸は rows（idPattern）、列の軸とセルの集計は cells。
     * 列は解決のときに索引から広げるので、ここでは columns を空にした VirtualGridDef（matrix つき）にする。
     */
    private fun parseMatrixView(raw: Map<*, *>, source: DocumentId): VirtualGridDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        listOf("columns", "detail", "newRow", "sections", "attachments", "match", "schemaId").firstOrNull { raw.containsKey(it) }
            ?.let { error("$it は kind: matrix のビューでは使えません（列は cells.columns で決まります）") }
        val rowsMap = raw["rows"] as? Map<*, *> ?: error("rows が必要です")
        if (rowsMap.containsKey("match")) error("kind: matrix の rows は idPattern か value で書きます（クエリ行は使えません）")
        // 行の軸: 論理 ID（idPattern）か、起点の要素ごとに計算した値（value。P46。同じ値を 1 行にまとめる）
        val rowsValue = rowsMap["value"]?.let { v ->
            rowsMap.keys.map { it.toString() }.firstOrNull { it != "value" }
                ?.let { error("rows.value と rows.$it は一緒に書けません（行の軸は ID か値のどちらか）") }
            if (raw.containsKey("order")) error("行の軸が値（rows.value）のクロス集計に order は書けません（値の順に並びます）")
            ExpressionValue.of(v, "rows.value", "行")
        }
        val rowsDef = if (rowsValue != null) null else VirtualRowsDef(
            idPattern = rowsMap["idPattern"]?.toString()?.takeIf { it.isNotBlank() } ?: error("rows.idPattern か rows.value が必要です"),
            schemaIds = stringList(rowsMap["schemaIds"]),
        )
        val orderMap = raw["order"] as? Map<*, *>
        val cellsMap = raw["cells"] as? Map<*, *> ?: error("cells（セルの集計）が必要です")
        val params = parseParams(raw, cellsMap, "matrix")
        val known = listOf("match", "where", "also", "of", "in", "path", "columns", "aggregate", "type")
        cellsMap.keys.map { it.toString() }.firstOrNull { it !in known }
            ?.let { error("cells: 未知のキーです: $it（使えるキー: ${known.joinToString(", ")}）") }
        val of = cellsMap["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("cells.of（数える要素の束縛名）が必要です")
        if (!cellsMap.containsKey("match")) error("cells.match（行の要素からセルの要素への照合）が必要です")
        val aggregate = cellsMap["aggregate"]?.toString()?.let { t ->
            ValueExpr.AggregateFn.entries.firstOrNull { it.id == t } ?: error("cells.aggregate は count / sum / min / max のいずれかです: $t")
        } ?: ValueExpr.AggregateFn.COUNT
        // 順序の種類（type）は省くと集計が読む項目の型から決まる
        val kind = cellsMap["type"]?.toString()?.let { ValueExpr.OrderKind.parse(it, "cells") }
        if (aggregate != ValueExpr.AggregateFn.COUNT && !cellsMap.containsKey("path")) {
            error("cells.path（集計する値の相対パス）が必要です（aggregate: ${aggregate.id}）")
        }
        val columnsMap = cellsMap["columns"] as? Map<*, *> ?: error("cells.columns（列の軸: via か path）が必要です")
        val key = parseMatrixKey(columnsMap)
        val all = LinkedHashMap<String, Any?>()
        listOf("match", "where", "also", "of", "in", "path").forEach { k -> cellsMap[k]?.let { all[k] = it } }
        // 定義の点検: 列の絞りを足す前の式（合計の列と同じ形）が、値の式として読めること
        val probe = LinkedHashMap<String, Any?>()
        probe[aggregate.id] = mapOf("all" to all)
        if (aggregate != ValueExpr.AggregateFn.COUNT) kind?.let { probe["type"] = it.id }
        ExpressionValue.of(probe, "cells")
        val totals = when (val t = raw["totals"]) {
            null -> true
            is Boolean -> t
            else -> error("totals は true / false で書きます: $t")
        }
        val unclassified = when (val u = raw["unclassified"]) {
            null -> "（未分類）"  // 括弧つき: 「未分類」という名前のエンティティ（費目など）の列と見分けるため
            false -> null
            is String -> u.takeIf { it.isNotBlank() } ?: error("unclassified は見出しの文字か false で書きます")
            else -> error("unclassified は見出しの文字か false で書きます: $u")
        }
        val display = runCatching { NumberDisplay.parse(raw["display"]?.toString()) }.getOrElse { error("display: ${it.message}") }
        return VirtualGridDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            rows = rowsDef,
            columns = emptyList(),
            orderBy = orderMap?.let { parseSource(it) }?.takeIf { !it.isEmpty },
            orderValue = orderMap?.let { parseOrderValue(it) },
            source = source,
            params = params,
            matrix = VirtualMatrixDef(
                cells = VirtualMatrixCells(all = all, of = of, aggregate = aggregate, kind = kind, key = key),
                totals = totals,
                unclassified = unclassified,
                display = display,
                rowsValue = rowsValue,
            ),
        )
    }

    /** 列の軸（cells.columns）: via（参照先を列に。idPattern・schemaIds・order）か path（値を列に。values）。 */
    private fun parseMatrixKey(m: Map<*, *>): VirtualMatrixKey {
        val via = m["via"]?.toString()?.takeIf { it.isNotBlank() }
        val pathText = m["path"]?.toString()?.takeIf { it.isNotBlank() }
        // 値の式が列（P46）: 要素ごとに計算した値で列を広げる（列はデータから決まる。他の書き方とは一緒に書けない）
        if (m.containsKey("value")) {
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("value", "label", "order") }
                ?.let { error("cells.columns の value と $it は一緒に書けません（列の軸は値の式で決まり、添えられるのは label＝見出しの式 と order＝先に並べる値 です）") }
            val label = m["label"]?.let { ExpressionValue.of(it, "cells.columns.label", "列の見出し") }
            val order = when (val o = m["order"]) {
                null -> emptyList()
                is List<*> -> o.map { it?.toString()?.takeIf { t -> t.isNotBlank() } ?: error("cells.columns.order は先に並べる値の配列で書きます") }
                else -> error("cells.columns.order は先に並べる値の配列で書きます（value の列の並び。書かない値はその後ろに値の順）")
            }
            return VirtualMatrixKey.Expr(ExpressionValue.of(m["value"], "cells.columns.value", "列"), label, order)
        }
        if (via == null && pathText == null) error("cells.columns には via（要素の参照先を列に）か path（要素の値を列に）か value（要素ごとの値の式を列に）が必要です")
        if (via != null && pathText != null) error("cells.columns の via と path は、どちらか一方を書きます")
        if (via != null) {
            if (m.containsKey("values")) error("cells.columns の values は path と一緒に書きます")
            val idPattern = m["idPattern"]?.toString()?.takeIf { it.isNotBlank() } ?: error("cells.columns.idPattern（列にする ID の形）が必要です")
            val order = (m["order"] as? Map<*, *>)?.let { parseSource(it) }?.takeIf { !it.isEmpty }
            return VirtualMatrixKey.Ref(via = via, idPattern = idPattern, schemaIds = stringList(m["schemaIds"]), order = order)
        }
        listOf("idPattern", "schemaIds", "order").firstOrNull { m.containsKey(it) }?.let { error("cells.columns の $it は via と一緒に書きます") }
        val path = runCatching { parseYPath(pathText!!) }.getOrElse { error("cells.columns.path が読めません: $pathText") }
        val values = (m["values"] as? List<*>)?.takeIf { it.isNotEmpty() } ?: error("cells.columns.values（列にする値の並び）が必要です")
        val parsed = values.map { v ->
            when (v) {
                is Map<*, *> -> (v["value"]?.toString()?.takeIf { it.isNotBlank() } ?: error("cells.columns.values の各要素は値か {value, label} で書きます")) to v["label"]?.toString()
                else -> v.toString() to null
            }
        }
        return VirtualMatrixKey.Value(path = path, pathText = pathText!!, values = parsed)
    }

    /** 横断グリッド（kind: grid）のパース。 */
    private fun parseGridView(raw: Map<*, *>, source: DocumentId, schemas: List<WorkspaceSchema>): VirtualGridDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        if (raw.containsKey("sections")) error("sections は kind: document のビューでのみ使えます")
        MATRIX_KEYS.firstOrNull { raw.containsKey(it) }?.let { error("$it は kind: matrix のビューでのみ使えます") }
        val rowsMap = raw["rows"] as? Map<*, *> ?: error("rows が必要です")
        // 行の定義: 従来（idPattern×schemaIds）とクエリ行（match。R22-02 / architecture/graph-query.md「クエリコア」）の排他
        var rowsDef: VirtualRowsDef? = null
        // 受け取る値（params）: rows の中の $名前 は宣言した名前だけ（列の値の式の $名前 は each の列の束縛で別物）
        val params = parseParams(raw, rowsMap, "grid")
        var rowsQuery: QuerySpec? = null
        var rowKey: String? = null
        if (rowsMap.containsKey("match")) {
            listOf("idPattern", "schemaIds").firstOrNull { rowsMap.containsKey(it) }
                ?.let { error("rows: match と $it は併用できません（従来の行定義とクエリ行は排他）") }
            if (raw.containsKey("order")) error("rows: match のビューに order は使えません（並びはクエリの決定的順序）")
            // 上限は limit を書いたときだけ（書かなければ上限なし＝値の式の all と同じ。黙って切れないように）
            val spec = QuerySpec.parse(rowsMap, "rows", ignoreKeys = setOf("rowKey"))
                .let { if (rowsMap.containsKey("limit")) it else it.copy(limit = Int.MAX_VALUE) }
            require(spec.binds.isNotEmpty()) { "rows.match には bind（束縛名）が1つ以上必要です" }
            val keyName = rowsMap["rowKey"]?.toString()?.takeIf { it.isNotBlank() } ?: spec.binds.first()
            require(keyName in spec.binds) { "rows.rowKey の束縛「$keyName」が match に見つかりません" }
            require(spec.bindStep(keyName)?.kind == QueryNodeKind.ENTITY) {
                "rows.rowKey の束縛「$keyName」は kind: entity である必要があります（行キーは論理ID）"
            }
            rowsQuery = spec
            rowKey = keyName
        } else {
            val idPattern = rowsMap["idPattern"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("rows.idPattern か rows.match が必要です")
            rowsDef = VirtualRowsDef(idPattern = idPattern, schemaIds = stringList(rowsMap["schemaIds"]))
        }
        val binds = rowsQuery?.binds
        // 値の式を受け取る項目（builtin-view の expression: true の印。P31）: どの項目かは印だけが知り、読み取りは共通。
        // each の列の値の式は、列の要素を指す $名前 が決まった ID の文字として読めるので、そのまま点検できる（列を広げるときに本当の ID になる）
        val expressions = ExpressionFields.read(YamlIO.fromAny(raw), BuiltinSchemaResources.viewSchemaTree)
        val columnsRaw = raw["columns"] as? List<*> ?: error("columns が必要です")
        require(columnsRaw.isNotEmpty()) { "columns には1列以上が必要です" }
        val columns = columnsRaw.flatMapIndexed { i: Int, entry: Any? ->
            val m = entry as? Map<*, *> ?: error("columns[$i] はobjectである必要があります")
            // of（R22-02）: クエリ行の値元の束縛。従来の行定義では使えない
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() }
            if (of != null) {
                if (binds == null) error("columns[$i]: of は rows: match のビューでのみ使えます")
                require(of in binds) { "columns[$i]: of の束縛「$of」が rows.match に見つかりません" }
            }
            if (m.containsKey("each")) {
                // 要素ごとに列を広げる: 読み取り専用の派生列。見出し・列は要素から決まるので、他の列の種類・編集とは排他
                val e = m["each"] as? Map<*, *> ?: error("columns[$i].each は object（idPattern・bind など）で書きます")
                listOf("label", "path", "backlinks", "definition", "of").firstOrNull { m.containsKey(it) }
                    ?.let { error("columns[$i]: each と $it は併用できません（見出しは要素のタイトルになります）") }
                if (m["editable"] == true) error("columns[$i]: each の列は editable にできません（読み取り専用の派生列）")
                if (!m.containsKey("value")) error("columns[$i]: each には value（列ごとの値の式。列の要素は \$名前 で指す）が必要です")
                val idPattern = e["idPattern"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].each.idPattern（列にする ID の形）が必要です")
                val bind = e["bind"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].each.bind（式の中で列の要素を指す名前）が必要です")
                val order = (e["order"] as? Map<*, *>)?.let { parseSource(it) }?.takeIf { !it.isEmpty }
                val value = expressions[listOf("columns", i, "value")] ?: error("columns[$i].value を読めません")
                return@flatMapIndexed listOf(
                    VirtualViewColumn(
                        label = "$idPattern（要素ごと）", from = parseSource(m), path = emptyList(), value = value,
                        display = parseDisplay(m, i), format = parseFormat(m, i),
                        each = VirtualEachDef(idPattern = idPattern, schemaIds = stringList(e["schemaIds"]), order = order, bind = bind, rawValue = m["value"]),
                    )
                )
            }
            if (m.containsKey("value")) {
                // 値の式の列: 読み取り専用の派生列のため、他の列の種類・編集とは排他。出現の選択（schemaId / fileGlob）は普通の列と同じ
                listOf("path", "backlinks", "definition", "computed").firstOrNull { m.containsKey(it) }
                    ?.let { error("columns[$i]: value と $it は併用できません") }
                if (m["editable"] == true) error("columns[$i]: value 列は editable にできません（読み取り専用の派生列）")
                val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].label が必要です")
                val value = expressions[listOf("columns", i, "value")] ?: error("columns[$i].value を読めません")
                return@flatMapIndexed listOf(
                    VirtualViewColumn(
                        label = label, from = parseSource(m), path = emptyList(), value = value, of = of,
                        display = parseDisplay(m, i), format = parseFormat(m, i),
                    )
                )
            }
            if (m.containsKey("definition")) {
                if (of != null) error("columns[$i]: definition と of は併用できません")
                // definition 参照列（R14-02）: named definition のセル型フィールドを列集合に展開する
                return@flatMapIndexed expandDefinitionColumns(m, i, schemas)
            }
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].label が必要です")
            if (m.containsKey("computed")) {
                // 廃止した書き方（テンプレートの導出）: 同じ意味の value の書き方を文面で案内する（黙って別の意味にしない）
                error("columns[$i]: computed は廃止されました。同じ意味の value で書いてください:\n${computedToValueHint(m)}")
            }
            if (m.containsKey("backlinks")) {
                // バックリンク列: 被参照の派生表示のため、出現の選択（path / schemaId / fileGlob）や編集とは排他
                val b = m["backlinks"] as? Map<*, *> ?: error("columns[$i].backlinks はobjectである必要があります")
                if (m["path"] != null) error("columns[$i]: path と backlinks は併用できません")
                if (m["editable"] == true) error("columns[$i]: backlinks 列は editable にできません")
                if (m["schemaId"] != null || m["fileGlob"] != null) {
                    error("columns[$i]: backlinks 列に schemaId / fileGlob は指定できません（絞り込みは backlinks 配下に書きます）")
                }
                listOf(
                    VirtualViewColumn(
                        label = label,
                        path = emptyList(),
                        backlinks = VirtualBacklinksDef(
                            relation = b["relation"]?.toString(),
                            schemaIds = stringList(b["schemaIds"]),
                        ),
                        of = of,
                    )
                )
            } else listOf(
                VirtualViewColumn(
                    label = label,
                    from = parseSource(m),
                    path = parseYPath(m["path"]?.toString() ?: error("columns[$i].path が必要です")),
                    editable = m["editable"] as? Boolean ?: false,
                    of = of,
                    display = parseDisplay(m, i),
                    format = parseFormat(m, i),
                )
            )
        }
        val detail = (raw["detail"] as? Map<*, *>)?.let { m ->
            // 作り先は、ファイル名として使う式（いまの要素＝行の要素）
            val pattern = expressions[listOf("detail", "filePattern")]?.let { FileNameRules.requireWellFormed(it) }
            VirtualDetailDef(source = parseSource(m), filePattern = pattern)
        }
        val newRow = (raw["newRow"] as? Map<*, *>)?.let { m ->
            val fileMode = m["filePattern"]?.takeIf { it !is String || it.isNotBlank() } != null
            if (fileMode) {
                // ファイルモード: 配列モードのキーとは排他（どちらの意図か曖昧な定義を弾く）
                listOf("file", "path").firstOrNull { m.containsKey(it) }
                    ?.let { error("newRow: filePattern と $it は併用できません（配列モードとファイルモードは排他）") }
                // 行がまだ無いので、埋められる穴は {id} だけ（判断点 E-07）
                val pattern = expressions[listOf("newRow", "filePattern")]?.let { FileNameRules.requireWellFormed(it, idOnly = true) }
                    ?: error("newRow.filePattern を読めません")
                VirtualNewRowDef(filePattern = pattern)
            } else VirtualNewRowDef(
                file = m["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("newRow.file か newRow.filePattern が必要です"),
                path = parseYPath(m["path"]?.toString() ?: error("newRow.path が必要です")),
            )
        }
        return VirtualGridDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            rows = rowsDef,
            rowsQuery = rowsQuery,
            rowKey = rowKey,
            columns = columns,
            orderBy = (raw["order"] as? Map<*, *>)?.let { parseSource(it) }?.takeIf { !it.isEmpty },
            orderValue = (raw["order"] as? Map<*, *>)?.let { parseOrderValue(it) },
            detail = detail,
            newRow = newRow,
            source = source,
            params = params,
            rowsRaw = if (rowsQuery != null && params.isNotEmpty()) rowsMap.entries.associate { it.key.toString() to it.value } else null,
        )
    }

    /**
     * definition 参照列（R14-02）の展開。named definition（object）のセル型フィールド
     * （`Display.tableColumns` と同じ規則。ID型は行キーと重複するため除く）を列に展開し、
     * ラベル・パスをスキーマから導出する（列定義の重複記載をなくす）。
     */
    private fun expandDefinitionColumns(m: Map<*, *>, index: Int, schemas: List<WorkspaceSchema>): List<VirtualViewColumn> {
        listOf("label", "path", "backlinks").firstOrNull { m.containsKey(it) }
            ?.let { error("columns[$index]: definition と $it は併用できません（ラベル・パスは定義から導出されます）") }
        val from = parseSource(m)
        val ref = m["definition"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$index].definition が空です")
        val (schema, target) = resolveDefinition(ref, from.schemaId, schemas)
            ?: error("columns[$index]: definition「$ref」が見つかりません")
        val resolved = schema.tree.resolve(target) ?: target
        require(resolved.type == FieldType.OBJECT) { "columns[$index]: definition「$ref」は object である必要があります" }
        val editable = m["editable"] as? Boolean ?: false
        val columns = resolved.fields.mapNotNull { field ->
            val key = field.key ?: return@mapNotNull null
            val eff = schema.tree.resolve(field) ?: field
            if (eff.type !in DisplayCore.CELL_TYPES || eff.type == FieldType.ID) return@mapNotNull null
            VirtualViewColumn(label = eff.label ?: key, from = from, path = listOf(key), editable = editable)
        }
        require(columns.isNotEmpty()) { "columns[$index]: definition「$ref」に列にできるセル型フィールドがありません" }
        return columns
    }

    /**
     * definition 参照（`module#name`。非修飾は fallbackModule の definitions から引く）を解決する。
     * schemas 未指定（定義ゲート以外からの素のパース）では解決できないため null。
     */
    private fun resolveDefinition(
        ref: String,
        fallbackModule: String?,
        schemas: List<WorkspaceSchema>,
    ): Pair<WorkspaceSchema, FieldDef>? {
        if (schemas.isEmpty()) error("definition「$ref」の解決にはスキーマ情報が必要です")
        val hash = ref.indexOf('#')
        val moduleId = if (hash >= 0) ref.substring(0, hash)
        else fallbackModule ?: error("definition「$ref」には module#name 形式か、schemaId の指定が必要です")
        val name = if (hash >= 0) ref.substring(hash + 1) else ref
        val schema = schemas.firstOrNull { it.id == moduleId } ?: return null
        val target = schema.tree.definitions[name] ?: return null
        return schema to target
    }

    /** 文書ビュー（kind: document。R14-01）のパース。 */
    private fun parseDocView(raw: Map<*, *>, source: DocumentId, schemas: List<WorkspaceSchema>): VirtualDocViewDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        listOf("rows", "columns", "order", "detail", "newRow").firstOrNull { raw.containsKey(it) }
            ?.let { error("$it は kind: grid のビューでのみ使えます") }
        MATRIX_KEYS.firstOrNull { raw.containsKey(it) }?.let { error("$it は kind: matrix のビューでのみ使えます") }
        val match = raw["match"]?.toString()?.takeIf { it.isNotBlank() }
        val schemaId = raw["schemaId"]?.toString()?.takeIf { it.isNotBlank() }
        if (match == null && schemaId == null) error("文書ビューには match（適用先glob）か schemaId が必要です")
        match?.let { runCatching { globToRegex(it) }.getOrElse { e -> error("match が不正です: ${e.message}") } }
        val sectionsRaw = raw["sections"] as? List<*> ?: error("sections が必要です")
        require(sectionsRaw.isNotEmpty()) { "sections には1つ以上のセクションが必要です" }
        val sections = sectionsRaw.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("sections[$i] はobjectである必要があります")
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() }
            val fieldsRaw = m["fields"]
            val backlinksRaw = m["backlinks"]
            val flowRaw = m["flow"]
            val chartRaw = m["chart"]
            if (listOf(fieldsRaw, backlinksRaw, flowRaw, chartRaw).count { it != null } != 1) {
                error("sections[$i]: fields / backlinks / flow / chart のいずれか一方が必要です")
            }
            if (chartRaw != null) {
                val cm = chartRaw as? Map<*, *> ?: error("sections[$i].chart はobjectである必要があります")
                require(cm.keys.all { it == "view" }) { "sections[$i].chartにはviewだけを指定できます" }
                val target = (cm["view"] as? String)?.takeIf { it.isNotBlank() } ?: error("sections[$i].chart.viewが必要です")
                return@mapIndexed VirtualDocSection(label = label, chartView = target)
            }
            if (fieldsRaw != null) {
                val fm = fieldsRaw as? Map<*, *> ?: error("sections[$i].fields はobjectである必要があります（空なら {}）")
                // definition（R14-02）: このセクションが参照する named definition の宣言。修飾名へ正規化して保持し、
                // 存在（パース・ゲート）と実使用（リント）を検査する
                val definition = fm["definition"]?.toString()?.takeIf { it.isNotBlank() }?.let { ref ->
                    val qualified = if (ref.contains('#')) ref
                    else "${schemaId ?: error("sections[$i].fields.definition: 非修飾名「$ref」を使うにはビューの schemaId が必要です")}#$ref"
                    if (schemas.isNotEmpty() && resolveDefinition(qualified, null, schemas) == null) {
                        error("sections[$i].fields.definition「$qualified」が見つかりません")
                    }
                    qualified
                }
                // 表示キーの選別（R14-03）: include はこの順で表示、exclude は除外（両立不可）
                val include = stringList(fm["include"])
                val exclude = stringList(fm["exclude"])
                if (include.isNotEmpty() && exclude.isNotEmpty()) {
                    error("sections[$i].fields: include と exclude は併用できません")
                }
                VirtualDocSection(
                    label = label,
                    fieldsPath = parseYPath(fm["path"]?.toString() ?: ""),
                    fieldsDefinition = definition,
                    fieldsInclude = include,
                    fieldsExclude = exclude,
                )
            } else {
                if (flowRaw != null) {
                    val fm = flowRaw as? Map<*, *> ?: error("sections[$i].flow はobjectである必要があります")
                    require(fm.keys.all { it == "view" }) { "sections[$i].flowにはviewだけを指定できます" }
                    val target = (fm["view"] as? String)?.takeIf { it.isNotBlank() }
                        ?: error("sections[$i].flow.viewが必要です")
                    VirtualDocSection(label = label, flowView = target)
                } else {
                    val bm = backlinksRaw as? Map<*, *> ?: error("sections[$i].backlinks はobjectである必要があります")
                    VirtualDocSection(label = label, backlinks = parseDocBacklinks(bm, "sections[$i].backlinks"))
                }
            }
        }
        val attachments = (raw["attachments"] as? List<*>)?.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("attachments[$i] はobjectである必要があります")
            val atText = m["at"]?.toString()?.takeIf { it.isNotBlank() } ?: error("attachments[$i].at が必要です")
            val bm = m["backlinks"] as? Map<*, *> ?: error("attachments[$i].backlinks が必要です")
            VirtualDocAttachment(
                label = m["label"]?.toString()?.takeIf { it.isNotBlank() },
                atText = atText,
                at = runCatching { parseAttachPattern(atText) }
                    .getOrElse { error("attachments[$i].at が不正です: ${it.message}") },
                backlinks = parseDocBacklinks(bm, "attachments[$i].backlinks"),
            )
        } ?: emptyList()
        return VirtualDocViewDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            match = match,
            schemaId = schemaId,
            sections = sections,
            attachments = attachments,
            source = source,
        )
    }

    /** 被参照表の定義（セクション・アタッチ共用）のパース。 */
    private fun parseDocBacklinks(bm: Map<*, *>, where: String): VirtualDocBacklinksDef {
        val columns = (bm["columns"] as? List<*>)?.mapIndexed { ci, c ->
            val cm = c as? Map<*, *> ?: error("$where.columns[$ci] はobjectである必要があります")
            VirtualDocBacklinkColumn(
                label = cm["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.columns[$ci].label が必要です"),
                path = parseYPath(cm["path"]?.toString() ?: error("$where.columns[$ci].path が必要です")),
            )
        } ?: emptyList()
        return VirtualDocBacklinksDef(
            idPattern = bm["idPattern"]?.toString()?.takeIf { it.isNotBlank() },
            filter = VirtualBacklinksDef(
                relation = bm["relation"]?.toString(),
                schemaIds = stringList(bm["schemaIds"]),
            ),
            columns = columns,
        )
    }

    /** アタッチ先のパスパターン。YPath 構文＋ `[*]`（任意のインデックス。schema.PathPattern）。 */
    private fun parseAttachPattern(text: String): List<Any> = PathPattern.parse(text)

    /**
     * 廃止した `computed`（lookup / source / path / values / map / default）から、同じ意味の `value` の列の書き方を作る
     * （定義エラーの文面に添える案内）。参照を辿る lookup は読み出しと入れ物の `via`、source と values は `in`、
     * 穴の解決順（source → values → 行の要素）は scopes の並びと末尾に暗黙に続く「いまの要素」で同じになる。
     * 行の出現（旧 lookup の起点。無ければ source）は列の schemaId / fileGlob にする。
     */
    private fun computedToValueHint(m: Map<*, *>): String {
        val comp = m["computed"] as? Map<*, *> ?: return ""
        val lookup = comp["lookup"] as? Map<*, *>
        val via = lookup?.get("via")?.toString()?.takeIf { it.isNotBlank() }
        val source = comp["source"] as? Map<*, *>
        fun scope(selector: Map<*, *>?): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            via?.let { put("via", it) }
            selector?.let { put("in", it) }
        }
        val template = scope(source) + mapOf("path" to comp["path"])
        val scopes = listOf(scope(source)) + (comp["values"] as? List<*>).orEmpty().map { scope(it as? Map<*, *>) }
        var expr: Any = linkedMapOf("format" to template, "scopes" to scopes)
        (comp["map"] as? Map<*, *>)?.let { expr = linkedMapOf("map" to expr, "using" to it) }
        comp["default"]?.let { expr = linkedMapOf("first" to listOf(expr, it)) }
        val column = linkedMapOf<String, Any?>("label" to m["label"])
        val from = lookup ?: source
        from?.get("schemaId")?.let { column["schemaId"] = it }
        from?.get("fileGlob")?.let { column["fileGlob"] = it }
        m["of"]?.let { column["of"] = it }
        column["value"] = expr
        return YamlIO.dumpPlain(YamlIO.fromAny(listOf(column))).trim()
    }

    /** 出現の選択条件（schemaId / fileGlob）を、定義の当該オブジェクト直下のキーから読む。 */
    /** 行の並びの値（order.path・type・direction）。path が無ければ従来の出現順で、type / direction は書けない。 */
    private fun parseOrderValue(m: Map<*, *>): dev.toros.yde.semantic.VirtualOrderValue? {
        val pathText = m["path"]?.toString()?.takeIf { it.isNotBlank() }
        if (pathText == null) {
            listOf("type", "direction").firstOrNull { m.containsKey(it) }?.let { error("order.$it は order.path（並びの値）と一緒に書きます") }
            return null
        }
        val path = runCatching { parseYPath(pathText) }.getOrElse { error("order.path が読めません: $pathText") }
        val kind = m["type"]?.toString()?.let { dev.toros.yde.semantic.schema.ValueExpr.OrderKind.parse(it, "order") }
        val descending = when (val d = m["direction"]?.toString()) {
            null, "asc" -> false
            "desc" -> true
            else -> error("order.direction は asc / desc のどちらかです: $d")
        }
        return dev.toros.yde.semantic.VirtualOrderValue(path, kind, descending)
    }

    /** 列の数の表示の形（display: grouped＝桁区切り）。 */
    private fun parseDisplay(m: Map<*, *>, i: Int): NumberDisplay? =
        runCatching { NumberDisplay.parse(m["display"]?.toString()) }.getOrElse { error("columns[$i]: ${it.message}") }

    /** 列の表示の形（format: DecimalFormat / DateTimeFormatter のパターン。画面の見せ方だけ）。 */
    private fun parseFormat(m: Map<*, *>, i: Int): String? =
        m["format"]?.toString()?.takeIf { it.isNotBlank() }?.also { format ->
            if (ValueFormat.validate(FieldType.INT, format) != null && ValueFormat.validate(FieldType.DATE, format) != null) {
                error("columns[$i]: format が数（DecimalFormat）の形としても日付（DateTimeFormatter）の形としても読めません: $format")
            }
        }

    private fun parseSource(m: Map<*, *>): VirtualViewSource = VirtualViewSource(
        schemaId = m["schemaId"]?.toString(),
        fileGlob = m["fileGlob"]?.toString(),
    )

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    // ---------------------------------------------------------------- ビュー定義リント（R14-02）

    /**
     * ビュー→スキーマの参照（schemaId・パス・definition）の整合検査。スキーマ変更でビューが黙って
     * 壊れる事態をロード時に検出する。指摘はビューを無効化しない（解決・表示は従来どおり行われる）。
     * パスの検査は「スキーマのID要素（ID型フィールドを持つ object）のいずれかから静的に解決できるか」で
     * 判定する（出現要素は必ずID要素のため。ID要素が見つからないスキーマでは判定しない＝誤検知を避ける）。
     */
    fun lint(views: List<VirtualGridDef>, docViews: List<VirtualDocViewDef>, schemas: List<WorkspaceSchema>): List<String> {
        val errors = mutableListOf<String>()
        val ids = schemas.map { it.id }.toSet()
        val byId = schemas.associateBy { it.id }
        val idElementsCache = mutableMapOf<String, List<FieldDef>>()
        fun idElementsOf(s: WorkspaceSchema): List<FieldDef> = idElementsCache.getOrPut(s.id) { idElements(s) }
        fun unresolvableFromIdElements(s: WorkspaceSchema, path: YPath): Boolean {
            if (path.isEmpty()) return false
            val candidates = idElementsOf(s)
            return candidates.isNotEmpty() && candidates.none { staticFieldAt(s, it, path) != null }
        }
        // fileGlob だけの出現選択は、代表パス（glob のワイルドカードを仮埋め）にマッチするスキーマで検査する
        // （newRow.filePattern の検査と同じ流儀。マッチするスキーマが無ければ判定しない＝誤検知を避ける。UX-36）
        fun representative(glob: String): String = glob
            .replace(Regex("\\{([^}]*)\\}")) { it.groupValues[1].substringBefore(',') }
            .replace("**", "X").replace("*", "X").replace("?", "X")
        fun schemasBy(schemaId: String?, fileGlob: String?): List<WorkspaceSchema> = when {
            schemaId != null -> listOfNotNull(byId[schemaId])
            fileGlob != null -> representative(fileGlob).let { rel -> schemas.filter { it.matches(rel) } }
            else -> emptyList()
        }
        fun schemasFor(sel: VirtualViewSource): List<WorkspaceSchema> = schemasBy(sel.schemaId, sel.fileGlob)
        fun names(sels: List<WorkspaceSchema>): String = sels.joinToString("、") { it.id }

        views.forEach { v ->
            fun report(msg: String) { errors += "${fileName(v.source)}: $msg" }
            v.rows?.schemaIds?.filterNot { it in ids }?.forEach { report("rows.schemaIds のスキーマ「$it」が見つかりません") }
            v.orderBy?.schemaId?.let { if (it !in ids) report("order のスキーマ「$it」が見つかりません") }
            v.detail?.source?.schemaId?.let { if (it !in ids) report("detail のスキーマ「$it」が見つかりません") }
            v.columns.forEach { col ->
                col.backlinks?.schemaIds?.filterNot { it in ids }?.forEach {
                    report("列「${col.label}」の参照元スキーマ「$it」が見つかりません")
                }
                if (col.backlinks != null) return@forEach
                col.value?.let { value ->
                    // 値の式の列: 読む項目（いまの要素からの読み出し・短い書き方の穴・集計のパス・参照を辿る項目）が出現のスキーマの
                    // ID要素から解決できるか。`in` / `via` で相手を指定した読み出しの path は相手のスキーマから。
                    // 集計の順序の種類（type）と項目の型の食い違いも指摘する（日付の項目は type: date で比べる）
                    col.from.schemaId?.let { sid ->
                        if (sid !in ids) { report("列「${col.label}」のスキーマ「$sid」が見つかりません"); return@forEach }
                    }
                    val expr = value.expr
                    expr.sources().mapNotNull { it.inDoc?.schemaId }.distinct().filterNot { it in ids }.forEach {
                        report("列「${col.label}」の in のスキーマ「$it」が見つかりません")
                    }
                    val fromSchemas = schemasFor(col.from)
                    if (fromSchemas.isEmpty()) return@forEach
                    val reserved = setOf(SemanticValues.RESERVED_ID, SemanticValues.RESERVED_TITLE)
                    // いまの要素から読む場所: 短い書き方の穴・of も in も via も無い読み出し（集計の短い書き方を含む）
                    val ownReads = expr.holes() +
                        expr.reads().filter { it.of == null && it.via.isEmpty() && it.inDoc == null }.map { PathPattern.pretty(it.path) }
                    ownReads.distinct().filterNot { it in reserved }.forEach { read ->
                        val pattern = runCatching { PathPattern.parse(read) }.getOrNull() ?: return@forEach
                        if (fromSchemas.all { unresolvableFromIdElements(it, pattern) }) {
                            report("列「${col.label}」の値の式が読む「$read」はスキーマ「${names(fromSchemas)}」のID要素から解決できません")
                        }
                    }
                    // 参照を辿る項目（via の最初の段）は、行のスキーマの参照（ref）型の項目。2 段目からは相手のスキーマの項目なので、解決のときに確かめる
                    expr.sources().filter { it.of == null }.mapNotNull { it.via.firstOrNull() }.distinct().forEach { via ->
                        val fields = fromSchemas.flatMap { s -> idElementsOf(s).mapNotNull { staticFieldAt(s, it, via) }.map { s.tree.resolve(it) ?: it } }
                        when {
                            fields.isEmpty() -> report("列「${col.label}」の via「${via.pretty()}」はスキーマ「${names(fromSchemas)}」のID要素から解決できません")
                            fields.none { it.type == FieldType.REF } -> report("列「${col.label}」の via「${via.pretty()}」は参照（ref）型の項目ではありません（参照の線として辿ります）")
                        }
                    }
                    // 相手を指定した読み出しの path は、相手のスキーマ（in の指定）のID要素から
                    expr.reads().filter { it.of == null && (it.via.isNotEmpty() || it.inDoc != null) }.forEach { get ->
                        val targetSchemas = schemasBy(get.inDoc?.schemaId, get.inDoc?.fileGlob)
                        if (targetSchemas.isNotEmpty() && targetSchemas.all { unresolvableFromIdElements(it, get.path) }) {
                            report("列「${col.label}」の path「${PathPattern.pretty(get.path)}」はスキーマ「${names(targetSchemas)}」のID要素から解決できません")
                        }
                    }
                    // 集計に書いた順序の種類（type）と項目の型の食い違い: 引数がいまの要素からの読み出し（短い書き方）のときだけ静的に
                    // 見られる（type を省けば項目の型で比べるので、指摘するのは書いた種類が項目の型と食い違うときだけ）
                    value.expr.aggregates()
                        .filter { it.fn != ValueExpr.AggregateFn.COUNT && it.kind != null }
                        .forEach { agg ->
                            val get = agg.input as? ValueExpr.Get ?: return@forEach
                            if (get.of != null || get.via.isNotEmpty() || get.inDoc != null) return@forEach
                            val pattern = get.path
                            val fields = fromSchemas.flatMap { s ->
                                idElementsOf(s).mapNotNull { staticFieldAt(s, it, pattern) }.map { s.tree.resolve(it) ?: it }
                            }
                            if (fields.isEmpty()) return@forEach
                            // 項目の型が持つ順序の種類（意味の基底型は連鎖の末端の型で見分ける）
                            val typed = fields.mapNotNull { ValueExpr.OrderKind.ofType(it.type) }.distinct()
                            val declared = agg.kind!!
                            val subject = "${agg.fn.id} が読む「${PathPattern.pretty(pattern)}」"
                            if (typed.isNotEmpty() && declared !in typed) {
                                report("列「${col.label}」の${subject}は${typed.joinToString(" / ") { it.label }}の項目です（type: ${declared.id} は書けません。type を省くと項目の型で比べます）")
                            } else if (typed.isEmpty() && declared.value != null) {
                                report("列「${col.label}」の${subject}は${declared.label}の項目ではありません（type: ${declared.id} は${declared.label}の項目に書きます）")
                            }
                        }
                    return@forEach
                }
                col.from.schemaId?.let { sid ->
                    if (sid !in ids) { report("列「${col.label}」のスキーマ「$sid」が見つかりません"); return@forEach }
                }
                val fromSchemas = schemasFor(col.from)
                if (fromSchemas.isNotEmpty() && fromSchemas.all { unresolvableFromIdElements(it, col.path) }) {
                    report("列「${col.label}」のパス「${col.path.pretty()}」はスキーマ「${names(fromSchemas)}」のID要素から解決できません")
                }
            }
            v.detail?.filePattern?.let { pattern ->
                if (!pattern.usesId) report(FileNameRules.message(FileNameRules.Kind.NO_ID, pattern.where))
            }
            v.newRow?.filePattern?.let { pattern ->
                // 代表ID（idPattern の * を仮埋め。クエリ行では行キー束縛の id パターン・なければ "X"）で
                // 作成先を解決し、スキーマなし・detail 認定外の事故を検出する
                val representativeId = (v.rows?.idPattern ?: v.rowKey?.let { v.rowsQuery?.bindStep(it)?.idPattern })
                    ?.replace("*", "X") ?: "X"
                val rel = DocumentScaffolds.representativePath(pattern, representativeId) ?: return@let
                val matched = schemas.firstOrNull { it.matches(rel) }
                if (matched == null) {
                    report("newRow.filePattern「${pattern.text}」の作成先にマッチするスキーマがありません（作成される文書が空になります）")
                }
                v.detail?.source?.let { src ->
                    val globOk = src.fileGlob?.let { g ->
                        runCatching { globToRegex(g).matches(rel) }.getOrDefault(true)
                    } ?: true
                    val schemaOk = src.schemaId == null || matched == null || matched.id == src.schemaId
                    if (!globOk || !schemaOk) {
                        report("newRow.filePattern「${pattern.text}」の作成先が detail の認定条件に一致しません（作成直後の行が「未作成」になります）")
                    }
                }
            }
        }

        docViews.forEach { v ->
            fun report(msg: String) { errors += "${fileName(v.source)}: $msg" }
            val docSchema = v.schemaId?.let { sid ->
                byId[sid] ?: run { report("適用先スキーマ「$sid」が見つかりません"); null }
            }
            v.sections.forEachIndexed { i, s ->
                val where = s.label ?: "sections[${i + 1}]"
                val fieldsPath = s.fieldsPath
                if (fieldsPath != null && docSchema != null) {
                    val field = staticFieldAt(docSchema, docSchema.tree.root, fieldsPath)
                    if (fieldsPath.isNotEmpty() && field == null) {
                        report("セクション「$where」のパス「${fieldsPath.pretty()}」はスキーマ「${docSchema.id}」から解決できません")
                    } else if (s.fieldsDefinition != null && field != null) {
                        // 宣言した definition を、パス先のフィールド（配列は要素・map は値）が実際に参照しているか
                        val resolved = docSchema.tree.resolve(field) ?: field
                        val actual = listOfNotNull(field, resolved.item, resolved.value)
                            .firstNotNullOfOrNull { docSchema.tree.definitionRefOf(it) }
                        if (actual != s.fieldsDefinition) {
                            report(
                                "セクション「$where」のパス「${fieldsPath.pretty()}」は definition「${s.fieldsDefinition}」を参照していません" +
                                    (actual?.let { "（実際: $it）" } ?: "")
                            )
                        }
                    }
                    // 選別キー（include / exclude）がスキーマの子フィールドに存在するか（R14-03）
                    val selection = s.fieldsInclude + s.fieldsExclude
                    if (selection.isNotEmpty() && field != null) {
                        val resolvedField = docSchema.tree.resolve(field) ?: field
                        if (resolvedField.type == FieldType.OBJECT) {
                            val keys = staticChildren(docSchema, resolvedField).mapNotNull { it.key }.toSet()
                            selection.filterNot { it in keys }.forEach {
                                report("セクション「$where」の選別キー「$it」はスキーマにありません")
                            }
                        }
                    }
                }
                s.backlinks?.let { b ->
                    b.filter.schemaIds.filterNot { it in ids }.forEach {
                        report("セクション「$where」の参照元スキーマ「$it」が見つかりません")
                    }
                    val sourceSchemas = b.filter.schemaIds.mapNotNull { byId[it] }
                    if (b.columns.isNotEmpty() && sourceSchemas.isNotEmpty()) {
                        b.columns.forEach { col ->
                            val ok = sourceSchemas.any { !unresolvableFromIdElements(it, col.path) }
                            if (!ok) {
                                report("セクション「$where」の列「${col.label}」のパス「${col.path.pretty()}」は参照元スキーマのID要素から解決できません")
                            }
                        }
                    }
                }
            }
            v.attachments.forEachIndexed { i, a ->
                val where = a.label ?: "attachments[${i + 1}]"
                a.backlinks.filter.schemaIds.filterNot { it in ids }.forEach {
                    report("アタッチ「$where」の参照元スキーマ「$it」が見つかりません")
                }
                val sourceSchemas = a.backlinks.filter.schemaIds.mapNotNull { byId[it] }
                if (a.backlinks.columns.isNotEmpty() && sourceSchemas.isNotEmpty()) {
                    a.backlinks.columns.forEach { col ->
                        val ok = sourceSchemas.any { !unresolvableFromIdElements(it, col.path) }
                        if (!ok) {
                            report("アタッチ「$where」の列「${col.label}」のパス「${col.path.pretty()}」は参照元スキーマのID要素から解決できません")
                        }
                    }
                }
                if (docSchema != null) {
                    val target = staticFieldAt(docSchema, docSchema.tree.root, a.at)
                    if (target == null) {
                        report("アタッチ「$where」の at「${a.atText}」はスキーマ「${docSchema.id}」から解決できません")
                    } else {
                        // アタッチ先の要素にIDがなければ被参照を解決できず、何も表示されない
                        val resolvedTarget = docSchema.tree.resolve(target) ?: target
                        val hasId = resolvedTarget.type == FieldType.OBJECT &&
                            staticChildren(docSchema, resolvedTarget).any { (docSchema.tree.resolve(it) ?: it).type == FieldType.ID }
                        if (!hasId) {
                            report("アタッチ「$where」の at「${a.atText}」の要素にID型フィールドがありません（被参照はIDに対して解決されます）")
                        }
                    }
                }
            }
        }
        return errors
    }

    /** スキーマ内の「ID要素」（ID型フィールドを持つ object）の候補。出現要素からのパス検査の起点に使う。 */
    fun idElements(schema: WorkspaceSchema): List<FieldDef> {
        val result = mutableListOf<FieldDef>()
        fun walk(field: FieldDef?, depth: Int, stack: Set<String>) {
            if (field == null || depth > 24) return
            // 再帰スキーマ（$ref の循環）は definition の修飾名で打ち切る（generateSample と同じ規則）
            val refKey = schema.tree.definitionRefOf(field)
            if (refKey != null && refKey in stack) return
            val nextStack = if (refKey == null) stack else stack + refKey
            val f = schema.tree.resolve(field) ?: return
            when (f.type) {
                FieldType.OBJECT -> {
                    val children = staticChildren(schema, f)
                    if (children.any { (schema.tree.resolve(it) ?: it).type == FieldType.ID }) result += f
                    children.forEach { walk(it, depth + 1, nextStack) }
                }
                FieldType.ARRAY -> walk(f.item, depth + 1, nextStack)
                FieldType.MAP -> walk(f.value, depth + 1, nextStack)
                else -> Unit
            }
        }
        walk(schema.tree.root, 0, emptySet())
        return result
    }

    /** object の静的な子フィールド候補（enum cases は全ケースの和＝「存在しうる子」。ノードなしの検査用）。 */
    fun staticChildren(schema: WorkspaceSchema, def: FieldDef): List<FieldDef> {
        val out = mutableListOf<FieldDef>()
        fun add(field: FieldDef, depth: Int) {
            out += field
            if (depth > 8) return
            val eff = schema.tree.resolve(field) ?: field
            if (eff.type == FieldType.ENUM) eff.cases.values.flatten().forEach { add(it, depth + 1) }
        }
        def.fields.forEach { add(it, 0) }
        return out
    }

    /**
     * データノードなしでスキーマ定義だけを辿るフィールド解決（リント用の `fieldAt` の静的版）。
     * 戻り値は**未解決**のフィールド（definition 判定に `$ref` を残す）。配列は任意インデックス
     * （アタッチパターンの `[*]` 含む）、map は任意キーとして辿る。解決できなければ null。
     */
    fun staticFieldAt(schema: WorkspaceSchema, start: FieldDef, path: List<Any>): FieldDef? {
        var current = start
        for (seg in path) {
            val def = schema.tree.resolve(current) ?: return null
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String -> staticChildren(schema, def).firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && (seg is Int || seg === AnyIndex) -> def.item
                else -> null
            }
            current = next ?: return null
        }
        return current
    }
}
