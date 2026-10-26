package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath

/*
 * 操作の定義（P41。計算体系の「書き換え（Rewrite）」の、初めての利用者定義体。CORE-CALCULUS §11）。二層に分かれる。
 *
 * - 型の操作（methods）: object の定義（名前付きでも、その場でも）に書く、その型の要素 1 つを受け手にする操作。
 *   書き換えは受け手の部分木（保有する子を含む）の中だけで、パスは受け手からの相対。他の要素・他の文書・グラフには触れない
 *   （それが要るならアクション）。予約の名前は `create`（新しい要素の中身。省けば必須の初期値＋受け取った値）。
 *   配列の項目に書く methods は `insert`（追加のときの採番 `id` と、同じ明細の見分け `key`。skill の取り込みと同じ綴り）だけ。
 * - 横断の操作（actions）: スキーマ文書の `actions:` に書く。受け手は `target`（`on` は YAML が真偽値に読むため使わない）。パラメータ（項目の定義そのまま）× 前提（検査と同じ書き方。
 *   起点は選んだ要素に固定）× 効果（set / insert / delete / create / call / each）。効果は変更セットになり、既存の適用
 *   （ChangeSetApplier＝検証・競合・Undo 1 回）を通る。新しい書込経路は作らない。
 *
 * 値とひな型は変換定義のひな型（Template）で書く。受け取った値の一式が「いまの場所」（`$copy: name` や条件の `path: from` は
 * 受け取った値を読む）、要素は束縛名で指す（`$of: 束縛名`。受け手は `self`）。文字列 `$名前` は受け取った値そのもの。
 * 「今日」などの非決定の値は式に入れず、呼び出し側が受け取った値として渡す。
 * 計画（変更セットの生成）は workspace-core の ActionPlanner。ここは宣言と、その形の点検だけ。
 */

/** 操作の対象の場所: どの束縛の（null＝いまの要素）・どの文書の出現の・どのパス（受け手からの相対）。 */
data class StepSite(val of: String? = null, val inDoc: DerivationSelector? = null, val path: YPath = emptyList())

/** 効果の 1 手。conditions（`when`）に合わないときは飛ばす。 */
sealed interface ActionStep {
    val conditions: List<TemplateCondition>
    /** 書かれた場所（誤りの文面と、計画の要約に使う）。 */
    val origin: String

    /** 値を入れる（無ければ作る）。 */
    data class Set(val site: StepSite, val value: Template, override val conditions: List<TemplateCondition>, override val origin: String) : ActionStep

    /** 配列の末尾へ足す。params は要素の型の create へ渡す値、value は要素そのもののひな型（どちらか一方）。 */
    data class Insert(
        val site: StepSite,
        val params: Template?,
        val value: Template?,
        override val conditions: List<TemplateCondition>,
        override val origin: String,
    ) : ActionStep

    /** 要素や値を消す。 */
    data class Delete(val site: StepSite, override val conditions: List<TemplateCondition>, override val origin: String) : ActionStep

    /** 文書を作る（そのスキーマのルートの型の create）。 */
    data class Create(val schemaId: String, val params: Template, override val conditions: List<TemplateCondition>, override val origin: String) : ActionStep

    /** 要素の型の操作を呼ぶ。 */
    data class Call(
        val site: StepSite,
        val method: String,
        val params: Template,
        override val conditions: List<TemplateCondition>,
        override val origin: String,
    ) : ActionStep

    /** 起点を固定した照合の解ごとに、中の手を当てる（アクションだけ）。from は起点の束縛（省略＝受け手）。 */
    data class EachMatch(
        val from: String?,
        val spec: QuerySpec,
        val steps: List<ActionStep>,
        override val conditions: List<TemplateCondition>,
        override val origin: String,
    ) : ActionStep

    /** 配列の要素ごとに、その要素を受け手として中の手を当てる。 */
    data class EachPath(val site: StepSite, val steps: List<ActionStep>, override val conditions: List<TemplateCondition>, override val origin: String) : ActionStep
}

/** 手を書ける場所。型の操作は受け手の部分木に閉じる。 */
enum class StepScope { METHOD, ACTION }

/** 手の読み取り。 */
object ActionSteps {
    private val KINDS = listOf("set", "insert", "delete", "create", "call", "each")
    private val SITE_KEYS = setOf("of", "in", "path")

