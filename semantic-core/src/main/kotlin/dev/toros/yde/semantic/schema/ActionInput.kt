package dev.toros.yde.semantic.schema

/** フォームの準備だけの定義。CLIの省略値や実行時のdefaultを変更しない。 */
data class ActionInput(val initial: Map<String, Template>, val submit: String = "all") {
    companion object {
        const val CONTEXT = "context"

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
