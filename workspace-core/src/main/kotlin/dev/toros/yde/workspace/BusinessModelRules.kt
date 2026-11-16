package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.ElementRule
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.QueryWhere
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.workspace.BusinessModelTraversal.Use
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode

/**
 * 業務モデルの項のデータの決まりの側: 型の検査・スキーマの検査・関係レジストリの宣言を、決まり（`constraints`）の項にし、
 * 操作ごとに、書くと破れうる決まり（`checks`）を添える。
 *
 * - **constraints**: 決まり 1 つずつ。名前（`label`＝説明）・文（`message`。書かれていれば）・重さ（`level`）・形（`form`）・
 *   起点のエンティティ型（`entity`）・要素の場所（`at`。ID の無い明細の行の決まりなら `history[*]` など）・読む項目（`reads`。
 *   `エンティティ型.要素の中のパス`）・書かれた形（`rule`。State の名前は値の条件へ置き換えた形）
 *   - 形 `element`: 要素ごとの決まり（型の検査と、起点の node 1 つだけのスキーマの検査）
 *   - 形 `pattern`: 入れ子の線や参照を辿る決まり（スキーマの検査の match に edge がある）
 *   - 形 `occurrence`: 書かれた場所の数の決まり（どの文書に書かれるか＝文書の分け方）
 *   - 形 `relation`: 関係レジストリの宣言（`kind` は acyclic・outMax・outMin・inMax・inMin・types）。読む項目はその関係の参照の項目
 * - **actions の checks**: 操作が書く項目と、決まりが読む項目が重なる決まり（項目は要素の直下のキーで比べる）。ただし、書いた後の要素が
 *   決まりの絞り込みに入らないと定義から分かるもの（[applies]）は除く。`items` は重なった項目、`params` は決まりが読む値を入れる
 *   受け取る値（あれば、受け取る値が原因で破れうる）。決まりの形と重さ（form・level・kind・multiple）を写して持つ
 *
 * どの決まりをどう確かめるか（Java・SQL・対象外）は設計の規則で、ここでは決めない（生成の定義が決める）。
 */
internal object BusinessModelRules {

    /**
     * 決まり 1 つ。reads は読む項目（エンティティ型 → 要素の中のパス）の並び（初出順・重なりなし）。
     * entity・at は、絞り込み（gates）を当てる要素（起点のエンティティ型と、その中の場所。ID の無い明細の行なら `history[*]`）。
     */
    class Rule(
        val id: String, val term: Map<String, Any?>, val reads: List<Pair<String, String>>,
        val entity: String? = null, val at: String = "", val gates: List<Gate> = emptyList(),
    )

    /**
     * 決まりの絞り込み（where）のうち、書いた後の値で「当てはまらない」と定義から分かるもの: 項目に値がある（exists: true）と、
     * 項目が決まった値のどれか（equals・oneOf。negated はその否定）。path は要素の中のパス。
     */
    class Gate(val path: String, val values: List<String>? = null, val negated: Boolean = false)

    private fun gatesOf(where: List<dev.toros.yde.semantic.schema.QueryWhereClause>, bind: String?): List<Gate> =
        where.mapNotNull { c -> c.alternatives.singleOrNull() }
            .filter { w -> w.path.isNotEmpty() && (w.of == bind || (bind == null && w.of == QueryWhere.SELF)) }
            .mapNotNull { w ->
                val path = PathPattern.pretty(w.path)
                when {
                    w.exists == true && !w.negated -> Gate(path)
                    w.equals != null -> Gate(path, listOf(w.equals!!), w.negated)
                    w.test?.first?.id == "oneOf" -> Gate(path, w.test!!.second, w.negated)
                    else -> null
                }
            }

