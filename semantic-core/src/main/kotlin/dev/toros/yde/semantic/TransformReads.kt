package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.AnyIndex
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TemplateCondition
import dev.toros.yde.semantic.schema.TemplateSite
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath

/**
 * 変換の定義が読む入力の場所（入力の足あと）。変換を当てずに定義だけを読み、`$copy`・`$format` の穴・`$each` の源・条件・
 * 値の式が読む場所を、名前の付いた入れ物（生成なら `model`）からのパスのパターン（`actions[*].effects[*].field.key`）で返す。
 * 「この設計書は業務モデルのどの概念から作られるか」を、定義から機械的に示すのに使う（生成の系譜）。
 *
 * - 繰り返し（`$each`）の中は、源の要素（`[*]`）がいまの場所。`$bind` の名前もその要素を指す
 * - 規則（`$apply`）は、当てる場所をいまの場所にして、同じ名前の規則をすべて読む（条件で選ばれるものが実行時に決まるため）
 * - まとめ（`$group`）は、入力が場所なら、まとまりの `items[n]` をその要素として読む。入力が計算した値なら、その中の読み取りは数えない
 * - 計算した値の中（`$group` のキーなど）からの読み取りは、入力の場所に結びつかないので数えない（入力を作るところで数えている）
 * 同じ規則が入れ子で自分を呼ぶ（値の式の組み立てなど）ときは、3 段までたどる。
 */
object TransformReads {

    /**
     * places は名前の付いた入れ物 → その入れ物のパス（生成の `model` は空のパス、`element` は `actions[*]`）。
     * start はいまの場所の始まり（null＝入力が場所に結びつかない）。戻り値は読む場所のパターン（初出順・重なりなし）。
     */
    fun of(def: TransformDef, places: Map<String, YPath>, start: YPath?): List<String> {
        val walker = Walker(def, places)
        walker.walk(def.build, Here(start), emptyMap())
        return walker.reads.toList()
    }

    /** いまの場所。path が null なら計算した値（場所に結びつかない）。group はまとまりの要素の場所（`items[n]` が指す）。 */
    private data class Here(val path: YPath?, val group: YPath? = null)

    private class Walker(def: TransformDef, private val places: Map<String, YPath>) {
        val reads = LinkedHashSet<String>()
        private val rules = def.rules.groupBy { it.id }
        private val active = HashMap<String, Int>()

