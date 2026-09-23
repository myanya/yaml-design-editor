package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.SemanticRewrite

/**
 * 値の式を受け取る項目の値（P31・R31-05）。項目の定義に `expression: true` の印を付けると、その値は値の式でもよい——
 * 短い書き方（`{パス}` を含む文字列）でも、完全な書き方（式の object）でも。式が返す値の型が、その項目の type。
 * どの項目が式を受け取るかは項目の定義（メタスキーマ・利用者のスキーマ）だけが知り、読み取り（ExpressionFields）・
 * 評価（semantic.SemanticValues）・文面（ExpressionMessages）は共通。使う側は「その場所の式」を引いて、自分の環境で評価する。
 */
data class ExpressionValue(
    /** 書かれたままの文字（object で書いたときは、その YAML）。表示用。 */
    val text: String,
    val expr: ValueExpr,
    /** 場所の表示（例 filePattern・detail.filePattern）。文面に使う。 */
    val where: String,
    /** 項目の表示名（項目の定義の label。無ければ where）。文面に使う。 */
    val label: String? = null,
    /** 短い書き方（文字列）で書かれたか。 */
    val isString: Boolean = true,
) {
    /** いまの要素から読む項目の名前（予約の id・title を含む。with で付けた名前は含まない）。 */
    val reads: List<String> get() = expr.currentReads()

    /** 予約の `{id}`（または `{path: id}`）を使っているか。 */
    val usesId: Boolean get() = "id" in reads

    companion object {
        /** 書かれた値から作る。式として読めなければ IllegalArgumentException（文面は ExpressionMessages.unreadable）。 */
        fun of(raw: Any?, where: String, label: String? = null): ExpressionValue {
            val text = when (raw) {
                null -> ""
                is String -> raw
                else -> YamlIO.dumpPlain(YamlIO.fromAny(raw)).trim()
            }
            val expr = try {
                ValueExpr.parseValue(raw, where)
            } catch (e: Exception) {
                throw IllegalArgumentException(ExpressionMessages.unreadable(label, where, e.message), e)
            }
            return ExpressionValue(text, expr, where, label, raw is String)
        }
    }
}

/** 式に関わる文面の1つの表（どのエラーのときに、どの文面か）。 */
object ExpressionMessages {
    private fun name(label: String?, where: String) = label?.let { "「$it」（$where）" } ?: "「$where」"

    /** 式として読めない（定義の読み込み・文書の検証）。 */
    fun unreadable(label: String?, where: String, reason: String?): String =
        "${name(label, where)}の式が読めません: ${reason ?: "不明な誤り"}"

    /** 穴が埋まらない（評価。使う側が、作成などをエラーにする）。 */
    fun unfilled(label: String?, where: String, missing: List<String>, text: String): String =
        "${name(label, where)}の穴が埋まりません（${missing.joinToString("・") { "{$it}" }}。元の要素にその値が書かれていません）: $text"
}

/**
 * 式を受け取る項目の値を、文書の木から集める共通処理。スキーマの木（`expression: true` の印）に沿って文書を辿り
 * （SemanticRewrite.walkDocument＝スキーマ誘導の走査の1実装）、印のある項目に値があれば式にする。
 * スキーマ文書（メタスキーマの印）も、ビュー定義（builtin-view の印）も、利用者の文書（利用者のスキーマの印）も、同じ関数を通る。
 */
object ExpressionFields {

    /** 読み取りの結果: 場所 → 式と、式として読めなかった場所とその文面。 */
    class Result(val values: Map<YPath, ExpressionValue>, val errors: List<Pair<YPath, String>>)

    /** スキーマの木に、印のある項目が1つでもあるか（無ければ文書を辿らない）。 */
    fun declares(tree: SchemaTree): Boolean {
        val seen = mutableSetOf<DefinitionKey>()
        fun has(f0: FieldDef): Boolean {
            tree.definitionTarget(f0)?.let { (key, _) -> if (!seen.add(key)) return false }
            val f = tree.resolve(f0) ?: f0
            if (f.expression) return true
            return f.fields.any(::has) || f.item?.let(::has) == true || f.value?.let(::has) == true ||
                f.cases.values.any { list -> list.any(::has) }
        }
        return has(tree.root) || tree.definitions.values.any(::has)
    }

    /** 印のある項目の値を集める。読めない値があれば、最初のものを IllegalArgumentException にする（定義の読み込み用）。 */
    fun read(root: YNode?, tree: SchemaTree): Map<YPath, ExpressionValue> {
        val result = readAll(root, tree)
        result.errors.firstOrNull()?.let { (_, message) -> throw IllegalArgumentException(message) }
        return result.values
    }

    /** 印のある項目の値を集める（読めない値は文面にして続ける。文書の検証用）。 */
    fun readAll(root: YNode?, tree: SchemaTree): Result {
        if (root == null || !declares(tree)) return Result(emptyMap(), emptyList())
        val values = LinkedHashMap<YPath, ExpressionValue>()
        val errors = mutableListOf<Pair<YPath, String>>()
        SemanticRewrite.walkDocument(tree, root, onField = { path, field ->
            val eff = tree.resolve(field) ?: field
            if (!eff.expression) return@walkDocument
            val node = getAt(root, path) ?: return@walkDocument
            val where = path.pretty()
            runCatching { ExpressionValue.of(YamlIO.toAny(node), where, eff.label) }
                .onSuccess { values[path] = it }
                .onFailure { errors += path to (it.message ?: ExpressionMessages.unreadable(eff.label, where, null)) }
        })
        return Result(values, errors)
    }
}
