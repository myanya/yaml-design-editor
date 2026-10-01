package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty

/**
 * 値の計算（意味プリミティブ SP-10）と、値を読む文書の指定（SP-01）の宣言モデル。
 * 比較のオペランド（check）・スキーマの値の式・ビューの列に書ける式の閉じた一式——利用者が定義する関数・条件分岐・再帰は持たない。
 * 式の値は項（スカラー・配列・マップ）と出どころ（ADR-0010。CORE-CALCULUS「Expression Calculus」）。
 * 語彙は 2 種類: 引数を先に評価して値を受け取る関数と、形を受け取って自分の規則で評価する語。
 * YAML の書き方:
 * - 読み出し   `{of: 束縛名, path: 相対パス}`（`in: {schemaId, fileGlob}` で、値を読む文書を指定できる。
 *   `via: 参照の項目` を添えると、その要素に書かれた参照を 1 段辿った相手の要素から読む＝検査の外だけ。参照がちょうど 1 本のとき相手が決まる）
 *   パスの場所にある項をそのまま返す（配列なら配列）。パスに `[*]`（任意の番号）を書くと、合う場所の値を並べた配列になる
 *   （`comments[*].date`＝各コメントの date。書かれていない要素が 1 つでもあれば「得られない」）
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
 * - 集計 `{sum | min | max: {配列を返す式, type: number | date}}`・`{count: 配列を返す式}`（配列を受け取る関数。
 *   短い書き方 `{max: {path: "comments[*].date", type: date}}` はいまの要素からの読み出し。`{sum: {all: ...}}` のように式も書ける。
 *   値が書かれていない・順序の種類として読めない要素が 1 つでもあれば「得られない」。`count` は配列の件数で、`length` は文字数）。
 *   `type` は順序の種類で、`min` / `max` がどう比べるか（省略時は数値。`date` は YYYY-MM-DD の日付として比べる。
 *   表記から意味を決めないため、日付は宣言する）
 * - 集合から配列へ `{all: {of: 束縛名, in: 指定, path: 相対パス}}`（同じ起点の全ての解にわたり、その束縛の要素（重複は 1 つ）の
 *   path の値を並べた配列。path を省くと ID の配列）・`{all: {from: 起点の束縛名, match: [...], where: [...], also: {...}, of, in, path}}`
 *   （起点を固定したパターン照合の解から並べた配列。from を省くといまの要素が起点。of を省くと match の最後の bind）
 * - 要素ごとの式 `{each: 配列を返す式, value: 式}`（各要素を「いまの要素」として value を評価した配列。
 *   名前を付けた引数は無く、要素の項目は `{path: price}` のように読む）
 * - 参照の本数 `{refCount: {of: 束縛名, dir: out | in, relation: 関係名, via: キー名}}`（SP-03。その要素から出ていく／
 *   その要素へ入ってくる参照の本数。同じ相手への参照が2本あれば2と数える。出ていく参照は相手が見つからないものも数える）
 * - 書かれた場所の数 `{occurrenceCount: {of: 束縛名, schema: スキーマID または その並び, fileGlob: パターン, role: defining | detail}}`
 *   （SP-05。その ID が書かれている場所のうち、指定に合うものの数。指定は省略でき、すべて「かつ」。
 *   `distinct: type` を書くと、場所の数ではなく、それらの場所で宣言されているエンティティ型の種類の数）
 * 古い書き方（集計の中の `of`＝解にわたる集計・`count: {match}`）は残さず、同じ意味の `all` の書き方を定義エラーの文面で案内する。
 * 評価は semantic.SemanticValues（1 つの環境での評価）。計算そのもの（文字列と数値の純粋な関数）は semantic.SemanticExpression にある。
 *
 * 検査の外（ひな型・表示・組み立て。P31）では「いまの要素」を前提に書ける——`of` を省略するといまの要素（読み出し・
 * 参照の本数・書かれた場所の数）、`format` の scopes も省略できる。`{パス}` を含む文字列は `format` の省略形（parseValue）。
 */
