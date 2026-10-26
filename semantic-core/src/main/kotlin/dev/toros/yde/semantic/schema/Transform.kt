package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import java.text.DecimalFormat

/*
 * 「YAML から YAML への変換」の宣言モデル（P27 段C。計算体系の6つ目の体系「組み立て」＝CORE-CALCULUS §26）。
 *
 * 変換 ＝ 選ぶ × 組み立てる ×（入れ子には）規則を子に当てる。
 * - 選ぶ: `from.match` に既存のクエリ（要素と文書を選んで名前を付ける）。書かなければ、呼び出し側が渡した項1つが入力。
 *   選んだものの「中」は、パスと繰り返し（`$each` / `$eachEntry`）で辿る——クエリ自体は ID の無い場所まで広げない（TF-02）
 * - 組み立てる: 出力の形をそのまま YAML で書いた「ひな型」。`$` で始まるキーを持つマップだけが指示で、それ以外は書いたとおりに出力される
 * - 規則を子に当てる: `rules` に名前を付けた規則を置き、`$apply` で入力の木の子に当てる（TF-03。必ず終わる形だけを認める）
 * 出力は常に YAML の項で、ワークスペースの文書としては保存しない（TF-09）。
 *
 * ひな型に書ける指示（どれも `$when` で「条件に合うときだけ出す」にできる。値が得られない指示は「無い」になり、マップのキーごと出力から落ちる）:
 *   {$copy: パス}                         その場所の値（部分木ごと）をそのまま写す。`.` は、いまの場所そのもの
 *   {$format: "…{パス}…", $at: パス, $with: {名前: ひな型}}
 *                                         文字のひな型を、その場所の値で埋める（既存の Projection。埋まらない穴があれば「無い」）。
 *                                         `$with` は計算した値を穴へ差し込む（場所の値より先に埋まる。値の式の format の with と同じ）
 *   {$map: ひな型, $using: {a: b}}        スカラーの値を辞書で置き換える（辞書に無い値はそのまま）
 *   {$first: [ひな型, …]}                 最初に得られた値
 *   {$date: ひな型, $as: 形}              年月日をそろえる（2021/8/21・2021-8-21・20210821・2021年8月21日 → yyyy-MM-dd。`$as` で yyyyMMdd・yyyy-MM などの形。読めなければ「無い」）
 *   {$number: ひな型, $as: 形}            数として読む（¥ と桁区切りと全角、▲ の負を許す。読めなければ「無い」。値の式の number と同じ読み方）。
 *                                         `$as` は DecimalFormat の形（`00`・`#,##0`）で、その形の文字にして出す（連番の 0 詰めなど）
 *   {$value: 値の式}                      値の式（検査の check・ビューの列と同じ書き方）をいまの場所で計算する（`of` を省くといまの場所、
 *                                         束縛は `of: 名前`。四則演算・集計・日付の部分・each など。グラフを辿る all・refCount・
 *                                         occurrenceCount は「無い」。数の結果は数の項、配列は配列の項、それ以外は文字）
 *   {$trim: ひな型}                       前後の空白を落とし、続く空白を 1 つにまとめる（全角の空白も。空なら「無い」）
 *   {$each: パス, $build: ひな型}         配列の要素ごとに組み立てて、配列を作る（`$apply: 規則名` とも書ける）
 *   {$eachEntry: パス, $build: ひな型}    マップの項目ごとに組み立てて、同じキーのマップを作る（同上）
 *   {$apply: 規則名, $at: パス}           名前を付けた規則を、その場所に当てる
 *   {$concat: [ひな型, …]}                配列をつなげる
 *   {$when: 条件, $then: ひな型}          条件に合うときだけ、その値
 * 場所の指定には、`$of: 束縛名`（クエリで付けた名前。省略＝いまの場所）と `$in: {schemaId, fileGlob}`（どの文書の出現か）を添えられる。
 * 文字列 `$名前` は受け取った値（params）への参照。`$$` は文字 `$`（出力のキーや値を `$` で始めたいとき）。
 * 繰り返しの中では `$key`（マップの項目のキー）と `$index`（配列の番号。0 始まり）と `$position`（何番目か。1 始まり）が使える。
 *
 * 条件は、クエリの where と同じ書き方（path ＋ equals / notEquals / exists / empty / oneOf / matches / startsWith / endsWith /
 * contains / lt / lte / gt / gte。`{not: 条件}`・`{or: [条件, …]}`。並べて書けば「かつ」）。`of` は省略できる（いまの場所）。
 */

