package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty

/**
 * 値の計算（意味プリミティブ SP-10）と、値を読む文書の指定（SP-01）の宣言モデル（P29 Phase 6 第1段）。
 * 比較のオペランド（check）に書ける式の閉じた一式——利用者が定義する関数・条件分岐・再帰は持たない。
 * YAML の書き方:
 * - 読み出し   `{of: 束縛名, path: 相対パス}`（`in: {schemaId, fileGlob}` で、値を読む文書を指定できる）
 * - 決まった値 値をそのまま書く（`10`・`approved`）。`{value: 値}` とも書ける（値が無ければ「得られない」扱い——
 *   定義のひな型で、渡されないかもしれない値を受けるのに使う）
 * - テンプレートの展開 `{format: 式, scopes: [{of: 束縛名, in: 指定 または 指定の配列}, ...]}`
 *   （式が返す文字列の `{パス}` を、scopes の要素の値で近い順に置き換える。置き換えられなければ「得られない」）
 * - 辞書での置き換え `{map: 式, using: {元: 先}}`（辞書に無い値はそのまま）
 * - 最初に得られた値 `{first: [式, ...]}`
 * - 値を1つ受け取る計算 `{length: 式}`（文字数）・`{upper: 式}`（大文字へ）・`{lower: 式}`（小文字へ）・
 *   `{number: 式}`（数値として読み直して表記を揃える。`12.0` → `12`。数値として読めなければ「得られない」）
 * - 四則演算 `{add: [式, ...]}`・`{multiply: [式, ...]}`（2つ以上）・`{subtract: [式, 式]}`・`{divide: [式, 式]}`
 *   （どれかが数値として読めない・0 で割る場合は「得られない」。結果の表記は number と同じに揃える）
 * - 参照の本数 `{refCount: {of: 束縛名, dir: out | in, relation: 関係名, via: キー名}}`（SP-03。その要素から出ていく／
 *   その要素へ入ってくる参照の本数。同じ相手への参照が2本あれば2と数える。出ていく参照は相手が見つからないものも数える）
 * - 集計 `{sum | min | max: {of: 束縛名, in: 指定, path: 相対パス}}`・`{count: {of: 束縛名}}`（SP-03。同じ起点について
 *   見つかった全ての解にわたり、その束縛の要素（重複は1つ）の値を合計・最小・最大する／要素を数える。値は、指定に合う文書の
 *   うち定義した側を優先して1つから読む。値が得られない・数値として読めない要素が1つでもあれば「得られない」）
 * - 書かれた場所の数 `{occurrenceCount: {of: 束縛名, schema: スキーマID または その並び, fileGlob: パターン, role: defining | detail}}`
 *   （SP-05。その ID が書かれている場所のうち、指定に合うものの数。指定は省略でき、すべて「かつ」。
 *   `distinct: type` を書くと、場所の数ではなく、それらの場所で宣言されているエンティティ型の種類の数）
 * 評価（どの出現から読むか・得られない値の扱い）は semantic.SemanticQuery の check 判定が担う。
 * 計算そのもの（文字列と数値の純粋な関数）は semantic.SemanticExpression にある。
 *
 * 検査の外（ひな型・表示・組み立て。P31）では「いまの要素」を前提に書ける——`of` を省略するといまの要素（読み出し・
 * 参照の本数・書かれた場所の数）、`format` の `scopes` を省略するといまの要素の値で穴を埋める。`{パス}` を含む文字列は
 * `{format: その文字列}` の省略形（短い書き方。parseValue）。`format` には `with:`（名前を付けた値＝穴に差し込む計算した値）
 * も書ける。1つの環境での評価は semantic.SemanticValues。
 */
sealed interface ValueExpr {

    /** 束縛からの値の読み出し（of が null＝いまの要素）。inDoc は値を読む文書の指定（null = 同じ ID が書かれた全文書が候補）。 */
    data class Get(val of: String?, val inDoc: DerivationSelector?, val path: YPath) : ValueExpr

    /** 決まった値（null は「得られない」）。 */
    data class Literal(val value: String?) : ValueExpr

    /**
     * テンプレートの展開。template が返す文字列のプレースホルダを scopes（近い順）の値で置き換える（scopes が空＝いまの要素）。
     * with は名前を付けた値（穴の名前 → 式）で、どの入れ物よりも先に穴を埋める。
     */
    data class Format(val template: ValueExpr, val scopes: List<ScopeRef>, val with: Map<String, ValueExpr> = emptyMap()) : ValueExpr

