package dev.toros.yde.workspace

/**
 * 条件に State を名前で書く（`where: [{of: t, state: todo-ACTIVE}]`。UX-315）: 読み込みのときに、その State の条件
 * （値の項目の `equals` / `oneOf`）へ置き換える。検査の語彙（CheckCatalog）と同じく、評価の前に基本の書き方へ展開する
 * 名前付きの述語の置き換えで、新しい評価器は持たない。
 *
 * 置き換えられるのは、enum から作った State と、その詳細化の親（子のどれか＝値の oneOf）。述語で書いた State は、
 * 条件 1 つに下ろせないので書けない（誤り）。状態の一覧と「未完了」の中身は状態の流れ（behavior）だけが持ち、
 * 操作の前提・検査・ダッシュボードの区画は State の名前で指す（状態を足しても、指す側を直さずに済む）。
 */
object StateConditions {
    /** State 1 つの条件: エンティティの要素の、値の項目（path）が values のどれか。 */
    data class Lowering(val entity: String, val path: String, val values: List<String>)

    /** 置き換えられる State の条件の一覧（enum から作った State と、葉がそれだけの詳細化の親）。 */
    fun loweringsOf(catalog: BehaviorChecks.Catalog): Map<String, Lowering> {
        val byId = catalog.states.associateBy { it.id }
        val result = linkedMapOf<String, Lowering>()
        for (state in catalog.states) {
            val leaves = catalog.leavesOf(state.id).map { byId[it] }
            val first = leaves.firstOrNull() ?: continue
            val path = first.enumPath ?: continue
            if (leaves.any { it?.enumValue == null || it.enumPath != path || it.entity != first.entity }) continue
            result[state.id] = Lowering(state.entity, path, leaves.map { it!!.enumValue!! })
        }
        return result
    }

    /** 生の定義のどこかの where に、State を名前で書いた条件があるか。 */
    fun uses(raw: Any?): Boolean = when (raw) {
        is Map<*, *> -> raw.any { (k, v) -> (k == "where" && v is List<*> && v.any(::hasState)) || uses(v) }
        is List<*> -> raw.any(::uses)
        else -> false
    }

    private fun hasState(item: Any?): Boolean {
        val m = item as? Map<*, *> ?: return false
        return "state" in m || (m["not"] as? Map<*, *>)?.containsKey("state") == true ||
            (m["or"] as? List<*>)?.any { hasState(it) } == true
    }

    /**
     * 生の定義の where の State の条件を置き換えた写しを返す（元は変えない）。見つからない State・置き換えられない State は
     * errors に足し、その条件はそのまま残す（評価では判定保留）。
     */
    fun expand(raw: Any?, lowerings: Map<String, Lowering>, errors: MutableList<String>): Any? = when (raw) {
        is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { out ->
            raw.forEach { (k, v) ->
                out[k] = if (k == "where" && v is List<*>) v.map { clause(it, lowerings, errors) } else expand(v, lowerings, errors)
            }
        }
        is List<*> -> raw.map { expand(it, lowerings, errors) }
        else -> raw
    }

    private fun clause(item: Any?, lowerings: Map<String, Lowering>, errors: MutableList<String>): Any? {
        val m = item as? Map<*, *> ?: return item
        return when {
            "or" in m -> mapOf("or" to (m["or"] as? List<*>)?.map { leaf(it, lowerings, errors) })
            "not" in m -> mapOf("not" to leaf(m["not"], lowerings, errors))
            else -> leaf(m, lowerings, errors)
        }
    }

    private fun leaf(item: Any?, lowerings: Map<String, Lowering>, errors: MutableList<String>): Any? {
        val m = item as? Map<*, *> ?: return item
        val raw = m["state"] ?: return item
        val ids = (raw as? List<*>)?.map { it.toString() } ?: listOf(raw.toString())
        val found = ids.map { id ->
            lowerings[id] ?: run {
                errors += "where の state「$id」は、enum から作った State か、その詳細化の親ではありません（見つからないか、述語で定義された State です）"
                return item
            }
        }
        if (found.map { it.path }.distinct().size > 1 || found.map { it.entity }.distinct().size > 1) {
            errors += "where の state（${ids.joinToString(", ")}）は、同じエンティティの同じ項目の State だけを並べます"
            return item
        }
        val values = found.flatMap { it.values }.distinct()
        return linkedMapOf<String, Any?>().apply {
            m["of"]?.let { put("of", it) }
            put("path", found.first().path)
            if (values.size == 1) put("equals", values.single()) else put("oneOf", values)
        }
    }
}