    fun parse(raw: Any?, where: String, scope: StepScope): List<ActionStep> {
        val list = raw as? List<*> ?: error("$where は手の配列である必要があります")
        require(list.isNotEmpty()) { "$where には 1 手以上が必要です" }
        return list.mapIndexed { i, e -> parseStep(e, "$where[$i]", scope) }
    }

    private fun parseStep(raw: Any?, where: String, scope: StepScope): ActionStep {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        m.keys.map { it.toString() }.firstOrNull { it !in KINDS && it != "when" }
            ?.let { error("$where: 未知のキーです: $it（使えるキー: ${(KINDS + "when").joinToString(", ")}）") }
        val kinds = KINDS.filter { m.containsKey(it) }
        require(kinds.size == 1) { "$where: 手の種類（${KINDS.joinToString(" / ")}）をちょうど 1 つ書きます" }
        val conditions = TransformDef.parseConditions(m["when"], "$where.when")
        val kind = kinds.single()
        val body = m[kind] as? Map<*, *> ?: error("$where.$kind はobjectである必要があります")
        fun keys(vararg allowed: String) {
            body.keys.map { it.toString() }.firstOrNull { it !in SITE_KEYS && it !in allowed }
                ?.let { error("$where.$kind: 未知のキーです: $it（使えるキー: ${(SITE_KEYS + allowed).joinToString(", ")}）") }
        }
        fun site(): StepSite {
            val of = body["of"]?.toString()?.takeIf { it.isNotBlank() }
            val inDoc = ValueExpr.parseSelector(body["in"], "$where.$kind.in")
            if (scope == StepScope.METHOD) {
                require(of == null && inDoc == null) {
                    "$where.$kind: 型の操作では of / in を書けません（受け手の要素の中だけを、相対のパスで書き換えます。他の要素や文書に触る操作はアクションに書きます）"
                }
            }
            val text = body["path"]?.toString() ?: ""
            val path = if (text.isBlank() || text == ".") emptyList() else runCatching { parseYPath(text) }
                .getOrElse { error("$where.$kind.path: パスとして読めません: $text") }
            return StepSite(of, inDoc, path)
        }
        fun template(key: String, required: Boolean): Template? {
            val v = body[key] ?: return if (required) error("$where.$kind.$key が必要です") else null
            return TransformDef.parseTemplate(v, "$where.$kind.$key")
        }
        return when (kind) {
            "set" -> { keys("value"); ActionStep.Set(site(), template("value", true)!!, conditions, where) }
            "delete" -> { keys(); ActionStep.Delete(site(), conditions, where) }
            "insert" -> {
                keys("params", "value")
                val params = template("params", false)
                val value = template("value", false)
                require((params != null) != (value != null)) { "$where.insert: params（要素の型の create へ渡す値）か value（要素そのもの）のどちらか一方を書きます" }
                ActionStep.Insert(site(), params, value, conditions, where)
            }
            "create" -> {
                require(scope == StepScope.ACTION) { "$where.create: 文書を作るのはアクションだけです（型の操作は受け手の要素の中だけを書き換えます）" }
                body.keys.map { it.toString() }.firstOrNull { it !in setOf("schema", "params") }
                    ?.let { error("$where.create: 未知のキーです: $it（使えるキー: schema, params）") }
                val schemaId = body["schema"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.create.schema（作る文書のスキーマ ID）が必要です")
                ActionStep.Create(schemaId, template("params", true)!!, conditions, where)
            }
            "call" -> {
                require(scope == StepScope.ACTION) { "$where.call: 他の要素の操作を呼べるのはアクションだけです" }
                keys("method", "params")
                val s = site()
                require(s.path.isEmpty()) { "$where.call: path は書けません（of で要素を指します）" }
                val method = body["method"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.call.method（操作の名前）が必要です")
                require(method != MethodDef.CREATE) { "$where.call: create は呼べません（新しい要素は insert か create で作ります）" }
                ActionStep.Call(s, method, template("params", false) ?: Template.MapOf(emptyList()), conditions, where)
            }
            else -> {  // each
                val inner = body["do"] ?: error("$where.each.do（中の手）が必要です")
                if (body.containsKey("match")) {
                    require(scope == StepScope.ACTION) { "$where.each: 照合（match）で相手を集められるのはアクションだけです（型の操作では path で自分の配列を回します）" }
                    body.keys.map { it.toString() }.firstOrNull { it !in setOf("from", "match", "where", "also", "limit", "do") }
                        ?.let { error("$where.each: 未知のキーです: $it（使えるキー: from, match, where, also, limit, do）") }
                    val spec = QuerySpec.parse(body, "$where.each", ignoreKeys = setOf("from", "do")).let { s ->
                        if (body["limit"] == null) s.copy(limit = Int.MAX_VALUE) else s
                    }
                    require(spec.start.kind == QueryNodeKind.ENTITY) { "$where.each: match の先頭の node は kind: entity である必要があります（起点の要素に合わせます）" }
                    ActionStep.EachMatch(
                        body["from"]?.toString()?.takeIf { it.isNotBlank() }, spec,
                        parse(inner, "$where.each.do", scope), conditions, where,
                    )
                } else {
                    keys("do")
                    ActionStep.EachPath(site(), parse(inner, "$where.each.do", scope), conditions, where)
                }
            }
        }
    }

    /** 手の中で受け取った値として参照している名前（宣言の点検に使う）。 */
    fun paramNames(steps: List<ActionStep>): Set<String> {
        val out = LinkedHashSet<String>()
        fun conditions(list: List<TemplateCondition>) = list.forEach { c ->
            c.alternatives.forEach { t -> t.operands.filterIsInstance<Template.Param>().forEach { out += it.name } }
        }
        fun walk(s: ActionStep) {
            conditions(s.conditions)
            when (s) {
                is ActionStep.Set -> out += s.value.paramNames()
                is ActionStep.Insert -> { s.params?.let { out += it.paramNames() }; s.value?.let { out += it.paramNames() } }
                is ActionStep.Delete -> Unit
                is ActionStep.Create -> out += s.params.paramNames()
                is ActionStep.Call -> out += s.params.paramNames()
                is ActionStep.EachMatch -> s.steps.forEach(::walk)
                is ActionStep.EachPath -> s.steps.forEach(::walk)
            }
        }
        steps.forEach(::walk)
        return out
    }

    /** 手の中で使っている束縛名（`of` / `from`。宣言の点検に使う。中の each が付ける名前は除く）。 */
    fun bindNames(steps: List<ActionStep>): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(s: ActionStep, declared: Set<String>) {
            fun site(site: StepSite) { site.of?.let { if (it !in declared) out += it } }
            when (s) {
                is ActionStep.Set -> { site(s.site); s.value.bindNames().forEach { if (it !in declared) out += it } }
                is ActionStep.Insert -> { site(s.site); listOfNotNull(s.params, s.value).flatMap { it.bindNames() }.forEach { if (it !in declared) out += it } }
                is ActionStep.Delete -> site(s.site)
                is ActionStep.Create -> s.params.bindNames().forEach { if (it !in declared) out += it }
                is ActionStep.Call -> { site(s.site); s.params.bindNames().forEach { if (it !in declared) out += it } }
                is ActionStep.EachMatch -> {
                    s.from?.let { if (it !in declared) out += it }
                    s.steps.forEach { walk(it, declared + s.spec.binds) }
                }
                is ActionStep.EachPath -> { site(s.site); s.steps.forEach { walk(it, declared) } }
            }
            s.conditions.forEach { c -> c.alternatives.forEach { t -> t.site.of?.let { if (it !in declared) out += it } } }
        }
        steps.forEach { walk(it, emptySet()) }
        return out
    }
}

/** ひな型の中の、受け取った値への参照の名前。 */
internal fun Template.paramNames(): Set<String> {
    val out = LinkedHashSet<String>()
    fun walk(t: Template) {
        when (t) {
            is Template.Literal -> Unit
            is Template.Param -> if (t.name !in TransformDef.LOOP_NAMES) out += t.name
            is Template.MapOf -> t.entries.forEach { walk(it.second) }
            is Template.ListOf -> t.items.forEach(::walk)
            is Template.Copy -> Unit
            is Template.Format -> t.with.forEach { walk(it.second) }
            is Template.MapValue -> walk(t.input)
            is Template.First -> t.options.forEach(::walk)
            is Template.DateOf -> walk(t.input)
            is Template.NumberOf -> walk(t.input)
            is Template.TrimOf -> walk(t.input)
            is Template.ValueOf -> Unit
            is Template.ViewRows -> Unit
            is Template.Each -> walk(t.body)
            is Template.Apply -> Unit
            is Template.Concat -> t.parts.forEach(::walk)
            is Template.Guarded -> {
                t.conditions.forEach { c -> c.alternatives.forEach { a -> a.operands.filterIsInstance<Template.Param>().forEach { out += it.name } } }
                walk(t.inner)
            }
        }
    }
    walk(this)
    return out
}

/** ひな型の中で指している束縛名（`$of`）。 */
internal fun Template.bindNames(): Set<String> {
    val out = LinkedHashSet<String>()
    fun site(s: TemplateSite) { s.of?.let { out += it } }
    fun walk(t: Template) {
        when (t) {
            is Template.Literal, is Template.Param, is Template.Apply -> Unit
            is Template.MapOf -> t.entries.forEach { walk(it.second) }
            is Template.ListOf -> t.items.forEach(::walk)
            is Template.Copy -> site(t.site)
            is Template.Format -> { site(t.site); t.with.forEach { walk(it.second) } }
            is Template.MapValue -> walk(t.input)
            is Template.First -> t.options.forEach(::walk)
            is Template.DateOf -> walk(t.input)
            is Template.NumberOf -> walk(t.input)
            is Template.TrimOf -> walk(t.input)
            is Template.ValueOf -> out += t.expr.binds()
            is Template.ViewRows -> Unit
            is Template.Each -> { site(t.site); walk(t.body) }
            is Template.Concat -> t.parts.forEach(::walk)
            is Template.Guarded -> { t.conditions.forEach { c -> c.alternatives.forEach { a -> site(a.site) } }; walk(t.inner) }
        }
    }
    walk(this)
    return out
}

/** 配列の追加の決まり（配列の項目の `methods.insert`）: 採番と、同じ明細の見分け。skill の取り込みの `target.id` / `target.key` と同じ綴り。 */
data class InsertRule(val id: IdNumbering?, val key: List<YPath>, val origin: String) {
    companion object {
        fun parse(raw: Any?, where: String): InsertRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("id", "key", "label", "description") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: id, key, label, description）") }
            val id = m["id"]?.let { IdNumbering.parse(it, "$where.id") }
            val key = when (val k = m["key"]) {
                null -> emptyList()
                is List<*> -> k.map { parseYPath(it.toString()) }
                else -> listOf(parseYPath(k.toString()))
            }
            return InsertRule(id, key, where)
        }
    }
}

