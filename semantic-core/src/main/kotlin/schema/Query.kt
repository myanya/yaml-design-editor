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
 * node ステップ: フロンティアのノード集合を絞る（kind・エンティティ型・出現スキーマ・IDパターン）。
 * bind を書いたノードは結果の束縛列になる（束縛されない中間ノードは行の同一性に関与しない）。
 * same を書いたノードは「先行して束縛済みのノードそのもの」に到達することを要求する
 * （ノード一致。2経路が同一ノードへ到達する照合＝型不一致検査等の土台。R28-07）。
 * same は他のキーと同居できない（到達先の同一性がフィルタのすべてのため）。
 */
data class QueryNodeStep(
    val kind: QueryNodeKind = QueryNodeKind.ENTITY,
    val entity: String? = null,     // エンティティ型名（kind: entity のみ）
    val schema: String? = null,     // 出現スキーマID（kind: entity のみ）
    val idPattern: String? = null,  // ID（kind: document では documentId）の glob
    val bind: String? = null,       // 束縛名（結果の列になる）
    val same: String? = null,       // 先行する束縛名（そのノード自身への到達を要求。R28-07）
) {
    companion object {
        private val KEYS = setOf("kind", "entity", "schema", "id", "bind", "same")
        private val BIND_RE = Regex("""[a-zA-Z][a-zA-Z0-9_-]*""")

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
            val entity = m["entity"]?.toString()?.takeIf { it.isNotBlank() }
            val schema = m["schema"]?.toString()?.takeIf { it.isNotBlank() }
            if (kind == QueryNodeKind.DOCUMENT && (entity != null || schema != null)) {
                error("$where.node: kind: document に entity / schema は指定できません（文書ノードは id（documentId のパターン）で絞ります）")
            }
            val bind = m["bind"]?.toString()?.takeIf { it.isNotBlank() }
            if (bind != null && !BIND_RE.matches(bind)) {
                error("$where.node.bind は英字で始まる英数字・ハイフン・アンダースコアである必要があります: $bind")
            }
            return QueryNodeStep(
                kind = kind,
                entity = entity,
                schema = schema,
                idPattern = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                bind = bind,
            )
        }
    }
}