sealed interface ValueExpr {

    /**
     * 束縛からの値の読み出し（of が null＝いまの要素）。inDoc は値を読む文書の指定（null = 同じ ID が書かれた全文書が候補）。
     * via は参照を 1 段辿る項目（その要素に書かれた参照の相手の要素から読む。null＝辿らない）。
     * path は `[*]`（AnyIndex）を含んでよいパスのパターン。含めば、合う場所の値を並べた配列を返す。
     */
    data class Get(val of: String?, val inDoc: DerivationSelector?, val path: YPath, val via: YPath? = null) : ValueExpr {
        /** `[*]` を含む（配列を返す）読み出しか。 */
        val spreads: Boolean get() = PathPattern.hasWildcard(path)
    }

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

    /**
     * 集計（配列を受け取る関数）。input は配列を返す式で、先に評価する。kind は順序の種類（min / max がどう比べるか。
     * null＝読んだ項目の型から決める。型が決まらなければ数値）。count は要素の数（値は読まない）。
     */
    data class Aggregate(val fn: AggregateFn, val input: ValueExpr, val kind: OrderKind? = null) : ValueExpr

    /**
     * 集合から配列へ（CORE-CALCULUS「Aggregate」の All）。query が null なら同じ起点の全ての解にわたり、of の束縛の要素（重複は 1 つ）
     * から path の値を並べる。query があれば、from の束縛（null＝いまの要素）を起点に固定した照合の解から並べる。
     * path が null なら ID の配列。path の `[*]` は要素の中の配列に広がり、平らな配列になる。
     */
    data class All(
        val of: String,
        val inDoc: DerivationSelector?,
        val path: List<Any>?,
        val from: String? = null,
        val query: QuerySpec? = null,
    ) : ValueExpr

    /** 要素ごとの式。input（配列を返す式）の各要素を「いまの要素」として value を評価し、配列にする。 */
    data class Each(val input: ValueExpr, val value: ValueExpr) : ValueExpr

    /** 集計の種類。label は指摘の文面に添える言葉。 */
    enum class AggregateFn(val id: String, val label: String) {
        SUM("sum", "合計"),
        MIN("min", "最小"),
        MAX("max", "最大"),
        COUNT("count", "件数"),
    }

    /**
     * 順序の種類（値をどう比べるか）。スキーマの項目の型と同じ言葉で、順序を持つ型は数値（int / number）と
     * 意味の基底型（date / time / datetime＝値の表現 ValueSemantics で比べる）。式に `type` を書かなければ、読んだ項目の型から決める。
     */
    enum class OrderKind(val id: String, val label: String, val value: ValueSemantics? = null) {
        NUMBER("number", "数値"),
        DATE("date", "日付", ValueSemantics.DATE),
        TIME("time", "時刻", ValueSemantics.TIME),
        DATETIME("datetime", "日時", ValueSemantics.DATETIME),
        ;

        companion object {
            fun parse(text: String, where: String): OrderKind =
                entries.firstOrNull { it.id == text }
                    ?: error("$where.type は ${entries.joinToString(" / ") { it.id }} のいずれかです: $text")

            /** 項目の型が持つ順序の種類（数値・日付・時刻・日時。文字列などは null＝順序の種類は決まらない）。 */
            fun ofType(type: FieldType): OrderKind? = when (type) {
                FieldType.INT, FieldType.NUMBER -> NUMBER
                else -> type.value?.let { v -> entries.firstOrNull { it.value == v } }
            }
        }
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

    /**
     * テンプレートの展開で値を探す入れ物1つ（束縛の、指定に合う文書に書かれた要素。of が null＝いまの要素）。
     * via は参照を 1 段辿る項目（辿った相手の要素が入れ物になる）。
     */
    data class ScopeRef(val of: String?, val inDoc: DerivationSelector?, val via: YPath? = null)

