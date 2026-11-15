package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import java.util.IdentityHashMap

/**
 * 項の変更の文書 1 つ（段の規則か補正。ADR-0034）。項（業務モデルの項など）の並びの要素を選び、値を置き換え・足す。
 * kind は規則（`rule`＝全体の方針。条件で選ぶ）か補正（`patch`＝個別の例外。当たる要素が無ければ誤り）。
 * rules は値のひな型が `$apply` で使う、名前を付けた組み立て（生成の定義の rules と同じ）。
 */
data class TermChangeDoc(
    val id: String,
    val name: String,
    val description: String?,
    val stage: String,
    val kind: Kind,
    val changes: List<TermChange>,
) {
    enum class Kind(val id: String, val label: String) { RULE("rule", "規則"), PATCH("patch", "補正") }
}

/**
 * 変更 1 つ: いまの場所の並び（`each`。`[*]` で入れ子の並びにも広がる）の要素のうち、条件（`where`）に合うものごとに、
 * 値を置き換え（`set`）・要素の中の並びへ足し（`add`）・要素の後ろへ足し（`after`）、中の変更（`changes`）を当てる。
 * each を書かなければ、いまの場所そのもの。bind は選んだ要素の名前（中の変更の値と条件から `$of` で読む）。
 */
data class TermChange(
    val at: String,
    val each: List<Any>?,
    val where: TransformDef?,
    val bind: String?,
    val set: List<Pair<YPath, TransformDef>>,
    val add: List<Pair<YPath, TransformDef>>,
    val after: TransformDef?,
    val changes: List<TermChange>,
)

/**
 * 項の変更（段の規則と補正）の読み取りと当て方。値は変換のひな型（[SemanticTransform]）で計算する:
 * いまの場所＝選んだ要素・`$of: model`＝この文書を当てる前の項・`$of: <bind>`＝外側の変更で選んだ要素。
 *
 * - 当てる順: 変更を上から。1 つの変更の中は、置き換え → 中へ足す → 後ろへ足す → 中の変更
 * - 出どころ: 足した要素（object）には `origin`（呼び出し側が渡す。段・文書・種類）を付ける（書いてあれば書いたまま）。
 *   置き換えた場所は、要素の `origins` に {path, …出どころ} を 1 つ足す
 * - 補正（patch）は、当たる要素が 1 つも無い変更を誤りにする（業務モデルの ID や名前が変わったことに気づくため）
 * 計算は決定的で、同じ項と文書からは同じ項になる。渡した項は変えない。
 */
object TermChanges {
    /** 値のひな型が `$of` で読む、当てる前の項の名前。 */
    const val MODEL = "model"

    private val TOP_KEYS = setOf("id", "name", "description", "stage", "kind", "changes", "rules")
    private val CHANGE_KEYS = setOf("each", "where", "bind", "set", "add", "after", "changes")
    private val NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")

