package schema

/**
 * 参照認識の命名規約（I-02。3層コア移行 SM-001 で SchemaInference.kt から移動）。
 * スキーマなし文書の参照検出（走査カーネル SemanticScanner）とスキーマ推測（R11-01）が
 * 同じ規約を共有する（独自の規約を増やさない。ARCHITECTURE §4.23）。
 */
object RefNaming {
    fun isRefKey(k: String): Boolean = k == "ref" || k.endsWith("_ref")

    fun isRefListKey(k: String): Boolean =
        k == "refs" || k.endsWith("_refs") || k == "depends_on" || k == "relates_to"
}