    /** 業務モデルの決まりの一覧（型の検査 → スキーマの検査 → 関係レジストリの順）。 */
    fun rules(schemas: List<WorkspaceSchema>, raw: Map<String, YNode.Map?>, catalog: BehaviorChecks.Catalog?): List<Rule> {
        val lowerings = catalog?.let { StateConditions.loweringsOf(it) }.orEmpty()
        fun lowered(x: Any?): Any? = if (lowerings.isEmpty()) x else StateConditions.expand(x, lowerings, mutableListOf())
        val walk = walk(schemas)
        val out = mutableListOf<Rule>()
        // 型の検査（要素ごと）。同じ型を複数の場所で使っていても、同じエンティティ型の同じ場所なら 1 つ
        walk.elementRules.distinctBy { listOf(it.rule.origin, it.entity, it.at) }.forEach { f ->
            val r = f.rule
            val reads = (r.where.flatMap { c -> c.alternatives.mapNotNull { w -> w.path.takeIf { it.isNotEmpty() } } }.map(PathPattern::pretty) +
                r.check.flatMap { c -> c.operands.flatMap(::readsOf) }.map { it.second })
                .map { f.entity to join(f.at, it) }.distinct()
            val declared = rawElementRule(r.origin, schemas, raw)
            val written = declared?.second?.let(::lowered) as? Map<*, *>
            val term = LinkedHashMap<String, Any?>()
            term["id"] = r.id ?: "${f.schema}:${r.origin}"
            term["label"] = r.description ?: r.id ?: r.origin
            r.message?.let { term["message"] = it }
            term["level"] = r.level.id
            term["form"] = "element"
            term["entity"] = f.entity
            if (f.at.isNotEmpty()) term["at"] = f.at
            term["reads"] = reads.map { (e, p) -> "$e.$p" }
            written?.let { w -> term["rule"] = w.filterKeys { it in RULE_KEYS }.mapKeys { it.key.toString() } }
            // 書かれた場所（型を書いた文書の ID と、型の名前）
            term["source"] = declared?.let { "${it.first}#${r.origin.removePrefix("definitions.").substringBefore(".constraints")}" } ?: f.schema
            out += Rule(term["id"].toString(), term, reads, f.entity, f.at, gatesOf(r.where, null))
        }
        // スキーマの検査（constraints に直接書いたもの。語彙から展開した検査は、関係レジストリの側で数える）
        for (s in schemas) {
            val list = (raw[s.id]?.entries?.get("constraints") as? YNode.Seq)?.items.orEmpty()
            s.constraints.filter { it.origin?.at?.startsWith("constraints[") == true }.forEach { r ->
                val index = r.origin!!.at.removePrefix("constraints[").substringBefore(']').toIntOrNull()
                val written = index?.let { list.getOrNull(it) }?.let { lowered(YamlIO.toAny(it)) } as? Map<*, *>
                out += schemaRule(s, r, written, walk.holdings)
            }
        }
        // 関係レジストリ
        schemas.filter { it.kind == SchemaKind.RELATIONS }.forEach { s -> s.relations.forEach { out += relationRules(s, it, walk.refFields) } }
        return out
    }

    /**
     * 関係レジストリの関係（業務モデルの項の relations）: 名前・表示名・逆向きの表示名・説明・元と先の型・多重度（宣言したものだけ）・
     * 非循環・その関係の参照を書く項目（型・キー・複数か）。
     */
    fun relations(schemas: List<WorkspaceSchema>): List<Map<String, Any?>> {
        val refFields = walk(schemas).refFields
        return schemas.filter { it.kind == SchemaKind.RELATIONS }.flatMap { s ->
            s.relations.map { r ->
                val m = LinkedHashMap<String, Any?>()
                m["id"] = r.name
                m["label"] = r.displayLabel
                m["inverseLabel"] = r.displayInverseLabel
                r.description?.let { m["description"] = it }
                m["source"] = r.source
                m["target"] = r.target
                r.cardinality?.let { c ->
                    linkedMapOf("outMin" to c.outMin, "outMax" to c.outMax, "inMin" to c.inMin, "inMax" to c.inMax).filterValues { it != null }
                        .takeIf { it.isNotEmpty() }?.let { m["cardinality"] = it }
                }
                if (r.acyclic) m["acyclic"] = true
                m["fields"] = refFields.filter { it.relation == r.name }.distinctBy { it.entity to it.key }
                    .map { linkedMapOf("entity" to it.entity, "key" to it.key, "multiple" to it.multiple) }
                m
            }
        }
    }