    /** 文書 1 つを読む（where は誤りの文に出す名前）。 */
    fun parse(raw: Any?, where: String): TermChangeDoc {
        val m = raw as? Map<*, *> ?: error("$where: ルートは object で書きます")
        m.keys.map { it.toString() }.firstOrNull { it !in TOP_KEYS }?.let { error("$where: 未知のキーです: $it（使えるキー: ${TOP_KEYS.joinToString(", ")}）") }
        val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where: id が必要です")
        val stage = m["stage"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where: stage（当てる段の id）が必要です")
        val kindText = m["kind"]?.toString() ?: error("$where: kind（rule＝規則・patch＝補正）が必要です")
        val kind = TermChangeDoc.Kind.entries.firstOrNull { it.id == kindText } ?: error("$where: kind は rule（規則）か patch（補正）で書きます: $kindText")
        val rules = m["rules"]
        val list = m["changes"] as? List<*> ?: error("$where: changes（変更の並び）が必要です")
        require(list.isNotEmpty()) { "$where: changes に変更を 1 つ以上書きます" }
        val changes = list.mapIndexed { i, c -> parseChange(c, "changes[$i]", "$where.changes[$i]", id, rules, emptySet()) }
        return TermChangeDoc(id, m["name"]?.toString() ?: id, m["description"]?.toString(), stage, kind, changes)
    }

    private fun parseChange(raw: Any?, at: String, where: String, docId: String, rules: Any?, binds: Set<String>): TermChange {
        val m = raw as? Map<*, *> ?: error("$where: 変更は object（each・where・set・add・after・changes）で書きます")
        m.keys.map { it.toString() }.firstOrNull { it !in CHANGE_KEYS }?.let { error("$where: 未知のキーです: $it（使えるキー: ${CHANGE_KEYS.joinToString(", ")}）") }
        val each = m["each"]?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { PathPattern.parse(text) }.getOrElse { error("$where.each: 並びの場所として読めません: $text（${it.message}）") }
        }
        val bind = m["bind"]?.toString()?.also {
            require(NAME.matches(it)) { "$where.bind: 名前は英字で始まる英数字で書きます: $it" }
            require(it != MODEL) { "$where.bind: $MODEL は当てる前の項の名前なので使えません" }
        }
        val places = setOf(MODEL) + binds
        fun template(body: Any?, name: String): TransformDef =
            TransformDef.parse(linkedMapOf("id" to docId, "build" to body, "rules" to rules).filterValues { it != null }, name, places)
        val guard = m["where"]?.let { w ->
            require(w is List<*>) { "$where.where は条件の並び（変換の \$when と同じ書き方）で書きます" }
            template(linkedMapOf("\$when" to w, "\$then" to true), "$where.where")
        }
        fun paths(key: String): List<Pair<YPath, TransformDef>> {
            val v = m[key] ?: return emptyList()
            val entries = v as? Map<*, *> ?: error("$where.$key は「場所: 値」の object で書きます")
            return entries.map { (k, body) ->
                val path = runCatching { parseYPath(k.toString()) }.getOrElse { error("$where.$key: 場所として読めません: $k（${it.message}）") }
                require(path.isNotEmpty()) { "$where.$key: 場所を書きます" }
                path to template(body, "$where.$key.$k")
            }
        }
        val after = m["after"]?.let { body ->
            require(each != null) { "$where.after は each で選んだ並びの要素にだけ書けます（後ろへ足す並びが要るため）" }
            template(body, "$where.after")
        }
        val inner = (m["changes"] as? List<*>)?.mapIndexed { i, c ->
            parseChange(c, "$at.changes[$i]", "$where.changes[$i]", docId, rules, binds + listOfNotNull(bind))
        } ?: (if (m["changes"] == null) emptyList() else error("$where.changes は変更の並びで書きます"))
        val set = paths("set")
        val add = paths("add")
        require(set.isNotEmpty() || add.isNotEmpty() || after != null || inner.isNotEmpty()) {
            "$where: set・add・after・changes のどれかを書きます"
        }
        return TermChange(at, each, guard, bind, set, add, after, inner)
    }

    /** 当てた結果: 新しい項と、当てられなかった変更の誤り。 */
    class Applied(val term: YNode, val errors: List<String>)

    /**
     * 文書 1 つを項に当てる。origin は足した要素と置き換えた場所に付ける出どころ（段・文書・種類。呼び出し側が決める）。
     * 渡した項は変えず、写しに当てる。
     */
    fun apply(term: YNode, doc: TermChangeDoc, origin: Map<String, Any?>): Applied {
        val before = copy(term)
        val working = copy(term)
        val errors = mutableListOf<String>()
        val matched = IdentityHashMap<TermChange, Int>()
        val model = SemanticTransform.TemplatePlace(before, null, emptyList())

        fun eval(def: TransformDef, place: SemanticTransform.TemplatePlace, places: Map<String, SemanticTransform.TemplatePlace>, what: String): YNode? =
            runCatching { SemanticTransform.runAt(def, place, places = places).term }
                .getOrElse { errors += "${doc.id}: $what: ${it.message}"; null }

        fun run(change: TermChange, here: YNode, herePath: YPath, binds: Map<String, SemanticTransform.TemplatePlace>) {
            // 選ぶ要素（並びと、その中の要素）。each が無ければ、いまの場所そのもの
            val targets: List<Triple<YNode.Seq?, YNode, YPath>> = if (change.each == null) listOf(Triple(null, here, herePath)) else {
                PathPattern.expand(here, change.each).flatMap { p ->
                    val seq = getAt(here, p) as? YNode.Seq ?: return@flatMap emptyList()
                    seq.items.toList().mapIndexed { i, item -> Triple(seq, item, herePath + p + i) }
                }
            }
            val places = mapOf(MODEL to model) + binds
            for ((seq, element, path) in targets) {
                val place = SemanticTransform.TemplatePlace(element, null, path)
                if (change.where != null && eval(change.where, place, places, "${change.at}.where") == null) continue
                matched.merge(change, 1, Int::plus)
                for ((p, def) in change.set) {
                    val value = eval(def, place, places, "${change.at}.set") ?: continue
                    runCatching { setAt(element, p, value, origin) }.onFailure { errors += "${doc.id}: ${change.at}.set: ${it.message}" }
                }
                for ((p, def) in change.add) {
                    val value = eval(def, place, places, "${change.at}.add") ?: continue
                    runCatching { addAt(element, p, itemsOf(value, origin)) }.onFailure { errors += "${doc.id}: ${change.at}.add: ${it.message}" }
                }
                change.after?.let { def ->
                    val value = eval(def, place, places, "${change.at}.after") ?: return@let
                    val list = seq ?: return@let
                    // 足すのは選んだ要素の直後（同じ並びの前の変更で足したものがあっても、要素そのものの後ろ）
                    val index = list.items.indexOfFirst { it === element }
                    if (index >= 0) list.items.addAll(index + 1, itemsOf(value, origin))
                }
                val inner = change.bind?.let { binds + (it to place) } ?: binds
                change.changes.forEach { run(it, element, path, inner) }
            }
        }
        doc.changes.forEach { run(it, working, emptyList(), emptyMap()) }

        // 補正は、当たる要素が無い変更を誤りにする（外側の変更が当たったのに、中の変更が当たらないものも）
        if (doc.kind == TermChangeDoc.Kind.PATCH) {
            fun check(change: TermChange) {
                if ((matched[change] ?: 0) == 0) {
                    val target = change.each?.let { "（each: ${PathPattern.pretty(it)}）" }.orEmpty()
                    errors += "${doc.id}: 補正の ${change.at}$target に当たる要素がありません。業務モデルの ID や名前が変わったかもしれません"
                    return
                }
                change.changes.forEach(::check)
            }
            doc.changes.forEach(::check)
        }
        return Applied(working, errors)
    }