/** edge ステップ: フロンティアから参照エッジを1段辿る。 */
data class QueryEdgeStep(
    val dir: QueryDir = QueryDir.OUT,
    val relation: String? = null,
    val via: String? = null,        // 参照が書かれたキー名（viaKey）
) {
    companion object {
        private val KEYS = setOf("dir", "relation", "via")

        fun parse(raw: Any?, where: String): QueryEdgeStep {
            val m = raw as? Map<*, *> ?: error("$where.edge はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where.edge: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            return QueryEdgeStep(
                dir = m["dir"]?.toString()?.let { QueryDir.parse(it) } ?: QueryDir.OUT,
                relation = m["relation"]?.toString()?.takeIf { it.isNotBlank() },
                via = m["via"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** 連鎖の1ホップ（edge で移動して node で絞る）。 */
data class QueryHop(val edge: QueryEdgeStep, val node: QueryNodeStep)

/** 束縛済みノードからの分岐（1段のみ・内部結合。§4.32）。 */
data class QueryBranch(val from: String, val hops: List<QueryHop>)

/**
 * プロパティ述語。equals / notEquals / exists のいずれか一つを持つ。
 * 値は「いずれかの出現の該当パスのスカラー」（多段定義の OR）。
 * notEquals は「値が1つ以上あり、どの出現の値も指定値でない」（equals の OR 解釈の否定。
 * 値がなければ黙る＝判定保留の哲学 §4.26。R28-02）。
 */
data class QueryWhere(
    val of: String,
    val path: YPath,
    val equals: String? = null,
    val exists: Boolean? = null,
    val notEquals: String? = null,
) {
    companion object {
        private val KEYS = setOf("of", "path", "equals", "notEquals", "exists")

        fun parse(raw: Any?, where: String): QueryWhere {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.of（束縛名）が必要です")
            val path = parseYPath(m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です"))
            val equals = m["equals"]?.toString()
            val notEquals = m["notEquals"]?.toString()
            val exists = m["exists"] as? Boolean
            val given = listOfNotNull(equals, notEquals, exists?.toString()).size
            if (given != 1) error("$where: equals / notEquals / exists のいずれか一つが必要です")
            return QueryWhere(of = of, path = path, equals = equals, exists = exists, notEquals = notEquals)
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
    val where: List<QueryWhere> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) {
    /** 束縛名の一覧（宣言順）。結果の列順・rowKey / of / from の解決に使う。 */
    val binds: List<String> =
        (listOf(start) + hops.map { it.node } + also?.hops?.map { it.node }.orEmpty()).mapNotNull { it.bind }

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
            val whereList = (raw["where"] as? List<*>)?.mapIndexed { i, e -> QueryWhere.parse(e, "$where.where[$i]") }
                ?: run {
                    if (raw.containsKey("where")) error("$where.where は配列である必要があります")
                    emptyList()
                }
            val limit = (raw["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT
            require(limit >= 1) { "$where.limit は1以上である必要があります" }

            val spec = QuerySpec(start = start, hops = hops, also = also, where = whereList, limit = limit)
            // 束縛名の重複と、also.from / where.of の解決をパース段階で固定する（実行時に黙って空にならないため）
            spec.binds.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()
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
    fun violated(n: Int): Boolean = (min != null && n < min) || (max != null && n > max)
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

/** check 判定述語の比較演算子（R28-08）。順序系（lte/gte/lt/gt）は数値比較・eq/ne はスカラー文字列の等値。 */
enum class CheckOp(val id: String, val violationSymbol: String) {
    LTE("lte", ">"),
    GTE("gte", "<"),
    LT("lt", "≥"),
    GT("gt", "≤"),
    EQ("eq", "≠"),
    NE("ne", "=");

    companion object {
        fun parse(raw: String, where: String): CheckOp = entries.firstOrNull { it.id == raw }
            ?: error("$where: 演算子は ${entries.joinToString(" / ") { it.id }} のいずれかです: $raw")
    }
}

/** check のオペランド: 束縛の値の読み出し（出現割当て σ の下の単一値。CORE-CALCULUS §7）。 */
data class CheckOperand(val of: String, val path: YPath) {
    companion object {
        private val KEYS = setOf("of", "path")

        fun parse(raw: Any?, where: String): CheckOperand {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{of: 束縛名, path: 相対パス}）")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.of（束縛名）が必要です")
            val path = parseYPath(m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です"))
            return CheckOperand(of = of, path = path)
        }
    }
}

/**
 * check 判定述語の1項目（R28-08。CORE-CALCULUS §8 の判定述語＝反例量化）: 2つの束縛の値の比較。
 * `{lte|gte|lt|gt|eq|ne: [左, 右]}` の1キー object で書く。評価は出現割当て σ の反例量化——
 * 違反する出現の組を1つでも発見したら違反（反例を指摘に列挙）・評価可能な組がなければ黙る
 * （判定保留 §4.26）。項目内の σ は一貫（同一束縛は同一出現から読む）。
 */
data class ConstraintCheck(val op: CheckOp, val left: CheckOperand, val right: CheckOperand) {
    companion object {
        fun parse(raw: Any?, where: String): ConstraintCheck {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります（{演算子: [左, 右]}）")
            if (m.size != 1) {
                error("$where は {演算子: [左, 右]} の1キーで書きます（演算子: ${CheckOp.entries.joinToString(" / ") { it.id }}）")
            }
            val (k, v) = m.entries.first()
            val op = CheckOp.parse(k.toString(), where)
            val list = v as? List<*> ?: error("$where.${op.id} は [左, 右] の2要素配列である必要があります")
            if (list.size != 2) error("$where.${op.id} は [左, 右] の2要素配列である必要があります")
            return ConstraintCheck(
                op = op,
                left = CheckOperand.parse(list[0], "$where.${op.id}[0]"),
                right = CheckOperand.parse(list[1], "$where.${op.id}[1]"),
            )
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
) {
    companion object {
        private val OWN_KEYS = setOf("id", "description", "expect", "count", "check", "level", "activeFrom")

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
                listOf(c.left, c.right).forEach { operand ->
                    require(operand.of in spec.binds) { "$where.check の束縛「${operand.of}」が見つかりません" }
                }
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
            )
        }
    }
}
