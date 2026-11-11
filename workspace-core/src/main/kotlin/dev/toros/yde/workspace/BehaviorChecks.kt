package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.LifecycleDef
import dev.toros.yde.semantic.schema.StateDeclaration
import dev.toros.yde.semantic.schema.StateDef
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.semantic.schema.expand

/**
 * State / Lifecycle 宣言のリンクと静的点検。状態の真偽判定や Action 実行は既存の
 * SemanticRules / ActionPlanner の責務とし、ここでは新たな評価・書込経路を作らない。
 */
object BehaviorChecks {
    /** 展開済みのカタログ。利用者の enum の値をそのまま State ID に展開する。 */
    data class Catalog(
        val states: List<StateDef>,
        val lifecycles: List<LifecycleDef>,
        val errors: List<String>,
    )

    fun loadErrors(schemas: List<WorkspaceSchema>): List<String> = compile(schemas).errors

    fun compile(schemas: List<WorkspaceSchema>): Catalog {
        val errors = mutableListOf<String>()
        val states = mutableListOf<StateDef>()
        val lifecycles = mutableListOf<LifecycleDef>()
        val stateSources = mutableMapOf<String, String>()
        val lifecycleSources = mutableMapOf<String, String>()
        fun addState(state: StateDef, source: String) {
            val previous = stateSources.putIfAbsent(state.id, source)
            if (previous != null) errors += "$source: State「${state.id}」が重複しています（定義元: $previous）"
            else states += state
        }
        for (schema in schemas) {
            val source = schema.source.value
            for ((i, declaration) in schema.states.withIndex()) {
                when (declaration) {
                    is StateDeclaration.Named -> addState(declaration.definition, source)
                    is StateDeclaration.FromEnum -> runCatching { declaration.expand(schema.tree) }
                        .onSuccess { expanded -> expanded.forEach { addState(it, source) } }
                        .onFailure { errors += "$source [states[$i]]: ${it.message}" }
                }
            }
            for ((i, lifecycle) in schema.lifecycles.withIndex()) {
                val previous = lifecycleSources.putIfAbsent(lifecycle.id, source)
                if (previous != null) {
                    errors += "$source [lifecycles[$i]]: Lifecycle「${lifecycle.id}」が重複しています（定義元: $previous）"
                } else lifecycles += lifecycle
            }
        }

        val byState = states.associateBy { it.id }
        val enumGroups = states.filter { it.enumOrigin != null }.groupBy { it.enumOrigin }
        val actions = schemas.flatMap { it.actions }.groupBy { it.id }
        for (lifecycle in lifecycles) {
            val source = lifecycleSources.getValue(lifecycle.id)
            val at = "$source [lifecycle:${lifecycle.id}]"
            fun issue(message: String) { errors += "$at: $message" }
            val members = lifecycle.states.toSet()
            if (members.size != lifecycle.states.size) issue("states に重複した State があります")
            lifecycle.states.forEach { id ->
                val state = byState[id]
                when {
                    state == null -> issue("State「$id」が定義されていません")
                    state.entity != lifecycle.entity -> issue("State「$id」の対象 entity「${state.entity}」は「${lifecycle.entity}」と異なります")
                }
            }
            // 一つの enum から作られた State の一部しか含まない Lifecycle は網羅性を欠く。
            lifecycle.states.mapNotNull { byState[it]?.enumOrigin }.distinct().forEach { origin ->
                val missing = enumGroups[origin].orEmpty().map { it.id }.filter { it !in members }
                if (missing.isNotEmpty()) issue("enum 由来の State が網羅されていません: ${missing.joinToString(", ")}")
            }
            fun member(id: String, where: String) {
                if (id !in members) issue("$where の State「$id」は lifecycle.states に含まれません")
            }
            fun cause(cause: TransitionCause, where: String) {
                when (cause) {
                    is TransitionCause.Action -> {
                        val matching = actions[cause.id].orEmpty()
                        when {
                            matching.isEmpty() -> issue("$where の Action「${cause.id}」が見つかりません")
                            matching.size > 1 -> issue("$where の Action「${cause.id}」が複数あります（原因を一意に特定できません）")
                            // Action は別の Entity を操作し、その影響でこの Lifecycle の State を変えうる。
                            // 対象 Entity の一致は要求しない。実際の遷移の照合は BehaviorRuntime が行う。
                        }
                    }
                    is TransitionCause.Event -> issue("$where の Event「${cause.id}」はまだ定義カタログがないため検証できません")
                }
            }
            lifecycle.entry.forEachIndexed { i, entry ->
                member(entry.to, "entry[$i].to")
                cause(entry.by, "entry[$i].by")
            }
            lifecycle.transitions.forEachIndexed { i, transition ->
                member(transition.from, "transitions[$i].from")
                member(transition.to, "transitions[$i].to")
                cause(transition.by, "transitions[$i].by")
            }
            lifecycle.terminal.forEach { member(it, "termination.terminal") }
            if (lifecycle.terminal.size != lifecycle.terminal.toSet().size) issue("termination.terminal に重複があります")
            val terminal = lifecycle.terminal.toSet()
            lifecycle.transitions.filter { it.from in terminal }.forEach {
                issue("終端 State「${it.from}」から「${it.to}」への遷移は定義できません")
            }
            if (!lifecycle.openEnded) {
                lifecycle.states.filter { it !in terminal && lifecycle.transitions.none { t -> t.from == it } }.forEach {
                    issue("非終端 State「$it」には次の遷移が必要です（無期限に留まるなら openEnded: true）")
                }
            }
            // 各 entry から、宣言された遷移だけを辿って到達できるか。循環は訪問済み集合で必ず停止する。
            val reached = lifecycle.entry.map { it.to }.filter { it in members }.toMutableSet()
            var changed: Boolean
            do {
                val before = reached.size
                lifecycle.transitions.filter { it.from in reached && it.to in members }.forEach { reached += it.to }
                changed = reached.size != before
            } while (changed)
            (members - reached).forEach { issue("State「$it」は entry から到達できません") }
            if (!lifecycle.openEnded) {
                val canFinish = terminal.filter { it in members }.toMutableSet()
                do {
                    val before = canFinish.size
                    lifecycle.transitions.filter { it.to in canFinish && it.from in members }.forEach { canFinish += it.from }
                    changed = canFinish.size != before
                } while (changed)
                (members - canFinish).forEach { issue("State「$it」から終端に到達できません（openEnded: true なら無期限の継続を許容します）") }
            }
        }
        return Catalog(states, lifecycles, errors)
    }
}