    /** 辞書での置き換え（辞書に無い値はそのまま返す）。 */
    data class MapValue(val input: ValueExpr, val using: Map<String, String>) : ValueExpr

    /** 最初に得られた値。 */
    data class First(val options: List<ValueExpr>) : ValueExpr

    /** 値を1つ受け取る計算（文字数・大文字へ・小文字へ・数値として読み直す）。 */
    data class Unary(val fn: UnaryFn, val input: ValueExpr) : ValueExpr

    /** 四則演算（数値として読めない値があれば「得られない」）。 */
    data class Arith(val op: ArithOp, val operands: List<ValueExpr>) : ValueExpr

    /** 参照の本数（SP-03）。relation / via を書かなければ、その向きの全ての参照を数える。of が null＝いまの要素。 */
    data class RefCount(val of: String?, val dir: QueryDir, val relation: String?, val via: String?) : ValueExpr

    /**
     * 書かれた場所の数（SP-05）。schemas が空なら全スキーマ・fileGlob / role が null なら制限なし。
     * distinct が "type" のときは、合う場所で宣言されているエンティティ型の種類の数。
     */
    data class OccurrenceCount(
        val of: String?,
        val schemas: List<String>,
        val fileGlob: String?,
        val role: EntityRole?,
        val distinct: String?,
    ) : ValueExpr {
        private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

        /** 場所（スキーマID・ワークスペース相対パス・役割）が指定に合うか。 */
        fun matches(schemaId: String?, relPath: String, occRole: EntityRole?): Boolean =
            (schemas.isEmpty() || schemaId in schemas) &&
                (regex == null || regex!!.matches(relPath)) &&
                (role == null || occRole == role)
    }

    /** 解にわたる集計（SP-03）。count は path を持たない。 */
    data class Aggregate(val fn: AggregateFn, val of: String?, val inDoc: DerivationSelector?, val path: YPath?) : ValueExpr

    /** 集計の種類。label は指摘の文面に添える言葉。 */
    enum class AggregateFn(val id: String, val label: String) {
        SUM("sum", "合計"),
        MIN("min", "最小"),
        MAX("max", "最大"),
        COUNT("count", "件数"),
    }

    /** 値を1つ受け取る計算の種類。label は指摘の文面で「name の文字数」のように添える言葉（無ければ添えない）。 */
    enum class UnaryFn(val id: String, val label: String?) {
        LENGTH("length", "文字数"),
        UPPER("upper", "大文字"),
        LOWER("lower", "小文字"),
        NUMBER("number", null),
    }

    /** 四則演算の種類。pairOnly は「ちょうど2つの値を取る」（引き算・割り算）。 */
    enum class ArithOp(val id: String, val symbol: String, val pairOnly: Boolean) {
        ADD("add", "+", false),
        SUBTRACT("subtract", "-", true),
        MULTIPLY("multiply", "×", false),
        DIVIDE("divide", "÷", true),
    }

    /** テンプレートの展開で値を探す入れ物1つ（束縛の、指定に合う文書に書かれた要素。of が null＝いまの要素）。 */
    data class ScopeRef(val of: String?, val inDoc: DerivationSelector?)

    /** この式が読む束縛と文書の指定の組（出現の選び方を決める単位。初出順・重複なし）。 */
    fun sources(): List<ScopeRef> = when (this) {
        is Get -> listOf(ScopeRef(of, inDoc))
        is Literal -> emptyList()
        is Format -> (template.sources() + scopes + with.values.flatMap { it.sources() }).distinct()
        is MapValue -> input.sources()
        is First -> options.flatMap { it.sources() }.distinct()
        is Unary -> input.sources()
        is Arith -> operands.flatMap { it.sources() }.distinct()
        // 参照の本数・書かれた場所の数・集計は、値を読む出現を選ばない（数はグラフから・集計は定義した側を優先して1つに決める）
        is RefCount, is OccurrenceCount, is Aggregate -> emptyList()
    }

    /** この式が使う束縛の名前（パース時に、束縛が見つかるかを点検する）。 */
    fun binds(): List<String> = when (this) {
        is RefCount -> listOfNotNull(of)
        is OccurrenceCount -> listOfNotNull(of)
        is Aggregate -> listOfNotNull(of)
        is Format -> (template.binds() + scopes.mapNotNull { it.of } + with.values.flatMap { it.binds() }).distinct()
        is MapValue -> input.binds()
        is First -> options.flatMap { it.binds() }.distinct()
        is Unary -> input.binds()
        is Arith -> operands.flatMap { it.binds() }.distinct()
        is Get -> listOfNotNull(of)
        is Literal -> emptyList()
    }

