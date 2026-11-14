package dev.toros.yde.app.core

import dev.toros.yde.app.view.flow.FlowConnection
import dev.toros.yde.app.view.flow.FlowConnectionState
import dev.toros.yde.app.view.flow.FlowDetail
import dev.toros.yde.app.view.flow.FlowEdge
import dev.toros.yde.app.view.flow.FlowElementKind
import dev.toros.yde.app.view.flow.FlowLane
import dev.toros.yde.app.view.flow.FlowLayoutMode
import dev.toros.yde.app.view.flow.FlowLocation
import dev.toros.yde.app.view.flow.FlowModel
import dev.toros.yde.app.view.flow.FlowNode
import dev.toros.yde.semantic.DashboardFlowPosition
import dev.toros.yde.semantic.DashboardFlowResult
import dev.toros.yde.semantic.DashboardMove
import dev.toros.yde.semantic.VirtualDashboardDef
import dev.toros.yde.yaml.model.YPath

/**
 * 業務の流れの図: フロービューと同じ表示モデル（FlowModel）と、押した要素から段・工程・入口を引く対応表。
 * 配置（GraphLayouts.arrangeFlow）と描画（drawFlow）はフロービューの部品がそのまま受け持ち、図の見え方は共通になる。
 */
data class DashboardFlowDiagram(
    val model: FlowModel,
    /** 流れの向き（フロービューと同じ down / right）。 */
    val direction: String,
    val steps: Map<YPath, String>,
    val moves: Map<YPath, DashboardMove>,
    /** 入口のノード（流れに start があり、図に入口を出すとき）。 */
    val start: YPath? = null,
    /** 段の群（枠）のレーンの出力位置 → 群（区画）の id。 */
    val groups: Map<YPath, String> = emptyMap(),
) {
    /** 群があれば枠で囲む配置（フロービューの「グループ」）、無ければレーンなし。 */
    val mode: FlowLayoutMode get() = if (groups.isEmpty()) FlowLayoutMode.PLAIN else FlowLayoutMode.GROUPED

    sealed interface Target {
        data class Step(val id: String) : Target
        data class Move(val move: DashboardMove) : Target
        data object Start : Target
    }

    /** 押した要素（ノードか辺の出力位置。枠はレーンの出力位置）の行き先。入口の線は入口へ、枠は群の区画へ。 */
    fun target(kind: FlowElementKind, output: YPath): Target? = when (kind) {
        FlowElementKind.NODE -> if (output == start) Target.Start else (steps[output] ?: groups[output])?.let { Target.Step(it) }
        FlowElementKind.EDGE -> moves[output]?.let { Target.Move(it) } ?: Target.Start.takeIf { start != null && isStartEdge(output) }
    }

    fun stepPath(id: String): YPath? = (steps.entries + groups.entries).firstOrNull { it.value == id }?.key
    fun movePath(move: DashboardMove): YPath? = moves.entries.firstOrNull { it.value == move }?.key

    private fun isStartEdge(output: YPath): Boolean =
        model.edges.firstOrNull { it.output == output }?.from?.target == start
}

object DashboardFlowDiagrams {
    /** 入口のノードの鍵（区画の id と重ならない）。 */
    const val START_KEY: String = "@start"

    /** ダッシュボードの流れ図: 段は「名前・件数」、工程は「操作の名前 いま操作できる数/その段の数」と、操作できない数。 */
    fun overview(view: VirtualDashboardDef, result: DashboardFlowResult): DashboardFlowDiagram {
        val moves = result.moves.associateBy { it.move }
        return build(view, result.flow.start?.let { result.startLabel ?: it.action },
            step = { id ->
                val s = result.step(id)
                val name = s?.section?.label ?: id
                when {
                    s == null -> name
                    s.error != null -> "$name\n表示できません"
                    else -> "$name\n${s.count} 件" + if (s.terminal) "（終点）" else ""
                }
            },
            group = { id ->
                val g = result.groups.firstOrNull { it.section.id == id }
                val name = g?.section?.label ?: id
                if (g?.count != null) "$name ${g.count} 件" else name
            },
            move = { m ->
                val r = moves[m]
                if (r == null) m.action else {
                    val total = r.ready.size + r.blocked.size + r.unchecked.size
                    "${r.label} ${r.ready.size}/$total" + if (r.blocked.isNotEmpty()) "（できない ${r.blocked.size}）" else ""
                }
            })
    }