    /** 操作の項（効果は項目の事実を添えた形）が書くと破れうる決まり。 */
    @Suppress("UNCHECKED_CAST")
    fun checksOf(action: Map<String, Any?>, rules: List<Rule>): List<Map<String, Any?>> {
        val params = (action["params"] as? List<Map<String, Any?>>).orEmpty()
        val writes = writesOf((action["effects"] as? List<Map<String, Any?>>).orEmpty(), params)
        return rules.mapNotNull { rule ->
            val touched = rule.reads.filter { (e, p) -> writes.any { it.entity == e && top(it.path) == top(p) } }
            if (touched.isEmpty() || !applies(rule, writes)) return@mapNotNull null
            val inputs = rule.reads.flatMap { (e, p) -> writes.filter { it.entity == e && overlaps(it.path, p) }.mapNotNull { it.param } }.distinct()
            val check = linkedMapOf<String, Any?>("constraint" to rule.id)
            // 決まりの形と重さ（生成の定義が、確かめ方を決めるのに読む。決まりの項の写し）
            listOf("form", "level", "kind", "multiple").forEach { k -> rule.term[k]?.let { check[k] = it } }
            check["items"] = touched.map { (e, p) -> "$e.${top(p)}" }.distinct()
            if (inputs.isNotEmpty()) check["params"] = inputs
            check
        }
    }

    /**
     * 書いた後の要素が、決まりの絞り込みに入りうるか。入らないと定義から分かれば false:
     * - 項目に決まった値を書き、その値が絞り込みの値に合わない（状態を「中断」に書く操作と「完了した TODO には…」）
     * - 新しく作る要素（文書を作る・要素や明細の行を足す）が、絞り込みの「値がある」項目を書かない（完了日を書かない登録と「完了日は…」）
     * ID の無い明細の行の決まりは、足す行だけを見る（今ある行は書き換えない）。配列ごと書き換える操作は、行の中身が分からないので入りうる。
     */
    private fun applies(rule: Rule, writes: List<Write>): Boolean {
        val entity = rule.entity ?: return true
        if (rule.gates.isEmpty()) return true
        val prefix = if (rule.at.isEmpty()) "" else "${rule.at}."
        if (rule.at.isNotEmpty() && writes.any { it.entity == entity && it.path == top(rule.at) }) return true
        val mine = writes.filter { it.entity == entity && (prefix.isEmpty() || it.path.startsWith(prefix)) }
            .map { it.copy(path = it.path.removePrefix(prefix)) }
        val isNew = rule.at.isNotEmpty() || mine.any { it.path == "id" }
        return rule.gates.all { g ->
            val found = mine.filter { it.path == g.path }
            when {
                g.values == null -> !isNew || found.isNotEmpty()
                // 決まった値だけを書くなら、その値で判定する。受け取る値や式を書くなら分からない（入りうる）
                found.isNotEmpty() && found.all { it.literal != null } ->
                    found.any { w -> w.literal!!.any { v -> (v in g.values) != g.negated } }
                found.isEmpty() && isNew -> g.negated
                else -> true
            }
        }
    }

    // ================================================================ 決まりの項

    private val RULE_KEYS = setOf("match", "where", "check", "expect", "count", "forAll")