    /**
     * この式が読む束縛と文書の指定の組（出現の選び方を決める単位。初出順・重複なし）。
     * `all` は自分で出現を選ぶ（定義した側を優先して 1 つ）ので含めない。`each` の value の中で要素から読むもの（of 無し）も含めない。
     */
    fun sources(): List<ScopeRef> = when (this) {
        is Get -> listOf(ScopeRef(of, inDoc, via))
        is Literal -> emptyList()
        is Format -> (template.sources() + scopes + with.values.flatMap { it.sources() }).distinct()
        is MapValue -> input.sources()
        is First -> options.flatMap { it.sources() }.distinct()
        is Unary -> input.sources()
        is Arith -> operands.flatMap { it.sources() }.distinct()
        is Aggregate -> input.sources()
        is Each -> (input.sources() + value.sources().filter { it.of != null }).distinct()
        // 参照の本数・書かれた場所の数・集合からの配列は、値を読む出現を選ばない（数はグラフから・all は定義した側を優先して1つに決める）
        is RefCount, is OccurrenceCount, is All -> emptyList()
    }

    /** この式が使う（外側の）束縛の名前（パース時に、束縛が見つかるかを点検する）。all の match の中の束縛は含めない。 */
    fun binds(): List<String> = when (this) {
        is RefCount -> listOfNotNull(of)
        is OccurrenceCount -> listOfNotNull(of)
        is Aggregate -> input.binds()
        is All -> if (query == null) listOf(of) else listOfNotNull(from)
        is Each -> (input.binds() + value.binds()).distinct()
        is Format -> (template.binds() + scopes.mapNotNull { it.of } + with.values.flatMap { it.binds() }).distinct()
        is MapValue -> input.binds()
        is First -> options.flatMap { it.binds() }.distinct()
        is Unary -> input.binds()
        is Arith -> operands.flatMap { it.binds() }.distinct()
        is Get -> listOfNotNull(of)
        is Literal -> emptyList()
    }

    /**
     * 同じ起点の全ての解の一覧を要するか（`all` の of の形）。含む検査は、解を先に全部集めてから評価し、どの解で評価しても
     * 同じ結果になるので、違反を解の数だけ並べない。起点を固定した照合（`all` の match）は起点だけに依る。
     */
    fun needsSolutions(): Boolean = when (this) {
        is All -> query == null
        is Aggregate -> input.needsSolutions()
        is Each -> input.needsSolutions() || value.needsSolutions()
        is Format -> template.needsSolutions() || with.values.any { it.needsSolutions() }
        is MapValue -> input.needsSolutions()
        is First -> options.any { it.needsSolutions() }
        is Unary -> input.needsSolutions()
        is Arith -> operands.any { it.needsSolutions() }
        is Get, is Literal, is RefCount, is OccurrenceCount -> false
    }

    /**
     * この式の中で「テンプレートの文字列」を読んでいる読み出し（テンプレートの展開 format の対象になる Get）。
     * テンプレートが書かれた文書の側で、プレースホルダが解決できるかを先に点検する（テンプレート健全性）のに使う。
     */
    fun templateReads(): List<Get> = when (this) {
        is Get, is Literal, is RefCount, is OccurrenceCount, is All -> emptyList()
        is Format -> template.reads() + template.templateReads() + with.values.flatMap { it.templateReads() }
        is MapValue -> input.templateReads()
        is First -> options.flatMap { it.templateReads() }
        is Unary -> input.templateReads()
        is Arith -> operands.flatMap { it.templateReads() }
        is Aggregate -> input.templateReads()
        is Each -> input.templateReads()
    }

