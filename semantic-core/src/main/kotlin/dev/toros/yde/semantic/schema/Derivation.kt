package dev.toros.yde.semantic.schema

/**
 * 文書の指定（値の式の `in`・ビューの列の出現の選択・クエリの `in`）: schemaId / fileGlob の AND（null は制限なし）。
 * 同じ ID が複数の文書に書かれているとき、どの文書の出現から読むかを決める。式側の選び方は定義出現優先で 1 つ
 * （semantic.SemanticExpression.select）、検査の σ は指定に合う全ての出現を量化する。
 */
data class DerivationSelector(
    val schemaId: String? = null,
    val fileGlob: String? = null,
) {
    private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

    fun matches(occSchemaId: String?, relPath: String): Boolean =
        (schemaId == null || occSchemaId == schemaId) &&
            (regex == null || regex!!.matches(relPath))

    val isEmpty: Boolean get() = schemaId == null && fileGlob == null
}
