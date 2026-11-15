package dev.toros.yde.workspace

/**
 * 業務モデルの項のビューの側（ADR-0033）: ビュー（表・集計・チャート）とダッシュボードと、ダッシュボードからたどれる範囲。
 * 項の材料はビューの定義の原文（`.yde/views`）と、業務モデルの項のエンティティ型と操作。文書（データ）は読まない。
 *
 * - **views**: ビューの ID・名前・種類（grid・matrix・chart など）と、行のエンティティ型（`rows`）・列の見出しと読む項目（`columns`）
 * - **dashboards**: ダッシュボードの絞り込みの入力・上部の操作・区画と、そこからたどれる範囲（`reach`）
 * - **reach**: どのダッシュボードからかたどれる範囲の和
 *
 * たどれる範囲は、YDE の閲覧専用モードでダッシュボードからたどれるもの:
 * 区画のビューの行と数えた要素のエンティティ型 → その型の参照の項目の参照先（たどれるだけたどる）＝`entities`、
 * たどれる型の中に入れ子で書かれた型＝`nested`、上部の操作と、たどれる型・入れ子の型を受け手にする操作＝`operations`。
 * 業務の流れ（`flow`）の図は、いまは数えない。
 */
internal object BusinessModelViews {

    /** ビューとダッシュボードの項と、たどれる範囲の和。entities・actions は業務モデルの項のもの。 */
    fun terms(viewDocuments: List<Map<String, Any?>>, entities: List<Map<String, Any?>>, actions: List<Map<String, Any?>>): Map<String, Any?> {
        val views = viewDocuments.filter { it["kind"] != "dashboard" }.mapNotNull(::viewTerm)
        val byId = views.associateBy { it["id"].toString() }
        val dashboards = viewDocuments.filter { it["kind"] == "dashboard" }.mapNotNull { dashboardTerm(it, byId, entities, actions) }
        @Suppress("UNCHECKED_CAST")
        fun union(key: String) = dashboards.flatMap { ((it["reach"] as Map<String, Any?>)[key] as List<String>) }.distinct()
        return linkedMapOf(
            "views" to views,
            "dashboards" to dashboards,
            "reach" to linkedMapOf("entities" to union("entities"), "nested" to union("nested"), "operations" to union("operations")),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
    private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

    /** クエリの照合（match）の最初の node のエンティティ型。 */
    private fun firstEntity(match: Any?): String? = match.asList().firstNotNullOfOrNull { it.asMap()?.get("node").asMap()?.get("entity")?.toString() }

    /** 照合の中で、名前（bind）を付けた node のエンティティ型。 */
    private fun boundEntity(match: Any?, bind: String?): String? =
        match.asList().mapNotNull { it.asMap()?.get("node").asMap() }.firstOrNull { it["bind"] == bind }?.get("entity")?.toString()

    private fun viewTerm(m: Map<String, Any?>): Map<String, Any?>? {
        val id = m["id"]?.toString() ?: return null
        val kind = m["kind"]?.toString() ?: "grid"
        val out = LinkedHashMap<String, Any?>()
        out["id"] = id
        out["label"] = m["name"] ?: id
        out["kind"] = kind
        m["description"]?.let { out["description"] = it }
        m["params"]?.let { out["params"] = it }
        when (kind) {
            "grid" -> {
                firstEntity(m["rows"].asMap()?.get("match"))?.let { out["rows"] = it }
                out["columns"] = m["columns"].asList().mapNotNull { c ->
                    val cm = c.asMap() ?: return@mapNotNull null
                    linkedMapOf<String, Any?>(
                        "label" to cm["label"], "schema" to cm["schemaId"], "path" to cm["path"],
                        "computed" to (cm["value"] != null).takeIf { it }, "editable" to cm["editable"],
                    ).filterValues { it != null }
                }
                m["detail"]?.let { out["detail"] = it }
            }
            "matrix" -> {
                val cells = m["cells"].asMap()
                firstEntity(cells?.get("match"))?.let { out["rows"] = it }
                boundEntity(cells?.get("match"), cells?.get("of")?.toString())?.let { out["counts"] = it }
                cells?.get("columns")?.let { out["columns"] = it }
            }
            "chart" -> m["view"]?.let { out["source"] = it }
        }
        return out
    }

    private fun dashboardTerm(
        m: Map<String, Any?>, views: Map<String, Map<String, Any?>>, entities: List<Map<String, Any?>>, actions: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        val id = m["id"]?.toString() ?: return null
        val sections = m["sections"].asList().mapNotNull { s ->
            val sm = s.asMap() ?: return@mapNotNull null
            linkedMapOf<String, Any?>("id" to sm["id"], "label" to sm["label"], "group" to sm["group"], "view" to sm["view"], "filter" to sm["filter"])
                .filterValues { it != null }
        }
        val actionIds = m["actions"].asList().map { it.toString() }
        val out = LinkedHashMap<String, Any?>()
        out["id"] = id
        out["label"] = m["name"] ?: id
        m["description"]?.let { out["description"] = it }
        out["default"] = m["default"] == true
        out["inputs"] = m["inputs"].asList()
        out["actions"] = actionIds
        out["sections"] = sections
        out["reach"] = reach(sections.mapNotNull { views[it["view"].toString()] }, views, actionIds, entities, actions)
        return out
    }

    /** ダッシュボードからたどれる範囲（エンティティ型・入れ子の型・操作）。 */
    private fun reach(
        sectionViews: List<Map<String, Any?>>, views: Map<String, Map<String, Any?>>, dashboardActions: List<String>,
        entities: List<Map<String, Any?>>, actions: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val byEntity = entities.associateBy { it["id"].toString() }
        // チャートは元の表の行をたどる
        val shown = sectionViews.map { v -> v["source"]?.let { views[it.toString()] } ?: v }
        val reached = LinkedHashSet<String>()
        val queue = ArrayDeque(shown.flatMap { listOfNotNull(it["rows"]?.toString(), it["counts"]?.toString()) })
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            if (e !in byEntity || !reached.add(e)) continue
            refTargets(byEntity.getValue(e)).forEach { queue += it }
        }
        val nested = entities.filter { it["owner"].asMap()?.get("entity")?.toString() in reached }.map { it["id"].toString() }
            .filter { it !in reached }
        val receivers = reached + nested
        val operations = (dashboardActions + actions.filter { a -> a["target"].asMap()?.get("entity")?.toString() in receivers }.map { it["id"].toString() })
            .distinct()
        return linkedMapOf("entities" to reached.toList(), "nested" to nested, "operations" to operations)
    }

    /** エンティティ型の項の、参照の項目の参照先（側面の項目と、配列の要素）。 */
    private fun refTargets(entity: Map<String, Any?>): List<String> =
        entity["aspects"].asList().flatMap { a -> a.asMap()?.get("fields").asList() }.flatMap { f ->
            val fm = f.asMap() ?: return@flatMap emptyList()
            listOfNotNull(fm["target"], fm["item"].asMap()?.get("target")).flatMap { t -> if (t is List<*>) t.map { it.toString() } else listOf(t.toString()) }
        }
}