    /** この式が値を読む読み出し（入れ子を含む）。each の value の中の読み出しは要素からなので含めない。 */
    fun reads(): List<Get> = when (this) {
        is Get -> listOf(this)
        is Literal, is RefCount, is OccurrenceCount, is All -> emptyList()
        is Format -> template.reads() + with.values.flatMap { it.reads() }
        is MapValue -> input.reads()
        is First -> options.flatMap { it.reads() }
        is Unary -> input.reads()
        is Arith -> operands.flatMap { it.reads() }
        is Aggregate -> input.reads()
        is Each -> input.reads()
    }

    /**
     * いまの要素から読む項目の名前（`of` を省略した読み出しのパス・短い書き方の穴の名前。入れ子と with の中を含む。
     * with で付けた名前の穴は含まない）。予約の名前（id・title）も、書かれていればそのまま含む。
     * ファイル名のパターンの点検（{id} の有無・newRow で使える穴）に使う。
     */
    fun currentReads(): List<String> = when (this) {
        // 参照を辿る読み出しが、いまの要素から読むのは参照の項目（値そのものは相手の要素から読む）
        is Get -> if (of != null) emptyList() else listOf(via?.pretty() ?: PathPattern.pretty(path))
        is Literal -> emptyList()
        is Format -> (ownHoles() + template.currentReads() + with.values.flatMap { it.currentReads() }).distinct()
        is MapValue -> input.currentReads()
        is First -> options.flatMap { it.currentReads() }.distinct()
        is Unary -> input.currentReads()
        is Arith -> operands.flatMap { it.currentReads() }.distinct()
        is Aggregate -> input.currentReads()
        is Each -> input.currentReads()
        is RefCount, is OccurrenceCount, is All -> emptyList()
    }

    /**
     * 短い書き方の穴の名前のうち、いまの要素の値で埋まりうるもの（入れ物を書かないか、いまの要素の入れ物があるとき。
     * with で付けた名前は除く）。入れ子と with の中を含む。ビューのリントが、行のスキーマから解決できるかを見るのに使う。
     */
    fun holes(): List<String> = when (this) {
        is Format -> (ownHoles() + template.holes() + with.values.flatMap { it.holes() }).distinct()
        is MapValue -> input.holes()
        is First -> options.flatMap { it.holes() }.distinct()
        is Unary -> input.holes()
        is Arith -> operands.flatMap { it.holes() }.distinct()
        is Aggregate -> input.holes()
        is Each -> input.holes()
        is Get, is Literal, is RefCount, is OccurrenceCount, is All -> emptyList()
    }

    /** テンプレートの展開の、いまの要素で埋まりうる穴（入れ物を書かないか、参照を辿らない「いまの要素」の入れ物があるとき）。 */
    private fun Format.ownHoles(): List<String> {
        val holes = (template as? Literal)?.value?.let(Projection::placeholders).orEmpty()
        return if (scopes.isEmpty() || scopes.any { it.of == null && it.via == null }) holes.filter { it !in with } else emptyList()
    }

    /** この式に含まれる集計（入れ子を含む。each の value の中は要素に対するものなので含めない。ビューのリントが順序の種類の食い違いを見るのに使う）。 */
    fun aggregates(): List<Aggregate> = when (this) {
        is Aggregate -> listOf(this) + input.aggregates()
        is Format -> template.aggregates() + with.values.flatMap { it.aggregates() }
        is MapValue -> input.aggregates()
        is First -> options.flatMap { it.aggregates() }
        is Unary -> input.aggregates()
        is Arith -> operands.flatMap { it.aggregates() }
        is Each -> input.aggregates()
        is Get, is Literal, is RefCount, is OccurrenceCount, is All -> emptyList()
    }