/** 採番: 要素の ID の項目（path）が空なら、接頭辞（要素で埋めるひな型）＋連番（digits 桁）を付ける。 */
data class IdNumbering(val path: YPath, val prefix: Template, val digits: Int) {
    companion object {
        fun parse(raw: Any?, where: String): IdNumbering {
            val m = raw as? Map<*, *> ?: error("$where は {path, prefix, digits} のobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("path", "prefix", "digits") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: path, prefix, digits）") }
            val path = m["path"]?.toString()?.takeIf { it.isNotBlank() }?.let { parseYPath(it) } ?: listOf("id")
            val prefix = TransformDef.parseTemplate(m["prefix"] ?: error("$where.prefix（接頭辞）が必要です"), "$where.prefix")
            val digits = (m["digits"] as? Number)?.toInt() ?: 3
            require(digits in 1..9) { "$where.digits は 1〜9 です: $digits" }
            return IdNumbering(path, prefix, digits)
        }
    }
}

/**
 * 型の操作 1 つ。create は builds（定義側 → 使う側の順に重ねる）、それ以外は steps。
 * 使う側（`type: 名前` で項目を足した型）は定義側の操作を引き継ぎ、同じ名前を書けば置き換える。create だけは重ねる。
 */
data class MethodDef(
    val name: String,
    val label: String?,
    val description: String?,
    val params: List<FieldDef>,
    /** create の中身のひな型（定義側から順）。無ければ必須の初期値＋受け取った値。 */
    val builds: List<Template> = emptyList(),
    val steps: List<ActionStep> = emptyList(),
    val origin: String,
) {
    val isCreate: Boolean get() = name == CREATE
    val displayName: String get() = label ?: name

    companion object {
        const val CREATE = "create"
        private val RESERVED_ELSEWHERE = setOf("insert", "delete", "rename", "merge")
        private val NAME = Regex("""[A-Za-z][A-Za-z0-9_-]*""")

        /** object の `methods`（名前 → 定義）。 */
        fun parseObjectMethods(raw: Any?, where: String, definitionScope: String): List<MethodDef> {
            val m = raw as? Map<*, *> ?: error("$where はobject（操作の名前 → 定義）である必要があります")
            return m.entries.map { (k, v) ->
                val name = k.toString()
                require(NAME.matches(name)) { "$where: 操作の名前は英字で始まる英数字（- _ 可）で書きます: $name" }
                require(name !in RESERVED_ELSEWHERE) {
                    when (name) {
                        "insert" -> "$where.insert: insert は配列の項目の methods に書きます（追加のときの採番と同じ明細の見分け）"
                        else -> "$where.$name: $name は組込みの操作で、定義し直せません"
                    }
                }
                parseOne(name, v, "$where.$name", definitionScope)
            }
        }

        /** 配列の `methods`（insert だけ）。 */
        fun parseArrayMethods(raw: Any?, where: String): InsertRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it != "insert" }
                ?.let { error("$where: 配列の項目に書ける操作は insert だけです: $it") }
            return InsertRule.parse(m["insert"] ?: error("$where.insert が必要です"), "$where.insert")
        }

        private fun parseOne(name: String, raw: Any?, where: String, definitionScope: String): MethodDef {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            val allowed = setOf("label", "description", "params") + (if (name == CREATE) setOf("build") else setOf("do"))
            m.keys.map { it.toString() }.firstOrNull { it !in allowed }?.let { k ->
                error(
                    when {
                        name == CREATE && k == "do" -> "$where: create は build（新しい要素の中身のひな型）で書きます（do は書けません）"
                        name != CREATE && k == "build" -> "$where: build は create にだけ書けます（他の操作は do に手を並べます）"
                        else -> "$where: 未知のキーです: $k（使えるキー: ${allowed.joinToString(", ")}）"
                    }
                )
            }
            val params = ActionParams.parse(m["params"], "$where.params", definitionScope)
            val declared = params.mapNotNull { it.key }.toSet()
            val builds = if (name == CREATE) listOfNotNull(m["build"]?.let { TransformDef.parseTemplate(it, "$where.build") }) else emptyList()
            val steps = if (name == CREATE) emptyList() else ActionSteps.parse(m["do"] ?: error("$where.do（手の並び）が必要です"), "$where.do", StepScope.METHOD)
            val used = (builds.flatMap { it.paramNames() } + ActionSteps.paramNames(steps)).toSet()
            used.firstOrNull { it !in declared }?.let { error("$where: 受け取る値「\$$it」が params に宣言されていません") }
            (builds.flatMap { it.bindNames() } + ActionSteps.bindNames(steps)).firstOrNull { it != SELF }
                ?.let { error("$where: 型の操作で指せる要素は受け手（$SELF）だけです: $it") }
            return MethodDef(
                name = name,
                label = m["label"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                params = params,
                builds = builds,
                steps = steps,
                origin = where,
            )
        }

        /** 受け手の束縛名。 */
        const val SELF = "self"

        /** 使う側の操作を定義側の上に重ねる: 同じ名前は置き換え、create は build を重ね params は同じキーを置き換えて足す。 */
        fun merge(def: List<MethodDef>, use: List<MethodDef>): List<MethodDef> {
            if (use.isEmpty()) return def
            if (def.isEmpty()) return use
            val out = def.toMutableList()
            for (u in use) {
                val i = out.indexOfFirst { it.name == u.name }
                if (i < 0) { out += u; continue }
                out[i] = if (u.isCreate) {
                    val d = out[i]
                    u.copy(
                        params = d.params.map { p -> u.params.firstOrNull { it.key == p.key } ?: p } + u.params.filter { p -> d.params.none { it.key == p.key } },
                        builds = d.builds + u.builds,
                        label = u.label ?: d.label,
                        description = u.description ?: d.description,
                    )
                } else u
            }
            return out
        }
    }
}

