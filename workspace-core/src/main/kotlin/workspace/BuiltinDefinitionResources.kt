package workspace

/**
 * 組込み定義体の資源供給（P29 Phase 6。CORE-CALCULUS §25）。
 * 原文（YAML）は resources の builtin-definitions ディレクトリ配下（各 .yaml）が正——検査の定義（functions）と
 * 語彙の定義（vocabulary）だけを持ち、コードは持たない。利用者がワークスペースに置く定義と同じ書き方・
 * 同じ仕組み（schema.CheckLibrary）で読み込まれる（組込みは特権を持たない。CALC-28）。
 */
object BuiltinDefinitionResources {

    /** 資源名（読み込みの順序もこの並び＝語彙の展開順）。検査を定義体へ移すたびに、ここへ1つずつ足す。 */
    val names: List<String> = listOf("consistency")

    /** 1件の原文を読む（読み込み失敗はあり得ない前提。失敗時は例外で気付かせる）。 */
    fun text(name: String): String =
        requireNotNull(BuiltinDefinitionResources::class.java.getResourceAsStream("/builtin-definitions/$name.yaml")) {
            "組込み定義体の資源が見つかりません: $name"
        }.use { it.readBytes().decodeToString() }
}
