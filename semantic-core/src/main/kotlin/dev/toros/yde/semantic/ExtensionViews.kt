package dev.toros.yde.semantic

// ================================================================ 拡張の種類のビュー（ADR-0014。architecture/views.md「ビューの種類の差し込み口」）
//
// chart・flow のような拡張の種類は、拡張モジュール（view-*）が定義の型と解決を持つ。定義を読む差し込み口は workspace-core の ViewKind。
// ここは、読んだ定義と解決した結果を中核（文書ビューの区画・ダッシュボードの区画・CLI・read_view）が扱うための形で、
// 中核は中身を知らずに ID・件数・値・文だけを使う。

/** 拡張の種類のビュー定義。中身（チャートの系列など）は種類が持つ。 */
interface ExtensionViewDef : VirtualViewDefinition {
    /** kind の値（chart など）。 */
    val viewKind: String

    /** 種類の名前（チャートなど）。誤りの文と一覧に使う。 */
    val kindLabel: String

    /** CLI の view の一覧の 1 件（JSON にできる値）。 */
    fun listing(): Map<String, Any?>

    /** エージェントの read_view の一覧の 1 行（「- ID（名前）: 」に続く部分）。 */
    fun listingText(): String

    /** 解決する。元の表は context から引く。解決できなければ誤りの文で例外を投げる。 */
    fun resolve(context: ViewContext): ExtensionViewResult
}

/** 拡張の種類のビューが解決に使う、中核の表（グリッドとクロス集計）の窓口。呼ぶ側が同じ索引・原本・受け取る値で解決する。 */
interface ViewContext {
    /** ID から一意なグリッドかクロス集計の定義（無ければ null）。 */
    fun grid(id: String): VirtualGridDef?

    /** 表の行。 */
    fun rows(grid: VirtualGridDef): List<VirtualRow>
}

/** 拡張の種類のビューを解決した結果。描き方は画面の側（種類ごとの描画）が知る。 */
interface ExtensionViewResult {
    val def: ExtensionViewDef

    /** ダッシュボードのタイルの件数（数の文字）。 */
    val count: String

    /** 件数の単位（項目など）。 */
    val unit: String

    /** CLI と read_view の値（JSON にできる値）。 */
    fun report(errors: List<String> = emptyList()): Map<String, Any?>

    /** エージェントへの開示テキスト。 */
    fun text(): String
}

/** 文書ビューの区画に埋め込んだ拡張の種類のビュー（`chart: {view}` なら kind は chart）。 */
data class VirtualDocEmbed(val kind: String, val view: String)