    companion object {
        private val READ_KEYS = setOf("of", "in", "path", "via")
        private val ALL_KEYS = setOf("of", "in", "path", "from", "match", "where", "also")
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
         * 値の式の読み取り。current は「いまの要素がある」文脈（スキーマの値の式・ビューの列・each の value）で、of と scopes を省略できる。
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
                // each は {each, value} の形なので、決まった値の {value} より先に見る
                m.containsKey("each") -> {
                    onlyKeys(setOf("each", "value"))
                    val valueRaw = m["value"] ?: error("$where.value（各要素に当てる式）が必要です")
                    // value の中では各要素が「いまの要素」になる（of を省略した読み出しが要素の項目を読む）
                    Each(parse(m["each"], "$where.each", current), parse(valueRaw, "$where.value", current = true))
                }
                m.containsKey("value") -> {
                    onlyKeys(setOf("value"))
                    Literal(m["value"]?.toString())
                }
                m.containsKey("all") -> {
                    onlyKeys(setOf("all"))
                    parseAll(m["all"], "$where.all", current)
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
                    parseAggregate(aggregate, m[aggregate.id], "$where.${aggregate.id}", current)
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
                    onlyKeys(READ_KEYS)
                    val of = m["of"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: if (current) null else error("$where.of（束縛名）が必要です（スキーマの値の式では省略できます＝いまの要素）")
                    val path = PathPattern.parse(m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です"))
                    Get(of, parseSelector(m["in"], "$where.in"), path, parseVia(m["via"], where, current))
                }
            }
        }

        /**
         * 集計の読み取り。引数は配列を返す式。短い書き方（`{path, in, via}`）はいまの要素からの読み出し。`type` は順序の種類
         * （省くと読んだ項目の型から決める）。
         * 古い書き方（`of` で解にわたる集計・`count` に `match`）は残さず、同じ意味の `all` の書き方を文面で案内する。
         */
        private fun parseAggregate(fn: AggregateFn, raw: Any?, where: String, current: Boolean): Aggregate {
            val a = raw as? Map<*, *>
                ?: error("$where はobject（{path: 配列のパス, type: number | date} か、配列を返す式）である必要があります")
            val typeText = a["type"]?.toString()?.takeIf { it.isNotBlank() }
            if (fn == AggregateFn.COUNT) require(typeText == null) { "$where に type は書けません（数えるだけで、値は比べません）" }
            val kind = typeText?.let { OrderKind.parse(it, where) }
            require(!(fn == AggregateFn.SUM && kind?.value != null)) { "$where に type: ${kind?.id} は書けません（${kind?.label}は足せません）" }
            val rest = a.filterKeys { it.toString() != "type" }
            val keys = rest.keys.map { it.toString() }
            if ("of" in keys || "match" in keys) error(oldAggregateHint(fn, rest, where))
            val input: ValueExpr = when {
                keys.isEmpty() -> error("$where には配列を返す式が必要です（{path: \"items[*].amount\"}・{all: ...}・{each: ...} など）")
                keys.all { it in READ_KEYS } -> {
                    // 短い書き方: いまの要素からの読み出し（検査の中にはいまの要素が無い）
                    require(current) { "$where: 検査の中にはいまの要素がありません。解にわたる集計は {all: {of: 束縛名, path: ...}} と書きます" }
                    val pathText = rest["path"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.path が必要です")
                    Get(null, parseSelector(rest["in"], "$where.in"), PathPattern.parse(pathText), parseVia(rest["via"], where, current))
                }
                else -> parse(rest, where, current)
            }
            return Aggregate(fn, input, kind)
        }

        /** 古い集計の書き方への案内（同じ意味の新しい書き方を文面に出す。黙って別の意味にしない）。 */
        private fun oldAggregateHint(fn: AggregateFn, rest: Map<*, *>, where: String): String {
            val of = rest["of"]?.toString()?.takeIf { it.isNotBlank() }
            val path = rest["path"]?.toString()?.takeIf { it.isNotBlank() }
            return if (rest.containsKey("match")) {
                "$where: count に match は書けません。起点を固定した照合の件数は {count: {all: {match: [...], where: [...], of: 束縛名}}} と書きます" +
                    "（ビューでは from を省くと行の要素が起点。検査の中では from に起点の束縛名を書きます）"
            } else {
                val inner = buildString {
                    append("of: $of")
                    rest["in"]?.let { append(", in: ...") }
                    if (path != null) append(", path: $path")
                }
                "$where: 集計の中に of は書けません（解にわたる集計は all で集めます）。{${fn.id}: {all: {$inner}}} と書きます" +
                    "（束縛の要素の中の配列に式を当てるなら {${fn.id}: {each: {of: $of, path: 配列}, value: {path: 項目}}}）"
            }
        }

        /**
         * 集合から配列へ（all）。match が無ければ「同じ起点の全ての解」の形（of が必要）。match があれば起点を固定した照合の形で、
         * from は起点の束縛名（検査の中では必要。検査の外では省略＝いまの要素）、of は集める束縛（省略＝match の最後の bind）。
         * 解は全て集める（limit は書けない）。
         */
        private fun parseAll(raw: Any?, where: String, current: Boolean): All {
            val a = raw as? Map<*, *>
                ?: error("$where はobject（{of: 束縛名, in: 指定, path: 相対パス} か {from, match, where, also, of, in, path}）である必要があります")
            a.keys.map { it.toString() }.firstOrNull { it !in ALL_KEYS }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: ${ALL_KEYS.joinToString(", ")}）") }
            val inDoc = parseSelector(a["in"], "$where.in")
            val path = a["path"]?.toString()?.takeIf { it.isNotBlank() }?.let { PathPattern.parse(it) }
            val of = a["of"]?.toString()?.takeIf { it.isNotBlank() }
            val from = a["from"]?.toString()?.takeIf { it.isNotBlank() }
            if (!a.containsKey("match")) {
                require(from == null) { "$where.from は match と一緒に書きます（同じ起点の全ての解にわたるときは書きません）" }
                return All(of ?: error("$where には of（束縛名）か match（起点を固定した照合）が必要です"), inDoc, path)
            }
            require(current || from != null) { "$where.from（起点の束縛名）が必要です（検査の中にはいまの要素がありません）" }
            val query = QuerySpec.parse(a, where, ignoreKeys = setOf("of", "in", "path", "from")).let { it.copy(limit = Int.MAX_VALUE) }
            require(query.binds.isNotEmpty()) { "$where.match には集める対象の node に bind が必要です" }
            val target = of ?: query.binds.last()
            require(target in query.binds) { "$where.of の束縛「$target」が match に見つかりません" }
            return All(target, inDoc, path, from, query)
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

        /** scopes の1項目。in に指定の配列を書くと、指定ごとに1つずつ（書いた順に）入れ物になる。via は参照を辿る項目。 */
        private fun parseScopes(raw: Any?, where: String, current: Boolean): List<ScopeRef> {
            val m = raw as? Map<*, *> ?: error("$where はobject（{of: 束縛名, in: 文書の指定, via: 参照の項目}）である必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("of", "in", "via") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: of, in, via）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: if (current) null else error("$where.of（束縛名）が必要です")
            val via = parseVia(m["via"], where, current)
            return when (val inRaw = m["in"]) {
                null -> listOf(ScopeRef(of, null, via))
                is List<*> -> inRaw.mapIndexed { i, s -> ScopeRef(of, parseSelector(s, "$where.in[$i]"), via) }
                else -> listOf(ScopeRef(of, parseSelector(inRaw, "$where.in"), via))
            }
        }

        /**
         * 参照を辿る項目（via）。検査の外（スキーマの値の式・ビューの列）だけで書ける——検査の中では、辿るのはクエリの edge の
         * 役目で、式は σ を固定した単一値の計算に留める。
         */
        private fun parseVia(raw: Any?, where: String, current: Boolean): YPath? {
            val text = raw?.toString()?.takeIf { it.isNotBlank() } ?: return null
            require(current) { "$where.via は検査の中では書けません（参照を辿るのはクエリの edge で書きます）" }
            return parseYPath(text)
        }
    }
}