    /** スキーマの検査 1 つ。束縛のエンティティ型は match の node から、入れ子の線は外側の型の、内側の型を持つ項目を読む。 */
    private fun schemaRule(s: WorkspaceSchema, r: ConstraintRule, written: Map<*, *>?, holdings: Map<Pair<String, String>, String>): Rule {
        val spec = r.spec
        val nodes = listOf(spec.start) + spec.hops.map { it.node }
        val entityOf = nodes.mapNotNull { n -> n.bind?.let { b -> n.entities.firstOrNull()?.let { b to it } } }.toMap()
        val start = spec.start.entities.firstOrNull()
        fun entity(of: String?): String? = if (of == null || of == QueryWhere.SELF) start else entityOf[of]
        val reads = mutableListOf<Pair<String, String>>()
        r.check.flatMap { c -> c.operands.flatMap(::readsOf) }.forEach { (of, p) -> entity(of)?.let { reads += it to p } }
        spec.where.flatMap { it.alternatives }.forEach { w -> if (w.path.isNotEmpty()) entity(w.of)?.let { reads += it to PathPattern.pretty(w.path) } }
        // 入れ子の線（contains）: 外側の型の、内側の型を持つ項目
        nodes.zipWithNext().zip(spec.hops.map { it.edge }).forEach { (pair, edge) ->
            val outer = pair.first.entities.firstOrNull()
            val inner = pair.second.entities.firstOrNull()
            if (edge.contains && outer != null && inner != null) holdings[outer to inner]?.let { reads += outer to it }
        }
        val counted = r.check.flatMap { c -> c.operands }.any { it is ValueExpr.OccurrenceCount }
        val term = LinkedHashMap<String, Any?>()
        term["id"] = r.id ?: "${s.id}:${r.origin?.at}"
        term["label"] = r.description ?: r.id ?: r.origin?.at
        r.message?.let { term["message"] = it }
        term["level"] = r.level.id
        term["form"] = when {
            counted -> "occurrence"
            spec.hops.isNotEmpty() -> "pattern"
            else -> "element"
        }
        start?.let { term["entity"] = it }
        term["reads"] = reads.distinct().map { (e, p) -> "$e.$p" }
        written?.let { w -> term["rule"] = w.filterKeys { it in RULE_KEYS }.mapKeys { it.key.toString() } }
        term["source"] = s.id
        // 絞り込みは起点の要素の条件だけ（入れ子の相手の条件は、相手が複数あるので当てない）。書かれた場所の数は当てない
        val gates = if (counted) emptyList() else gatesOf(spec.where, spec.start.bind)
        return Rule(term["id"].toString(), term, reads.distinct(), start, "", gates)
    }

    /** 関係レジストリの関係 1 つの決まり（非循環・多重度・対象型）。読む項目は、その関係の参照を書く項目。 */
    private fun relationRules(s: WorkspaceSchema, r: RelationDef, refFields: List<RefField>): List<Rule> {
        val fields = refFields.filter { it.relation == r.name }.distinctBy { it.entity to it.key }
        val reads = fields.map { it.entity to it.key }
        val entity = r.source.firstOrNull() ?: fields.firstOrNull()?.entity
        val label = r.displayLabel
        fun rule(kind: String, text: String, level: String, extra: Map<String, Any?> = emptyMap()): Rule {
            val term = LinkedHashMap<String, Any?>()
            term["id"] = "${r.name}.$kind"
            term["label"] = text
            term["level"] = level
            term["form"] = "relation"
            term["kind"] = kind
            term["relation"] = r.name
            term["relationLabel"] = label
            entity?.let { term["entity"] = it }
            term["multiple"] = fields.any { it.multiple }
            term["fields"] = fields.map { linkedMapOf("entity" to it.entity, "key" to it.key, "multiple" to it.multiple) }
            term["reads"] = reads.map { (e, p) -> "$e.$p" }
            term.putAll(extra)
            term["source"] = s.id
            return Rule(term["id"].toString(), term, reads)
        }
        val out = mutableListOf<Rule>()
        if (r.acyclic) out += rule("acyclic", "「$label」をたどって、元の要素に戻らないこと（循環しない）", "error")
        r.cardinality?.let { c ->
            c.outMax?.let { out += rule("outMax", "「$label」は ${it} つまで", r.level.id, mapOf("limit" to it)) }
            c.outMin?.let { out += rule("outMin", "「$label」は ${it} つ以上", r.level.id, mapOf("limit" to it)) }
            c.inMax?.let { out += rule("inMax", "「${r.displayInverseLabel}」は ${it} つまで", r.level.id, mapOf("limit" to it)) }
            c.inMin?.let { out += rule("inMin", "「${r.displayInverseLabel}」は ${it} つ以上", r.level.id, mapOf("limit" to it)) }
        }
        if (r.source.isNotEmpty() || r.target.isNotEmpty()) {
            out += rule(
                "types", "「$label」は、宣言した型（${r.source.joinToString("・").ifEmpty { "どれでも" }} → ${r.target.joinToString("・").ifEmpty { "どれでも" }}）の間に引くこと",
                r.level.id, linkedMapOf("sources" to r.source, "targets" to r.target),
            )
        }
        return out
    }