    /** 対象 1 件の位置の図: いる段に「いまここ」、そこから出る工程に可否。入口は出さない。 */
    fun position(view: VirtualDashboardDef, position: DashboardFlowPosition, label: (String) -> String?): DashboardFlowDiagram {
        val next = position.moves.associateBy { it.move }
        return build(view, null,
            step = { id ->
                val name = view.sections.firstOrNull { it.id == id }?.label ?: id
                if (id in position.steps) "$name\nいまここ" else name
            },
            group = { id -> view.sections.firstOrNull { it.id == id }?.label ?: id },
            move = { m ->
                val n = next[m]
                when {
                    n == null -> label(m.action) ?: m.action
                    n.check.enabled -> n.label
                    else -> "${n.label}（できない）"
                }
            })
    }

    /** startLabel は入口の線に載せる操作の名前（入口を出さないなら null）。入口の箱は「入口」で、工程と同じく線が操作を表す。 */
    private fun build(
        view: VirtualDashboardDef, startLabel: String?, step: (String) -> String, group: (String) -> String, move: (DashboardMove) -> String,
    ): DashboardFlowDiagram {
        val flow = requireNotNull(view.flow) { "ダッシュボード「${view.id}」には流れ（flow）がありません" }
        fun detail(path: YPath) = FlowDetail(null, listOf(FlowLocation(view.source, path)), editable = false)
        val nodes = mutableListOf<FlowNode>()
        val stepPaths = mutableMapOf<YPath, String>()
        var start: YPath? = null
        if (startLabel != null && flow.start != null) {
            start = listOf("nodes", nodes.size)
            nodes += FlowNode(start, START_KEY, "入口", "box", null, detail(listOf("flow", "start")))
        }
        // 段の群は枠（レーン）。中の段はその枠に入れ、群から出る工程は枠から出す
        val lanes = flow.groups.mapIndexed { i, g -> FlowLane(listOf("lanes", i), g.id, group(g.id)) }
        val groupPaths = lanes.associate { it.output to it.key!! }
        fun laneOf(id: String): FlowConnection? = flow.groupsOf(id).firstOrNull()?.let { g ->
            lanes.firstOrNull { it.key == g.id }?.let { FlowConnection(g.id, FlowConnectionState.RESOLVED, it.output) }
        }
        flow.steps.forEachIndexed { i, id ->
            val path = listOf<Any>("nodes", nodes.size)
            stepPaths[path] = id
            nodes += FlowNode(path, id, step(id), "rounded", laneOf(id), detail(listOf("flow", "steps", i)))
        }
        fun end(key: String): FlowConnection {
            val target = if (key == START_KEY) start
                else (stepPaths.entries + groupPaths.entries).firstOrNull { it.value == key }?.key
            return FlowConnection(key, if (target != null) FlowConnectionState.RESOLVED else FlowConnectionState.MISSING, target)
        }
        val edges = mutableListOf<FlowEdge>()
        val movePaths = mutableMapOf<YPath, DashboardMove>()
        flow.start?.takeIf { start != null }?.let { s ->
            edges += FlowEdge(listOf("edges", edges.size), START_KEY, startLabel, end(START_KEY), end(s.to), detail(listOf("flow", "start")))
        }
        flow.moves.forEachIndexed { i, m ->
            val path = listOf<Any>("edges", edges.size)
            movePaths[path] = m
            // 段に留まる工程は、その段への自己ループ（フローの配置が閉路・自己ループをまとめて置く）
            edges += FlowEdge(path, "${m.from}|${m.action}|${m.to.orEmpty()}", move(m), end(m.from), end(m.to ?: m.from), detail(listOf("flow", "moves", i)))
        }
        return DashboardFlowDiagram(FlowModel(nodes, edges, lanes), flow.direction.id, stepPaths, movePaths, start, groupPaths)
    }
}