/** 受け取る値（パラメータ）の宣言: 項目の定義そのまま。名前は `$名前` で参照できる形（英字で始まる英数字）。 */
object ActionParams {
    private val NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")

    fun parse(raw: Any?, where: String, definitionScope: String): List<FieldDef> {
        val list = when (raw) {
            null -> return emptyList()
            is List<*> -> raw
            else -> error("$where は項目の定義の配列である必要があります")
        }
        val params = list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("$where[$i] はobjectである必要があります")
            val key = m["key"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where[$i].key（受け取る値の名前）が必要です")
            require(NAME.matches(key)) { "$where[$i].key: 名前は英字で始まる英数字で書きます（\$名前 で参照するため）: $key" }
            require(key !in TransformDef.LOOP_NAMES) { "$where[$i].key: $key は繰り返しの中で使う名前のため、宣言できません" }
            SchemaTreeParser.parseField(m, key, definitionScope)
        }
        params.groupBy { it.key }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("$where: 名前「$it」が重複しています") }
        return params
    }
}

/** アクションの受け手: エンティティ型の要素か、スキーマの文書。bind は手の中でその要素を指す名前。 */
data class ActionTarget(val entity: String?, val schema: String?, val bind: String) {
    companion object {
        fun parse(raw: Any?, where: String): ActionTarget {
            val m = raw as? Map<*, *> ?: error("$where は {entity | schema, bind} のobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("entity", "schema", "bind") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: entity, schema, bind）") }
            val entity = m["entity"]?.toString()?.takeIf { it.isNotBlank() }
            val schema = m["schema"]?.toString()?.takeIf { it.isNotBlank() }
            require((entity != null) != (schema != null)) { "$where: entity（エンティティ型）か schema（文書のスキーマ）のどちらか一方を書きます" }
            val bind = m["bind"]?.toString()?.takeIf { it.isNotBlank() } ?: MethodDef.SELF
            return ActionTarget(entity, schema, bind)
        }
    }
}

