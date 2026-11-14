package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.EventDef
import dev.toros.yde.semantic.schema.LifecycleDef
import dev.toros.yde.semantic.schema.LifecycleEntry
import dev.toros.yde.semantic.schema.LifecycleTransition
import dev.toros.yde.semantic.schema.RefinementDef
import dev.toros.yde.semantic.schema.RefinementExit
import dev.toros.yde.semantic.schema.StateDeclaration
import dev.toros.yde.semantic.schema.StateDef
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.semantic.schema.expand

/**
 * State / Event / Lifecycle 宣言のリンクと静的点検。状態の真偽判定や Action 実行は既存の
 * SemanticRules / ActionPlanner の責務とし、ここでは新たな評価・書込経路を作らない。
 *
 * State の詳細化（refinements。ADR-0030）は、ここで Lifecycle を葉の State へ展開する。書いたままの
 * Lifecycle（粗い流れ）はそれだけで点検し、展開した Lifecycle を実行・操作・業務の流れが読む。
 */
object BehaviorChecks {
    /** 展開済みのカタログ。利用者の enum の値をそのまま State ID に展開する。 */
    data class Catalog(
        val states: List<StateDef>,
        /** 葉の State へ展開した Lifecycle（詳細化した State の位置に、その中の流れを埋め込む）。 */
        val lifecycles: List<LifecycleDef>,
        val errors: List<String>,
        val events: List<EventDef> = emptyList(),
        /** 詳細化した State → 直接の子。 */
        val children: Map<String, List<String>> = emptyMap(),
    ) {
        /** State の葉（詳細化していなければ自分）。 */
        fun leavesOf(id: String): List<String> = leaves(id, children)

        /** 詳細化した State の子孫（子・孫…。詳細化していなければ空）。 */
        fun descendantsOf(id: String): Set<String> {
            val found = linkedSetOf<String>()
            val stack = ArrayDeque(children[id].orEmpty())
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                if (found.add(next)) stack.addAll(children[next].orEmpty())
            }
            return found
        }

