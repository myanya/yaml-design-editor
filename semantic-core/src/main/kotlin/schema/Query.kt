package schema

import model.YPath
import model.parseYPath

/**
 * クエリコアの宣言モデルとパース（R22-01。ARCHITECTURE §4.32）。
 * グラフパターン照合の語彙（node / edge の交互連鎖＋1段分岐＋プロパティ述語＋ノード一致 same）をここに一元化し、
 * ビューの `rows: match:`・foreach の `source: query`・スキーマの `constraints:`・CLI `query` が共用する。
 * 評価器は `index/Query.kt`（依存方向 §2 上、`Validator` から使うため index 層。§4.26 の Derivation と同じ2分割）。
 *
 * やらないこと（P22 の不変条件。§4.32）: 集約は件数のみ・任意深さの再帰パスなし・任意式なし・
 * optional 結合なし・分岐は also の1段まで。
 */

/** ノードの種別。エンティティ（論理ID）と文書（容れ物の仮想ノード）は別種（ROADMAP §4.9 原則3）。 */
enum class QueryNodeKind(val id: String) {
    ENTITY("entity"),
    DOCUMENT("document");

    companion object {
        fun parse(raw: String): QueryNodeKind = entries.firstOrNull { it.id == raw }
            ?: error("kind は entity / document のいずれかです: $raw")
    }
}

/** エッジを辿る向き。out = 参照を辿る、in = 被参照を辿る。 */
enum class QueryDir(val id: String) {
    OUT("out"),
    IN("in");

    companion object {
        fun parse(raw: String): QueryDir = entries.firstOrNull { it.id == raw }
            ?: error("dir は out / in のいずれかです: $raw")
    }
}

/**
 * ノードの絞り込み条件（エンティティ型のどれか・出現スキーマ・IDパターン）。node ステップの否定（not）が持つ。
 * 複数書くと「すべてに合う」で、not はその全体の否定になる。
 */
data class QueryNodeFilter(
    val entities: List<String> = emptyList(),
    val schema: String? = null,
    val idPattern: String? = null,
) {
    val isEmpty: Boolean get() = entities.isEmpty() && schema == null && idPattern == null
}

/**
 * node ステップ: フロンティアのノード集合を絞る（kind・エンティティ型・出現スキーマ・IDパターン）。
 * entity には型を1つ、または型の並び（どれかに合えばよい＝「または」）を書ける。not には同じ絞り込みを書き、
 * それに合わないノードだけを残す（SP-02。型が宣言されていないエンティティは、型の否定では判定保留＝残さない）。
 * bind を書いたノードは結果の束縛列になる（束縛されない中間ノードは行の同一性に関与しない）。
 * same を書いたノードは「先行して束縛済みのノードそのもの」に到達することを要求する
 * （ノード一致。2経路が同一ノードへ到達する照合＝型不一致検査等の土台。R28-07）。
 * same は他のキーと同居できない（到達先の同一性がフィルタのすべてのため）。
 */
data class QueryNodeStep(
    val kind: QueryNodeKind = QueryNodeKind.ENTITY,
    val entities: List<String> = emptyList(), // エンティティ型名（kind: entity のみ。複数 = どれか）
    val schema: String? = null,     // 出現スキーマID（kind: entity のみ）
    val idPattern: String? = null,  // ID（kind: document では documentId）の glob
    val bind: String? = null,       // 束縛名（結果の列になる）
    val same: String? = null,       // 先行する束縛名（そのノード自身への到達を要求。R28-07）
    val not: QueryNodeFilter? = null, // この絞り込みに合わないノードだけを残す（SP-02）
    val typed: Boolean? = null,     // true = エンティティ型が宣言されている要素だけ／false = 宣言されていない要素だけ
) {
    /** 母集団を絞る条件が1つでも書かれているか（マトリクス・遷移検証の選択は1つ以上を要求する）。 */
    val hasFilter: Boolean get() = entities.isNotEmpty() || schema != null || idPattern != null || not != null || typed != null

    companion object {
        private val KEYS = setOf("kind", "entity", "schema", "id", "bind", "same", "not", "typed")
        internal val BIND_RE = Regex("""[a-zA-Z][a-zA-Z0-9_-]*""")

        /** entity の値: 型名1つ、または型名の並び（1つ以上）。 */
        private fun parseEntities(raw: Any?, where: String): List<String> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it?.toString()?.takeIf { s -> s.isNotBlank() } ?: error("$where.entity に空の型名は書けません") }
                .also { require(it.isNotEmpty()) { "$where.entity の並びには1つ以上の型名が必要です" } }
            else -> listOfNotNull(raw.toString().takeIf { it.isNotBlank() })
        }

        fun parse(raw: Any?, where: String): QueryNodeStep {
            val m = raw as? Map<*, *> ?: error("$where.node はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where.node: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val same = m["same"]?.toString()?.takeIf { it.isNotBlank() }
            if (same != null) {
                if (m.keys.size > 1) {
                    error("$where.node: same には他のキーを同居できません（到達先は束縛済みノードそのものになります）")
                }
                return QueryNodeStep(same = same)
            }
            val kind = m["kind"]?.toString()?.let { QueryNodeKind.parse(it) } ?: QueryNodeKind.ENTITY
            val entities = parseEntities(m["entity"], "$where.node")
            val schema = m["schema"]?.toString()?.takeIf { it.isNotBlank() }
            val not = m["not"]?.let { raw ->
                val n = raw as? Map<*, *> ?: error("$where.node.not はobject（{entity, schema, id}）である必要があります")
                n.keys.map { it.toString() }.firstOrNull { it !in setOf("entity", "schema", "id") }
                    ?.let { error("$where.node.not: 未知のキーです: $it（使えるキー: entity, schema, id）") }
                QueryNodeFilter(
                    entities = parseEntities(n["entity"], "$where.node.not"),
                    schema = n["schema"]?.toString()?.takeIf { it.isNotBlank() },
                    idPattern = n["id"]?.toString()?.takeIf { it.isNotBlank() },
                ).also { require(!it.isEmpty) { "$where.node.not には entity / schema / id のいずれかが必要です" } }
            }
            if (kind == QueryNodeKind.DOCUMENT &&
                (entities.isNotEmpty() || schema != null || not?.entities?.isNotEmpty() == true || not?.schema != null || m["typed"] != null)
            ) {
                error("$where.node: kind: document に entity / schema は指定できません（文書ノードは id（documentId のパターン）で絞ります）")
            }
            val bind = m["bind"]?.toString()?.takeIf { it.isNotBlank() }
            if (bind != null && !BIND_RE.matches(bind)) {
                error("$where.node.bind は英字で始まる英数字・ハイフン・アンダースコアである必要があります: $bind")
            }
            return QueryNodeStep(
                kind = kind,
                entities = entities,
                schema = schema,
                idPattern = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                bind = bind,
                not = not,
                typed = m["typed"]?.let { it as? Boolean ?: error("$where.node.typed は true / false で書きます") },
            )
        }
    }
}

