package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode

/** フォームの準備だけの定義。CLIの省略値や実行時のdefaultを変更しない。 */
data class ActionInput(val initial: Map<String, Template>, val submit: String = "all") {
    companion object {
        const val CONTEXT = "context"

        /** デスクトップと外部フォームで同じ送信値を選ぶ。空欄と未変更と明示指定を区別する。 */
        fun submitted(params: List<FieldDef>, input: ActionInput?, values: YNode.Map, tree: SchemaTree?,
                      explicit: Map<String, Any?>, baseline: Map<String, Any?>): Map<String, Any?> {
            val required = params.filter { p -> tree?.requiredAt(tree.resolve(p) ?: p, null) ?: p.required }.mapNotNull { it.key }.toSet()
            return values.entries.entries
                .filterNot { (_, v) -> input == null && v is YNode.Scalar && v.value?.toString().isNullOrBlank() }
                .filter { (key, value) -> input?.submit != "changed" || key in explicit || key !in baseline ||
                    key in required || YamlIO.dumpPlain(value) != YamlIO.dumpPlain(YamlIO.fromAny(baseline[key])) }
                .associate { (k, v) -> k to YamlIO.toAny(v) }
        }

        fun parse(raw: Any?, params: List<FieldDef>, receiver: String?, where: String): ActionInput? {
            if (raw == null) return null
            val map = raw as? Map<*, *> ?: error("$where はobjectです")
            require(map.keys.all { it in setOf("initial", "submit") }) { "$where: initial / submit 以外は指定できません" }
            val submit = map["submit"]?.toString() ?: "all"
            require(submit in setOf("all", "changed")) { "$where.submit は all / changed です" }
            require(receiver != CONTEXT) { "$where: context は初期設定の日時を指す予約名です" }
            val declared = params.mapNotNull { it.key }.toSet()
            val initial = map["initial"]?.let { it as? Map<*, *> ?: error("$where.initial は入力名からひな型へのobjectです") }.orEmpty()
            return ActionInput(initial.entries.associate { (key, value) ->
                val name = key.toString()
                require(name in declared) { "$where.initial: $name はparamsにありません" }
                val template = TransformDef.parseTemplate(value, "$where.initial.$name")
                require(template.paramNames().all { it in declared }) { "$where.initial.$name: 未宣言の入力を参照しています" }
                require(template.bindNames().all { it == CONTEXT || it == receiver }) { "$where.initial.$name: 対象またはcontextだけを参照できます" }
                name to template
            }, submit)
        }
    }
}
