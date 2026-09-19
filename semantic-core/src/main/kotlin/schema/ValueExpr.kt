package schema

import model.YPath
import model.parseYPath

/**
 * 値の計算（意味プリミティブ SP-10）と、値を読む文書の指定（SP-01）の宣言モデル（P29 Phase 6 第1段）。
 * 比較のオペランド（check）に書ける式の閉じた一式——利用者が定義する関数・条件分岐・再帰は持たない。
 * YAML の書き方:
 * - 読み出し   `{of: 束縛名, path: 相対パス}`（`in: {schemaId, fileGlob}` で、値を読む文書を指定できる）
 * - 決まった値 `{value: 値}`（値が無ければ「得られない」扱い）
 * - テンプレートの展開 `{format: 式, scopes: [{of: 束縛名, in: 指定 または 指定の配列}, ...]}`
 *   （式が返す文字列の `{パス}` を、scopes の要素の値で近い順に置き換える。置き換えられなければ「得られない」）
 * - 辞書での置き換え `{map: 式, using: {元: 先}}`（辞書に無い値はそのまま）
 * - 最初に得られた値 `{first: [式, ...]}`
 * 評価（どの出現から読むか・得られない値の扱い）は semantic.SemanticQuery の check 判定が担う。
 */
sealed interface ValueExpr {

    /** 束縛からの値の読み出し。inDoc は値を読む文書の指定（null = 同じ ID が書かれた全文書が候補）。 */
    data class Get(val of: String, val inDoc: DerivationSelector?, val path: YPath) : ValueExpr

    /** 決まった値（null は「得られない」）。 */
    data class Literal(val value: String?) : ValueExpr

    /** テンプレートの展開。template が返す文字列のプレースホルダを scopes（近い順）の値で置き換える。 */
    data class Format(val template: ValueExpr, val scopes: List<ScopeRef>) : ValueExpr

    /** 辞書での置き換え（辞書に無い値はそのまま返す）。 */
    data class MapValue(val input: ValueExpr, val using: Map<String, String>) : ValueExpr

    /** 最初に得られた値。 */
    data class First(val options: List<ValueExpr>) : ValueExpr

    /** テンプレートの展開で値を探す入れ物1つ（束縛の、指定に合う文書に書かれた要素）。 */
    data class ScopeRef(val of: String, val inDoc: DerivationSelector?)

    /** この式が読む束縛と文書の指定の組（出現の選び方を決める単位。初出順・重複なし）。 */
    fun sources(): List<ScopeRef> = when (this) {
        is Get -> listOf(ScopeRef(of, inDoc))
        is Literal -> emptyList()
        is Format -> (template.sources() + scopes).distinct()
        is MapValue -> input.sources()
        is First -> options.flatMap { it.sources() }.distinct()
    }

    /**
     * この式の中で「テンプレートの文字列」を読んでいる読み出し（テンプレートの展開 format の対象になる Get）。
     * テンプレートが書かれた文書の側で、プレースホルダが解決できるかを先に点検する（テンプレート健全性）のに使う。
     */
    fun templateReads(): List<Get> = when (this) {
        is Get, is Literal -> emptyList()
        is Format -> template.reads() + template.templateReads()
        is MapValue -> input.templateReads()
        is First -> options.flatMap { it.templateReads() }
    }

    /** この式が値を読む読み出し（入れ子を含む）。 */
    fun reads(): List<Get> = when (this) {
        is Get -> listOf(this)
        is Literal -> emptyList()
        is Format -> template.reads()
        is MapValue -> input.reads()
        is First -> options.flatMap { it.reads() }
    }

    companion object {
        private val GET_KEYS = setOf("of", "in", "path")

        fun parse(raw: Any?, where: String): ValueExpr {
            val m = raw as? Map<*, *>
                ?: error("$where はobjectである必要があります（{of: 束縛名, path: 相対パス} など）")
            fun onlyKeys(allowed: Set<String>) {
                m.keys.map { it.toString() }.firstOrNull { it !in allowed }
                    ?.let { error("$where: 未知のキーです: $it（使えるキー: ${allowed.joinToString(", ")}）") }
            }
            return when {
                m.containsKey("format") -> {
                    onlyKeys(setOf("format", "scopes"))
                    val scopesRaw = m["scopes"] as? List<*> ?: error("$where.scopes（値を探す入れ物の配列）が必要です")
                    Format(
                        template = parse(m["format"], "$where.format"),
                        scopes = scopesRaw.flatMapIndexed { i, s -> parseScopes(s, "$where.scopes[$i]") },
                    )
                }
                m.containsKey("map") -> {
                    onlyKeys(setOf("map", "using"))
                    val using = when (val u = m["using"]) {
                        null -> emptyMap()
                        is Map<*, *> -> u.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
                        else -> error("$where.using はobject（元の値: 置き換える値）である必要があります")
                    }
                    MapValue(parse(m["map"], "$where.map"), using)
                }
                m.containsKey("first") -> {
                    onlyKeys(setOf("first"))
                    val list = m["first"] as? List<*> ?: error("$where.first は式の配列である必要があります")
                    require(list.isNotEmpty()) { "$where.first には1つ以上の式が必要です" }
                    First(list.mapIndexed { i, e -> parse(e, "$where.first[$i]") })
                }
                m.containsKey("value") -> {
                    onlyKeys(setOf("value"))
                    Literal(m["value"]?.toString())
                }
                else -> {
                    onlyKeys(GET_KEYS)
                    val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.of（束縛名）が必要です")
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
        private fun parseScopes(raw: Any?, where: String): List<ScopeRef> {
            val m = raw as? Map<*, *> ?: error("$where はobject（{of: 束縛名, in: 文書の指定}）である必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in setOf("of", "in") }
                ?.let { error("$where: 未知のキーです: $it（使えるキー: of, in）") }
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.of（束縛名）が必要です")
            return when (val inRaw = m["in"]) {
                null -> listOf(ScopeRef(of, null))
                is List<*> -> inRaw.mapIndexed { i, s -> ScopeRef(of, parseSelector(s, "$where.in[$i]")) }
                else -> listOf(ScopeRef(of, parseSelector(inRaw, "$where.in")))
            }
        }
    }
}