    /** 解にわたる集計を含むか（含む検査は、同じ起点の全ての解を先に集めてから評価する）。 */
    fun hasAggregate(): Boolean = when (this) {
        is Aggregate -> true
        is Format -> template.hasAggregate() || with.values.any { it.hasAggregate() }
        is MapValue -> input.hasAggregate()
        is First -> options.any { it.hasAggregate() }
        is Unary -> input.hasAggregate()
        is Arith -> operands.any { it.hasAggregate() }
        is Get, is Literal, is RefCount, is OccurrenceCount -> false
    }

    /**
     * この式の中で「テンプレートの文字列」を読んでいる読み出し（テンプレートの展開 format の対象になる Get）。
     * テンプレートが書かれた文書の側で、プレースホルダが解決できるかを先に点検する（テンプレート健全性）のに使う。
     */
    fun templateReads(): List<Get> = when (this) {
        is Get, is Literal, is RefCount, is OccurrenceCount, is Aggregate -> emptyList()
        is Format -> template.reads() + template.templateReads() + with.values.flatMap { it.templateReads() }
        is MapValue -> input.templateReads()
        is First -> options.flatMap { it.templateReads() }
        is Unary -> input.templateReads()
        is Arith -> operands.flatMap { it.templateReads() }
    }

    /** この式が値を読む読み出し（入れ子を含む）。 */
    fun reads(): List<Get> = when (this) {
        is Get -> listOf(this)
        is Literal, is RefCount, is OccurrenceCount, is Aggregate -> emptyList()
        is Format -> template.reads() + with.values.flatMap { it.reads() }
        is MapValue -> input.reads()
        is First -> options.flatMap { it.reads() }
        is Unary -> input.reads()
        is Arith -> operands.flatMap { it.reads() }
    }

    /**
     * いまの要素から読む項目の名前（`of` を省略した読み出しのパス・短い書き方の穴の名前。入れ子と with の中を含む。
     * with で付けた名前の穴は含まない）。予約の名前（id・title）も、書かれていればそのまま含む。
     * ファイル名のパターンの点検（{id} の有無・newRow で使える穴）に使う。
     */
    fun currentReads(): List<String> = when (this) {
        is Get -> if (of == null) listOf(path.pretty()) else emptyList()
        is Literal -> emptyList()
        is Format -> {
            val holes = (template as? Literal)?.value?.let(Projection::placeholders).orEmpty()
            val own = if (scopes.isEmpty() || scopes.any { it.of == null }) holes.filter { it !in with } else emptyList()
            (own + template.currentReads() + with.values.flatMap { it.currentReads() }).distinct()
        }
        is MapValue -> input.currentReads()
        is First -> options.flatMap { it.currentReads() }.distinct()
        is Unary -> input.currentReads()
        is Arith -> operands.flatMap { it.currentReads() }.distinct()
        is RefCount, is OccurrenceCount, is Aggregate -> emptyList()
    }

