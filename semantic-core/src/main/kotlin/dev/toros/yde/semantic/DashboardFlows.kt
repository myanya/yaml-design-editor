package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import java.time.LocalDate

/** 流れ図の向き。 */
enum class DashboardFlowDirection(val id: String) {
    RIGHT("right"),
    DOWN("down"),
    ;

    companion object {
        fun parse(text: String?, where: String): DashboardFlowDirection =
            entries.firstOrNull { it.id == text } ?: error("$where は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")
    }
}

/** 工程: 段（区画）の対象に、操作を当てて次の段へ移す。to が null なら段に留まる操作（明細を足すなど）。 */
data class DashboardMove(val from: String, val action: String, val to: String? = null) {
    val stays: Boolean get() = to == null || to == from
}

/**
 * 段の群（枠）: 区画（id）の中に段（steps）を囲む。群から出る工程（from が群の id）は、中のどの段の対象にも当たる。
 * Lifecycle の詳細化（ADR-0030）の親から作る（区画の filter は「子のどれか」）。
 */
data class DashboardFlowGroup(val id: String, val steps: List<String>)

/** 流れの入口: 受け手を持たない操作で、作った対象が入る段。 */
data class DashboardFlowStart(val action: String, val to: String)

/** 業務詳細に出す履歴: 対象の要素の配列（行の日付・操作・前と後の状態・メモの項目名）。 */
data class DashboardHistory(
    val path: String,
    val date: String = "date",
    val action: String = "action",
    val from: String = "from",
    val to: String = "to",
    val note: String = "note",
)

/**
 * ダッシュボードの流れ（`flow`）。区画を段、操作を工程として並べる見せ方で、原本・操作の定義は変えない。
 * 段にいるかは区画の filter（Predicate）、件数は区画の行（Aggregate）、工程の可否は操作の前提（入口がセッションで判定して渡す）。
 */
data class DashboardFlow(
    val steps: List<String>,
    val moves: List<DashboardMove>,
    val start: DashboardFlowStart? = null,
    val direction: DashboardFlowDirection = DashboardFlowDirection.RIGHT,
    val history: DashboardHistory? = null,
    /** 段の群（枠）。群から出る工程は、中の段から出る工程として扱う。 */
    val groups: List<DashboardFlowGroup> = emptyList(),
) {
    /** 段を囲む群。 */
    fun groupsOf(step: String): List<DashboardFlowGroup> = groups.filter { step in it.steps }

    /** 段から出る工程（段を囲む群から出る工程を含む）。 */
    fun movesFrom(step: String): List<DashboardMove> {
        val around = groupsOf(step).map { it.id }.toSet()
        return moves.filter { it.from == step || it.from in around }
    }

    /** 出る工程（別の段へ移す工程）が無い段＝終点。 */
    fun terminal(step: String): Boolean = movesFrom(step).none { !it.stays }
}

/** 操作の可否（ActionPlanner.availability の写し。画面・CLI がそれぞれのセッションで判定して渡す）。 */
data class DashboardMoveCheck(val enabled: Boolean, val reason: String? = null, val needsInput: Boolean = false)

data class DashboardFlowStepResult(
    val section: DashboardSection,
    /** 段にいる対象の数（区画の件数と同じ。解決できなければ null）。 */
    val count: String?,
    val terminal: Boolean,
    val error: String? = null,
)

data class DashboardFlowBlocked(val row: VirtualRow, val reason: String)

data class DashboardFlowMoveResult(
    val move: DashboardMove,
    /** 操作の名前（定義の label。無ければ id）。 */
    val label: String,
    /** いま操作できる対象（入力の後に確かめる前提があるものを含む）。 */
    val ready: List<VirtualRow>,
    /** 前提を満たさない対象と理由。 */
    val blocked: List<DashboardFlowBlocked>,
    /** 判定を省いた対象（段の対象が上限を超えたとき）。 */
    val unchecked: List<VirtualRow> = emptyList(),
)

data class DashboardFlowResult(
    val flow: DashboardFlow,
    val steps: List<DashboardFlowStepResult>,
    val moves: List<DashboardFlowMoveResult>,
    val startLabel: String? = null,
    /** 段の群（枠）の区画と件数（終点は持たない）。 */
    val groups: List<DashboardFlowStepResult> = emptyList(),
) {
    fun step(id: String): DashboardFlowStepResult? = steps.firstOrNull { it.section.id == id }

    /** 段か群の区画（工程の from は群のこともある）。 */
    fun section(id: String): DashboardFlowStepResult? = step(id) ?: groups.firstOrNull { it.section.id == id }
}

data class DashboardFlowPositionMove(val move: DashboardMove, val label: String, val check: DashboardMoveCheck)

/** 履歴の 1 行（表示用の文字。値は原本の値、見出しは選択肢の label）。 */
data class DashboardHistoryRow(
    val date: String?,
    val action: String?,
    val actionLabel: String?,
    val from: String?,
    val to: String?,
    val note: String?,
)

