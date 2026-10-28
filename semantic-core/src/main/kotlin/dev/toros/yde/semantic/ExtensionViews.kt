package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

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

    /**
     * 文書に縛られる種類か（flow のように、文書 1 つを射影する）。true なら解決に対象の文書（ViewContext.document）が要り、
     * 台帳のビュータブの一覧には並べない（自分の画面か、文書ビューの区画で描く）。
     */
    val perDocument: Boolean get() = false

    /** 文書に縛られる種類が、この文書に当たるか（schemaId はその文書に当たるスキーマの ID）。 */
    fun appliesTo(document: DocumentId, schemaId: String?): Boolean = false

    /** 解決する。元の表は context から引く。解決できなければ誤りの文で例外を投げる。 */
    fun resolve(context: ViewContext): ExtensionViewResult
}

/**
 * 拡張の種類のビューが解決に使う窓口。呼ぶ側が同じ索引・原本（未保存の編集を重ねたもの）・受け取る値で答える。
 * 表を元にする種類（chart）は grid と rows、文書に縛られる種類（flow）は document と文書・項目・参照の読み出しを使う。
 */
interface ViewContext {
    /** ID から一意なグリッドかクロス集計の定義（無ければ null）。 */
    fun grid(id: String): VirtualGridDef?

    /** 表の行。 */
    fun rows(grid: VirtualGridDef): List<VirtualRow>

    /** 文書に縛られる種類が解決する対象の文書（null＝渡されていない）。 */
    val document: DocumentId? get() = null

    /** 文書の中身。 */
    fun tree(document: DocumentId): YNode? = null

    /** 文書に当たるスキーマの ID。 */
    fun schemaId(document: DocumentId): String? = null

    /** 文書の場所の項目（スキーマから解決したもの）。 */
    fun fieldAt(document: DocumentId, path: YPath): FieldDef? = null

    /** 文書の場所に書かれた参照（索引から）。 */
    fun refsAt(document: DocumentId, path: YPath): List<ScannedRef> = emptyList()

    /** 参照の相手が索引にあるか。 */
    fun resolves(ref: ScannedRef): Boolean = false
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

/** 文書ビューの区画に埋め込んだ拡張の種類のビュー（`chart: {view}` なら kind は chart）。label は種類の名前（区画の既定の見出し）。 */
data class VirtualDocEmbed(val kind: String, val view: String, val label: String = kind)
