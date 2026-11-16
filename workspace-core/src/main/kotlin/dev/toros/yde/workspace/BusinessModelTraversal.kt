package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.SchemaTree

/**
 * 業務モデルの展開で、型への再帰と、原文に有限に書かれた子を区別する。
 * 型を解く規則は SchemaTree のまま。再帰先の共通の子は型の参照に残し、利用箇所の子だけを進む。
 * written を解決済みの子で置き換えると、継承した子をまた明記と扱って展開し続けるため、両方を渡す。
 */
internal class BusinessModelTraversal(private val tree: SchemaTree, private val active: Set<String> = emptySet()) {
    class Use(val written: FieldDef, val effective: FieldDef = written)

    class Field(
        val use: Use,
        val resolved: FieldDef,
        val recursiveType: String?,
        val next: BusinessModelTraversal,
    ) {
        private val recursive get() = recursiveType != null

        private fun children(written: List<FieldDef>, effective: List<FieldDef>): List<Use> =
            if (!recursive) effective.map(::Use)
            else written.map { w -> Use(w, effective.firstOrNull { it.key == w.key } ?: w) }

        private fun child(written: FieldDef?, effective: FieldDef?): Use? =
            if (!recursive) effective?.let(::Use) else written?.let { Use(it, effective ?: it) }

        val fields get() = children(use.written.fields, resolved.fields)
        val item get() = child(use.written.item, resolved.item)
        val value get() = child(use.written.value, resolved.value)
        val cases get() = if (!recursive) resolved.cases.mapValues { (_, fields) -> fields.map(::Use) }
            else use.written.cases.mapValues { (key, fields) -> children(fields, resolved.cases[key].orEmpty()) }
    }

    fun resolve(use: Use, name: String? = null): Field {
        val resolved = tree.resolve(use.effective) ?: use.effective
        // 同じ基底型を継承する別の型は再帰ではない。型定義の入口では、その定義自身の名前を渡す。
        val type = name ?: resolved.lineage.firstOrNull()
        return Field(use, resolved, type?.takeIf { it in active },
            BusinessModelTraversal(tree, active + listOfNotNull(type)))
    }
}