/** 対象 1 件の流れの中の位置: いる段・判定できなかった段・次にできる工程・履歴。 */
data class DashboardFlowPosition(
    val id: String,
    val steps: List<String>,
    val undetermined: List<Pair<String, String>> = emptyList(),
    val moves: List<DashboardFlowPositionMove> = emptyList(),
    val history: List<DashboardHistoryRow> = emptyList(),
)

/** 移り先の予告: 操作の前と後で、対象がいる段。 */
data class DashboardFlowTransition(
    val id: String,
    val before: List<String>,
    val after: List<String>,
    /** 流れに宣言した工程（この操作の、前の段からの移り先）。 */
    val declared: List<String?>,
) {
    /** 前と後で段が変わった。 */
    val moved: Boolean get() = before.toSet() != after.toSet()

    /**
     * 宣言にない移り方: 段が変わり、移った先が宣言した移り先（留まる工程なら前の段）のどれでもない。
     * 流れの外の操作でも段を変えれば宣言外。段を変えない操作と、前に段にいない対象は判定しない。
     */
    val undeclared: Boolean get() = before.isNotEmpty() && moved &&
        after.none { a -> declared.any { t -> if (t == null) a in before else t == a } }
}

object DashboardFlows {
    /** 1 つの工程で前提を確かめる対象の上限（段の対象がそれ以上なら残りは「判定を省いた」に入る）。 */
    const val CHECK_LIMIT: Int = 500

