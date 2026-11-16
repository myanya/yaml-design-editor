package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YPath

/**
 * 型の解決前に明記された属性と、その場所。未指定と false・0・空の値を区別するため、値の差から作らない。
 * 子の宣言は子の FieldDef に、操作と業務の検査はそれぞれの定義に持つ。ここに解決済みの値を戻してはならない。
 */
data class FieldDeclaration(val schema: String, val path: YPath, val attributes: Map<String, Any?>) {
    companion object {
        private val CHILDREN = setOf("fields", "item", "value", "cases", "methods", "constraints")

        fun of(schema: String, path: YPath, raw: Map<*, *>): FieldDeclaration = FieldDeclaration(
            schema, path.toList(), raw.entries.filter { it.key.toString() !in CHILDREN }
                .associate { it.key.toString() to snapshot(it.value) },
        )

        private fun snapshot(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to snapshot(it.value) }
            is List<*> -> value.map(::snapshot)
            else -> value
        }
    }
}