    /** 値の式が読む項目（束縛の名前 → パス）。書かれた場所の数・参照の本数・集合からの配列は、要素そのもの（id）を読む。 */
    private fun readsOf(e: ValueExpr): List<Pair<String?, String>> = when (e) {
        is ValueExpr.OccurrenceCount -> listOf(e.of to "id")
        is ValueExpr.RefCount -> listOf(e.of to "id")
        is ValueExpr.All -> listOf(e.of to (e.path?.let(PathPattern::pretty) ?: "id"))
        else -> e.reads().map { it.of to PathPattern.pretty(it.path) }
    }

    /** 型の検査の原文（`definitions.型.constraints[i]`。型を書いた文書から）。その場の object に書いた検査は原文を引かない。 */
    private fun rawElementRule(origin: String, schemas: List<WorkspaceSchema>, raw: Map<String, YNode.Map?>): Pair<String, Any?>? {
        val m = ORIGIN.matchEntire(origin) ?: return null
        val (type, index) = m.destructured
        return schemas.firstNotNullOfOrNull { s ->
            val defs = raw[s.id]?.entries?.get("definitions") as? YNode.Map
            val def = defs?.entries?.get(type) as? YNode.Map
            ((def?.entries?.get("constraints") as? YNode.Seq)?.items?.getOrNull(index.toInt()))?.let { s.id to YamlIO.toAny(it) }
        }
    }

    private val ORIGIN = Regex("""definitions\.(.+)\.constraints\[(\d+)]""")

    // ================================================================ 文書の木を歩く

    private class ElementFound(val schema: String, val entity: String, val at: String, val rule: ElementRule)
    private class RefField(val entity: String, val key: String, val relation: String, val multiple: Boolean)
    private class Walk(
        val elementRules: List<ElementFound>,
        /** (外側のエンティティ型, 内側のエンティティ型) → 外側の要素の、内側の要素を持つ項目。 */
        val holdings: Map<Pair<String, String>, String>,
        val refFields: List<RefField>,
    )

    /** 文書のスキーマの木を歩き、エンティティ型の要素の中の型の検査・入れ子の項目・関係の参照の項目を集める（BusinessModel の側面と同じ見つけ方）。 */
    private fun walk(schemas: List<WorkspaceSchema>): Walk {
        val rules = mutableListOf<ElementFound>()
        val holdings = LinkedHashMap<Pair<String, String>, String>()
        val refs = mutableListOf<RefField>()
        for (s in schemas.filter { it.kind == SchemaKind.DOCUMENT && !it.isTemplate }) {
            val tree = s.tree
            val schemaLevel = s.entities.firstOrNull()
            fun walk(expanded: BusinessModelTraversal.Field, at: String, entity: String?, base: String) {
                val f = expanded.resolved
                when (f.type) {
                    FieldType.OBJECT -> {
                        val decl = f.entity ?: schemaLevel?.takeIf { f.fields.any { c -> (tree.resolve(c) ?: c).type == FieldType.ID } }
                        var e = entity
                        var b = base
                        if (decl != null && !decl.type.startsWith(DefinitionIndex.PREFIX)) {
                            if (entity != null && entity != decl.type) holdings.putIfAbsent(entity to decl.type, top(relative(at, base)))
                            e = decl.type
                            b = at
                            f.fields.forEach { c0 ->
                                val c = tree.resolve(c0) ?: c0
                                val key = c0.key ?: return@forEach
                                val item = c.item?.let { tree.resolve(it) ?: it }
                                (c.relation ?: item?.relation)?.let { refs += RefField(decl.type, key, it, c.type == FieldType.ARRAY) }
                            }
                        }
                        if (e != null) f.checks.forEach { rules += ElementFound(s.id, e, relative(at, b), it) }
                        expanded.fields.forEach { c -> c.written.key?.let { k ->
                            walk(expanded.next.resolve(c), if (at.isEmpty()) k else "$at.$k", e, b)
                        } }
                    }
                    FieldType.ARRAY -> expanded.item?.let { walk(expanded.next.resolve(it), "$at[*]", entity, base) }
                    FieldType.MAP -> expanded.value?.let { walk(expanded.next.resolve(it), "$at.*", entity, base) }
                    else -> Unit
                }
            }
            walk(BusinessModelTraversal(tree).resolve(Use(tree.root)), "", null, "")
        }
        return Walk(rules, holdings, refs)
    }

