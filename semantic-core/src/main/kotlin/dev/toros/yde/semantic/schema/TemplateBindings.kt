package dev.toros.yde.semantic.schema

/** ひな型が外から受け取る束縛名。繰り返しの局所的な名前は、その中だけで解決する。 */
internal fun Template.bindNames(): Set<String> {
    val out = LinkedHashSet<String>()
    fun site(s: TemplateSite, local: Set<String>) {
        s.of?.let { name ->
            if (name !in local) out += name
            else require(s.inDoc == null) { "\$bind の「$name」は入力中の場所です。\$in / in で別の出現は選べません" }
        }
    }
    fun conditions(cs: List<TemplateCondition>, local: Set<String>) =
        cs.forEach { c -> c.alternatives.forEach { site(it.site, local) } }
    fun walk(t: Template, local: Set<String>) {
        when (t) {
            is Template.Literal, is Template.Param -> Unit
            is Template.MapOf -> t.entries.forEach { walk(it.second, local) }
            is Template.ListOf -> t.items.forEach { walk(it, local) }
            is Template.Copy -> site(t.site, local)
            is Template.Format -> { site(t.site, local); t.with.forEach { walk(it.second, local) } }
            is Template.MapValue -> walk(t.input, local)
            is Template.First -> t.options.forEach { walk(it, local) }
            is Template.DateOf -> walk(t.input, local)
            is Template.NumberOf -> walk(t.input, local)
            is Template.TrimOf -> walk(t.input, local)
            is Template.ValueOf -> out += t.expr.binds() - local
            is Template.ViewRows -> conditions(t.conditions, local)
            is Template.Each -> { site(t.site, local); walk(t.body, local + listOfNotNull(t.bind)) }
            is Template.Zip -> { t.sites.forEach { site(it.second, local) }; walk(t.body, local) }
            is Template.Apply -> site(t.site, local)
            is Template.Concat -> t.parts.forEach { walk(it, local) }
            is Template.Guarded -> { conditions(t.conditions, local); walk(t.inner, local) }
        }
    }
    walk(this, emptySet())
    return out
}