/**
 * 前提 1 件: 検査と同じ書き方（match ＋ expect / count / check ＋ message）。受け取った値（`$名前`）を差し込んでから
 * 検査の形に読むため、原文のまま持つ。定義の段階では仮の値で形だけ確かめる。
 */
data class ActionRequire(val raw: Map<String, Any?>, val origin: String, val referenced: Set<String> = emptySet()) {
    /** 受け取った値を差し込んだ検査。使っている値が無ければ（省かれた任意の値）誤り——黙って絞り込みが外れるのを防ぐ。 */
    fun rule(values: Map<String, Any?>): ConstraintRule {
        referenced.firstOrNull { values[it] == null }
            ?.let { error("前提（$origin）は受け取る値「\$$it」を使いますが、値がありません（この前提を使う操作では、その値を必ず渡します）") }
        val substituted = CheckLibrary.substitute(raw, values) as? Map<*, *> ?: error("$origin はobjectである必要があります")
        return ConstraintRule.parse(substituted, origin)
    }

    /**
     * ダッシュボードの共有の入力（空＝絞らない）向け: optional の名前のうち値が無い（空の）ものを使う条件（where / check の項目）を
     * 外してから差し込む。外した結果 expect / count / check / forAll が残らなければ「条件なし」（null）。match などに残った参照は
     * rule と同じく誤り（黙って外れない）。
     */
    fun ruleOrNull(values: Map<String, Any?>, optional: Set<String>): ConstraintRule? {
        val given = values.filterValues { it != null && it.toString().isNotBlank() }
        val missing = optional.filter { it !in given }.toSet()
        val pruned = prune(raw, missing)
        if (RULE_KEYS.none { pruned.containsKey(it) }) return null
        return ActionRequire(pruned, origin, referencedNames(pruned)).rule(given)
    }

