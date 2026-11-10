package dev.toros.yde.semantic.schema

/**
 * 業務オントロジーの宣言。State は保存される status ではなく、エンティティについて
 * 真偽を問える「名前付きの述語」。enum 由来も既存 Query / Predicate と同じ表現に lower する。
 * Lifecycle は State を束ねる宣言であって、データの別の保存先・変更経路ではない。
 */
data class StateDef(
    val id: String,
    val entity: String,
    val label: String?,
    val predicate: ConstraintRule,
    val enumValue: String? = null,
    /** 同じ列挙型の全候補であることを静的に証明するための出どころ。 */
    val enumOrigin: String? = null,
)

sealed interface StateDeclaration {
    data class Named(val definition: StateDef) : StateDeclaration

    /** 列挙型の全値を名前付き State に展開する。idPrefix は値の前に付ける。 */
    data class FromEnum(val entity: String, val path: String, val enumRef: String, val idPrefix: String) : StateDeclaration

    companion object {
        fun parseAll(raw: Any?): List<StateDeclaration> = entries(raw, "states").mapIndexed { i, entry ->
            val where = "states[$i]"
            val m = entry.asObject(where)
            if ("source" in m) {
                m.onlyKeys(where, "source", "idPrefix")
                val source = m["source"].asObject("$where.source")
                source.onlyKeys("$where.source", "entity", "path", "enum")
                val entity = source.required("entity", "$where.source")
                FromEnum(
                    entity = entity,
                    path = source.required("path", "$where.source"),
                    enumRef = source.required("enum", "$where.source"),
                    idPrefix = m["idPrefix"]?.toString() ?: "$entity-",
                )
            } else {
                m.onlyKeys(where, "id", "entity", "label", "predicate")
                val id = m.required("id", where)
                val entity = m.required("entity", where)
                val rule = ConstraintRule.parse(m["predicate"], "$where.predicate")
                require(entity in rule.spec.start.entities) {
                    "$where.predicate: 起点の entity は「$entity」を含む必要があります"
                }
                Named(StateDef(id, entity, m["label"]?.toString(), rule))
            }
        }
    }
}

/** enum 由来の述語も既存の ConstraintRule として構築し、別の式評価器を持たない。 */
fun StateDeclaration.FromEnum.expand(tree: SchemaTree): List<StateDef> {
    val type = tree.resolveTypeName(enumRef)
        ?: error("states.source.enum「$enumRef」が見つかりません")
    require(type.type == FieldType.ENUM && type.values.isNotEmpty()) {
        "states.source.enum「$enumRef」は値を持つ enum 型である必要があります"
    }
    return type.enumOptions().map { option ->
        val predicate = ConstraintRule.parse(
            mapOf(
                "match" to listOf(mapOf("node" to mapOf("entity" to entity, "bind" to "self"))),
                "where" to listOf(mapOf("of" to "self", "path" to path, "equals" to option.value)),
                "expect" to "exists",
            ),
            "states.source[$enumRef:${option.value}]",
        )
        StateDef(idPrefix + option.value, entity, option.label, predicate, option.value, "$entity:$path:$enumRef")
    }
}

/** 遷移を引き起こす宣言。Event の事実モデル／発火は後続段階で追加する。 */
sealed interface TransitionCause {
    val id: String

    data class Action(override val id: String) : TransitionCause
    data class Event(override val id: String) : TransitionCause

    companion object {
        fun parse(raw: Any?, where: String): TransitionCause {
            val m = raw.asObject(where)
            m.onlyKeys(where, "action", "event")
            require(m.size == 1) { "$where は action / event のどちらか一つが必要です" }
            return if ("action" in m) Action(m.required("action", where)) else Event(m.required("event", where))
        }
    }
}

data class LifecycleEntry(val to: String, val by: TransitionCause)
data class LifecycleTransition(val from: String, val to: String, val by: TransitionCause)

data class LifecycleDef(
    val id: String,
    val entity: String,
    val states: List<String>,
    val entry: List<LifecycleEntry>,
    val transitions: List<LifecycleTransition>,
    /** 「終端」は記録の削除ではなく、この Lifecycle 内で以後遷移しないこと。 */
    val terminal: List<String>,
    /** エンティティが終端せずに存在し続ける場合は明示する。 */
    val openEnded: Boolean,
) {
    companion object {
        fun parseAll(raw: Any?): List<LifecycleDef> = entries(raw, "lifecycles").mapIndexed { i, entry ->
            val where = "lifecycles[$i]"
            val m = entry.asObject(where)
            m.onlyKeys(where, "id", "entity", "states", "entry", "transitions", "termination")
            val states = names(m["states"], "$where.states")
            require(states.isNotEmpty()) { "$where.states は空にできません" }
            val starts = entries(m["entry"], "$where.entry").mapIndexed { j, rawEntry ->
                val at = "$where.entry[$j]"
                val e = rawEntry.asObject(at)
                e.onlyKeys(at, "to", "by")
                LifecycleEntry(e.required("to", at), TransitionCause.parse(e["by"], "$at.by"))
            }
            require(starts.isNotEmpty()) { "$where.entry に生成時の入り口を1つ以上指定してください" }
            val transitions = entries(m["transitions"], "$where.transitions").mapIndexed { j, rawTransition ->
                val at = "$where.transitions[$j]"
                val t = rawTransition.asObject(at)
                t.onlyKeys(at, "from", "to", "by")
                LifecycleTransition(t.required("from", at), t.required("to", at), TransitionCause.parse(t["by"], "$at.by"))
            }
            val termination = m["termination"].asObject("$where.termination")
            termination.onlyKeys("$where.termination", "terminal", "openEnded")
            val terminal = names(termination["terminal"], "$where.termination.terminal")
            val openEnded = termination["openEnded"]?.let {
                it as? Boolean ?: error("$where.termination.openEnded は true / false である必要があります")
            } ?: false
            require(terminal.isNotEmpty() || openEnded) {
                "$where.termination: 終端 state を列挙するか openEnded: true を明示してください"
            }
            LifecycleDef(m.required("id", where), m.required("entity", where), states, starts, transitions, terminal, openEnded)
        }
    }
}

private fun Any?.asObject(where: String): Map<*, *> =
    this as? Map<*, *> ?: error("$where は object である必要があります")

private fun Map<*, *>.required(key: String, where: String): String =
    get(key)?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.$key は空にできません")

private fun Map<*, *>.onlyKeys(where: String, vararg allowed: String) {
    keys.map { it.toString() }.firstOrNull { it !in allowed }?.let { error("$where: 未知のキー「$it」です") }
}

private fun entries(raw: Any?, where: String): List<*> = when (raw) {
    null -> emptyList<Any>()
    is List<*> -> raw
    else -> error("$where は配列である必要があります")
}

private fun names(raw: Any?, where: String): List<String> = entries(raw, where).mapIndexed { i, item ->
    item?.toString()?.takeIf { it.isNotBlank() } ?: error("$where[$i] は空にできません")
}