        /**
         * State の並びを、葉が全部そろった親でまとめる（表示用。「できるのは「未完了」のとき」）。
         * まとめられる親のうち外側を選び、並びは最初に出た位置に置く。
         */
        fun collapse(ids: List<String>): List<String> {
            if (children.isEmpty()) return ids.distinct()
            val present = ids.flatMap(::leavesOf).toSet()
            val parentOf = children.flatMap { (parent, kids) -> kids.map { it to parent } }.toMap()
            fun top(id: String): String {
                var best = id
                var current = parentOf[id]
                while (current != null) {
                    if (leavesOf(current).all { it in present }) best = current
                    current = parentOf[current]
                }
                return best
            }
            return ids.map(::top).distinct()
        }
    }

    fun loadErrors(schemas: List<WorkspaceSchema>): List<String> = compile(schemas).errors

    fun compile(schemas: List<WorkspaceSchema>): Catalog {
        val errors = mutableListOf<String>()
        val states = mutableListOf<StateDef>()
        val lifecycles = mutableListOf<LifecycleDef>()
        val events = mutableListOf<EventDef>()
        val composites = linkedMapOf<String, StateDeclaration.Composite>()
        val refinements = linkedMapOf<String, RefinementDef>()
        val stateSources = mutableMapOf<String, String>()
        val lifecycleSources = mutableMapOf<String, String>()
        val eventSources = mutableMapOf<String, String>()
        val refinementSources = mutableMapOf<String, String>()
        fun claimState(id: String, source: String): Boolean {
            val previous = stateSources.putIfAbsent(id, source)
            if (previous != null) errors += "$source: State「$id」が重複しています（定義元: $previous）"
            return previous == null
        }
        for (schema in schemas) {
            val source = schema.source.value
            for ((i, declaration) in schema.states.withIndex()) {
                when (declaration) {
                    is StateDeclaration.Named -> if (claimState(declaration.definition.id, source)) states += declaration.definition
                    is StateDeclaration.Composite -> if (claimState(declaration.id, source)) composites[declaration.id] = declaration
                    is StateDeclaration.FromEnum -> runCatching { declaration.expand(schema.tree) }
                        .onSuccess { expanded -> expanded.forEach { if (claimState(it.id, source)) states += it } }
                        .onFailure { errors += "$source [states[$i]]: ${it.message}" }
                }
            }
            for ((i, lifecycle) in schema.lifecycles.withIndex()) {
                val previous = lifecycleSources.putIfAbsent(lifecycle.id, source)
                if (previous != null) {
                    errors += "$source [lifecycles[$i]]: Lifecycle「${lifecycle.id}」が重複しています（定義元: $previous）"
                } else lifecycles += lifecycle
            }
            for ((i, refinement) in schema.refinements.withIndex()) {
                val previous = refinementSources.putIfAbsent(refinement.state, source)
                if (previous != null) {
                    errors += "$source [refinements[$i]]: State「${refinement.state}」の詳細化が重複しています（定義元: $previous）"
                } else refinements[refinement.state] = refinement
            }
            for ((i, event) in schema.events.withIndex()) {
                val previous = eventSources.putIfAbsent(event.id, source)
                if (previous != null) errors += "$source [events[$i]]: Event「${event.id}」が重複しています（定義元: $previous）"
                else events += event
            }
        }

        val written = states.associateBy { it.id }
        fun known(id: String) = id in written || id in composites
        fun entityOf(id: String) = written[id]?.entity ?: composites[id]?.entity
        val actions = schemas.flatMap { it.actions }.groupBy { it.id }
        fun causeError(cause: TransitionCause, where: String): String? = when (cause) {
            is TransitionCause.Action -> {
                val matching = actions[cause.id].orEmpty()
                when {
                    matching.isEmpty() -> "$where の Action「${cause.id}」が見つかりません"
                    matching.size > 1 -> "$where の Action「${cause.id}」が複数あります（原因を一意に特定できません）"
                    // Action は別の Entity を操作し、その影響でこの Lifecycle の State を変えうる。
                    // 対象 Entity の一致は要求しない。実際の遷移の照合は BehaviorRuntime が行う。
                    else -> null
                }
            }
            is TransitionCause.Event -> if (cause.id !in eventSources) "$where の Event「${cause.id}」が見つかりません" else null
        }

        val children = refinementErrors(refinements, refinementSources, composites, written, ::known, ::entityOf, ::causeError, errors)
        composites.keys.filter { it !in refinements }.forEach { id ->
            errors += "${stateSources[id]} [state:$id]: 述語を書かない State「$id」には詳細化（refinements の state: $id）が要ります"
        }
        // 詳細化した State の述語は「葉のどれか」。葉が同じ項目の enum から作った State のときに導ける。
        for ((id, composite) in composites) {
            if (id !in children) continue
            val leafStates = leaves(id, children).map { written[it] }
            val first = leafStates.firstOrNull()
            if (first == null || leafStates.any { it?.enumValue == null || it.enumOrigin != first.enumOrigin }) {
                errors += "${stateSources[id]} [state:$id]: 「$id」の葉の State が同じ項目の enum から作った State ではないので、" +
                    "「子のどれか」の述語を導けません"
                continue
            }
            val raw: Map<String, Any?> = mapOf(
                "match" to listOf(mapOf("node" to mapOf("entity" to composite.entity, "bind" to "self"))),
                "where" to listOf(mapOf("of" to "self", "path" to first.enumPath, "oneOf" to leafStates.map { it!!.enumValue })),
                "expect" to "exists",
            )
            states += StateDef(id, composite.entity, composite.label, ConstraintRule.parse(raw, "refinements[$id]"), predicateRaw = raw)
        }

        val byState = states.associateBy { it.id }
        val enumGroups = states.filter { it.enumOrigin != null }.groupBy { it.enumOrigin }
        val parentOf = children.flatMap { (parent, kids) -> kids.map { it to parent } }.toMap()
        fun ancestors(id: String): List<String> = generateSequence(parentOf[id]) { parentOf[it] }.take(children.size).toList()
        val expanded = mutableListOf<LifecycleDef>()
        for (lifecycle in lifecycles) {
            val source = lifecycleSources.getValue(lifecycle.id)
            val at = "$source [lifecycle:${lifecycle.id}]"
            val before = errors.size
            fun issue(message: String) { errors += "$at: $message" }
            val members = lifecycle.states.toSet()
            if (members.size != lifecycle.states.size) issue("states に重複した State があります")
            lifecycle.states.forEach { id ->
                when {
                    !known(id) -> issue("State「$id」が定義されていません")
                    entityOf(id) != lifecycle.entity -> issue("State「$id」の対象 entity「${entityOf(id)}」は「${lifecycle.entity}」と異なります")
                }
                ancestors(id).firstOrNull { it in members }?.let { parent ->
                    issue("State「$id」は「$parent」の詳細化の子です（Lifecycle の states には親だけを書きます）")
                }
            }
            fun member(id: String, where: String) {
                if (id !in members) issue("$where の State「$id」は lifecycle.states に含まれません")
            }
            lifecycle.entry.forEachIndexed { i, entry ->
                member(entry.to, "entry[$i].to")
                causeError(entry.by, "entry[$i].by")?.let(::issue)
            }
            lifecycle.transitions.forEachIndexed { i, transition ->
                member(transition.from, "transitions[$i].from")
                member(transition.to, "transitions[$i].to")
                causeError(transition.by, "transitions[$i].by")?.let(::issue)
            }
            lifecycle.terminal.forEach { member(it, "termination.terminal") }
            if (lifecycle.terminal.size != lifecycle.terminal.toSet().size) issue("termination.terminal に重複があります")
            flowErrors(lifecycle).forEach(::issue)
            // 一つの enum から作られた State の一部しか含まない Lifecycle は網羅性を欠く（詳細化があれば展開した葉で見る）。
            fun completeness(of: LifecycleDef) {
                val leafMembers = of.states.toSet()
                of.states.mapNotNull { byState[it]?.enumOrigin }.distinct().forEach { origin ->
                    val missing = enumGroups[origin].orEmpty().map { it.id }.filter { it !in leafMembers }
                    if (missing.isNotEmpty()) issue("enum 由来の State が網羅されていません: ${missing.joinToString(", ")}")
                }
            }
            if (lifecycle.states.none { it in children }) {
                completeness(lifecycle)
                expanded += lifecycle
                continue
            }
            // 詳細化した State を葉へ展開し、展開した流れも同じ規則で点検する（中の流れの行き止まり・到達）。
            val flat = if (errors.size == before) Flattener(refinements, children, ::issue).lifecycle(lifecycle) else null
            if (flat == null || errors.size != before) {
                expanded += lifecycle
                continue
            }
            flowErrors(flat).forEach(::issue)
            completeness(flat)
            expanded += flat
        }
        errors += actionLifecycleErrors(schemas, expanded.associateBy { it.id }, byState)
        return Catalog(states, expanded, errors, events, children)
    }

    /**
     * 詳細化の点検（子・入口・遷移・出口・循環）。点検を通った詳細化の「親 → 直接の子」を返す。
     */
    private fun refinementErrors(
        refinements: Map<String, RefinementDef>,
        sources: Map<String, String>,
        composites: Map<String, StateDeclaration.Composite>,
        written: Map<String, StateDef>,
        known: (String) -> Boolean,
        entityOf: (String) -> String?,
        causeError: (TransitionCause, String) -> String?,
        errors: MutableList<String>,
    ): Map<String, List<String>> {
        val children = linkedMapOf<String, List<String>>()
        val parentOf = mutableMapOf<String, String>()
        for ((parent, refinement) in refinements) {
            val at = "${sources[parent]} [refinement:$parent]"
            fun issue(message: String) { errors += "$at: $message" }
            when {
                parent in written -> issue("詳細化する State「$parent」は predicate を書かずに宣言します（子のどれかが真のとき真になります）")
                parent !in composites -> issue("State「$parent」が定義されていません")
            }
            val members = refinement.states.toSet()
            if (members.size != refinement.states.size) issue("states に重複した State があります")
            refinement.states.forEach { id ->
                when {
                    id == parent -> issue("State「$id」は自分の子にできません")
                    !known(id) -> issue("State「$id」が定義されていません")
                    entityOf(parent) != null && entityOf(id) != entityOf(parent) ->
                        issue("State「$id」の対象 entity「${entityOf(id)}」は親「$parent」の「${entityOf(parent)}」と異なります")
                }
                val other = parentOf.putIfAbsent(id, parent)
                if (other != null && other != parent) issue("State「$id」はすでに「$other」の子です（子の親は 1 つです）")
            }
            fun member(id: String, where: String) {
                if (id !in members) issue("$where の State「$id」は詳細化の states に含まれません")
            }
            refinement.entry.forEachIndexed { i, entry ->
                member(entry.to, "entry[$i].to")
                causeError(entry.by, "entry[$i].by")?.let(::issue)
            }
            refinement.transitions.forEachIndexed { i, transition ->
                member(transition.from, "transitions[$i].from")
                member(transition.to, "transitions[$i].to")
                causeError(transition.by, "transitions[$i].by")?.let(::issue)
            }
            refinement.exits.forEachIndexed { i, exit ->
                exit.from.forEach { member(it, "exits[$i].from") }
                causeError(exit.by, "exits[$i].by")?.let(::issue)
            }
            refinement.exits.groupBy { it.by }.filterValues { it.size > 1 }.keys.forEach {
                issue("exits に同じ原因（${it.text()}）が 2 つあります")
            }
            children[parent] = refinement.states
        }
        // 詳細化の循環（親が自分の子孫に現れる）。
        val cyclic = children.keys.filter { start ->
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque(children[start].orEmpty())
            var found = false
            while (stack.isNotEmpty() && !found) {
                val id = stack.removeLast()
                if (id == start) found = true
                else if (seen.add(id)) stack.addAll(children[id].orEmpty())
            }
            found
        }
        if (cyclic.isNotEmpty()) {
            errors += "${sources[cyclic.first()]} [refinements]: 詳細化が循環しています（${cyclic.joinToString(", ")}）"
            cyclic.forEach { children.remove(it) }
        }
        return children
    }

    /** 流れの点検: 終端から出る遷移・行き止まり・入口からの到達・終端への到達。粗い流れにも展開した流れにも使う。 */
    private fun flowErrors(lifecycle: LifecycleDef): List<String> {
        val errors = mutableListOf<String>()
        val members = lifecycle.states.toSet()
        val terminal = lifecycle.terminal.toSet()
        lifecycle.transitions.filter { it.from in terminal }.forEach {
            errors += "終端 State「${it.from}」から「${it.to}」への遷移は定義できません"
        }
        if (!lifecycle.openEnded) {
            lifecycle.states.filter { it !in terminal && lifecycle.transitions.none { t -> t.from == it } }.forEach {
                errors += "非終端 State「$it」には次の遷移が必要です（無期限に留まるなら openEnded: true）"
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
        (members - reached).forEach { errors += "State「$it」は entry から到達できません" }
        if (!lifecycle.openEnded) {
            val canFinish = terminal.filter { it in members }.toMutableSet()
            do {
                val before = canFinish.size
                lifecycle.transitions.filter { it.to in canFinish && it.from in members }.forEach { canFinish += it.from }
                changed = canFinish.size != before
            } while (changed)
            (members - canFinish).forEach { errors += "State「$it」から終端に到達できません（openEnded: true なら無期限の継続を許容します）" }
        }
        return errors
    }

    /**
     * 詳細化の展開。詳細化した State の位置に中の流れを埋め込む: 親へ入る遷移は、同じ原因の詳細化の入口の子へ、
     * 親から出る遷移は、出られる子（exits で狭めたもの。書かなければ全部の子）から出る。中の遷移はそのまま足す。
     */
    private class Flattener(
        private val refinements: Map<String, RefinementDef>,
        private val children: Map<String, List<String>>,
        private val issue: (String) -> Unit,
    ) {
        private var failed = false
        private fun fail(message: String) { failed = true; issue(message) }

        /** 葉の形にした流れ（詳細化の中の流れなら、出口も葉の形）。 */
        private class Flat(
            val states: List<String>,
            val entry: List<LifecycleEntry>,
            val transitions: List<LifecycleTransition>,
            val exits: List<RefinementExit>,
        )

        fun lifecycle(lifecycle: LifecycleDef): LifecycleDef? {
            val flat = expand(lifecycle.states, lifecycle.entry, lifecycle.transitions, emptyList(), emptySet(), emptySet(), lifecycle.terminal.toSet())
            if (failed) return null
            return lifecycle.copy(
                states = flat.states, entry = flat.entry, transitions = flat.transitions,
                terminal = lifecycle.terminal.flatMap { leaves(it, children) },
            )
        }

        /**
         * outer は、この流れの持ち主（詳細化した State）が外へ出る原因。terminal は終端の State（中に遷移を書けない）。
         */
        private fun expand(
            states: List<String>, entry: List<LifecycleEntry>, transitions: List<LifecycleTransition>,
            exits: List<RefinementExit>, outer: Set<TransitionCause>, seen: Set<String>, terminal: Set<String>,
        ): Flat {
            // この流れの中で、State s から出られる原因（自分の遷移と、持ち主が外へ出る原因のうち s が出られるもの）。
            fun leaving(s: String): Set<TransitionCause> = transitions.filter { it.from == s }.map { it.by }.toSet() +
                outer.filter { cause -> exits.none { it.by == cause } || exits.any { it.by == cause && s in it.from } }
            val subs: Map<String, Flat?> = states.associateWith { s ->
                val refinement = refinements[s]?.takeIf { s in children && s !in seen } ?: return@associateWith null
                if (s in terminal && refinement.transitions.isNotEmpty()) {
                    fail("終端の State「$s」の詳細化には遷移を書けません（終端の中は動きません）")
                }
                val canLeave = leaving(s)
                refinement.exits.filter { it.by !in canLeave }.forEach {
                    fail("「$s」の詳細化の出口（${it.by.text()}）は、「$s」から出る遷移にありません")
                }
                expand(refinement.states, refinement.entry, refinement.transitions, refinement.exits, canLeave, seen + s,
                    if (s in terminal) refinement.states.toSet() else emptySet())
            }
            val entered = mutableMapOf<String, MutableSet<TransitionCause>>()
            fun into(s: String, cause: TransitionCause): List<String> {
                val sub = subs[s] ?: return listOf(s)
                entered.getOrPut(s) { mutableSetOf() } += cause
                val to = sub.entry.filter { it.by == cause }.map { it.to }.distinct()
                if (to.isEmpty()) fail("「$s」へ入る遷移（${cause.text()}）の入口が、「$s」の詳細化にありません")
                return to
            }
            fun outOf(s: String, cause: TransitionCause): List<String> {
                val sub = subs[s] ?: return listOf(s)
                val narrowed = sub.exits.filter { it.by == cause }
                return if (narrowed.isEmpty()) sub.states else narrowed.flatMap { it.from }.distinct()
            }
            val flatEntry = entry.flatMap { e -> into(e.to, e.by).map { LifecycleEntry(it, e.by) } }
            val flatTransitions = transitions.flatMap { t ->
                val targets = into(t.to, t.by)
                outOf(t.from, t.by).flatMap { from -> targets.map { LifecycleTransition(from, it, t.by) } }
            } + subs.values.filterNotNull().flatMap { it.transitions }
            subs.forEach { (s, sub) ->
                sub?.entry?.map { it.by }?.distinct()?.filter { it !in entered[s].orEmpty() }?.forEach {
                    fail("「$s」の詳細化の入口（${it.text()}）で、「$s」へ入る遷移がありません")
                }
            }
            return Flat(
                states = states.flatMap { subs[it]?.states ?: listOf(it) },
                entry = flatEntry.distinct(),
                transitions = flatTransitions.distinct(),
                exits = exits.map { x -> RefinementExit(x.by, x.from.flatMap { outOf(it, x.by) }.distinct()) },
            )
        }
    }

    /**
     * Lifecycle を書いた操作（`lifecycle:`。ADR-0029）の点検: その Lifecycle に入口か遷移があること・受け手の型・
     * 同じ State からの行き先が 1 つに決まること・行き先が書き込める（enum から作った）State であること。
     */
    private fun actionLifecycleErrors(
        schemas: List<WorkspaceSchema>, lifecycles: Map<String, LifecycleDef>, states: Map<String, StateDef>,
    ): List<String> {
        val errors = mutableListOf<String>()
        for (schema in schemas) for (action in schema.actions) {
            val ref = action.lifecycle ?: continue
            val at = "${schema.source.value} [actions:${action.id}.lifecycle]"
            val lifecycle = lifecycles[ref.id] ?: run { errors += "$at: Lifecycle「${ref.id}」が見つかりません"; null } ?: continue
            fun byThis(cause: TransitionCause) = cause is TransitionCause.Action && cause.id == action.id
            val entries = lifecycle.entry.filter { byThis(it.by) }
            val moves = lifecycle.transitions.filter { byThis(it.by) }
            if (entries.isEmpty() && moves.isEmpty()) {
                errors += "$at: Lifecycle「${lifecycle.id}」に、この操作の入口も遷移もありません（by: {action: ${action.id}} を書きます）"
                continue
            }
            if (moves.isNotEmpty() && action.target?.entity != lifecycle.entity) {
                errors += "$at: 遷移を起こす操作の受け手は、Lifecycle の対象にします（target: {entity: ${lifecycle.entity}}）"
            }
            if (entries.isNotEmpty() && action.target != null) {
                errors += "$at: 入口の操作（新しい ${lifecycle.entity} を作る）は、受け手を持たない操作にします"
            }
            if (entries.size > 1) errors += "$at: この操作の入口が 2 つ以上あります（${entries.joinToString(", ") { it.to }}）"
            moves.groupBy { it.from }.filterValues { list -> list.map { it.to }.distinct().size > 1 }.forEach { (from, list) ->
                errors += "$at: 「$from」からの行き先が 2 つ以上あります（${list.joinToString(", ") { it.to }}）。行き先が分かれる操作には lifecycle を書かず、手で状態を書きます"
            }
            (entries.map { it.to } + moves.map { it.to }).distinct().forEach { to ->
                val state = states[to]
                if (state != null && (state.enumPath == null || state.enumValue == null)) {
                    errors += "$at: 行き先の State「$to」は述語で定義されているので、操作から書き込めません（enum から作った State にします）"
                }
            }
        }
        return errors
    }
}

/** State の葉（詳細化した State は子の葉を、していなければ自分）。循環は辿らない。 */
private fun leaves(id: String, children: Map<String, List<String>>, seen: Set<String> = emptySet()): List<String> {
    val kids = children[id] ?: return listOf(id)
    if (id in seen) return emptyList()
    return kids.flatMap { leaves(it, children, seen + id) }
}

private fun TransitionCause.text(): String = when (this) {
    is TransitionCause.Action -> "by: {action: $id}"
    is TransitionCause.Event -> "by: {event: $id}"
}