/**
 * edge ステップ: フロンティアから参照エッジを1段辿る。
 * inDoc は「その参照が書かれた文書」の指定（SP-01。同じ ID が複数の文書に書かれているとき、
 * どの文書に書かれた参照を辿るかを絞る。値を読む文書の指定と同じ書き方）。
 * bind を書くと、辿った**参照そのもの**（どの文書のどこに書かれた参照か）を束縛する。束縛した参照は解の同一性に
 * 加わり（同じ相手への参照が2本あれば2つの解）、指摘を参照の場所へ出す reportAt: {ref: 束縛名} で使える。
 */
data class QueryEdgeStep(
    val dir: QueryDir = QueryDir.OUT,
    val relation: String? = null,
    val via: String? = null,        // 参照が書かれたキー名（viaKey）
    val inDoc: DerivationSelector? = null, // 参照が書かれた文書の指定（SP-01）
    val bind: String? = null,       // 参照の束縛名
) {
    companion object {
        private val KEYS = setOf("dir", "relation", "via", "in", "bind")

        fun parse(raw: Any?, where: String): QueryEdgeStep {
            val m = raw as? Map<*, *> ?: error("$where.edge はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where.edge: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            return QueryEdgeStep(
                dir = m["dir"]?.toString()?.let { QueryDir.parse(it) } ?: QueryDir.OUT,
                relation = m["relation"]?.toString()?.takeIf { it.isNotBlank() },
                via = m["via"]?.toString()?.takeIf { it.isNotBlank() },
                inDoc = ValueExpr.parseSelector(m["in"], "$where.edge.in"),
                bind = m["bind"]?.toString()?.takeIf { it.isNotBlank() }?.also {
                    if (!QueryNodeStep.BIND_RE.matches(it)) {
                        error("$where.edge.bind は英字で始まる英数字・ハイフン・アンダースコアである必要があります: $it")
                    }
                },
            )
        }
    }
}

/** 連鎖の1ホップ（edge で移動して node で絞る）。 */
data class QueryHop(val edge: QueryEdgeStep, val node: QueryNodeStep)

/** 束縛済みノードからの分岐（1段のみ・内部結合。§4.32）。 */
data class QueryBranch(val from: String, val hops: List<QueryHop>)

/**
 * プロパティ述語（ノードを値で絞る条件）。条件を一つだけ持つ。
 * 値は「いずれかの出現の該当パスのスカラー」（多段定義の OR）——どれかの出現の値が条件を満たせば成立。
 * - equals / notEquals: notEquals は「値が1つ以上あり、どの出現の値も指定値でない」（equals の OR 解釈の否定。
 *   値がなければ黙る＝判定保留の哲学 §4.26。R28-02）
 * - exists: true = 項目が書かれている／false = どの出現にも書かれていない
 * - empty: false = 中身のある値が書かれている／true = どの出現にも中身のある値がない
 *   （書かれていない・空文字・null・空の配列・空の object を「空」とみなす）
 * - 値の条件（check と同じ演算子・同じ判定。test）: oneOf（選択肢のどれか）・matches（正規表現の全体一致）・
 *   startsWith / endsWith / contains・lt / lte / gt / gte（数値として読めない値は成立しない）
 */