/** 値を読む場所の指定: どの束縛の（null＝いまの場所）・どの文書の出現の・どのパスか。 */
data class TemplateSite(val of: String? = null, val inDoc: DerivationSelector? = null, val path: YPath = emptyList())

/** ひな型の1ノード。 */
sealed interface Template {
    /** 決まった値（スカラー）。 */
    data class Literal(val value: Any?) : Template

    /** 受け取った値（params）・繰り返しの中の `$key` / `$index`。 */
    data class Param(val name: String) : Template

    /** 書いたとおりのマップ（値が「無い」項目は出力から落ちる）。 */
    data class MapOf(val entries: List<Pair<String, Template>>) : Template

    /** 書いたとおりの配列（値が「無い」要素は飛ばす）。 */
    data class ListOf(val items: List<Template>) : Template

    data class Copy(val site: TemplateSite) : Template

    /** 文字のひな型。with は計算した値を穴へ差し込む（名前 → ひな型。場所の値より先に埋まる）。 */
    data class Format(val template: String, val site: TemplateSite, val with: List<Pair<String, Template>> = emptyList()) : Template
    data class MapValue(val input: Template, val using: Map<String, String>) : Template
    data class First(val options: List<Template>) : Template

    /** 年月日をそろえる（pattern は出力の形。null＝yyyy-MM-dd）。 */
    data class DateOf(val input: Template, val pattern: String?) : Template

    /** 数として読む（pattern は出力の形＝DecimalFormat。null＝数の項、あれば その形の文字）。 */
    data class NumberOf(val input: Template, val pattern: String? = null) : Template

    /** 前後の空白を落とし、続く空白を 1 つにまとめる。 */
    data class TrimOf(val input: Template) : Template

    /** 値の式をいまの場所で計算する（R30-01。`of` の省略＝いまの場所、束縛は `of: 名前`）。 */
    data class ValueOf(val expr: ValueExpr) : Template

    /** 繰り返し。entries = true はマップの項目ごと（出力もマップ）・false は配列の要素ごと（出力も配列）。 */
    data class Each(val site: TemplateSite, val entries: Boolean, val body: Template) : Template
    data class Apply(val rule: String, val site: TemplateSite) : Template
    data class Concat(val parts: List<Template>) : Template

    /** 条件に合うときだけ出す。 */
    data class Guarded(val conditions: List<TemplateCondition>, val inner: Template) : Template
}

/** 条件1つ（where と同じ書き方）。operands は比べる値（決まった値か、受け取った値への参照）。 */
data class TemplateTest(
    val site: TemplateSite,
    val kind: Kind,
    val op: CheckOp? = null,
    val operands: List<Template> = emptyList(),
    val flag: Boolean = true,
    val negated: Boolean = false,
) {
    enum class Kind { EQUALS, NOT_EQUALS, EXISTS, EMPTY, TEST }
}

/** 条件の「または」（1つなら単独の条件）。 */
data class TemplateCondition(val alternatives: List<TemplateTest>)

/** 名前を付けた規則。同じ名前の規則を複数書くと、上から順に条件（when）を調べ、最初に合ったものを使う。 */
data class TransformRule(val id: String, val conditions: List<TemplateCondition>, val build: Template)