    /** 足す値を要素の並びに（配列ならその要素、そうでなければ 1 つ）。object には出どころを付ける。 */
    private fun itemsOf(value: YNode, origin: Map<String, Any?>): List<YNode> =
        (if (value is YNode.Seq) value.items.toList() else listOf(value)).onEach { item ->
            if (item is YNode.Map && "origin" !in item.entries && origin.isNotEmpty()) item.entries["origin"] = YamlIO.fromAny(origin)
        }

    /** 要素の中の場所へ値を置く（途中の object は作る）。置いた場所を要素の origins に記録する。 */
    private fun setAt(element: YNode, path: YPath, value: YNode, origin: Map<String, Any?>) {
        val holder = element as? YNode.Map ?: error("置き換えられるのは object の要素だけです")
        val parent = descend(holder, path.dropLast(1))
        when (val last = path.last()) {
            is String -> (parent as? YNode.Map ?: error("${pretty(path)} の手前が object ではありません")).entries[last] = value
            is Int -> {
                val seq = parent as? YNode.Seq ?: error("${pretty(path)} の手前が並びではありません")
                require(last in seq.items.indices) { "${pretty(path)} の番号が並びの外です" }
                seq.items[last] = value
            }
        }
        if (origin.isNotEmpty()) {
            val origins = holder.entries.getOrPut("origins") { YNode.Seq() } as? YNode.Seq ?: error("要素の origins が並びではありません")
            origins.items += YamlIO.fromAny(linkedMapOf<String, Any?>("path" to pretty(path)) + origin)
        }
    }

    /** 要素の中の並びの末尾へ足す（並びが無ければ作る）。 */
    private fun addAt(element: YNode, path: YPath, items: List<YNode>) {
        val holder = element as? YNode.Map ?: error("足せるのは object の要素の中だけです")
        val parent = descend(holder, path.dropLast(1))
        val last = path.last()
        val target = when (parent) {
            is YNode.Map -> parent.entries.getOrPut(last.toString()) { YNode.Seq() }
            is YNode.Seq -> parent.items.getOrNull(last as? Int ?: -1)
            else -> null
        }
        val seq = target as? YNode.Seq ?: error("${pretty(path)} は並びではありません")
        seq.items.addAll(items)
    }

    /** 途中の場所へ降りる（無い object のキーは作る）。 */
    private fun descend(root: YNode, path: YPath): YNode {
        var node = root
        for (seg in path) {
            node = when {
                node is YNode.Map && seg is String -> node.entries.getOrPut(seg) { YNode.Map() }
                node is YNode.Seq && seg is Int -> node.items.getOrNull(seg) ?: error("番号 $seg が並びの外です")
                else -> error("${seg} へ降りられません（object の中はキー、並びの中は番号で書きます）")
            }
        }
        return node
    }

    private fun pretty(path: YPath): String = PathPattern.pretty(path)

    /** 項の写し（値と形だけ）。 */
    private fun copy(node: YNode): YNode = when (node) {
        is YNode.Scalar -> YNode.Scalar(node.value)
        is YNode.Seq -> YNode.Seq(node.items.mapTo(mutableListOf()) { copy(it) })
        is YNode.Map -> YNode.Map(LinkedHashMap<String, YNode>().also { m -> node.entries.forEach { (k, v) -> m[k] = copy(v) } })
    }
}