        /** outerHere / outerBinds は、繰り返しの略記（`$each … $apply … $with`）の渡す値を計算する外側の場所。 */
        fun walk(t: Template, here: Here, binds: Map<String, Here>, outerHere: Here = here, outerBinds: Map<String, Here> = binds) {
            when (t) {
                is Template.Literal, is Template.Param, is Template.ViewRows -> Unit
                is Template.MapOf -> t.entries.forEach { walk(it.second, here, binds) }
                is Template.ListOf -> t.items.forEach { walk(it, here, binds) }
                is Template.Copy -> resolve(t.site, here, binds)?.let(::record)
                is Template.Format -> {
                    t.with.forEach { walk(it.second, here, binds) }
                    val names = t.with.map { it.first }.toSet()
                    val base = resolve(t.site, here, binds)
                    val baseHere = siteHere(t.site, here, binds)
                    Projection.placeholders(t.template).forEach { hole ->
                        val path = runCatching { parseYPath(hole) }.getOrNull() ?: return@forEach
                        if (path.firstOrNull() in names) return@forEach
                        if (base != null) record(base + path) else groupRead(baseHere, path)?.let(::record)
                    }
                }
                is Template.MapValue -> walk(t.input, here, binds)
                is Template.First -> t.options.forEach { walk(it, here, binds) }
                is Template.DateOf -> walk(t.input, here, binds)
                is Template.NumberOf -> walk(t.input, here, binds)
                is Template.TrimOf -> walk(t.input, here, binds)
                is Template.CaseOf -> walk(t.input, here, binds)
                is Template.EntriesOf -> walk(t.input, here, binds)
                is Template.Compose -> walk(t.input, here, binds)
                is Template.ValueOf -> t.expr.reads().forEach { g ->
                    val base = g.of?.let { binds[it]?.path ?: places[it] } ?: here.path
                    base?.let { record(it + g.path) }
                }
                is Template.Each -> {
                    if (t.rows != null) {
                        walk(t.body, Here(null), binds)
                        return
                    }
                    val source = resolve(t.site, here, binds)
                    source?.let(::record)
                    val element = Here(source?.let { it + AnyIndex })
                    walk(t.body, element, if (t.bind != null) binds + (t.bind!! to element) else binds, here, binds)
                }
                is Template.Zip -> {
                    t.sites.forEach { (_, site) -> resolve(site, here, binds)?.let(::record) }
                    walk(t.body, Here(null), binds, here, binds)
                }
                is Template.Apply -> {
                    val at = siteHere(t.site, here, binds)
                    // 渡す値: 場所の写し（$copy）なら、その場所。計算した値なら場所に結びつかない。略記は外側で計算する
                    val (wHere, wBinds) = if (t.outer) outerHere to outerBinds else here to binds
                    t.with.forEach { walk(it.second, wHere, wBinds) }
                    val passed = t.with.associate { (name, child) ->
                        name to Here((child as? Template.Copy)?.let { resolve(it.site, wHere, wBinds) })
                    }
                    val depth = active[t.rule] ?: 0
                    if (depth >= 3) return
                    active[t.rule] = depth + 1
                    rules[t.rule].orEmpty().forEach { rule ->
                        // 規則の中は、外側の繰り返しの名前（$bind）を読めない。読めるのは渡された値（with）だけ
                        rule.conditions.forEach { condition(it, at, passed) }
                        walk(rule.build, at, passed)
                    }
                    active[t.rule] = depth
                }
                is Template.Concat -> t.parts.forEach { walk(it, here, binds) }
                is Template.Group -> {
                    walk(t.input, here, binds)
                    // 入力が場所（$copy か、場所の $each の写し）なら、まとまりの items[n] はその要素
                    val element = (t.input as? Template.Copy)?.let { resolve(it.site, here, binds) }?.let { it + AnyIndex }
                    walk(t.by, Here(element), binds)
                    val group = Here(null, element)
                    walk(t.body, group, if (t.bind != null) binds + (t.bind!! to group) else binds, here, binds)
                }
                is Template.Guarded -> {
                    t.conditions.forEach { condition(it, here, binds) }
                    walk(t.inner, here, binds)
                }
            }
        }

        private fun condition(c: TemplateCondition, here: Here, binds: Map<String, Here>) {
            c.alternatives.forEach { test ->
                if (test.param == null) resolve(test.site, here, binds)?.let(::record)
                test.operands.forEach { walk(it, here, binds) }
            }
        }

        /** 場所の指定を、入れ物からのパスへ。計算した値の中なら null（まとまりの items[n] は、まとめた要素へ読み替える）。 */
        private fun resolve(site: TemplateSite, here: Here, binds: Map<String, Here>): YPath? {
            val base = siteHere(site, here, binds)
            base.path?.let { return it + site.path.normalize() }
            return groupRead(base, site.path)
        }

        /** 場所の指定の、いまの場所（パスを足す前）。 */
        private fun siteHere(site: TemplateSite, here: Here, binds: Map<String, Here>): Here = when {
            site.of == null -> here
            site.of in binds -> binds.getValue(site.of!!)
            site.of in places -> Here(places.getValue(site.of!!))
            else -> Here(null)
        }

        /** まとまり（{key, items}）からの読み取り: `items[n].x` はまとめた要素の x。 */
        private fun groupRead(here: Here, path: YPath): YPath? {
            val element = here.group ?: return null
            if (path.firstOrNull() != "items") return null
            return element + path.drop(2).normalize()
        }

        private fun YPath.normalize(): YPath = map { if (it is Int) AnyIndex else it }

        private fun record(path: YPath) {
            reads += PathPattern.pretty(path)
        }
    }
}