/** 変換の定義1件。 */
data class TransformDef(
    val id: String,
    val description: String?,
    /** 受け取る値の名前。 */
    val params: List<String>,
    /** クエリで選ぶときのスペック（null＝呼び出し側が渡した項1つが入力）。 */
    val from: QuerySpec?,
    val build: Template,
    val rules: List<TransformRule>,
) {
    companion object {
        private val TOP_KEYS = setOf("id", "name", "description", "params", "from", "build", "rules")
        private val RULE_KEYS = setOf("id", "when", "build")
        private val PARAM_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")

        /**
         * 繰り返しの中で使える名前（params に宣言しなくてよい）: key＝マップのキー・index＝0 始まりの番号・position＝1 始まりの番号
         * （`no` は YAML が真偽値に読むキーなので使わない）。
         */
        val LOOP_NAMES = setOf("key", "index", "position")

        private const val COPY = "\$copy"
        private const val FORMAT = "\$format"
        private const val MAP = "\$map"
        private const val FIRST = "\$first"
        private const val EACH = "\$each"
        private const val EACH_ENTRY = "\$eachEntry"
        private const val APPLY = "\$apply"
        private const val CONCAT = "\$concat"
        private const val THEN = "\$then"
        private const val OF = "\$of"
        private const val IN = "\$in"
        private const val USING = "\$using"
        private const val BUILD = "\$build"
        private const val AT = "\$at"
        private const val WHEN = "\$when"
        private const val DATE = "\$date"
        private const val NUMBER = "\$number"
        private const val TRIM = "\$trim"
        private const val VALUE = "\$value"
        private const val AS = "\$as"
        private const val WITH = "\$with"
        private val PRIMARY = listOf(COPY, FORMAT, MAP, FIRST, EACH, EACH_ENTRY, APPLY, CONCAT, THEN, DATE, NUMBER, TRIM, VALUE)
        private val DIRECTIVE_KEYS = PRIMARY + listOf(OF, IN, USING, BUILD, AT, WHEN, AS, WITH)

        /** `$as`（日付の出力の形）に書ける文字: 年 y・月 M・日 d と区切り。 */
        private val DATE_PATTERN = Regex("""[yMd\-/.]+""")

        private val CONDITION_OPS = CheckOp.entries.filter { it != CheckOp.EQ && it != CheckOp.NE }
        private val CONDITION_KEYS = listOf("equals", "notEquals", "exists", "empty") + CONDITION_OPS.map { it.id }

        fun parse(raw: Any?, where: String = "transform"): TransformDef {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in TOP_KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${TOP_KEYS.joinToString(", ")}）") }
            val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.id が必要です")
            val params = (m["params"] as? List<*>)?.map { it.toString() }
                ?: (if (m["params"] == null) emptyList() else error("$where.params は名前の配列である必要があります"))
            params.forEach { name ->
                require(PARAM_NAME.matches(name)) { "$where.params: 名前は英字で始まる英数字で書きます: $name" }
                require(name !in LOOP_NAMES) { "$where.params: $name は繰り返しの中で使う名前のため、宣言できません" }
            }
            val from = m["from"]?.let { f ->
                val fm = f as? Map<*, *> ?: error("$where.from はobject（{match: [...]}）である必要があります")
                require(fm["match"] != null) { "$where.from には match（クエリ）が必要です。入力の項を呼び出し側が渡す変換では、from を書きません" }
                QuerySpec.parse(fm, "$where.from").let { spec ->
                    // 変換はすべての行を相手にする——件数の上限は、書いたときだけ効かせる
                    if (fm["limit"] == null) spec.copy(limit = Int.MAX_VALUE) else spec
                }
            }
            val rules = (m["rules"] as? List<*>).orEmpty().mapIndexed { i, r ->
                val rm = r as? Map<*, *> ?: error("$where.rules[$i] はobjectである必要があります")
                rm.keys.map { it.toString() }.firstOrNull { it !in RULE_KEYS }
                    ?.let { error("$where.rules[$i]: 未知のキーです: $it（使えるキー: ${RULE_KEYS.joinToString(", ")}）") }
                TransformRule(
                    id = rm["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.rules[$i].id が必要です"),
                    conditions = parseConditions(rm["when"], "$where.rules[$i].when"),
                    build = parseTemplate(rm["build"] ?: error("$where.rules[$i].build が必要です"), "$where.rules[$i].build"),
                )
            }
            val build = parseTemplate(m["build"] ?: error("$where.build が必要です"), "$where.build")
            val def = TransformDef(id, m["description"]?.toString(), params, from, build, rules)
            def.requireClosed(where)
            return def
        }

        /**
         * ひな型だけの変換定義（P31・R31-03。スキーマの `scaffold` など、クエリも規則も受け取る値も持たず、呼び出し側が渡した
         * 項1つを「いまの場所」として組み立てるもの）。宣言の点検は、ふつうの変換定義と同じ。
         */
        fun ofTemplate(id: String, raw: Any?, where: String): TransformDef {
            val def = TransformDef(id, null, emptyList(), null, parseTemplate(raw, where), emptyList())
            def.requireClosed(where)
            return def
        }

        /** ひな型のパース。 */
        fun parseTemplate(raw: Any?, where: String): Template = when (raw) {
            is Map<*, *> -> parseMap(raw, where)
            is List<*> -> Template.ListOf(raw.mapIndexed { i, e -> parseTemplate(e, "$where[$i]") })
            is String -> parseString(raw)
            else -> Template.Literal(raw)
        }

        /** 文字列: `$名前` は受け取った値への参照・先頭の `$$` は文字 `$`。 */
        private fun parseString(raw: String): Template = when {
            raw.startsWith("\$\$") -> Template.Literal(raw.substring(1))
            raw.startsWith("\$") && PARAM_NAME.matches(raw.substring(1)) -> Template.Param(raw.substring(1))
            else -> Template.Literal(raw)
        }

        private fun unescape(text: String): String = if (text.startsWith("\$\$")) text.substring(1) else text

        private fun parseMap(m: Map<*, *>, where: String): Template {
            val keys = m.keys.map { it.toString() }
            val directives = keys.filter { it.startsWith("\$") && !it.startsWith("\$\$") }
            directives.firstOrNull { it !in DIRECTIVE_KEYS }
                ?.let { error("$where: 未知の指示です: $it（使える指示: ${DIRECTIVE_KEYS.joinToString(", ")}。出力のキーを \$ で始めるときは \$\$ と書きます）") }
            val conditions = parseConditions(m[WHEN], "$where.$WHEN")
            fun guarded(t: Template): Template = if (conditions.isEmpty()) t else Template.Guarded(conditions, t)

            val primary = directives.filter { it in PRIMARY }
            if (primary.isEmpty()) {
                // 書いたとおりのマップ（$when だけは添えられる）
                directives.firstOrNull { it != WHEN }?.let { error("$where: $it は、指示（${PRIMARY.joinToString(" / ")}）と一緒に書きます") }
                val entries = m.entries.filter { it.key.toString() != WHEN }
                    .map { (k, v) -> unescape(k.toString()) to parseTemplate(v, "$where.$k") }
                return guarded(Template.MapOf(entries))
            }
            // $each / $eachEntry は、繰り返す中身として $apply を取れる（それ以外は、指示は1つだけ）
            val main = primary.firstOrNull { it == EACH || it == EACH_ENTRY } ?: primary.first()
            keys.firstOrNull { !it.startsWith("\$") || it.startsWith("\$\$") }
                ?.let { error("$where: 指示（$main）と、出力のキー（$it）は一緒に書けません") }
            fun allow(vararg modifiers: String) {
                directives.firstOrNull { it != main && it != WHEN && it !in modifiers }
                    ?.let { error("$where: $main と $it は一緒に書けません") }
            }
            fun site(pathRaw: Any?, pathWhere: String): TemplateSite {
                val text = pathRaw?.toString() ?: ""
                val path = if (text == "." || text.isBlank()) emptyList() else runCatching { parseYPath(unescape(text)) }
                    .getOrElse { error("$pathWhere: パスとして読めません: $text") }
                return TemplateSite(
                    of = m[OF]?.toString()?.takeIf { it.isNotBlank() },
                    inDoc = ValueExpr.parseSelector(m[IN], "$where.$IN"),
                    path = path,
                )
            }
            return guarded(
                when (main) {
                    COPY -> { allow(OF, IN); Template.Copy(site(m[COPY], "$where.$COPY")) }
                    FORMAT -> {
                        allow(OF, IN, AT, WITH)
                        val with = m[WITH]?.let { raw ->
                            val wm = raw as? Map<*, *> ?: error("$where.$WITH は {穴の名前: ひな型} で書きます")
                            wm.entries.map { (k, v) -> k.toString() to parseTemplate(v, "$where.$WITH.$k") }
                        }.orEmpty()
                        Template.Format(m[FORMAT]?.toString() ?: error("$where.$FORMAT は文字列で書きます"), site(m[AT], "$where.$AT"), with)
                    }
                    DATE -> {
                        allow(AS)
                        val pattern = m[AS]?.toString()?.also { p ->
                            require(DATE_PATTERN.matches(p) && 'y' in p) {
                                "$where.$AS は年月日の形（y・M・d と区切り。例 yyyyMMdd・yyyy-MM）で書きます: $p"
                            }
                        }
                        Template.DateOf(parseTemplate(m[DATE], "$where.$DATE"), pattern)
                    }
                    NUMBER -> {
                        allow(AS)
                        val pattern = m[AS]?.toString()?.also { p ->
                            // 数の形: 桁の印（0 か #）を含む DecimalFormat のパターン
                            val readable = p.any { it == '0' || it == '#' } && runCatching { DecimalFormat(p) }.isSuccess
                            if (!readable) error("$where.$AS は数の形（DecimalFormat のパターン。例 00・#,##0）で書きます: $p")
                        }
                        Template.NumberOf(parseTemplate(m[NUMBER], "$where.$NUMBER"), pattern)
                    }
                    TRIM -> { allow(); Template.TrimOf(parseTemplate(m[TRIM], "$where.$TRIM")) }
                    VALUE -> { allow(); Template.ValueOf(ValueExpr.parse(m[VALUE], "$where.$VALUE", current = true)) }
                    MAP -> {
                        allow(USING)
                        val using = (m[USING] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() }
                            ?: error("$where.$USING（置き換えの辞書）が必要です")
                        Template.MapValue(parseTemplate(m[MAP], "$where.$MAP"), using)
                    }
                    FIRST -> {
                        allow()
                        val list = (m[FIRST] as? List<*>)?.takeIf { it.isNotEmpty() } ?: error("$where.$FIRST はひな型の配列（1つ以上）である必要があります")
                        Template.First(list.mapIndexed { i, e -> parseTemplate(e, "$where.$FIRST[$i]") })
                    }
                    EACH, EACH_ENTRY -> {
                        allow(OF, IN, BUILD, APPLY)
                        val body = when {
                            m.containsKey(BUILD) && m.containsKey(APPLY) -> error("$where: $BUILD と $APPLY は、どちらか一方を書きます")
                            m.containsKey(BUILD) -> parseTemplate(m[BUILD], "$where.$BUILD")
                            m.containsKey(APPLY) -> Template.Apply(ruleName(m[APPLY], "$where.$APPLY"), TemplateSite())
                            else -> error("$where: $main には、$BUILD（ひな型）か $APPLY（規則名）が必要です")
                        }
                        Template.Each(site(m[main], "$where.$main"), entries = main == EACH_ENTRY, body = body)
                    }
                    APPLY -> { allow(OF, IN, AT); Template.Apply(ruleName(m[APPLY], "$where.$APPLY"), site(m[AT], "$where.$AT")) }
                    CONCAT -> {
                        allow()
                        val list = m[CONCAT] as? List<*> ?: error("$where.$CONCAT はひな型の配列である必要があります")
                        Template.Concat(list.mapIndexed { i, e -> parseTemplate(e, "$where.$CONCAT[$i]") })
                    }
                    else -> {  // $then
                        allow()
                        require(conditions.isNotEmpty()) { "$where: $THEN は $WHEN（条件）と一緒に書きます" }
                        parseTemplate(m[THEN], "$where.$THEN")
                    }
                }
            )
        }

        private fun ruleName(raw: Any?, where: String): String =
            raw?.toString()?.takeIf { it.isNotBlank() } ?: error("$where には規則名を書きます")

        /** 条件（1つ、または並べて「かつ」）。 */
        fun parseConditions(raw: Any?, where: String): List<TemplateCondition> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.mapIndexed { i, e -> parseCondition(e, "$where[$i]") }
            else -> listOf(parseCondition(raw, where))
        }

        private fun parseCondition(raw: Any?, where: String): TemplateCondition {
            val m = raw as? Map<*, *> ?: error("$where は条件のobjectである必要があります")
            if (m.containsKey("or")) {
                if (m.size != 1) error("$where: or は単独で書きます（{or: [条件, ...]}）")
                val list = (m["or"] as? List<*>)?.takeIf { it.size >= 2 } ?: error("$where.or は条件の配列（2つ以上）である必要があります")
                return TemplateCondition(list.mapIndexed { i, e -> parseLeaf(e, "$where.or[$i]") })
            }
            return TemplateCondition(listOf(parseLeaf(raw, where)))
        }

        private fun parseLeaf(raw: Any?, where: String): TemplateTest {
            val m = raw as? Map<*, *> ?: error("$where は条件のobjectである必要があります")
            if (m.containsKey("or")) error("$where: or の入れ子は書けません（入れ子は1段まで）")
            if (m.containsKey("not")) {
                if (m.size != 1) error("$where: not は単独で書きます（{not: 条件}）")
                val inner = m["not"] as? Map<*, *> ?: error("$where.not は条件のobjectである必要があります")
                if (inner.containsKey("not") || inner.containsKey("or")) error("$where.not の中に not / or は書けません（入れ子は1段まで）")
                return parseLeaf(inner, "$where.not").copy(negated = true)
            }
            val allowed = setOf("of", "in", "path") + CONDITION_KEYS
            m.keys.map { it.toString() }.firstOrNull { it !in allowed }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${allowed.joinToString(", ")}）") }
            val given = CONDITION_KEYS.filter { m[it] != null }
            if (given.size != 1) error("$where: 条件（${CONDITION_KEYS.joinToString(" / ")}）のいずれか一つが必要です")
            val pathText = m["path"]?.toString() ?: ""
            val site = TemplateSite(
                of = m["of"]?.toString()?.takeIf { it.isNotBlank() },
                inDoc = ValueExpr.parseSelector(m["in"], "$where.in"),
                path = if (pathText == "." || pathText.isBlank()) emptyList() else parseYPath(unescape(pathText)),
            )
            fun flag(key: String): Boolean = m[key] as? Boolean ?: error("$where.$key は true / false で書きます")
            fun operand(v: Any?): Template = if (v is String) parseString(v) else Template.Literal(v)
            return when (val key = given.single()) {
                "equals" -> TemplateTest(site, TemplateTest.Kind.EQUALS, operands = listOf(operand(m[key])))
                "notEquals" -> TemplateTest(site, TemplateTest.Kind.NOT_EQUALS, operands = listOf(operand(m[key])))
                "exists" -> TemplateTest(site, TemplateTest.Kind.EXISTS, flag = flag(key))
                "empty" -> TemplateTest(site, TemplateTest.Kind.EMPTY, flag = flag(key))
                else -> {
                    val op = CheckOp.parse(key, where)
                    val v = m[key]
                    val operands = when {
                        v is List<*> -> v.map(::operand)
                        v is Map<*, *> -> error("$where.$key は決まった値か、受け取った値（\$名前）で書きます")
                        else -> listOf(operand(v))
                    }
                    operands.filterIsInstance<Template.Literal>().forEach { op.requireValidOperand(it.value.toString(), "$where.$key") }
                    TemplateTest(site, TemplateTest.Kind.TEST, op = op, operands = operands)
                }
            }
        }
    }

    /** 使っている規則名と受け取る値の名前が、すべて宣言されていること（定義の段階で点検する）。 */
    private fun requireClosed(where: String) {
        val ruleIds = rules.map { it.id }.toSet()
        val binds = from?.binds.orEmpty().toSet()
        fun site(s: TemplateSite) {
            s.of?.let { require(it in binds) { "$where: \$of / of の「$it」は、from のクエリで付けた名前ではありません（付けた名前: ${binds.joinToString(", ").ifEmpty { "なし" }}）" } }
        }
        fun walk(t: Template) {
            when (t) {
                is Template.Literal -> Unit
                is Template.Param -> require(t.name in params || t.name in LOOP_NAMES) {
                    "$where: 受け取る値「\$${t.name}」が params に宣言されていません（文字 \$ で始まる値は \$\$ と書きます）"
                }
                is Template.MapOf -> t.entries.forEach { walk(it.second) }
                is Template.ListOf -> t.items.forEach(::walk)
                is Template.Copy -> site(t.site)
                is Template.Format -> { site(t.site); t.with.forEach { walk(it.second) } }
                is Template.MapValue -> walk(t.input)
                is Template.First -> t.options.forEach(::walk)
                is Template.DateOf -> walk(t.input)
                is Template.NumberOf -> walk(t.input)
                is Template.TrimOf -> walk(t.input)
                is Template.ValueOf -> t.expr.binds().forEach { name ->
                    require(name in binds) { "$where: \$value の「of: $name」は、from のクエリで付けた名前ではありません（付けた名前: ${binds.joinToString(", ").ifEmpty { "なし" }}）" }
                }
                is Template.Each -> { site(t.site); walk(t.body) }
                is Template.Apply -> {
                    site(t.site)
                    require(t.rule in ruleIds) { "$where: 規則「${t.rule}」が rules にありません（ある規則: ${ruleIds.joinToString(", ").ifEmpty { "なし" }}）" }
                }
                is Template.Concat -> t.parts.forEach(::walk)
                is Template.Guarded -> { t.conditions.forEach(::condition); walk(t.inner) }
            }
        }
        walk(build)
        rules.forEach { r -> r.conditions.forEach(::condition); walk(r.build) }
    }

    private fun condition(c: TemplateCondition) {
        c.alternatives.forEach { test ->
            test.operands.filterIsInstance<Template.Param>().forEach { p ->
                require(p.name in params || p.name in LOOP_NAMES) { "条件の「\$${p.name}」が params に宣言されていません" }
            }
        }
    }
}