    companion object {
        private val PARAM = Regex("""\$([A-Za-z][A-Za-z0-9_]*)""")
        private val RULE_KEYS = setOf("expect", "count", "check", "forAll")

        /** 原文の中で参照している受け取る値の名前（`$$` は文字）。ビューの params の点検にも使う。 */
        fun referencedNames(raw: Any?): Set<String> = referencedIn(raw)

        /** 値の無い名前（missing）を使う条件（where / check の項目）を外す。項目が無くなった鍵は消す（空＝絞らない）。 */
        fun prune(raw: Map<String, Any?>, missing: Set<String>): Map<String, Any?> {
            if (missing.isEmpty()) return raw
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in raw) {
                if ((k == "where" || k == "check") && v is List<*>) {
                    val kept = v.filter { item -> referencedIn(item).none { it in missing } }
                    if (kept.isNotEmpty()) out[k] = kept
                } else out[k] = v
            }
            return out
        }

        /** 原文の中で参照している受け取る値の名前（`$$` は文字）。 */
        private fun referencedIn(raw: Any?): Set<String> = when (raw) {
            is Map<*, *> -> raw.values.flatMap { referencedIn(it) }.toSet()
            is List<*> -> raw.flatMap { referencedIn(it) }.toSet()
            is String -> PARAM.findAll(raw.replace("\$\$", "")).map { it.groupValues[1] }.toSet()
            else -> emptySet()
        }