    /** 定義だけで分かる点検（段・工程・入口の整合と、入口からの到達）。誤りの文を返す。 */
    fun lint(dashboard: VirtualDashboardDef): List<String> {
        val flow = dashboard.flow ?: return emptyList()
        val errors = mutableListOf<String>()
        val sections = dashboard.sections.associateBy { it.id }
        flow.steps.filter { it !in sections }.forEach { errors += "flow.steps の「$it」は区画（sections）の id ではありません" }
        val groups = flow.groups.map { it.id }.toSet()
        flow.groups.forEach { g ->
            if (g.id !in sections) errors += "flow の群「${g.id}」は区画（sections）の id ではありません"
            if (g.id in flow.steps) errors += "flow の群「${g.id}」は段（steps）とは別の区画にします"
            g.steps.filter { it !in flow.steps }.forEach { errors += "flow の群「${g.id}」の段「$it」は flow.steps にありません" }
        }
        flow.moves.forEach { m ->
            if (m.from !in flow.steps && m.from !in groups) errors += "flow.moves の from「${m.from}」は flow.steps にありません"
            if (m.to != null && m.to !in flow.steps) errors += "flow.moves の to「${m.to}」は flow.steps にありません"
        }
        flow.start?.let { if (it.to !in flow.steps) errors += "flow.start.to「${it.to}」は flow.steps にありません" }
        if (errors.isNotEmpty()) return errors
        // 入口（無ければ最初の段）から工程で辿れない段は、流れの外にある
        val origin = flow.start?.to ?: flow.steps.first()
        val reached = linkedSetOf(origin)
        val queue = ArrayDeque(listOf(origin))
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            flow.movesFrom(step).mapNotNull { it.to }.filter { reached.add(it) }.forEach(queue::addLast)
        }
        flow.steps.filter { it !in reached }.forEach { errors += "flow.steps の「$it」へは、入口の段「$origin」からどの工程でも着きません" }
        return errors
    }

    /**
     * 段の件数と工程ごとの可否。段の対象は区画の行（共有の入力で絞った結果。件数と同じ母集団）で、工程は from の段の対象ごとに
     * 操作の前提を確かめる（`check`）。区画を解決できなければ件数は null。
     */
    fun resolve(
        dashboard: VirtualDashboardDef,
        result: DashboardResolution,
        label: (String) -> String?,
        check: (action: String, target: String) -> DashboardMoveCheck,
        limit: Int = CHECK_LIMIT,
    ): DashboardFlowResult? {
        val flow = dashboard.flow ?: return null
        val sections = result.sections.associateBy { it.definition.id }
        val steps = flow.steps.mapNotNull { step ->
            val section = sections[step] ?: return@mapNotNull null
            DashboardFlowStepResult(section.definition, if (section.error == null) section.count else null, flow.terminal(step), section.error)
        }
        val groups = flow.groups.mapNotNull { g ->
            val section = sections[g.id] ?: return@mapNotNull null
            DashboardFlowStepResult(section.definition, if (section.error == null) section.count else null, false, section.error)
        }
        val moves = flow.moves.map { move ->
            val rows = sections[move.from]?.takeIf { it.error == null }?.rows.orEmpty().filter { it.occurrences.isNotEmpty() }
            val ready = mutableListOf<VirtualRow>()
            val blocked = mutableListOf<DashboardFlowBlocked>()
            rows.take(limit).forEach { row ->
                val c = check(move.action, row.id)
                if (c.enabled) ready += row else blocked += DashboardFlowBlocked(row, c.reason ?: "前提を満たしません")
            }
            DashboardFlowMoveResult(move, label(move.action) ?: move.action, ready, blocked, rows.drop(limit))
        }
        return DashboardFlowResult(flow, steps, moves, flow.start?.let { label(it.action) ?: it.action }, groups)
    }

    /**
     * 対象 1 件がいる段: 段の区画の filter をその対象を起点に判定する（基準日だけを渡し、共有の入力は使わない＝絞らない）。
     * filter の無い段は、どの対象もいるとみなす。判定できない段は理由つきで返す。
     */
    fun stepsOf(
        dashboard: VirtualDashboardDef,
        id: String,
        date: LocalDate,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        loader: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
    ): Pair<List<String>, List<Pair<String, String>>> {
        val flow = dashboard.flow ?: return emptyList<String>() to emptyList()
        if (ids[id].isNullOrEmpty()) return emptyList<String>() to emptyList()
        val optional = dashboard.inputs.map { it.key }.toSet()
        val inside = mutableListOf<String>()
        val undetermined = mutableListOf<Pair<String, String>>()
        flow.steps.forEach { step ->
            val section = dashboard.sections.firstOrNull { it.id == step } ?: return@forEach
            val rule = section.filter?.ruleOrNull(mapOf("today" to date.toString()), optional)
            if (rule == null) { inside += step; return@forEach }
            val r = SemanticRules.evaluate(ids, refs, graph, rule, id, loader = loader, fieldAt = fieldAt)
            when {
                r.outcome == CheckOutcome.SATISFIED -> inside += step
                r.outcome == CheckOutcome.WITHHELD || r.reason == CheckReason.NO_VALUE ->
                    undetermined += step to (r.reason?.text ?: "判定できません")
            }
        }
        return inside to undetermined
    }

    /** 対象 1 件の流れの中の位置（いる段・次の工程とその可否・履歴）。 */
    fun position(
        dashboard: VirtualDashboardDef,
        id: String,
        date: LocalDate,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        loader: (DocumentId) -> YNode?,
        label: (String) -> String?,
        check: (action: String, target: String) -> DashboardMoveCheck,
        fieldAt: FieldResolver = NO_FIELDS,
    ): DashboardFlowPosition? {
        val flow = dashboard.flow ?: return null
        val (steps, undetermined) = stepsOf(dashboard, id, date, ids, refs, graph, loader, fieldAt)
        val moves = steps.flatMap { flow.movesFrom(it) }.distinct().map { move ->
            DashboardFlowPositionMove(move, label(move.action) ?: move.action, check(move.action, id))
        }
        return DashboardFlowPosition(id, steps, undetermined, moves, history(flow, id, ids, loader, label, fieldAt))
    }

    /** 移り先の予告の材料: 宣言した工程のうち、この操作で前の段から出るもの（移り先。留まる工程は null）。 */
    fun declaredTargets(dashboard: VirtualDashboardDef, before: List<String>, action: String): List<String?> {
        val flow = dashboard.flow ?: return emptyList()
        return before.flatMap { flow.movesFrom(it) }.filter { it.action == action }.map { if (it.stays) null else it.to }
    }

    /** 履歴の行（その ID の出現の要素の配列。定義の出現を優先。新しい行が後ろ）。 */
    private fun history(
        flow: DashboardFlow,
        id: String,
        ids: Map<String, List<ScannedId>>,
        loader: (DocumentId) -> YNode?,
        label: (String) -> String?,
        fieldAt: FieldResolver,
    ): List<DashboardHistoryRow> {
        val h = flow.history ?: return emptyList()
        // 定義の出現を先に、無ければ詳細化の出現も順に見て、path の配列がある最初の出現から読む
        // （一覧の行と個票のように、状態と履歴が詳細化の側面にあることがある）
        val (occurrence, arrayPath, array) = SemanticExpression.preferDefining(ids[id].orEmpty()).firstNotNullOfOrNull { occ ->
            val root = loader(occ.document) ?: return@firstNotNullOfOrNull null
            val path: YPath = occ.path + parseYPath(h.path)
            (getAt(root, path) as? YNode.Seq)?.let { Triple(occ, path, it) }
        } ?: return emptyList()
        return array.items.mapIndexedNotNull { i, item ->
            val row = item as? YNode.Map ?: return@mapIndexedNotNull null
            fun text(key: String): String? = row.entries[key]?.let { scalarText(it) }?.takeIf { it.isNotBlank() }
            // 選択肢の値は見出しに（項目の定義が引ければ）
            fun shown(key: String): String? = text(key)?.let { v ->
                fieldAt(occurrence.document, arrayPath + listOf(i, key))?.optionFor(v)?.displayLabel ?: v
            }
            val action = text(h.action)
            DashboardHistoryRow(text(h.date), action, action?.let(label), shown(h.from), shown(h.to), text(h.note))
        }
    }
}