data class QueryWhere(
    val of: String,
    val path: YPath,
    val equals: String? = null,
    val exists: Boolean? = null,
    val notEquals: String? = null,
    val empty: Boolean? = null,
    /** 値の条件（演算子と、比べる決まった値。oneOf は複数・他は1つ）。 */
    val test: Pair<CheckOp, List<String>>? = null,
    /** 条件の否定（SP-02）。条件が「成り立たない」ときに成立する（値が無く判定できないときは成立しない）。 */
    val negated: Boolean = false,
) {
    companion object {
        /** where に書ける値の条件（eq / ne は equals / notEquals が担う）。 */
        private val TEST_OPS = CheckOp.entries.filter { it != CheckOp.EQ && it != CheckOp.NE }
        private val CONDITION_KEYS = listOf("equals", "notEquals", "exists", "empty") + TEST_OPS.map { it.id }
        private val KEYS = setOf("of", "path") + CONDITION_KEYS

        fun parse(raw: Any?, where: String): QueryWhere {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.of（束縛名）が必要です")
            val path = parseYPath(m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です"))
            val given = CONDITION_KEYS.filter { m[it] != null }
            if (given.size != 1) error("$where: 条件（${CONDITION_KEYS.joinToString(" / ")}）のいずれか一つが必要です")
            fun flag(key: String): Boolean? = m[key]?.let { it as? Boolean ?: error("$where.$key は true / false で書きます") }
            val test = TEST_OPS.firstOrNull { m[it.id] != null }?.let { op ->
                val v = m[op.id]
                val operands = when {
                    op == CheckOp.ONE_OF -> (v as? List<*>)?.takeIf { it.isNotEmpty() }?.map { it.toString() }
                        ?: error("$where.${op.id} は値の配列（1つ以上）である必要があります")
                    v is List<*> || v is Map<*, *> -> error("$where.${op.id} は決まった値（文字列・数値）で書きます")
                    else -> listOf(v.toString())
                }
                op.requireValidOperand(operands.first(), "$where.${op.id}")
                op to operands
            }
            return QueryWhere(
                of = of, path = path,
                equals = m["equals"]?.toString(), exists = flag("exists"), notEquals = m["notEquals"]?.toString(),
                empty = flag("empty"), test = test,
            )
        }
    }
}

/**
 * where の1件（SP-02）: 条件1つ、その否定（`{not: 条件}`）、または条件の「または」（`{or: [条件, ...]}`）。
 * 入れ子は1段まで——or の中には条件かその否定を書ける（or の中の or・not の中の or や not は書けない）。
 * or の条件はすべて同じ束縛（of）について書く（束縛が決まった時点で判定して枝刈りするため）。
 * 「かつ」は where に並べて書く。
 */
data class QueryWhereClause(val alternatives: List<QueryWhere>) {
    val of: String get() = alternatives.first().of

    companion object {
        fun parse(raw: Any?, where: String): QueryWhereClause {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            if (m.containsKey("or")) {
                if (m.size != 1) error("$where: or は単独で書きます（{or: [条件, ...]}）")
                val list = (m["or"] as? List<*>)?.takeIf { it.size >= 2 } ?: error("$where.or は条件の配列（2つ以上）である必要があります")
                val alternatives = list.mapIndexed { i, e -> parseLeaf(e, "$where.or[$i]") }
                require(alternatives.map { it.of }.distinct().size == 1) { "$where.or の条件は、すべて同じ束縛（of）について書きます" }
                return QueryWhereClause(alternatives)
            }
            return QueryWhereClause(listOf(parseLeaf(raw, where)))
        }

        /** 条件1つ、またはその否定。 */
        private fun parseLeaf(raw: Any?, where: String): QueryWhere {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            if (m.containsKey("or")) error("$where: or の入れ子は書けません（入れ子は1段まで）")
            if (!m.containsKey("not")) return QueryWhere.parse(raw, where)
            if (m.size != 1) error("$where: not は単独で書きます（{not: 条件}）")
            val inner = m["not"] as? Map<*, *> ?: error("$where.not は条件のobjectである必要があります")
            if (inner.containsKey("not") || inner.containsKey("or")) error("$where.not の中に not / or は書けません（入れ子は1段まで）")
            return QueryWhere.parse(inner, "$where.not").copy(negated = true)
        }
    }
}

/**
 * パターン照合のスペック1件。match（node で始まり node で終わる交互連鎖）＋ also（分岐）＋ where（述語）＋ limit。
 * パースは raw Map（YAML/JSON 由来）からの1実装で、日本語エラーをそのまま定義エラーとして表示する。
 */
data class QuerySpec(
    val start: QueryNodeStep,
    val hops: List<QueryHop>,
    val also: QueryBranch? = null,
    val where: List<QueryWhereClause> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) {
    /** ノードの束縛名の一覧（宣言順）。結果の列順・rowKey / of / from の解決に使う。 */
    val binds: List<String> =
        (listOf(start) + hops.map { it.node } + also?.hops?.map { it.node }.orEmpty()).mapNotNull { it.bind }

    /** 参照（edge）の束縛名の一覧（宣言順）。reportAt: {ref} の解決に使う。 */
    val edgeBinds: List<String> = (hops + also?.hops.orEmpty()).mapNotNull { it.edge.bind }

    /** 束縛名に対応する node ステップ（ビューの rowKey の kind 検査等に使う）。 */
    fun bindStep(name: String): QueryNodeStep? =
        (listOf(start) + hops.map { it.node } + also?.hops?.map { it.node }.orEmpty()).firstOrNull { it.bind == name }

    companion object {
        const val DEFAULT_LIMIT = 100
        private val KEYS = setOf("match", "also", "where", "limit")

        /**
         * スペックのパース。ignoreKeys は同じ object に同居する消費側の追加キー
         * （ビューの rowKey・制約の expect 等）で、ここでは未知キー扱いにしない。
         */
        fun parse(raw: Map<*, *>, where: String, ignoreKeys: Set<String> = emptySet()): QuerySpec {
            raw.keys.map { it.toString() }.firstOrNull { it !in KEYS && it !in ignoreKeys }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${(KEYS + ignoreKeys).joinToString(", ")}）") }
            val matchRaw = raw["match"] as? List<*> ?: error("$where.match（node / edge の連鎖）が必要です")
            val (startStep, hops) = parseChain(matchRaw, "$where.match", startsWithNode = true)
            val start = startStep ?: error("$where.match は node で始まる必要があります")
            val also = (raw["also"] as? Map<*, *>)?.let { m ->
                m.keys.map { it.toString() }.firstOrNull { it !in setOf("from", "steps") }
                    ?.let { error("$where.also: 未知のキーです: $it（使えるキー: from, steps）") }
                val from = m["from"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.also.from（束縛名）が必要です")
                val stepsRaw = m["steps"] as? List<*> ?: error("$where.also.steps が必要です")
                val (branchStart, branchHops) = parseChain(stepsRaw, "$where.also.steps", startsWithNode = false)
                require(branchStart == null) { "$where.also.steps は edge で始まる必要があります（起点は from の束縛ノード）" }
                require(branchHops.isNotEmpty()) { "$where.also.steps には1ホップ以上が必要です" }
                QueryBranch(from = from, hops = branchHops)
            } ?: run {
                if (raw.containsKey("also")) error("$where.also はobjectである必要があります")
                null
            }
            val whereList = (raw["where"] as? List<*>)?.mapIndexed { i, e -> QueryWhereClause.parse(e, "$where.where[$i]") }
                ?: run {
                    if (raw.containsKey("where")) error("$where.where は配列である必要があります")
                    emptyList()
                }
            val limit = (raw["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT
            require(limit >= 1) { "$where.limit は1以上である必要があります" }

            val spec = QuerySpec(start = start, hops = hops, also = also, where = whereList, limit = limit)
            // 束縛名の重複と、also.from / where.of の解決をパース段階で固定する（実行時に黙って空にならないため）
            (spec.binds + spec.edgeBinds).groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()
                ?.let { error("$where: 束縛名「$it」が重複しています") }
            also?.let { branch ->
                val matchBinds = (listOf(start) + hops.map { it.node }).mapNotNull { it.bind }
                require(branch.from in matchBinds) { "$where.also.from の束縛「${branch.from}」が match に見つかりません" }
            }
            whereList.forEach { pred ->
                require(pred.of in spec.binds) { "$where.where の束縛「${pred.of}」が見つかりません" }
            }
            // same の解決: 宣言順で「先行する」束縛のみ参照できる（起点には書けない。R28-07）
            run {
                require(start.same == null) { "$where.match: 先頭 node に same は書けません（先行する束縛がありません）" }
                val seen = mutableSetOf<String>()
                start.bind?.let { seen += it }
                (hops.map { it.node to "$where.match" } + also?.hops?.map { it.node to "$where.also.steps" }.orEmpty())
                    .forEach { (node, at) ->
                        node.same?.let {
                            require(it in seen) { "$at: same の束縛「$it」がこのステップより前に見つかりません" }
                        }
                        node.bind?.let { seen += it }
                    }
            }
            return spec
        }

        /**
         * node / edge の交互連鎖をパースする。startsWithNode=true は match（node 始まり）、
         * false は also.steps（edge 始まり）。いずれも node で終わることを強制する。
         * 戻り値は（先頭 node（edge 始まりでは null）, 以降のホップ列）。
         */
        private fun parseChain(list: List<*>, where: String, startsWithNode: Boolean): Pair<QueryNodeStep?, List<QueryHop>> {
            require(list.isNotEmpty()) { "$where には1ステップ以上が必要です" }
            var start: QueryNodeStep? = null
            val hops = mutableListOf<QueryHop>()
            var pendingEdge: QueryEdgeStep? = null
            var expectNode = startsWithNode
            list.forEachIndexed { i, e ->
                val m = e as? Map<*, *> ?: error("$where[$i] はobjectである必要があります")
                val nodeRaw = m["node"]
                val edgeRaw = m["edge"]
                if ((nodeRaw != null) == (edgeRaw != null)) error("$where[$i] は node か edge のどちらか一方を持つ必要があります")
                m.keys.map { it.toString() }.firstOrNull { it !in setOf("node", "edge") }
                    ?.let { error("$where[$i]: 未知のキーです: $it（使えるキー: node, edge）") }
                if (nodeRaw != null) {
                    if (!expectNode) error("$where[$i]: node が連続しています（node と edge は交互に書きます）")
                    val node = QueryNodeStep.parse(nodeRaw, "$where[$i]")
                    val edge = pendingEdge
                    if (edge == null) start = node else hops += QueryHop(edge, node)
                    pendingEdge = null
                    expectNode = false
                } else {
                    if (expectNode) error("$where[$i]: edge が連続しています（node と edge は交互に書きます）")
                    pendingEdge = QueryEdgeStep.parse(edgeRaw, "$where[$i]")
                    expectNode = true
                }
            }
            if (pendingEdge != null) error("$where は node で終わる必要があります（最後の edge の到達先を node で書きます）")
            return start to hops
        }
    }
}

/** パターン制約の期待。exists = 解が1つ以上あること、absent = 解がないこと。 */
enum class ConstraintExpect(val id: String) {
    EXISTS("exists"),
    ABSENT("absent");

    companion object {
        fun parse(raw: String): ConstraintExpect = entries.firstOrNull { it.id == raw }
            ?: error("expect は exists / absent のいずれかです: $raw")
    }
}

/**
 * count 制約の範囲（min / max の少なくとも一方。R28-02）。
 * 解の件数 = 束縛の組（bind 列）の distinct 件数（クエリ結果の行と同じ同一性）。
 * 数えたい対象のノードに bind を書くこと（束縛のないパターンでは件数は最大1になる）。
 */
data class ConstraintCount(val min: Int? = null, val max: Int? = null) {
    /**
     * 件数判定。unknownDropped（解探索での候補喪失。CALC-17）が真のとき、下限未達は
     * 判定保留＝違反にしない（失われた候補が不足分だったかもしれないため）。
     * 上限超過は見つかった解が実在する証拠のため降格しない。
     */
    fun violated(n: Int, unknownDropped: Boolean = false): Boolean =
        (min != null && n < min && !unknownDropped) || (max != null && n > max)
    fun describe(): String = listOfNotNull(min?.let { "min $it" }, max?.let { "max $it" }).joinToString("・")

    companion object {
        fun parse(raw: Any?, where: String): ConstraintCount {
            val m = raw as? Map<*, *> ?: error("$where.count はobjectである必要があります（min / max）")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("min", "max") }
                ?.let { error("$where.count: 未知のキーです: $it（使えるキー: min, max）") }
            val min = (m["min"] as? Number)?.toInt()
            val max = (m["max"] as? Number)?.toInt()
            if (min == null && max == null) error("$where.count には min / max の少なくとも一方が必要です")
            if (min != null && min < 0) error("$where.count.min は0以上である必要があります")
            if (max != null && max < 0) error("$where.count.max は0以上である必要があります")
            if (min != null && max != null && min > max) error("$where.count: min が max を超えています")
            return ConstraintCount(min = min, max = max)
        }
    }
}

/**
 * 値の条件の演算子（R28-08・P29 Phase 6 の部品 SP-10）。check（判定）と where（ノードの絞り込み）が共有する。
 * - eq / ne: スカラー文字列の等値（数値の表記は揃えない。揃えたいときは値の計算 number を通す）
 * - lte / gte / lt / gt: 数値比較（数値として読めなければ評価できない＝判定保留）
 * - oneOf: 選択肢のどれかと等しい（右辺は値の並び）
 * - matches: 正規表現の全体一致（フィールドの pattern と同じ規則）
 * - startsWith / endsWith / contains: 前方・後方・部分の一致
 * violationSymbol は、条件を満たさなかった組を文面に示すときに左右の間へ置く言葉。
 */
enum class CheckOp(val id: String, val violationSymbol: String, val satisfiedSymbol: String) {
    LTE("lte", ">", "≤"),
    GTE("gte", "<", "≥"),
    LT("lt", "≥", "<"),
    GT("gt", "≤", ">"),
    EQ("eq", "≠", "="),
    NE("ne", "=", "≠"),
    ONE_OF("oneOf", "が次のどれでもない:", "が次のどれかである:"),
    MATCHES("matches", "が次の正規表現に一致しない:", "が次の正規表現に一致する:"),
    STARTS_WITH("startsWith", "が次で始まらない:", "が次で始まる:"),
    ENDS_WITH("endsWith", "が次で終わらない:", "が次で終わる:"),
    CONTAINS("contains", "が次を含まない:", "が次を含む:");

    /** 決まった値として書かれた右辺の、定義時の点検（正規表現として読めること）。 */
    fun requireValidOperand(literal: String, where: String) {
        if (this == MATCHES) {
            runCatching { Regex(literal) }.onFailure { error("$where は正規表現として読めません: $literal") }
        }
    }

    companion object {
        fun parse(raw: String, where: String): CheckOp = entries.firstOrNull { it.id == raw }
            ?: error("$where: 演算子は ${entries.joinToString(" / ") { it.id }} のいずれかです: $raw")
    }
}

/**
 * check 判定述語の値の条件1つ（R28-08。CORE-CALCULUS §8 の判定述語）: 2つの値の比較。
 * 書き方は2通り——`{演算子: [左, 右]}`（1キーの短い形）と、
 * `{op: 演算子, left: 左, right: 右}`（演算子を値として渡せる形。定義体のひな型で使う）。
 * 左右は値の式（schema.ValueExpr。束縛からの読み出し・テンプレートの展開・計算・決まった値）。
 * oneOf の右は値の式の並び（`{oneOf: [左, [選択肢, ...]]}`）で、rights に全ての選択肢が入る（他の演算子は1つ）。
 * 値の条件1つが CheckTerm、check の1項目（条件1つ、またはその「または」）が ConstraintCheck。
 * 評価は、同じ ID が書かれた文書のうちどれから値を読むかの全ての組を調べ、条件を満たさない組を1つでも見つけたら
 * 違反（その組を指摘に示す）・評価できる組がなければ黙る（判定保留 §4.26）。項目の中で同じ「束縛と文書の指定」は
 * 同じ文書から読む。
 */
data class CheckTerm(
    val op: CheckOp,
    val left: ValueExpr,
    val rights: List<ValueExpr>,
    /** 条件の否定（SP-02）。`{not: {演算子: [左, 右]}}`。評価できない組は、否定しても評価できないまま（判定保留）。 */
    val negated: Boolean = false,
) {
    /** 右辺（oneOf では最初の選択肢）。 */
    val right: ValueExpr get() = rights.first()

    /** この条件の値の式すべて（左・右の順）。 */
    val operands: List<ValueExpr> get() = listOf(left) + rights

    /** 条件を満たさなかった組を文面に示すときに、左右の間へ置く言葉（否定した条件は、成り立ってしまった関係を示す）。 */
    val violationSymbol: String get() = if (negated) op.satisfiedSymbol else op.violationSymbol
}

/**
 * check の1項目: 値の条件1つ、またはその「または」（`{or: [条件, ...]}`。SP-02）。入れ子は1段まで——
 * or の中には条件かその否定（`{not: 条件}`）を書ける。「かつ」は check に並べて書く。
 * or は、どれかが成り立てば成立・成り立つものがなく評価できない条件が残れば判定保留・すべて成り立たなければ違反。
 */
data class ConstraintCheck(val terms: List<CheckTerm>) {

    constructor(op: CheckOp, left: ValueExpr, right: ValueExpr) : this(listOf(CheckTerm(op, left, listOf(right))))

    /** この項目の値の式すべて。 */
    val operands: List<ValueExpr> get() = terms.flatMap { it.operands }

    /** この項目が読む束縛の名前（パース時の束縛の解決検査に使う）。 */
    val binds: List<String> get() = operands.flatMap { it.binds() }.distinct()

    companion object {
        fun parse(raw: Any?, where: String): ConstraintCheck {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{演算子: [左, 右]}）")
            if (m.containsKey("or")) {
                if (m.size != 1) error("$where: or は単独で書きます（{or: [条件, ...]}）")
                val list = (m["or"] as? List<*>)?.takeIf { it.size >= 2 } ?: error("$where.or は条件の配列（2つ以上）である必要があります")
                return ConstraintCheck(list.mapIndexed { i, e -> parseTerm(e, "$where.or[$i]", nested = true) })
            }
            return ConstraintCheck(listOf(parseTerm(raw, where, nested = false)))
        }

        /** 値の条件1つ、またはその否定。nested は or の中（or の入れ子は書けない）。 */
        private fun parseTerm(raw: Any?, where: String, nested: Boolean): CheckTerm {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{演算子: [左, 右]}）")
            if (m.containsKey("or")) error("$where: or の入れ子は書けません（入れ子は1段まで）")
            if (m.containsKey("not")) {
                if (m.size != 1) error("$where: not は単独で書きます（{not: {演算子: [左, 右]}}）")
                val inner = m["not"] as? Map<*, *> ?: error("$where.not は条件のobjectである必要があります")
                if (inner.containsKey("not") || inner.containsKey("or")) error("$where.not の中に not / or は書けません（入れ子は1段まで）")
                return parseCompare(inner, "$where.not").copy(negated = true)
            }
            return parseCompare(m, where)
        }

        private fun parseCompare(m: Map<*, *>, where: String): CheckTerm {
            if (m.containsKey("op")) {
                m.keys.map { it.toString() }.firstOrNull { it !in setOf("op", "left", "right") }
                    ?.let { error("$where: 未知のキーです: $it（使えるキー: op, left, right）") }
                val op = CheckOp.parse(m["op"].toString(), where)
                return CheckTerm(
                    op = op,
                    left = ValueExpr.parse(m["left"] ?: error("$where.left が必要です"), "$where.left"),
                    rights = parseRights(op, m["right"] ?: error("$where.right が必要です"), "$where.right"),
                )
            }
            if (m.size != 1) {
                error("$where は {演算子: [左, 右]} の1キーで書きます（演算子: ${CheckOp.entries.joinToString(" / ") { it.id }}）")
            }
            val (k, v) = m.entries.first()
            val op = CheckOp.parse(k.toString(), where)
            val list = v as? List<*> ?: error("$where.${op.id} は [左, 右] の2要素配列である必要があります")
            if (list.size != 2) error("$where.${op.id} は [左, 右] の2要素配列である必要があります")
            return CheckTerm(
                op = op,
                left = ValueExpr.parse(list[0], "$where.${op.id}[0]"),
                rights = parseRights(op, list[1], "$where.${op.id}[1]"),
            )
        }

        /** 右辺のパース。oneOf は値の式の並び（1つ以上）・他は値の式1つ。決まった値は定義時に点検する（正規表現など）。 */
        private fun parseRights(op: CheckOp, raw: Any?, where: String): List<ValueExpr> {
            val rights = if (op == CheckOp.ONE_OF) {
                val list = (raw as? List<*>)?.takeIf { it.isNotEmpty() }
                    ?: error("$where は選択肢の配列（1つ以上）である必要があります")
                list.mapIndexed { i, e -> ValueExpr.parse(e, "$where[$i]") }
            } else {
                if (raw is List<*>) error("$where は値の式1つで書きます（配列を書けるのは oneOf の選択肢だけです）")
                listOf(ValueExpr.parse(raw, where))
            }
            rights.forEach { r -> (r as? ValueExpr.Literal)?.value?.let { op.requireValidOperand(it, where) } }
            return rights
        }
    }
}

/**
 * 指摘を出す場所の宣言（SP-08）。書かなければ、起点を定義した場所に出る。
 * - 値が書かれた場所 `{of: 束縛名, in: 文書の指定, path: 相対パス}`: 束縛の、指定に合う文書に書かれた要素の中のパス（check と組み合わせる）
 * - 参照が書かれた場所 `{ref: 参照の束縛名}`: edge の bind で束縛した参照の場所（expect: absent か check と組み合わせる。
 *   見つかった解ごとに、その参照が書かれた文書の検証で報告する）
 */
data class ReportAt(
    val of: String = "",
    val inDoc: DerivationSelector? = null,
    val path: YPath = emptyList(),
    val ref: String? = null,
) {
    val source: ValueExpr.ScopeRef get() = ValueExpr.ScopeRef(of, inDoc)

    companion object {
        fun parse(raw: Any?, where: String): ReportAt {
            val m = raw as? Map<*, *>
            if (m != null && m.containsKey("ref")) {
                if (m.size != 1) error("$where: ref は単独で書きます（{ref: 参照の束縛名}）")
                return ReportAt(ref = m["ref"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.ref（参照の束縛名）が必要です"))
            }
            val get = ValueExpr.parse(raw, where) as? ValueExpr.Get
                ?: error("$where は {of: 束縛名, in: 文書の指定, path: 相対パス} か {ref: 参照の束縛名} で書きます")
            return ReportAt(get.of, get.inDoc, get.path)
        }
    }
}

/**
 * エラー時の対応の宣言（SP-09）。対応の種類は閉じた一覧で、定義の側はどれを・どのラベルで示すかを選ぶ。
 * - jump: 値の出どころへ移動する。対象は `left.source` / `right.source`（テンプレートなど元の値が書かれた場所）と
 *   `left.inputs` / `right.inputs`（テンプレートへ差し込んだ値が書かれた場所。入力ごとに1件）
 *   と `referenceSources`（起点の要素への参照を書き足せそうな文書。`relation: 関係名` を添える。候補ごとに1件。
 *   ラベルに `{file}`＝候補の文書のパス）
 * - apply: 値（`left` / `right`）を、指摘の場所へ書き込む（reportAt を宣言した検査でだけ使える）
 * - create: 文書を新しく作る。`detailDocument`＝起点の ID の詳細文書を、`schemas` のスキーマの置き場所に作って開く
 *   （スキーマごとに1件。ラベルに `{schema}`＝作るスキーマの ID）
 * ラベルのひな型には `{left}` `{right}` `{left.raw}` `{right.raw}`、inputs では `{input.placeholder}` `{input.value}` を書ける。
 * note はメニューでラベルの下に添える補足の1行（省略可）。
 */
data class ResponseDecl(
    val kind: Kind,
    val target: String,
    val label: String,
    /** jump: referenceSources のときの関係名。 */
    val relation: String? = null,
    val note: String? = null,
    /** create: detailDocument のときの、作る文書のスキーマ ID。 */
    val schemas: List<String> = emptyList(),
) {
    enum class Kind { JUMP, APPLY, CREATE }

    /** 値の比較（check）で条件を満たさなかった組を使う対応か（使わない対応は、どの判定の検査にも書ける）。 */
    val needsCheck: Boolean get() = kind == Kind.APPLY || (kind == Kind.JUMP && target != REFERENCE_SOURCES)

    companion object {
        const val REFERENCE_SOURCES = "referenceSources"
        const val DETAIL_DOCUMENT = "detailDocument"
        private val JUMP_TARGETS = setOf("left.source", "right.source", "left.inputs", "right.inputs", REFERENCE_SOURCES)
        private val APPLY_TARGETS = setOf("left", "right")

        fun parse(raw: Any?, where: String): ResponseDecl {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{jump または apply, label}）")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("jump", "apply", "create", "label", "relation", "note", "schemas") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: jump, apply, create, label, relation, note, schemas）") }
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.label が必要です")
            val note = m["note"]?.toString()?.takeIf { it.isNotBlank() }
            val relation = m["relation"]?.toString()?.takeIf { it.isNotBlank() }
            val jump = m["jump"]?.toString()
            val apply = m["apply"]?.toString()
            val create = m["create"]?.toString()
            if (listOfNotNull(jump, apply, create).size != 1) error("$where には jump / apply / create のどれか1つが必要です")
            if (create != null) {
                require(create == DETAIL_DOCUMENT) { "$where.create は $DETAIL_DOCUMENT です: $create" }
                require(relation == null) { "$where: relation は jump: $REFERENCE_SOURCES のときだけ書けます" }
                val schemas = when (val v = m["schemas"]) {
                    is List<*> -> v.map { it.toString() }
                    null -> emptyList()
                    else -> listOf(v.toString())
                }
                require(schemas.isNotEmpty()) { "$where.schemas（作る文書のスキーマ ID）が必要です" }
                return ResponseDecl(Kind.CREATE, create, label, note = note, schemas = schemas)
            }
            require(m["schemas"] == null) { "$where: schemas は create のときだけ書けます" }
            return if (jump != null) {
                require(jump in JUMP_TARGETS) { "$where.jump は ${JUMP_TARGETS.joinToString(" / ")} のいずれかです: $jump" }
                require((jump == REFERENCE_SOURCES) == (relation != null)) {
                    "$where: relation（関係名）は jump: $REFERENCE_SOURCES のときに、必ず書きます"
                }
                ResponseDecl(Kind.JUMP, jump, label, relation, note)
            } else {
                require(apply in APPLY_TARGETS) { "$where.apply は ${APPLY_TARGETS.joinToString(" / ")} のいずれかです: $apply" }
                require(relation == null) { "$where: relation は jump: $REFERENCE_SOURCES のときだけ書けます" }
                ResponseDecl(Kind.APPLY, apply!!, label, note = note)
            }
        }
    }
}

/**
 * 直せる場所への案内の宣言（SP-08）: この検査の指摘は「別の文書へ何かを書き足す」ことで直る——その書き足し先になる
 * スキーマの文書の検証に、指摘が出ている起点の ID の一覧を、案内（情報）として出す。
 * message は案内の文面のひな型（`{count}`＝ID の件数・`{ids}`＝ID の並び。先頭5件と残りの件数）。
 * その文書に既に書かれている ID は載せない（書き足したその場で案内から消える）。
 */
data class FixSite(val schemas: List<String>, val message: String) {
    companion object {
        fun parse(raw: Any?, where: String): FixSite {
            val m = raw as? Map<*, *> ?: error("$where はobject（{schema, message}）である必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("schema", "message") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: schema, message）") }
            val schemas = when (val v = m["schema"]) {
                is List<*> -> v.map { it.toString() }
                null -> emptyList()
                else -> listOf(v.toString())
            }
            require(schemas.isNotEmpty()) { "$where.schema（書き足し先になる文書のスキーマ ID）が必要です" }
            return FixSite(schemas, m["message"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.message が必要です"))
        }
    }
}

/**
 * パターン制約（R22-03。ARCHITECTURE §4.32）。スキーマの `constraints:` に列挙する。
 * 「起点束縛（match の先頭 node。kind: entity 限定）に一致する各エンティティについて、
 * 残りのパターンに解が存在すること（expect: exists）／しないこと（expect: absent）／
 * 解の件数が範囲内であること（count: {min, max}。R28-02）／解ごとに束縛間の値比較が
 * 成り立つこと（check。R28-08）」をカバレッジの一般形として検証する。
 * expect・count・check はいずれか一つを宣言する。
 */
data class ConstraintRule(
    val id: String? = null,
    val description: String? = null,
    val spec: QuerySpec,
    val expect: ConstraintExpect? = null,
    val count: ConstraintCount? = null,
    val check: List<ConstraintCheck> = emptyList(),
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,
    /**
     * 指摘の文面のひな型（SP-08。null = 判定の種類ごとの既定の文面）。どの判定にも書ける。
     * 書けるもの: `{id}`＝起点の ID・`{束縛名.id}` `{束縛名.type}` `{束縛名.title}`＝見つかった解のノード・
     * `{count}`＝解の件数（count）・`{left}` `{right}` `{left.raw}` `{right.raw}`＝比べた値（check）。
     */
    val message: String? = null,
    /** 指摘を出す場所（SP-08。null = 起点を定義した場所）。値が書かれた場所を選ぶと、その文書の検証で報告する。 */
    val reportAt: ReportAt? = null,
    /** エラー時の対応（SP-09）。 */
    val responses: List<ResponseDecl> = emptyList(),
    /**
     * 充足の状況の一覧に出す宣言（SP-08）。書いた検査は、要素ごとの「検査の状況」（満たしている・不足している・
     * フェーズ未到達）の一覧に出る。値は、状況を1行で示すひな型（message と同じものを埋め込める。例「出現 {left} 件（必要 {right} 件）」）。
     */
    val status: String? = null,
    /** 直せる場所への案内（SP-08）。 */
    val fixSite: FixSite? = null,
    /**
     * 重大度の切り替え: 関係レジストリの強制スイッチ（enforcement）のキー名。そのキーが宣言されていれば、その重大度で
     * 指摘する（宣言されていなければ level）。ワークスペースごとに「警告に留めるか、エラーで塞ぐか」を選べる検査に使う。
     */
    val levelFrom: String? = null,
) {
    companion object {
        private val OWN_KEYS = setOf(
            "id", "description", "expect", "count", "check", "level", "activeFrom", "message", "reportAt", "responses",
            "status", "fixSite", "levelFrom",
        )

        fun parse(raw: Any?, where: String): ConstraintRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            val spec = QuerySpec.parse(m, where, ignoreKeys = OWN_KEYS)
            require(spec.start.kind == QueryNodeKind.ENTITY) { "$where: 制約の起点（match の先頭 node）は kind: entity である必要があります" }
            val expectRaw = m["expect"]?.toString()
            val countRaw = m["count"]
            val checkRaw = m["check"]
            if (listOf(expectRaw != null, countRaw != null, checkRaw != null).count { it } != 1) {
                error("$where: expect（exists / absent）・count（min / max）・check（判定述語。R28-08）のいずれか一つが必要です")
            }
            val expect = expectRaw?.let { ConstraintExpect.parse(it) }
            val count = countRaw?.let { ConstraintCount.parse(it, where) }
            val check = when {
                checkRaw == null -> emptyList()
                checkRaw !is List<*> -> error("$where.check は配列である必要があります")
                checkRaw.isEmpty() -> error("$where.check には1項目以上が必要です")
                else -> checkRaw.mapIndexed { i, e -> ConstraintCheck.parse(e, "$where.check[$i]") }
            }
            // 束縛の解決はパース段階で固定する（where.of と同じ規約。実行時に黙って空にならないため）
            check.forEach { c ->
                c.binds.forEach { bind ->
                    require(bind in spec.binds) { "$where.check の束縛「$bind」が見つかりません" }
                }
            }
            val reportAt = m["reportAt"]?.let { ReportAt.parse(it, "$where.reportAt") }
            reportAt?.let {
                if (it.ref != null) {
                    require(it.ref in spec.edgeBinds) { "$where.reportAt の参照の束縛「${it.ref}」が見つかりません（edge に bind を書きます）" }
                    require(check.isNotEmpty() || expect == ConstraintExpect.ABSENT) {
                        "$where.reportAt の ref は、expect: absent か check と組み合わせて使います（見つかった解ごとに、その参照の場所へ指摘します）"
                    }
                } else {
                    require(it.of in spec.binds) { "$where.reportAt の束縛「${it.of}」が見つかりません" }
                    require(check.isNotEmpty()) { "$where.reportAt は check と組み合わせて使います（値の場所へ指摘を出すのは値の比較だけです）" }
                }
            }
            val responses = when (val r = m["responses"]) {
                null -> emptyList()
                is List<*> -> r.mapIndexed { i, e -> ResponseDecl.parse(e, "$where.responses[$i]") }
                else -> error("$where.responses は配列である必要があります")
            }
            responses.firstOrNull { it.kind == ResponseDecl.Kind.APPLY }?.let {
                require(reportAt != null && reportAt.ref == null) { "$where.responses の apply は reportAt（書き込む値の場所）の宣言が必要です" }
            }
            require(responses.none { it.needsCheck } || check.isNotEmpty()) {
                "$where.responses の jump（left / right の出どころ）と apply は、check と組み合わせて使います"
            }
            val level = when (val v = m["level"]?.toString()) {
                null -> CoverageLevel.WARNING
                else -> CoverageLevel.entries.firstOrNull { it.id == v } ?: error("$where.level は warning / error のいずれかです: $v")
            }
            return ConstraintRule(
                id = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                spec = spec,
                expect = expect,
                count = count,
                check = check,
                level = level,
                activeFrom = m["activeFrom"]?.toString()?.takeIf { it.isNotBlank() },
                message = m["message"]?.toString()?.takeIf { it.isNotBlank() },
                reportAt = reportAt,
                responses = responses,
                status = m["status"]?.toString()?.takeIf { it.isNotBlank() }?.also {
                    require(reportAt == null) { "$where.status は、起点を定義した場所へ報告する検査に書けます（reportAt とは組み合わせられません）" }
                },
                levelFrom = m["levelFrom"]?.toString()?.takeIf { it.isNotBlank() }?.also {
                    require(it in EnforcementDef.KEYS) { "$where.levelFrom は ${EnforcementDef.KEYS.joinToString(" / ")} のいずれかです: $it" }
                },
                fixSite = m["fixSite"]?.let { FixSite.parse(it, "$where.fixSite") }?.also {
                    require(reportAt == null) { "$where.fixSite は、起点を定義した場所へ報告する検査に書けます（reportAt とは組み合わせられません）" }
                },
            )
        }
    }
}