        fun parse(raw: Any?, where: String, params: Set<String>, hasStart: Boolean): ActionRequire {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            val map = m.entries.associate { it.key.toString() to it.value }
            m.keys.map { it.toString() }.firstOrNull { it in setOf("responses", "reportAt", "status", "fixSite", "level", "levelFrom", "activeFrom") }
                ?.let { error("$where: 前提に $it は書けません（前提は実行できるかを決めるだけで、指摘は出しません）") }
            val referenced = referencedIn(map)
            referenced.firstOrNull { it !in params }?.let { error("$where: 受け取る値「\$$it」が params に宣言されていません") }
            val require = ActionRequire(map, where, referenced)
            // 形の点検: 受け取る値に仮の値を入れて、検査として読めること
            val rule = require.rule(params.associateWith { "X" })
            if (!hasStart) {
                require(rule.check.isEmpty() && rule.forAll == null) {
                    "$where: 受け手（on）の無いアクションの前提に書けるのは expect / count だけです（check / forAll は起点の要素が要ります）"
                }
            }
            return require
        }
    }
}

/** 横断の操作 1 件。 */
data class ActionDef(
    val id: String,
    val label: String?,
    val description: String?,
    val target: ActionTarget?,
    val params: List<FieldDef>,
    val require: List<ActionRequire>,
    val steps: List<ActionStep>,
    val origin: String,
    val input: ActionInput? = null,
) {
    val displayName: String get() = label ?: id

    companion object {
        private val KEYS = setOf("id", "label", "description", "target", "params", "require", "do", "input")
        private val ID = Regex("""[A-Za-z][A-Za-z0-9_-]*""")

        fun parse(raw: Any?, where: String, definitionScope: String): ActionDef {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.id（操作の ID）が必要です")
            require(ID.matches(id)) { "$where.id: ID は英字で始まる英数字（- _ 可）で書きます: $id" }
            val target = m["target"]?.let { ActionTarget.parse(it, "$where.target") }
            val params = ActionParams.parse(m["params"], "$where.params", definitionScope)
            val declared = params.mapNotNull { it.key }.toSet()
            val require = when (val r = m["require"]) {
                null -> emptyList()
                is List<*> -> r.mapIndexed { i, e -> ActionRequire.parse(e, "$where.require[$i]", declared, hasStart = target?.entity != null) }
                else -> error("$where.require は前提の配列である必要があります")
            }
            val steps = ActionSteps.parse(m["do"] ?: error("$where.do（手の並び）が必要です"), "$where.do", StepScope.ACTION)
            ActionSteps.paramNames(steps).firstOrNull { it !in declared }
                ?.let { error("$where: 受け取る値「\$$it」が params に宣言されていません") }
            val binds = setOfNotNull(target?.bind)
            ActionSteps.bindNames(steps).firstOrNull { it !in binds }
                ?.let { error("$where: 束縛名「$it」は宣言されていません（受け手は target の bind、照合で付けた名前は each の中だけで使えます）") }
            return ActionDef(
                id = id,
                label = m["label"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                target = target,
                params = params,
                require = require,
                steps = steps,
                origin = where,
                input = ActionInput.parse(m["input"], params, target?.bind, "$where.input"),
            )
        }
    }
}