    companion object {
        private val GET_KEYS = setOf("of", "in", "path")
        private val WITH_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")

        /**
         * スキーマの値の式（P31）: 検査の外で「いまの要素」を前提に書く式の読み取り。`{パス}` を含む文字列は format の省略形
         * （穴はいまの要素の値・予約の {id} {title}）、それ以外のスカラーは決まった値、object は完全な書き方（of を省略すると
         * いまの要素。format の scopes も省略できる）。
         */
        fun parseValue(raw: Any?, where: String): ValueExpr =
            if (raw is String && Projection.placeholders(raw).isNotEmpty()) Format(Literal(raw), emptyList())
            else parse(raw, where, current = true)

        /**
         * 値の式の読み取り。current は「いまの要素がある」文脈（スキーマの値の式）で、of と scopes を省略できる。
         * 検査の check（current = false）では、束縛名の省略は定義エラー（クエリの解に「いまの要素」は無い）。
         */
        fun parse(raw: Any?, where: String, current: Boolean = false): ValueExpr {
            // 決まった値は、そのまま書ける（文字列・数値・真偽値）
            if (raw is String || raw is Number || raw is Boolean) return Literal(raw.toString())
            val m = raw as? Map<*, *>
                ?: error("$where は値の式である必要があります（{of: 束縛名, path: 相対パス}・決まった値 など）")
            val unary = UnaryFn.entries.firstOrNull { m.containsKey(it.id) }
            val arith = ArithOp.entries.firstOrNull { m.containsKey(it.id) }
            val aggregate = AggregateFn.entries.firstOrNull { m.containsKey(it.id) }
            fun onlyKeys(allowed: Set<String>) {
                m.keys.map { it.toString() }.firstOrNull { it !in allowed }
                    ?.let { error("$where: 未知のキーです: $it（使えるキー: ${allowed.joinToString(", ")}）") }
            }
            return when {
                m.containsKey("format") -> {
                    onlyKeys(setOf("format", "scopes", "with"))
                    val scopesRaw = m["scopes"] as? List<*>
                        ?: if (current && m["scopes"] == null) emptyList<Any?>() else error("$where.scopes（値を探す入れ物の配列）が必要です")
                    val withRaw = m["with"]?.let { it as? Map<*, *> ?: error("$where.with はobject（穴の名前: 式）である必要があります") }
                    Format(
                        template = parse(m["format"], "$where.format", current),
                        scopes = scopesRaw.flatMapIndexed { i, s -> parseScopes(s, "$where.scopes[$i]", current) },
                        with = withRaw?.entries?.associate { (k, v) ->
                            val name = k.toString()
                            require(WITH_NAME.matches(name)) { "$where.with: 穴の名前は英字で始まる英数字で書きます: $name" }
                            name to parse(v, "$where.with.$name", current)
                        }.orEmpty(),
                    )
                }
                m.containsKey("map") -> {
                    onlyKeys(setOf("map", "using"))
                    val using = when (val u = m["using"]) {
                        null -> emptyMap()
                        is Map<*, *> -> u.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
                        else -> error("$where.using はobject（元の値: 置き換える値）である必要があります")
                    }
                    MapValue(parse(m["map"], "$where.map", current), using)
                }
                m.containsKey("first") -> {
                    onlyKeys(setOf("first"))
                    val list = m["first"] as? List<*> ?: error("$where.first は式の配列である必要があります")
                    require(list.isNotEmpty()) { "$where.first には1つ以上の式が必要です" }
                    First(list.mapIndexed { i, e -> parse(e, "$where.first[$i]", current) })
                }
                m.containsKey("value") -> {
                    onlyKeys(setOf("value"))
                    Literal(m["value"]?.toString())
                }
                m.containsKey("refCount") -> {
                    onlyKeys(setOf("refCount"))
                    val r = m["refCount"] as? Map<*, *> ?: error("$where.refCount はobject（{of, dir, relation, via}）である必要があります")
                    r.keys.map { it.toString() }.firstOrNull { it !in setOf("of", "dir", "relation", "via") }
                        ?.let { error("$where.refCount: 未知のキーです: $it（使えるキー: of, dir, relation, via）") }
                    RefCount(
                        of = r["of"]?.toString()?.takeIf { it.isNotBlank() } ?: if (current) null else error("$where.refCount.of（束縛名）が必要です"),
                        dir = QueryDir.parse(r["dir"]?.toString() ?: error("$where.refCount.dir（out / in）が必要です")),
                        relation = r["relation"]?.toString()?.takeIf { it.isNotBlank() },
                        via = r["via"]?.toString()?.takeIf { it.isNotBlank() },
                    )
                }
                m.containsKey("occurrenceCount") -> {
                    onlyKeys(setOf("occurrenceCount"))
                    val o = m["occurrenceCount"] as? Map<*, *>
                        ?: error("$where.occurrenceCount はobject（{of, schema, fileGlob, role, distinct}）である必要があります")
                    o.keys.map { it.toString() }.firstOrNull { it !in setOf("of", "schema", "fileGlob", "role", "distinct") }
                        ?.let { error("$where.occurrenceCount: 未知のキーです: $it（使えるキー: of, schema, fileGlob, role, distinct）") }
                    OccurrenceCount(
                        of = o["of"]?.toString()?.takeIf { it.isNotBlank() } ?: if (current) null else error("$where.occurrenceCount.of（束縛名）が必要です"),
                        schemas = when (val v = o["schema"]) {
                            null -> emptyList()
                            is List<*> -> v.map { it.toString() }
                            else -> listOf(v.toString())
                        },
                        fileGlob = o["fileGlob"]?.toString()?.takeIf { it.isNotBlank() },
                        role = when (val r = o["role"]?.toString()) {
                            null -> null
                            "defining" -> EntityRole.DEFINING
                            "detail" -> EntityRole.DETAIL
                            else -> error("$where.occurrenceCount.role は defining / detail のいずれかです: $r")
                        },
                        distinct = o["distinct"]?.toString()?.also {
                            require(it == "type") { "$where.occurrenceCount.distinct に書けるのは type（エンティティ型の種類を数える）だけです: $it" }
                        },
                    )
                }
                aggregate != null -> {
                    onlyKeys(setOf(aggregate.id))
                    val a = m[aggregate.id] as? Map<*, *> ?: error("$where.${aggregate.id} はobject（{of, in, path}）である必要があります")
                    a.keys.map { it.toString() }.firstOrNull { it !in GET_KEYS }
                        ?.let { error("$where.${aggregate.id}: 未知のキーです: $it（使えるキー: ${GET_KEYS.joinToString(", ")}）") }
                    val of = a["of"]?.toString()?.takeIf { it.isNotBlank() } ?: if (current) null else error("$where.${aggregate.id}.of（束縛名）が必要です")
                    val pathText = a["path"]?.toString()?.takeIf { it.isNotBlank() }
                    if (aggregate == AggregateFn.COUNT) {
                        require(pathText == null && a["in"] == null) { "$where.count は要素を数えます（書けるのは of だけです）" }
                    } else {
                        require(pathText != null) { "$where.${aggregate.id}.path が必要です" }
                    }
                    Aggregate(aggregate, of, parseSelector(a["in"], "$where.${aggregate.id}.in"), pathText?.let { parseYPath(it) })
                }
                unary != null -> {
                    onlyKeys(setOf(unary.id))
                    Unary(unary, parse(m[unary.id], "$where.${unary.id}", current))
                }
                arith != null -> {
                    onlyKeys(setOf(arith.id))
                    val list = m[arith.id] as? List<*> ?: error("$where.${arith.id} は式の配列である必要があります")
                    if (arith.pairOnly) require(list.size == 2) { "$where.${arith.id} は [式, 式] の2要素配列である必要があります" }
                    else require(list.size >= 2) { "$where.${arith.id} には2つ以上の式が必要です" }
                    Arith(arith, list.mapIndexed { i, e -> parse(e, "$where.${arith.id}[$i]", current) })
                }
                else -> {
                    onlyKeys(GET_KEYS)
                    val of = m["of"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: if (current) null else error("$where.of（束縛名）が必要です（スキーマの値の式では省略できます＝いまの要素）")
                    val path = parseYPath(m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です"))
                    Get(of, parseSelector(m["in"], "$where.in"), path)
                }
            }
        }

        /**
         * 値を読む文書の指定（schemaId と fileGlob の両方を満たす文書。ビューの列・導出の selector と同じ書き方）。
         * 書かれていなければ null（指定なし＝同じ ID が書かれた全文書が候補）。書いたのに中身が空なら定義エラー
         * （絞るつもりで書いた指定が、黙って「全文書」になるのを防ぐ）。
         */
        fun parseSelector(raw: Any?, where: String): DerivationSelector? {
            if (raw == null) return null
            val m = raw as? Map<*, *> ?: error("$where はobject（{schemaId, fileGlob}）である必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("schemaId", "fileGlob") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: schemaId, fileGlob）") }
            val selector = DerivationSelector(m["schemaId"]?.toString(), m["fileGlob"]?.toString())
            require(!selector.isEmpty) { "$where には schemaId か fileGlob が必要です" }
            return selector
        }

        /** scopes の1項目。in に指定の配列を書くと、指定ごとに1つずつ（書いた順に）入れ物になる。 */
        private fun parseScopes(raw: Any?, where: String, current: Boolean): List<ScopeRef> {
            val m = raw as? Map<*, *> ?: error("$where はobject（{of: 束縛名, in: 文書の指定}）である必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("of", "in") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: of, in）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: if (current) null else error("$where.of（束縛名）が必要です")
            return when (val inRaw = m["in"]) {
                null -> listOf(ScopeRef(of, null))
                is List<*> -> inRaw.mapIndexed { i, s -> ScopeRef(of, parseSelector(s, "$where.in[$i]")) }
                else -> listOf(ScopeRef(of, parseSelector(inRaw, "$where.in")))
            }
        }
    }
}