    // ================================================================ 操作が書く項目

    /** 操作が書く項目 1 つ（エンティティ型・要素の中のパス・値を入れる受け取る値・決まった値ならその値（候補の並び））。 */
    private data class Write(val entity: String, val path: String, val param: String?, val literal: List<String>? = null)

    /**
     * 効果から、書く項目を集める。文書を作る・エンティティの要素を足すは、その要素の書く項目と ID。ID の無い明細の行を足すは
     * 行の項目ごと（`history[*].date`）。値を書く・消すはその項目（消すのは、それを指す「値を消す」の受け取る値）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun writesOf(effects: List<Map<String, Any?>>, params: List<Map<String, Any?>>): List<Write> {
        val keys = params.mapNotNull { it["key"]?.toString() }.toSet()
        val clearedBy = params.mapNotNull { p -> p["clearedBy"]?.toString()?.let { it to p["key"].toString() } }.toMap()
        fun paramOf(v: Any?): String? = when (v) {
            is String -> v.takeIf { it.startsWith("$") && !it.startsWith("$$") }?.drop(1)?.substringBefore('.')?.takeIf { it in keys }
            is Map<*, *> -> (v["\$copy"] as? String)?.takeIf { v["\$of"] == null }?.substringBefore('.')?.takeIf { it in keys }
            else -> null
        }
        fun literalOf(v: Any?): List<String>? = when (v) {
            is String -> v.takeIf { !it.startsWith("$") }?.let { listOf(it) }
            is Boolean, is Number -> listOf(v.toString())
            is List<*> -> v.takeIf { l -> l.isNotEmpty() && l.all { it is String || it is Boolean || it is Number } }?.map { it.toString() }
            else -> null
        }
        fun whenParam(e: Map<String, Any?>): String? {
            val path = ((e["when"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("path")?.toString() ?: return null
            return clearedBy[path] ?: path.substringBefore('.').takeIf { it in keys }
        }
        val out = mutableListOf<Write>()
        for (e in effects) {
            val entity = e["entity"]?.toString() ?: continue
            val field = e["field"] as? Map<String, Any?>
            val written = (e["written"] as? List<Map<String, Any?>>).orEmpty()
            when (e["op"]) {
                "create" -> {
                    written.forEach { w -> out += Write(entity, w["key"].toString(), paramOf(w["value"]), literalOf(w["value"])) }
                    out += Write(entity, "id", null)
                }
                "insert" -> {
                    val key = field?.get("key")?.toString() ?: continue
                    val inner = (field["item"] as? Map<String, Any?>)?.get("entity")?.toString()
                    if (inner != null) {
                        written.forEach { w -> out += Write(inner, w["key"].toString(), paramOf(w["value"]), literalOf(w["value"])) }
                        out += Write(inner, "id", null)
                        if (inner != entity) out += Write(entity, key, null)
                    } else if (written.isNotEmpty()) {
                        written.forEach { w -> out += Write(entity, "$key[*].${w["key"]}", paramOf(w["value"]), literalOf(w["value"])) }
                    } else {
                        out += Write(entity, key, paramOf(e["value"]))
                    }
                }
                "set" -> e["path"]?.toString()?.let { out += Write(entity, it, paramOf(e["value"]) ?: whenParam(e), literalOf(e["value"])) }
                "delete" -> e["path"]?.toString()?.let { out += Write(entity, it, whenParam(e)) }
            }
        }
        return out.distinct()
    }

    // ================================================================ パス

    /** 要素の直下のキー（`history[*].date` → history）。 */
    private fun top(path: String): String = path.substringBefore('.').substringBefore('[')

    /** 書くパスと読むパスが重なるか（同じか、一方が他方の中）。 */
    private fun overlaps(written: String, read: String): Boolean =
        written == read || read.startsWith("$written.") || read.startsWith("$written[") || written.startsWith("$read.") || written.startsWith("$read[")

    private fun relative(at: String, base: String): String = when {
        base.isEmpty() -> at
        at == base -> ""
        else -> at.removePrefix("$base.")
    }

    private fun join(at: String, path: String): String = if (at.isEmpty()) path else "$at.$path"
}
