package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.ActionRequire
import dev.toros.yde.semantic.schema.CheckLibrary
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.NumberDisplay
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.idGlobToRegex
import dev.toros.yde.semantic.schema.QueryEdgeStep
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TemplateSite
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.semantic.schema.globToRegex

/**
 * 仮想ビュー（グリッド）のカーネル（6E 第1弾・RC-01。§17-⑥「仮想文書」方向の読み出しモデル）。
 * ビューは物理文書の**写像**であり、データを持たない（原本主義）。各セルは原本座標
 * （DocumentId × YPath）＝来歴（origin）へ解決され、編集は常に原本への通常経路で行う。
 *
 * - 解決はエフェメラルな読み出しモデル（呼び出しごとに構築・保持しない）。セルの value が
 *   原本ツリーのノード参照を持つのは SemanticAspects と同じ「読み出しモデル族」の例外
 *   （ADR-0003 帰結の注記）で、snapshot には決して保持しない
 * - 行の集合・被参照・計算列は SemanticSnapshot と同じ入力（ids / refs / graph）から解決する。
 *   UI は overlay 済みの索引、エージェントはステージングの入力を渡すことで同じ射影を得る
 * - 宣言の読み取りとリントは workspace-core の workspace/ViewDefinitions.kt（リントが、作り先に合うスキーマを選ぶため。
 *   P27 段D の D-c で root から移した）。ここは、宣言の型と解決を持つ
 */

// ---------------------------------------------------------------- 宣言（カーネル座標）

/** ビュー定義の共通部。kind により横断グリッド（VirtualGridDef）と文書ビュー（VirtualDocViewDef）に分かれる。 */
sealed interface VirtualViewDefinition {
    val id: String
    val name: String
    val description: String?

    /** ビュー定義文書の座標。 */
    val source: DocumentId
}

/** 列が値を取り出す出現の選択条件。schemaId / fileGlob の AND（null は制限なし）。 */
data class VirtualViewSource(
    val schemaId: String? = null,
    val fileGlob: String? = null,
) {
    private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

    fun matches(entry: ScannedId, rel: String): Boolean =
        (schemaId == null || entry.schemaId == schemaId) &&
            (regex == null || regex!!.matches(rel))

    val isEmpty: Boolean get() = schemaId == null && fileGlob == null
}

/** バックリンク列の条件。行IDへの参照（被参照）を関係種別・参照元スキーマで絞る。 */
data class VirtualBacklinksDef(
    val relation: String? = null,
    val schemaIds: List<String> = emptyList(),
) {
    fun matches(ref: ScannedRef): Boolean =
        (relation == null || ref.relation == relation) &&
            (schemaIds.isEmpty() || ref.sourceSchemaId in schemaIds)
}

/**
 * ビューの1列。出現要素からの相対YPathで値を指すか、backlinks / value（値の式）/ of（クエリ束縛）の列にする。
 * value は列の値を値の式で決める読み取り専用の派生列（from で選んだ行の出現の要素が「いまの要素」）。
 */
data class VirtualViewColumn(
    val label: String,
    val from: VirtualViewSource = VirtualViewSource(),
    val path: YPath,
    val editable: Boolean = false,
    val backlinks: VirtualBacklinksDef? = null,
    val value: ExpressionValue? = null,
    val of: String? = null,
    /** 数の表示の形（grouped＝桁区切り。画面の見せ方だけで、CLI や書き出しの値は変えない）。path の列は項目の側の宣言も効く。 */
    val display: NumberDisplay? = null,
    /** 表示の形（DecimalFormat / DateTimeFormatter のパターン。ValueFormat）。画面の見せ方だけ。path の列は項目の側の format も効く。 */
    val format: String? = null,
    /** 要素ごとに列を広げる宣言（columns の each）。非 null なら、解決のときに索引の要素ごとの列に広がる（VirtualViews.lower）。 */
    val each: VirtualEachDef? = null,
)

/**
 * 要素ごとに列を広げる（columns の each）: idPattern（と schemaIds）に合う索引の要素ごとに 1 列（見出しはタイトル・並びは order の出現の文書内順）。
 * 値の式（rawValue＝書かれたまま）の中の `$bind`（例 `node: { id: $cat }`）が、列ごとにその要素の ID に置き換わる。
 */
data class VirtualEachDef(
    val idPattern: String,
    val schemaIds: List<String> = emptyList(),
    val order: VirtualViewSource? = null,
    val bind: String,
    val rawValue: Any?,
)

/**
 * 行の並びの値（order.path）: 並び基準の出現（order の schemaId / fileGlob。無ければ行のどの出現でも）から読んだ値の順。
 * kind は順序の種類（number / date / time / datetime。null＝読む項目の型から決め、決まらなければ文字の順）、descending は向き。
 * 値の読めない行は向きによらず後ろ。
 */
data class VirtualOrderValue(
    val path: YPath,
    val kind: dev.toros.yde.semantic.schema.ValueExpr.OrderKind? = null,
    val descending: Boolean = false,
)

// ---------------------------------------------------------------- クロス集計（kind: matrix）

/** 列の軸への対応: セルの要素の参照先（via）を列にするか、要素の値（path）を列にするか。 */
sealed interface VirtualMatrixKey {
    /** 参照先のエンティティが列。列の集まりは idPattern（と schemaIds）に合う索引の ID で、見出しはタイトル。order は列の並び（出現の文書内順）。 */
    data class Ref(
        val via: String,
        val idPattern: String,
        val schemaIds: List<String> = emptyList(),
        val order: VirtualViewSource? = null,
    ) : VirtualMatrixKey

    /** 要素の値が列。values は列にする値の並び（値と見出し）。 */
    data class Value(val path: YPath, val pathText: String, val values: List<Pair<String, String?>>) : VirtualMatrixKey

    /**
     * 要素ごとに値の式で計算した値が列（P46。列は解決のときにデータから広げ、値の順序の種類で並べる）。
     * label は見出しの式（同じ要素で計算。null＝値の文字）、order は先に並べる値（書かない値はその後ろに値の順）。
     */
    data class Expr(val value: ExpressionValue, val label: ExpressionValue? = null, val order: List<String> = emptyList()) : VirtualMatrixKey
}

/**
 * セルの集計: 行の要素を起点に固定した照合（値の式の all の match の形）で集めた要素を、列ごとに絞って畳む。
 * all は書かれたまま（match / where / also / of / in / path）を持ち、列ごとの絞りを足して値の式にする（評価は値の式の列と同じ）。
 */
data class VirtualMatrixCells(
    val all: Map<String, Any?>,
    val of: String,
    val aggregate: dev.toros.yde.semantic.schema.ValueExpr.AggregateFn,
    /** 順序の種類（null＝集計が読む項目の型から）。 */
    val kind: dev.toros.yde.semantic.schema.ValueExpr.OrderKind?,
    val key: VirtualMatrixKey,
)

/** クロス集計の宣言（VirtualGridDef.matrix）。列を広げたグリッドに下ろして解決する（lower）。 */
data class VirtualMatrixDef(
    val cells: VirtualMatrixCells,
    /** 合計の列と行。 */
    val totals: Boolean = true,
    /** 列の軸に当たらない要素の列の見出し（null＝出さない）。 */
    val unclassified: String? = "（未分類）",
    /** セルの数の見せ方（桁区切り）。 */
    val display: NumberDisplay? = null,
    /**
     * 行の軸を値の式で広げる（P46。`rows: {value: 式}`）: 照合の起点の要素ごとに式を計算し、同じ値を 1 行にまとめる。
     * null＝行は rows.idPattern の論理 ID。
     */
    val rowsValue: ExpressionValue? = null,
) {
    /** 行か列の軸を値の式で広げるか（軸は解決のときに値から決まり、ヘッダは解決した行の列から取る＝VirtualViews.columnsOf）。 */
    val usesPivot: Boolean get() = rowsValue != null || cells.key is VirtualMatrixKey.Expr
}

/** 行の集合の定義。idPattern に一致する論理IDが行になる。 */
data class VirtualRowsDef(
    val idPattern: String,
    val schemaIds: List<String> = emptyList(),
)

/** 解決した行と、クエリ行の打ち切り（`limit` を書いたときだけ。total は打ち切り前の全行数、limit は書いた上限）。 */
data class ResolvedRows(val rows: List<VirtualRow>, val total: Int = rows.size, val limit: Int? = null) {
    /** 書いた上限で切れたか。 */
    val truncated: Boolean get() = limit != null && total > rows.size
}

/** 詳細文書の扱い。source に一致する出現を「詳細」と見なす。 */
data class VirtualDetailDef(
    val source: VirtualViewSource = VirtualViewSource(),
    /** 「作成」の作り先（式を受け取る項目の値。いまの要素＝行の要素。P31）。 */
    val filePattern: ExpressionValue? = null,
)

/** 新規行の入口。配列モード（file + path）とファイルモード（filePattern）のどちらか一方を持つ。 */
data class VirtualNewRowDef(
    val file: String? = null,
    val path: YPath = emptyList(),
    /** ファイルモードの作り先（式を受け取る項目の値。行がまだ無いので、書ける穴は {id} だけ。P31）。 */
    val filePattern: ExpressionValue? = null,
)

/** グリッドビューの宣言（カーネル座標。root の ViewDef は、この型の別名）。 */
data class VirtualGridDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    /** 従来の行定義（idPattern×schemaIds）。クエリ行（rowsQuery）とは排他で、どちらか一方が非null。 */
    val rows: VirtualRowsDef? = null,
    /** クエリ行（rows: match。R22-02）: 行 = パターン照合の束縛行。 */
    val rowsQuery: QuerySpec? = null,
    /** クエリ行の行キーの束縛名（rowsQuery 非null のとき非null）。 */
    val rowKey: String? = null,
    val columns: List<VirtualViewColumn>,
    val orderBy: VirtualViewSource? = null,
    /** 値の順（order.path）。orderBy は並び基準の出現の選び方として使う。 */
    val orderValue: VirtualOrderValue? = null,
    val detail: VirtualDetailDef? = null,
    val newRow: VirtualNewRowDef? = null,
    override val source: DocumentId,
    /** クロス集計（kind: matrix）。非 null なら columns は空で、列は解決のときに索引から広げる（VirtualViews.lower）。 */
    val matrix: VirtualMatrixDef? = null,
    /** 受け取る値の名前（`params`。クエリ行の rows.where とクロス集計の cells.where に `$名前`。ダッシュボードの入力から渡る）。 */
    val params: List<String> = emptyList(),
    /** クエリ行の原文（params を差し込んで読み直すため。params が無ければ null）。 */
    val rowsRaw: Map<String, Any?>? = null,
) : VirtualViewDefinition

// ---------------------------------------------------------------- 文書ビューの宣言（カーネル座標。RC-02）

/** 被参照表の1列。参照元要素からの相対YPathで値を取り出す（表示専用）。 */
data class VirtualDocBacklinkColumn(val label: String, val path: YPath)

/**
 * 被参照表の定義。文書内の対象ID（idPattern。null = 文書内の全ID）ごとに、
 * そのIDへの参照（被参照）を relation・参照元スキーマで絞って一覧する。
 */
data class VirtualDocBacklinksDef(
    val idPattern: String? = null,
    val filter: VirtualBacklinksDef = VirtualBacklinksDef(),
    val columns: List<VirtualDocBacklinkColumn> = emptyList(),
)

/**
 * 表の埋め込み（複合文書ビュー 段階 1。`grid: {view, params}`）: グリッドかクロス集計のビューを区画に置き、
 * 受け取る値（表の `params`）には、開いた文書の値（文書ルートからのパス）を差し込む。
 */
data class VirtualDocGrid(
    val view: String,
    /** 受け取る値の名前 → 開いた文書の中のパス（値が無ければ区画は解決しない）。 */
    val params: Map<String, YPath> = emptyMap(),
    /** 見せる列の見出し（この順。空＝表の全部の列）。文書の中では全行が同じ値の列（テーブルID など）を外すのに使う。 */
    val columns: List<String> = emptyList(),
)

/**
 * 文書ビューのセクション1件。fields（フォーム射影）・backlinks（被参照表）・grid（表の埋め込み）・embed（拡張の種類）のどれか一方を持つ。
 * fields に `in`（fieldsIn）を書いた区画は、開いた文書ではなく「同じ ID の別の出現（側面）」のフォーム（複合文書ビュー 段階 1）。
 */
data class VirtualDocSection(
    val label: String? = null,
    val fieldsPath: YPath? = null,
    val fieldsDefinition: String? = null,
    val fieldsInclude: List<String> = emptyList(),
    val fieldsExclude: List<String> = emptyList(),
    val backlinks: VirtualDocBacklinksDef? = null,
    /** 埋め込む拡張の種類のビュー（`flow: {view}`・`chart: {view}` など。ADR-0014）。 */
    val embed: VirtualDocEmbed? = null,
    /** 同じ ID の別の出現（側面）を編集する区画の、出現の選び方（`fields.in`）。null＝開いた文書の fields。 */
    val fieldsIn: DerivationSelector? = null,
    /** 表の埋め込み（`grid: {view, params}`）。 */
    val grid: VirtualDocGrid? = null,
) {
    /** 開いた文書そのもののフォーム区画か（別の出現の区画・表・被参照・埋め込みではない）。 */
    val isLocalFields: Boolean get() = fieldsPath != null && fieldsIn == null
}

/** 要素単位アタッチ。at パターンに一致する要素の展開フォーム内へ、その要素のIDの被参照表を差し込む。 */
data class VirtualDocAttachment(
    val label: String? = null,
    val atText: String,
    val at: List<Any>,
    val backlinks: VirtualDocBacklinksDef,
) {
    /** 要素パスがこのアタッチの対象か。`[*]`（AnyIndex）は任意のインデックスに一致する（schema.PathPattern）。 */
    fun matches(path: YPath): Boolean = PathPattern.matches(at, path)
}

/**
 * 文書ビューの宣言（kind: document。カーネル座標）。match（glob）／schemaId で適用先文書を決め、sections を上から順に描画する。
 * マッチしない文書は従来のスキーマ駆動フォーム＝暗黙の既定ビューで表示される（opt-in）。
 */
data class VirtualDocViewDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    val match: String? = null,
    val schemaId: String? = null,
    val sections: List<VirtualDocSection>,
    val attachments: List<VirtualDocAttachment> = emptyList(),
    override val source: DocumentId,
) : VirtualViewDefinition {
    private val regex: Regex? by lazy { match?.let { runCatching { globToRegex(it) }.getOrNull() } }

    /** ワークスペース相対パス（区切りは '/'）の文書にこのビューを適用するか。 */
    fun appliesTo(relPath: String, schemaId: String?): Boolean =
        (match == null || regex?.matches(relPath) == true) &&
            (this.schemaId == null || this.schemaId == schemaId)
}

// ---------------------------------------------------------------- 解決結果（読み出しモデル）

/** バックリンク列の1件。参照元要素を含む最も近いID（なければ null）と、参照値の原本座標。 */
data class VirtualBacklink(
    val sourceId: String?,
    val sourceTitle: String?,
    val document: DocumentId,
    val path: YPath,
    val relation: String?,
    val attributes: String? = null,
)

/** 計算セルの入力1件: プレースホルダの解決値と、入力値の原本座標（未解決なら座標なし）。 */
data class VirtualComputedInput(
    val placeholder: String,
    val value: String?,
    val document: DocumentId?,
    val path: YPath?,
)

/** 計算セルの来歴: 元値（ひな型・集計の材料。得られなければその理由）と、入力ごとの解決。 */
data class VirtualComputedTrace(
    val raw: String,
    val inputs: List<VirtualComputedInput>,
)

/**
 * 解決済みのセル。document が null のときは対応する出現がない（値なし）。
 * value は原本ツリーのノード参照（エフェメラル読み出しモデル。保持しない）。
 */
data class VirtualCell(
    val column: VirtualViewColumn,
    val document: DocumentId?,
    val path: YPath?,
    val value: YNode?,
    val backlinks: List<VirtualBacklink>? = null,
    val computed: Boolean = false,
    val trace: VirtualComputedTrace? = null,
    /** 計算セルの値の順序の種類（読んだ項目の型・集計の宣言から。合計の行が同じ種類で畳むために持つ）。 */
    val kind: dev.toros.yde.semantic.schema.ValueExpr.OrderKind? = null,
)

/** 文書ビューの被参照1件。参照元要素のID・タイトルと、参照値の原本座標＝ジャンプ先。 */
data class VirtualDocBacklinkItem(
    val sourceId: String?,
    val sourceTitle: String?,
    val document: DocumentId,
    val refPath: YPath,
    val relation: String?,
    val attributes: String? = null,
    val values: List<YNode?> = emptyList(),
)

/** 被参照表の1行（＝文書内の対象ID 1件）。 */
data class VirtualDocBacklinkRow(
    val id: String,
    val title: String?,
    val path: YPath,
    val backlinks: List<VirtualDocBacklinkItem>,
)

/**
 * 解決済みの文書ビューセクション。rows は backlinks セクションのみ非null。
 * 複合文書ビュー（段階 1）の区画は、変換のひな型（写し `$copy`・ビューの行 `$view`）を組んで評価した結果を持つ:
 * term＝組み立てた項（別の出現の写し・表の行）、origins＝出どころ（出力の場所 → 原本の文書と場所）。
 * 編集は出どころの座標へ結ぶ（`fields.in` の区画は occurrence＝原本 1 か所の部分木なので、そのフォームをそのまま編集できる）。
 */
data class VirtualDocSectionResolved(
    val section: VirtualDocSection,
    val rows: List<VirtualDocBacklinkRow>? = null,
    /** `fields.in` の区画: 同じ ID の別の出現（無ければ null で reason に理由）。 */
    val occurrence: ScannedId? = null,
    /** 変換で組み立てた区画の項（`fields.in` は出現の写し・`grid` は行の配列）。無ければ null。 */
    val term: YNode? = null,
    /** 項の出どころ（出力の場所 → 原本の文書と場所）。表の行は派生なので持たない。 */
    val origins: List<TransformOrigin> = emptyList(),
    /** `grid` の区画: 開いた文書から読んだ受け取る値（名前 → 値）。 */
    val gridParams: Map<String, Any?>? = null,
    /** `grid` の区画: 受け取る値で解決した表の行（表が無い・値が無いときは null で reason に理由）。 */
    val gridRows: List<VirtualRow>? = null,
    /** `grid` の区画: 解決に使った表の定義。 */
    val gridView: VirtualGridDef? = null,
    /** 解決できなかった理由（ルートに ID が無い・出現が無い・表が無い・受け取る値が無い）。 */
    val reason: String? = null,
)

/** 解決済みの要素単位アタッチ。rows の各行が「差し込み先の要素（path）とその被参照」。 */
data class VirtualDocAttachmentResolved(
    val attachment: VirtualDocAttachment,
    val rows: List<VirtualDocBacklinkRow>,
)

/** 解決済みの1行（＝1論理ID。クエリ行では行キー束縛のID）。 */
data class VirtualRow(
    val id: String,
    val title: String?,
    val occurrences: List<ScannedId>,
    val cells: List<VirtualCell>,
    val detailDocument: DocumentId?,
    val detailPath: YPath?,
    /** 行の同一性キー（クエリ行では束縛値の組で一意化。従来の行では id と同じ）。 */
    val key: String = id,
    /** クエリ行の束縛（束縛名 → ノードID。従来の行では null）。 */
    val bindings: Map<String, String>? = null,
    /** 値の軸の行（クロス集計の `rows.value`。id は値そのもので、原本の出現を持たない。合計の行ではない）。 */
    val valueRow: Boolean = false,
)

// ---------------------------------------------------------------- 解決

object VirtualViews {

    /** 属性付き参照の1行注記（例「role: 承認者」。ルート RefEntry.attributeSummary と同じ書式）。 */
    fun attributeSummary(ref: ScannedRef): String? = ref.attributeSummary()

    /**
     * ビューを行×セルに解決する。
     * - ids / refs / graph: 行の集合と出現の解決（SemanticSnapshot または overlay 済みの同形入力）
     * - rel: ワークスペース相対パス（fileGlob 判定。セッション経路では DocumentId.value そのもの）
     * - tree: セル値の読み取り（呼び出し側が未保存編集・ステージングを注入する）
     * - fieldAt: 値を読んだ場所の項目の定義（値の式の順序の種類と、行の並びの種類を項目の型から決める。無ければ `type` の宣言だけ）
     */
    fun resolve(
        viewDef: VirtualGridDef,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
        params: Map<String, Any?> = emptyMap(),
    ): List<VirtualRow> = resolveDetailed(viewDef, ids, refs, graph, rel, tree, fieldAt, params).rows

    /**
     * 受け取る値（params）を差し込む: クエリ行の原文とクロス集計の cells に書いた `$名前` を値に置き換える。
     * 値の無い名前を使う条件（where）は外す（空＝絞らない。ダッシュボードの入力と同じ決まり＝ActionRequire.prune）。
     */
    fun bindParams(view: VirtualGridDef, params: Map<String, Any?>): VirtualGridDef {
        if (view.params.isEmpty()) return view
        val given = params.filterKeys { it in view.params }.filterValues { it != null && it.toString().isNotBlank() }
        val missing = view.params.filter { it !in given }.toSet()
        @Suppress("UNCHECKED_CAST")
        fun bind(raw: Map<String, Any?>): Map<String, Any?> =
            CheckLibrary.substitute(ActionRequire.prune(raw, missing), given) as Map<String, Any?>
        val rowsQuery = view.rowsRaw?.let { raw ->
            QuerySpec.parse(bind(raw), "rows", ignoreKeys = setOf("rowKey")).let { if (raw.containsKey("limit")) it else it.copy(limit = Int.MAX_VALUE) }
        } ?: view.rowsQuery
        val matrix = view.matrix?.let { m -> m.copy(cells = m.cells.copy(all = bind(m.cells.all))) }
        return view.copy(rowsQuery = rowsQuery, matrix = matrix)
    }

    /**
     * resolve と同じ。クエリ行に `limit` を書いたときの打ち切り（全行数）も返す（黙って切れないように）。
     * params は受け取る値（表の `params` に `$名前` で書いた条件へ差し込む。値の無い名前の条件は外れる＝絞らない）。
     */
    fun resolveDetailed(
        definition: VirtualGridDef,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        fieldAt: FieldResolver = NO_FIELDS,
        params: Map<String, Any?> = emptyMap(),
    ): ResolvedRows {
        val viewDef = bindParams(definition, params)
        val view = lower(viewDef, ids, rel)  // クロス集計は、列を広げたグリッドとして解決する
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun cachedTree(document: DocumentId): YNode? = trees.getOrPut(document) { tree(document) }
        // 軸を値の式で広げるクロス集計は、行・列ともデータから決める（P46）
        viewDef.matrix?.takeIf { it.usesPivot }?.let { return ResolvedRows(resolvePivot(viewDef, it, ids, refs, graph, rel, ::cachedTree, fieldAt)) }
        fun occurrencesOf(id: String): List<ScannedId> = ids[id].orEmpty()

        // バックリンク列の「参照元要素のID」解決。参照位置を含む最も長いパスの出現を最内のIDとみなす
        val entriesByDoc: Map<DocumentId, List<ScannedId>> by lazy { ids.values.flatten().groupBy { it.document } }
        fun backlinksFor(id: String, def: VirtualBacklinksDef): List<VirtualBacklink> =
            refs.filter { it.targetId == id && def.matches(it) }
                .map { ref ->
                    val source = entriesByDoc[ref.document].orEmpty()
                        .filter { it.path.size <= ref.path.size && ref.path.subList(0, it.path.size) == it.path }
                        .maxByOrNull { it.path.size }
                    VirtualBacklink(
                        source?.id, source?.title, ref.document, ref.path, ref.relation, attributeSummary(ref),
                    )
                }
                .sortedWith(compareBy({ rel(it.document) }, { pathSortKey(it.path) }))

        /**
         * 値の式の列（読み取り専用の派生列＝計算セル）: 行の要素（列の schemaId / fileGlob で選んだ出現。式側の選び方＝定義出現優先）を
         * 「いまの要素」として、値の式を 1 つの環境で評価する（SemanticValues＝検査の check と同じ評価器。整合検証の期待値と同じ解決）。
         * セルの座標は値の出どころ（ひな型の場所・読み出した値の場所・最小と最大で選ばれた要素。合計は無し）。来歴は全ての材料。
         * 得られなければ値なしで、理由を来歴の元値に残す（表示で気づける）。クエリ行では束縛（of）も読める（割当ては渡さず、
         * 定義出現優先の選び方に任せる）。集合から値へ落とす all には、行を 1 つの解と見なした解の一覧（クエリ行）と、
         * 行の要素を起点に固定した照合（SemanticQuery.matchesFrom）を渡す。値が配列ならセルの値は配列の項。
         */
        fun valueCell(
            column: VirtualViewColumn,
            value: ExpressionValue,
            node: SemanticQueryNodeRef,
            occurrences: List<ScannedId>,
            nodes: Map<String, SemanticQueryNodeRef>,
            solutions: List<SemanticQueryRow>,
        ): VirtualCell {
            val selector = DerivationSelector(column.from.schemaId, column.from.fileGlob)
            val currentOccurrence = when (node.kind) {
                QueryNodeKind.ENTITY -> SemanticExpression.select(occurrences, selector, rel)
                    ?.let { SemanticExpression.Occurrence(it.document, it.path) }
                QueryNodeKind.DOCUMENT -> node.document?.let { SemanticExpression.Occurrence(it, emptyList()) }
            }
            val env = SemanticValues.Environment(
                tree = ::cachedTree, rel = rel,
                current = node, currentOccurrence = currentOccurrence,
                nodes = nodes, graph = graph,
                refsInto = { id -> refs.filter { it.targetId == id } },
                solutions = { solutions },
                solveFrom = { origin, spec -> SemanticQuery.matchesFrom(ids, refs, graph, spec, origin.id, fieldAt, ::cachedTree) },
                reserved = buildMap {
                    put(SemanticValues.RESERVED_ID, node.id)
                    node.title?.let { put(SemanticValues.RESERVED_TITLE, it) }
                },
                fieldAt = fieldAt,
            )
            val result = SemanticValues.evaluateDetailed(value.expr, env)
            val side = result.side
            val raw = side?.raw ?: result.reason
                ?: result.missing.takeIf { it.isNotEmpty() }?.let { m -> "埋まらない穴: " + m.joinToString("・") { "{$it}" } }
                ?: value.text
            return VirtualCell(
                column = column,
                document = side?.sourceDocument,
                path = side?.sourcePath,
                value = side?.let { it.node ?: YNode.Scalar(it.text) },
                computed = true,
                trace = VirtualComputedTrace(
                    raw = raw,
                    inputs = side?.inputs.orEmpty().map { VirtualComputedInput(it.placeholder, it.value, it.document, it.path) },
                ),
                kind = side?.kind,
            )
        }

        /** 従来の行（1 論理 ID）を、値の式の「いまの要素」として表すノード。 */
        fun entityNode(id: String, occurrences: List<ScannedId>): SemanticQueryNodeRef = SemanticQueryNodeRef(
            kind = QueryNodeKind.ENTITY,
            id = id,
            title = occurrences.firstNotNullOfOrNull { it.title },
            entityType = occurrences.firstNotNullOfOrNull { it.entityType },
            document = occurrences.firstOrNull()?.document,
            path = occurrences.firstOrNull()?.path ?: emptyList(),
        )

        // クエリ行（rows: match）: 行 = パターン照合の束縛行。並びはクエリの決定的順序で、order を書いたときだけ
        // 行キーの要素の出現で並べ直す（sortRows。同じ並びの行はクエリの順のまま）
        val query = view.rowsQuery
        if (query != null) {
            val rowKeyName = view.rowKey ?: query.binds.first()
            val ordered = view.orderBy != null || view.orderValue != null
            // limit を書いたときだけ打ち切りがありうる（書かなければ上限なし＝Int.MAX_VALUE）。order があれば並べてから切る
            val result = SemanticQuery.run(ids, refs, graph, if (ordered) query.copy(limit = Int.MAX_VALUE) else query, fieldAt, ::cachedTree)
            val rows = result.rows.map { qr ->
                val keyNode = qr.bindings.getValue(rowKeyName)
                val occurrences = occurrencesOf(keyNode.id)
                val cells = view.columns.map { column ->
                    val node = qr.bindings[column.of ?: rowKeyName]
                    val backlinksDef = column.backlinks
                    val valueDef = column.value
                    when {
                        node == null -> VirtualCell(column, null, null, null)
                        backlinksDef != null -> VirtualCell(column, null, null, null, backlinks = backlinksFor(node.id, backlinksDef))
                        valueDef != null -> valueCell(column, valueDef, node, occurrencesOf(node.id), qr.bindings, listOf(qr))
                        node.kind == QueryNodeKind.DOCUMENT -> {
                            // 文書ノードのセル: 文書ルートからの相対パス（座標はその文書＝通常の原本編集経路）
                            val document = node.document
                            if (document == null) VirtualCell(column, null, null, null)
                            else VirtualCell(column, document, column.path, cachedTree(document)?.let { getAt(it, column.path) })
                        }
                        else -> {
                            val occurrence = occurrencesOf(node.id).firstOrNull { column.from.matches(it, rel(it.document)) }
                            if (occurrence == null) VirtualCell(column, null, null, null)
                            else {
                                val absolute = occurrence.path + column.path
                                VirtualCell(column, occurrence.document, absolute, cachedTree(occurrence.document)?.let { getAt(it, absolute) })
                            }
                        }
                    }
                }
                val detailOccurrence = view.detail?.let { d ->
                    occurrences.firstOrNull { o -> d.source.matches(o, rel(o.document)) }
                }
                VirtualRow(
                    id = keyNode.id,
                    title = keyNode.title ?: occurrences.firstNotNullOfOrNull { it.title },
                    occurrences = occurrences,
                    cells = cells,
                    detailDocument = detailOccurrence?.document,
                    detailPath = detailOccurrence?.path,
                    key = qr.bindings.entries.joinToString("|") { (k, v) -> "$k=${v.id}" },
                    bindings = qr.bindings.mapValues { it.value.id },
                )
            }
            val shown = if (ordered) sortRows(view, rows, rel, ::cachedTree, fieldAt).take(query.limit) else rows
            return ResolvedRows(shown, result.total, query.limit.takeIf { it != Int.MAX_VALUE })
        }

        val rowsDef = view.rows ?: error("ビュー「${view.id}」に行の定義（rows）がありません")
        val rows = ids.asSequence()
            .filter { (id, _) -> idMatchesPattern(id, rowsDef.idPattern) }
            .filter { (_, occurrences) ->
                rowsDef.schemaIds.isEmpty() || occurrences.any { it.schemaId in rowsDef.schemaIds }
            }
            .map { (id, occurrences) ->
                val cells = view.columns.map { column ->
                    val backlinksDef = column.backlinks
                    val valueDef = column.value
                    val occurrence =
                        if (backlinksDef != null || valueDef != null) null
                        else occurrences.firstOrNull { column.from.matches(it, rel(it.document)) }
                    when {
                        backlinksDef != null -> VirtualCell(column, null, null, null, backlinks = backlinksFor(id, backlinksDef))
                        valueDef != null -> valueCell(column, valueDef, entityNode(id, occurrences), occurrences, emptyMap(), emptyList())
                        occurrence == null -> VirtualCell(column, null, null, null)
                        else -> {
                            val absolute = occurrence.path + column.path
                            VirtualCell(column, occurrence.document, absolute, cachedTree(occurrence.document)?.let { getAt(it, absolute) })
                        }
                    }
                }
                val detailOccurrence = view.detail?.let { d ->
                    occurrences.firstOrNull { o -> d.source.matches(o, rel(o.document)) }
                }
                VirtualRow(
                    id = id,
                    title = occurrences.firstNotNullOfOrNull { it.title },
                    occurrences = occurrences,
                    cells = cells,
                    detailDocument = detailOccurrence?.document,
                    detailPath = detailOccurrence?.path,
                )
            }
            .toList()
        return ResolvedRows(withTotals(view, sortRows(view, rows, rel, ::cachedTree, fieldAt)))
    }

    // ---------------------------------------------------------------- クロス集計（kind: matrix）の展開

    /** 合計の行の見出し（行の ID の位置に出る）。 */
    const val TOTAL_LABEL = "合計"

    /**
     * クロス集計を、列を広げたグリッドにする（matrix でなければそのまま）。列の軸は索引の ID（参照先）か決まった値で、
     * 列ごとに「行から集めた要素を、その列に当たるものに絞って畳む」値の式を組み立てる。未分類の列と合計の列も同じ式の形。
     * ヘッダを描く側（画面・書き出し）は、同じ索引でこの関数を呼ぶと、解決した行のセルと同じ列が得られる。
     */
    fun lower(view: VirtualGridDef, ids: Map<String, List<ScannedId>>, rel: (DocumentId) -> String): VirtualGridDef {
        // 軸を値の式で広げるクロス集計は、列が解決のときに決まる（ヘッダは columnsOf で解決した行から取る）
        view.matrix?.let { return if (it.usesPivot) view else lowerMatrix(view, it, ids, rel) }
        if (view.columns.none { it.each != null }) return view
        // 要素ごとに列を広げる（columns の each）: 列の要素を指す $名前 を、その要素の ID に置き換えた値の式の列にする
        val columns = view.columns.flatMap { column ->
            val each = column.each ?: return@flatMap listOf(column)
            entitiesOf(ids, each.idPattern, each.schemaIds, each.order, rel).map { (id, label) ->
                column.copy(label = label, each = null, value = ExpressionValue.of(substituteColumnRef(each.rawValue, each.bind, id), "columns.value", label))
            }
        }
        return view.copy(columns = columns)
    }

    /** 列の軸にする要素: idPattern（と schemaIds）に合う索引の ID を、order の出現の文書内順（無ければ ID 順）で並べ、見出し（タイトル）を添える。 */
    private fun entitiesOf(
        ids: Map<String, List<ScannedId>>,
        idPattern: String,
        schemaIds: List<String>,
        order: VirtualViewSource?,
        rel: (DocumentId) -> String,
    ): List<Pair<String, String>> {
        val regex = idGlobToRegex(idPattern)
        val matched = ids.entries.filter { (id, occ) ->
            regex.matches(id) && (schemaIds.isEmpty() || occ.any { it.schemaId in schemaIds })
        }
        fun anchor(occ: List<ScannedId>): ScannedId? = order?.let { o -> occ.firstOrNull { o.matches(it, rel(it.document)) } }
        return matched.sortedWith(
            compareBy<Map.Entry<String, List<ScannedId>>>(
                { (_, occ) -> if (order == null || anchor(occ) != null) 0 else 1 },
                { (_, occ) -> anchor(occ)?.let { rel(it.document) } ?: "" },
                { (_, occ) -> anchor(occ)?.path?.let(::pathSortKey) ?: "" },
                { (id, _) -> id },
            )
        ).map { (id, occ) -> id to (occ.firstNotNullOfOrNull { it.title } ?: id) }
    }

    /**
     * 値の式（書かれたまま）の中の `$bind`（列の要素を指す名前）を、列の要素の ID に置き換える（each の列を広げるとき）。
     * 置き換えるのは、その文字だけの値（`node: { id: $cat }`・`equals: $cat` など）。定義の文書の検証は置き換え前の式を読むので、
     * `$cat` は決まった ID の文字として読めるだけでよい。
     */
    fun substituteColumnRef(raw: Any?, bind: String, id: String): Any? = when (raw) {
        is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { m -> raw.forEach { (k, v) -> m[k] = substituteColumnRef(v, bind, id) } }
        is List<*> -> raw.map { substituteColumnRef(it, bind, id) }
        is String -> if (raw == "\$$bind") id else raw
        else -> raw
    }

    /** クロス集計（kind: matrix）を、列を広げたグリッドにする。 */
    private fun lowerMatrix(view: VirtualGridDef, matrix: VirtualMatrixDef, ids: Map<String, List<ScannedId>>, rel: (DocumentId) -> String): VirtualGridDef {
        val cells = matrix.cells
        val key = cells.key
        data class Axis(val key: String, val label: String)
        val axis: List<Axis> = when (key) {
            is VirtualMatrixKey.Ref -> entitiesOf(ids, key.idPattern, key.schemaIds, key.order, rel).map { (id, label) -> Axis(id, label) }
            is VirtualMatrixKey.Value -> key.values.map { (v, label) -> Axis(v, label ?: v) }
            is VirtualMatrixKey.Expr -> error("列の軸が値の式のクロス集計は、解決のときに列を決めます（resolvePivot）")
        }
        val keyPath = when (key) {
            is VirtualMatrixKey.Ref -> key.via
            is VirtualMatrixKey.Value -> key.pathText
            is VirtualMatrixKey.Expr -> error("列の軸が値の式のクロス集計は、解決のときに列を決めます（resolvePivot）")
        }
        fun narrowed(extraMatch: List<Any?>, extraWhere: List<Any?>): Map<String, Any?> {
            val all = LinkedHashMap<String, Any?>(cells.all)
            if (extraMatch.isNotEmpty()) all["match"] = (cells.all["match"] as? List<*>).orEmpty() + extraMatch
            if (extraWhere.isNotEmpty()) all["where"] = (cells.all["where"] as? List<*>).orEmpty() + extraWhere
            return all
        }
        fun column(label: String, all: Map<String, Any?>): VirtualViewColumn {
            val raw = LinkedHashMap<String, Any?>()
            raw[cells.aggregate.id] = mapOf("all" to all)
            if (cells.aggregate != dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.COUNT) cells.kind?.let { raw["type"] = it.id }
            return VirtualViewColumn(label = label, path = emptyList(), value = ExpressionValue.of(raw, "cells", label), display = matrix.display)
        }
        val columns = mutableListOf<VirtualViewColumn>()
        for (a in axis) {
            columns += when (key) {
                // 参照先が列: 照合に「その参照を辿って、列のエンティティに着く」を足す（属性つきの参照でも辿れる）
                is VirtualMatrixKey.Ref -> column(
                    a.label,
                    narrowed(listOf(mapOf("edge" to mapOf("via" to key.via)), mapOf("node" to mapOf("id" to a.key))), emptyList()),
                )
                is VirtualMatrixKey.Value -> column(a.label, narrowed(emptyList(), listOf(mapOf("of" to cells.of, "path" to keyPath, "equals" to a.key))))
                is VirtualMatrixKey.Expr -> error("列の軸が値の式のクロス集計は、解決のときに列を決めます（resolvePivot）")
            }
        }
        matrix.unclassified?.let { label ->
            // 列の軸に当たらない要素: 値が無いか、どの列の値でもない（参照は書かれた ID の文字で見る）
            val condition: Map<String, Any?> = if (axis.isEmpty()) emptyMap() else mapOf(
                "or" to listOf(
                    mapOf("of" to cells.of, "path" to keyPath, "exists" to false),
                    mapOf("not" to mapOf("of" to cells.of, "path" to keyPath, "oneOf" to axis.map { it.key })),
                )
            )
            columns += column(label, narrowed(emptyList(), if (condition.isEmpty()) emptyList() else listOf(condition)))
        }
        if (matrix.totals) columns += column(TOTAL_LABEL, cells.all)
        return view.copy(columns = columns)
    }

    // ---------------------------------------------------------------- 軸を値の式で広げるクロス集計（P46）

    /** 照合の起点の要素に束縛名が無いときの仮の名前。 */
    private const val PIVOT_START = "_start"

    /** 解決した行の列。軸を値の式で広げたクロス集計は lower では列が決まらないので、行のセルの列から取る（ふつうのビューでは同じ列）。 */
    fun columnsOf(view: VirtualGridDef, rows: List<VirtualRow>): List<VirtualViewColumn> =
        rows.firstOrNull { it.cells.isNotEmpty() }?.cells?.map { it.column } ?: view.columns

    /** 集めた要素 1 つ: ノード・値を読む出現・集計する値（path）とその順序の種類・列の軸の値（参照先は複数ありうる。null＝未分類）。 */
    private class PivotItem(
        val node: SemanticQueryNodeRef,
        val occurrence: SemanticExpression.Occurrence?,
        val measure: String?,
        val measureKind: ValueExpr.OrderKind?,
        val columnKeys: List<String?>,
    )

    /**
     * 軸を値の式で広げるクロス集計の解決。起点の要素（行が値の軸なら照合の最初の node に合う要素すべて・行が ID の軸なら rows.idPattern
     * に合う ID）ごとに、cells の照合（all の形。path を外して要素そのものを集める）を起点を固定して評価し、集めた要素を
     * 行の値（rows.value を起点で計算）× 列の値（参照先・項目の値・値の式を要素で計算）で振り分けて畳む。
     * 列の軸と値の軸の行は、データに現れた値を順序の種類（数値・日付・文字）で並べる。未分類・合計の列と合計の行は従来と同じ。
     */
    private fun resolvePivot(
        view: VirtualGridDef,
        matrix: VirtualMatrixDef,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        fieldAt: FieldResolver,
    ): List<VirtualRow> {
        val cells = matrix.cells
        val key = cells.key
        fun entityNode(id: String): SemanticQueryNodeRef {
            val occ = ids[id].orEmpty()
            return SemanticQueryNodeRef(
                QueryNodeKind.ENTITY, id, occ.firstNotNullOfOrNull { it.title }, occ.firstNotNullOfOrNull { it.entityType },
                occ.firstOrNull()?.document, occ.firstOrNull()?.path ?: emptyList(),
            )
        }
        fun occurrenceOf(node: SemanticQueryNodeRef, selector: DerivationSelector?): SemanticExpression.Occurrence? =
            SemanticExpression.select(ids[node.id].orEmpty(), selector ?: DerivationSelector(), rel)
                ?.let { SemanticExpression.Occurrence(it.document, it.path) }
        fun environment(node: SemanticQueryNodeRef): SemanticValues.Environment = SemanticValues.Environment(
            tree = tree, rel = rel, current = node, currentOccurrence = occurrenceOf(node, null), graph = graph,
            refsInto = { id -> refs.filter { it.targetId == id } },
            solveFrom = { origin, spec -> SemanticQuery.matchesFrom(ids, refs, graph, spec, origin.id, fieldAt, tree) },
            reserved = buildMap {
                put(SemanticValues.RESERVED_ID, node.id)
                node.title?.let { put(SemanticValues.RESERVED_TITLE, it) }
            },
            fieldAt = fieldAt,
        )
        // 起点の要素
        val starts: List<SemanticQueryNodeRef> = if (matrix.rowsValue != null) {
            val first = (cells.all["match"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return emptyList()
            val node = first["node"] as? Map<*, *> ?: return emptyList()
            val bind = node["bind"]?.toString()?.takeIf { it.isNotBlank() } ?: PIVOT_START
            val startNode = LinkedHashMap<Any?, Any?>(node).also { it["bind"] = bind }
            val spec = QuerySpec.parse(mapOf("match" to listOf(mapOf("node" to startNode))), "cells.match")
            SemanticQuery.run(ids, refs, graph, spec.copy(limit = Int.MAX_VALUE), fieldAt, tree).rows
                .mapNotNull { it.bindings[bind] }.distinctBy { it.id }
        } else {
            val rowsDef = view.rows ?: return emptyList()
            ids.entries
                .filter { (id, occ) -> idMatchesPattern(id, rowsDef.idPattern) && (rowsDef.schemaIds.isEmpty() || occ.any { it.schemaId in rowsDef.schemaIds }) }
                .map { (id, _) -> entityNode(id) }
        }
        // 集める式（path を外して要素そのものを集める）・集計する値・値を読む文書の指定
        val collect = ValueExpr.parse(mapOf("all" to LinkedHashMap<String, Any?>(cells.all).also { it.remove("path") }), "cells", current = true)
        val measurePath = cells.all["path"]?.toString()?.let { parseYPath(it) }
        val inDoc = ValueExpr.parseSelector(cells.all["in"], "cells.in")
        var exprKind: ValueExpr.OrderKind? = null
        // 列の見出し（columns.label）: 同じ値の最初の要素で決める
        val columnLabels = LinkedHashMap<String, String>()
        fun itemsFrom(start: SemanticQueryNodeRef): List<PivotItem> {
            val side = SemanticValues.evaluate(collect, environment(start)) ?: return emptyList()
            return side.elements.orEmpty().mapNotNull { it.ref }.map { node ->
                val occ = occurrenceOf(node, inDoc)
                val element = occ?.let { SemanticExpression.nodeAt(tree, it, emptyList()) }
                val measure = measurePath?.let { p -> element?.let { scalarText(getAt(it, p)) } }
                val measureKind = measurePath?.let { p -> occ?.let { o -> fieldAt(o.document, o.path + p)?.let { f -> ValueExpr.OrderKind.ofType(f.type) } } }
                val keys: List<String?> = when (key) {
                    // 参照先が列: その要素に書かれた via の参照の相手（複数なら各列に数える。無ければ未分類）
                    is VirtualMatrixKey.Ref -> graph.ownRefs(node.id).filter { r ->
                        occ != null && r.document == occ.document && r.path.size > occ.path.size &&
                            r.path.subList(0, occ.path.size) == occ.path && QueryEdgeStep.viaMatches(key.via, r.viaKey, r.path)
                    }.map { it.targetId }.ifEmpty { listOf(null) }
                    is VirtualMatrixKey.Value -> listOf(element?.let { scalarText(getAt(it, key.path)) })
                    is VirtualMatrixKey.Expr -> listOf(
                        SemanticValues.evaluate(key.value.expr, environment(node))?.also { exprKind = exprKind ?: it.kind }?.text?.also { k ->
                            val labelExpr = key.label
                            if (labelExpr != null && k !in columnLabels) SemanticValues.evaluate(labelExpr.expr, environment(node))?.text?.let { columnLabels[k] = it }
                        },
                    )
                }
                PivotItem(node, occ, measure, measureKind, keys)
            }
        }
        // 行ごとに集める（行が値の軸なら同じ値を 1 行に、ID の軸なら起点ごとに 1 行）
        class RowGroup(val key: String?, val kind: ValueExpr.OrderKind?, val start: SemanticQueryNodeRef?) {
            val items = mutableListOf<PivotItem>()
        }
        val groups = LinkedHashMap<String?, RowGroup>()
        for (start in starts) {
            val rowsValue = matrix.rowsValue
            val group = if (rowsValue == null) groups.getOrPut(start.id) { RowGroup(start.id, null, start) } else {
                val side = SemanticValues.evaluate(rowsValue.expr, environment(start))
                groups.getOrPut(side?.text) { RowGroup(side?.text, side?.kind, null) }
            }
            group.items += itemsFrom(start)
        }
        // 列の軸
        data class Axis(val key: String, val label: String)
        val axis: List<Axis> = when (key) {
            is VirtualMatrixKey.Ref -> entitiesOf(ids, key.idPattern, key.schemaIds, key.order, rel).map { (id, label) -> Axis(id, label) }
            is VirtualMatrixKey.Value -> key.values.map { (v, label) -> Axis(v, label ?: v) }
            // 値の軸: order に書いた値を先に（書いた順）、残りは値の順序の種類で並べる。見出しは label の式（無ければ値）
            is VirtualMatrixKey.Expr -> groups.values.flatMap { g -> g.items.flatMap { it.columnKeys } }.filterNotNull().distinct()
                .sortedWith(compareBy<String> { key.order.indexOf(it).let { i -> if (i < 0) key.order.size else i } }.then(comparatorFor(exprKind) { it }))
                .map { Axis(it, columnLabels[it] ?: it) }
        }
        val columns = buildList {
            axis.forEach { add(VirtualViewColumn(label = it.label, path = emptyList(), display = matrix.display)) }
            matrix.unclassified?.let { add(VirtualViewColumn(label = it, path = emptyList(), display = matrix.display)) }
            if (matrix.totals) add(VirtualViewColumn(label = TOTAL_LABEL, path = emptyList(), display = matrix.display))
        }
        val count = cells.aggregate == ValueExpr.AggregateFn.COUNT
        val aggregateLabel = when (cells.aggregate) {
            ValueExpr.AggregateFn.COUNT -> "件数"
            ValueExpr.AggregateFn.SUM -> "合計"
            ValueExpr.AggregateFn.MIN -> "最小"
            ValueExpr.AggregateFn.MAX -> "最大"
        }
        fun cell(column: VirtualViewColumn, items: List<PivotItem>): VirtualCell {
            val distinct = items.distinctBy { it.node.id }
            val kind = if (count) ValueExpr.OrderKind.NUMBER else cells.kind ?: distinct.firstNotNullOfOrNull { it.measureKind } ?: ValueExpr.OrderKind.NUMBER
            val unreadable = if (count) null else distinct.firstOrNull { it.measure == null }
            if (unreadable != null) {
                val name = "${unreadable.node.id} ${measurePath?.pretty()}"
                return VirtualCell(column, null, null, null, computed = true, trace = VirtualComputedTrace("$name が書かれていません", emptyList()), kind = kind)
            }
            val values = distinct.map { if (count) it.node.id else it.measure!! }
            val inputs = distinct.map { item ->
                VirtualComputedInput(
                    item.node.id, if (count) item.node.id else item.measure, item.occurrence?.document,
                    item.occurrence?.path?.let { p -> if (count || measurePath == null) p else p + measurePath },
                )
            }
            val text = SemanticExpression.aggregate(cells.aggregate, values, kind)
            return VirtualCell(
                column, null, null, text?.let { YNode.Scalar(it) }, computed = true,
                trace = VirtualComputedTrace("$aggregateLabel: " + values.joinToString("・"), inputs), kind = kind,
            )
        }
        fun rowOf(group: RowGroup): VirtualRow {
            val cellList = buildList {
                axis.forEachIndexed { i, a -> add(cell(columns[i], group.items.filter { a.key in it.columnKeys })) }
                var i = axis.size
                // 未分類: 値が無いか、どの列の値でもない要素
                if (matrix.unclassified != null) {
                    add(cell(columns[i], group.items.filter { item -> item.columnKeys.none { k -> k != null && axis.any { a -> a.key == k } } }))
                    i++
                }
                if (matrix.totals) add(cell(columns[i], group.items))
            }
            val start = group.start
            return if (start != null) {
                VirtualRow(id = start.id, title = start.title, occurrences = ids[start.id].orEmpty(), cells = cellList, detailDocument = null, detailPath = null)
            } else {
                VirtualRow(
                    id = group.key ?: matrix.unclassified ?: "（未分類）", title = null, occurrences = emptyList(), cells = cellList,
                    detailDocument = null, detailPath = null, valueRow = true,
                )
            }
        }
        val lowered = view.copy(columns = columns)
        val rows: List<VirtualRow> = if (matrix.rowsValue != null) {
            // 値の軸の行は、集めた要素が 1 つも無ければ出さない（受け取る値で絞ったときに、空の月の行や棒が並ばないように）
            val keyed = groups.values.filter { it.key != null && it.items.isNotEmpty() }
            val kind = keyed.firstNotNullOfOrNull { it.kind }
            val unclassifiedRow = groups[null]?.takeIf { it.items.isNotEmpty() && matrix.unclassified != null }
            keyed.sortedWith(comparatorFor(kind) { it.key!! }).map(::rowOf) + listOfNotNull(unclassifiedRow?.let(::rowOf))
        } else {
            sortRows(lowered, groups.values.map(::rowOf), rel, tree, fieldAt)
        }
        return withTotals(lowered, rows)
    }

    /** 値の軸の並び: 順序の種類（数値・日付など）が分かればその順、分からなければ文字の順（`2026-10` のような年月は文字の順で時間の順）。 */
    private fun <T> comparatorFor(kind: ValueExpr.OrderKind?, key: (T) -> String): Comparator<T> = Comparator { a, b ->
        val x = key(a)
        val y = key(b)
        val ordered: Int? = when {
            kind == ValueExpr.OrderKind.NUMBER -> {
                val p = SemanticExpression.numberOf(x)
                val q = SemanticExpression.numberOf(y)
                if (p != null && q != null) p.compareTo(q) else null
            }
            kind?.value != null -> kind.value.compare(x, y)
            else -> null
        }
        ordered ?: x.compareTo(y)
    }

    /** クロス集計の合計の行: 各列のセルの値を畳む（件数と合計は足し、最小・最大はその順で選ぶ）。読めないセルがあれば、その列の合計は無い。 */
    private fun withTotals(view: VirtualGridDef, rows: List<VirtualRow>): List<VirtualRow> {
        val matrix = view.matrix ?: return rows
        if (!matrix.totals || rows.isEmpty()) return rows
        val fn = when (matrix.cells.aggregate) {
            dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.MIN -> dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.MIN
            dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.MAX -> dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.MAX
            else -> dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.SUM
        }
        val totalCells = view.columns.mapIndexed { i, column ->
            val cells = rows.map { r -> r.cells.getOrNull(i) }
            // 順序の種類: 件数は数値・それ以外は宣言か、列のセルが読んだ項目の型（セルの値の種類）
            val kind = if (matrix.cells.aggregate == dev.toros.yde.semantic.schema.ValueExpr.AggregateFn.COUNT) {
                dev.toros.yde.semantic.schema.ValueExpr.OrderKind.NUMBER
            } else matrix.cells.kind ?: cells.firstNotNullOfOrNull { it?.kind } ?: dev.toros.yde.semantic.schema.ValueExpr.OrderKind.NUMBER
            val texts = cells.map { scalarText(it?.value) }
            val total = if (texts.any { it == null }) null else SemanticExpression.aggregate(fn, texts.map { it!! }, kind)
            VirtualCell(column = column, document = null, path = null, value = total?.let { YNode.Scalar(it) }, computed = true, kind = kind)
        }
        return rows + VirtualRow(
            id = TOTAL_LABEL, title = null, occurrences = emptyList(), cells = totalCells, detailDocument = null, detailPath = null,
        )
    }

    /**
     * グリッドの列を見出しで選ぶ（文書ビューに埋め込む表の `grid.columns`）。labels の順に並べ、見出しが無い列は落とす（読み込み時の点検が先に知らせる）。
     * 空なら定義のまま。クロス集計（matrix）と要素ごとに広げる列（each）は列が解決のときに決まるので、そのまま（点検で拒む）。
     */
    fun selectColumns(view: VirtualGridDef, labels: List<String>): VirtualGridDef =
        if (labels.isEmpty() || view.matrix != null) view
        else view.copy(columns = labels.mapNotNull { label -> view.columns.firstOrNull { it.label == label && it.each == null } })

    /**
     * 文書ビューを解決する（RC-02・複合文書ビュー 段階 1）。
     * - 開いた文書の fields セクションは描画側が文書ツリーから直接描く（ここでは何もしない）
     * - backlinks セクションは被参照表を原本座標つきで解決する
     * - `fields.in`（同じ ID の別の出現）と `grid`（表の埋め込み）は、区画から変換のひな型（写し `$copy`・ビューの行 `$view`）を組んで
     *   SemanticTransform で評価し、出どころ（origins）で原本に結ぶ（段階 2 の射影ビューと同じ経路。architecture/views.md「複合文書ビュー」）
     * - localIds: 未保存編集を含む文書内のID一覧（null なら ids から引く）。
     *   被参照は refs 基準＝入力索引の状態。行（文書内ID）だけが編集中ツリーへ追随する
     * - gridDef / gridRows: 表の定義の引き方（ビュー ID → グリッドかクロス集計）と、列を選んだ定義を受け取る値で解いた行。
     *   表を知らない呼び出し側は省く＝表の区画は理由つきで解決しない。列の選別（`grid.columns`）はここで定義に当ててから解く（selectColumns）
     */
    fun resolveDocument(
        view: VirtualDocViewDef,
        document: DocumentId,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        localIds: List<ScannedId>? = null,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        gridDef: (String) -> VirtualGridDef? = { null },
        gridRows: (VirtualGridDef, Map<String, Any?>) -> List<VirtualRow> = { _, _ -> emptyList() },
    ): List<VirtualDocSectionResolved> {
        val resolver = DocResolver(document, ids, refs, graph, localIds, rel, tree)
        return view.sections.map { section ->
            section.backlinks?.let { return@map VirtualDocSectionResolved(section, resolver.rowsFor(it)) }
            if (section.fieldsIn != null) return@map resolver.occurrenceSection(section)
            section.grid?.let { return@map resolver.gridSection(section, it, gridDef, gridRows) }
            VirtualDocSectionResolved(section)
        }
    }

    /**
     * 要素単位アタッチの解決（RC-02）。各アタッチについて「at パターンに一致し、IDを持つ要素」を
     * 行にし、その要素のIDへの被参照を解決する（差し込み先の座標は行の path）。
     */
    fun resolveAttachments(
        view: VirtualDocViewDef,
        document: DocumentId,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        localIds: List<ScannedId>? = null,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
    ): List<VirtualDocAttachmentResolved> {
        if (view.attachments.isEmpty()) return emptyList()
        val resolver = DocResolver(document, ids, refs, graph, localIds, rel, tree)
        return view.attachments.map { attachment ->
            VirtualDocAttachmentResolved(
                attachment,
                resolver.rowsFor(attachment.backlinks) { attachment.matches(it.path) },
            )
        }
    }

    /** 文書ビュー解決の共有部（文書内ID・参照元ツリーのキャッシュ・被参照行の構築）。 */
    private class DocResolver(
        private val document: DocumentId,
        ids: Map<String, List<ScannedId>>,
        private val refs: List<ScannedRef>,
        private val graph: SemanticEntityGraph,
        localIds: List<ScannedId>?,
        private val rel: (DocumentId) -> String,
        private val tree: (DocumentId) -> YNode?,
    ) {
        private val trees = mutableMapOf<DocumentId, YNode?>()
        private val docIds: List<ScannedId> = localIds ?: ids.values.flatten().filter { it.document == document }

        private fun cachedTree(d: DocumentId): YNode? = trees.getOrPut(d) { tree(d) }

        /** 開いた文書のルート要素の ID（文書ルートに書かれた ID。無ければ null）。 */
        private val rootId: String? = docIds.firstOrNull { it.path.isEmpty() }?.id

        /** 変換の「いまの場所」＝開いた文書のルート。 */
        private val here: SemanticTransform.TemplatePlace?
            get() = cachedTree(document)?.let { SemanticTransform.TemplatePlace(it, document, emptyList()) }

        /**
         * `fields.in` の区画: ルートの ID の出現のうち、選び方（schemaId / fileGlob）に合うものを束縛 `self` にし、
         * 写し（`$copy` に `$of: self`・`$in`）のひな型を変換で評価する。出どころが原本の座標（文書と場所）で、
         * 区画のフォームはその座標の原本をそのまま編集する（出どころが 1 か所の部分木＝編集できる、の規則）。
         */
        fun occurrenceSection(section: VirtualDocSection): VirtualDocSectionResolved {
            val selector = section.fieldsIn ?: return VirtualDocSectionResolved(section)
            val id = rootId ?: return VirtualDocSectionResolved(
                section, reason = "この文書のルートに ID がありません（fields.in の区画は、ルートの ID の別の出現を出します）",
            )
            val occurrences = SemanticExpression.preferDefining(graph.entities[id]?.occurrences.orEmpty())
            fun placeOf(sel: DerivationSelector?): SemanticTransform.TemplatePlace? =
                occurrences.firstOrNull { sel == null || sel.matches(it.schemaId, rel(it.document)) }
                    ?.let { occ -> cachedTree(occ.document)?.let { getAt(it, occ.path) }?.let { SemanticTransform.TemplatePlace(it, occ.document, occ.path) } }
            val occ = occurrences.firstOrNull { selector.matches(it.schemaId, rel(it.document)) }
            val result = SemanticTransform.build(
                Template.Copy(TemplateSite(of = "self", inDoc = selector, path = emptyList())),
                emptyMap(), here, bindings = mapOf("self" to ::placeOf),
            )
            if (occ == null || result.term == null) {
                val where = listOfNotNull(selector.schemaId?.let { "スキーマ「$it」" }, selector.fileGlob?.let { "「$it」" })
                    .joinToString("・").ifEmpty { "指定" }
                return VirtualDocSectionResolved(
                    section,
                    reason = if (occ == null) "同じ ID「$id」の出現が${where}の文書にありません" else "出現の文書「${rel(occ.document)}」を読めません",
                )
            }
            return VirtualDocSectionResolved(section, occurrence = occ, term = result.term, origins = result.origins)
        }

        /**
         * `grid` の区画: 開いた文書の値（params のパス）を受け取る値にして表を解決し、行をビューの行のひな型（`$view`）で受け取る。
         * 行は派生なので出どころは持たない（セルの座標は行の中にある）。
         */
        fun gridSection(
            section: VirtualDocSection,
            grid: VirtualDocGrid,
            gridDef: (String) -> VirtualGridDef?,
            gridRows: (VirtualGridDef, Map<String, Any?>) -> List<VirtualRow>,
        ): VirtualDocSectionResolved {
            val root = cachedTree(document) ?: return VirtualDocSectionResolved(section, reason = "文書を読めません")
            val params = LinkedHashMap<String, Any?>()
            for ((name, path) in grid.params) {
                val value = scalarText(getAt(root, path))?.takeIf { it.isNotBlank() }
                    ?: return VirtualDocSectionResolved(
                        section, gridParams = params,
                        reason = "受け取る値「$name」の元の項目「${path.pretty()}」に値がありません",
                    )
                params[name] = value
            }
            val def = gridDef(grid.view)?.let { selectColumns(it, grid.columns) }
                ?: return VirtualDocSectionResolved(
                    section, gridParams = params,
                    reason = "表「${grid.view}」が無いか、行の供給元が渡されていません",
                )
            val rows = gridRows(def, params)
            val result = SemanticTransform.build(
                Template.ViewRows(grid.view), emptyMap(), here,
                views = { id -> if (id == grid.view) ReportRows.node(rows) else null },
            )
            return VirtualDocSectionResolved(
                section, term = result.term, gridParams = params, gridRows = rows, gridView = def,
            )
        }

        fun rowsFor(def: VirtualDocBacklinksDef, elementFilter: (ScannedId) -> Boolean = { true }): List<VirtualDocBacklinkRow> =
            docIds.asSequence()
                .filter(elementFilter)
                .filter { def.idPattern == null || idMatchesPattern(it.id, def.idPattern) }
                .sortedBy { pathSortKey(it.path) }
                .distinctBy { it.id }
                .map { row(it, def) }
                .toList()

        private fun row(entry: ScannedId, def: VirtualDocBacklinksDef): VirtualDocBacklinkRow {
            val backlinks = refs
                .filter { it.targetId == entry.id && def.filter.matches(it) }
                .map { ref ->
                    // 参照元要素＝参照位置を含む最内のID出現（グリッドのバックリンク列と同じ解決）
                    val sourceEntry = graph.sourceEntryOf(ref)
                    val values =
                        if (def.columns.isEmpty() || sourceEntry == null) def.columns.map { null }
                        else def.columns.map { col ->
                            cachedTree(ref.document)?.let { getAt(it, sourceEntry.path + col.path) }
                        }
                    VirtualDocBacklinkItem(
                        sourceId = sourceEntry?.id,
                        sourceTitle = sourceEntry?.title,
                        document = ref.document,
                        refPath = ref.path,
                        relation = ref.relation,
                        attributes = attributeSummary(ref),
                        values = values,
                    )
                }
                .sortedWith(compareBy({ rel(it.document) }, { pathSortKey(it.refPath) }))
            return VirtualDocBacklinkRow(id = entry.id, title = entry.title, path = entry.path, backlinks = backlinks)
        }
    }

    /**
     * 行の並び。order.path があれば、並び基準の出現（orderBy に合う出現。無ければどの出現でも）から読んだ値の順
     * （type: number / date か文字の順。読めない行は向きによらず後ろ）。
     * 無ければ orderBy 出現の（ファイル→文書内パス）順 → 合う出現の無い行は後ろに。
     * 同じ並びの行は、クエリ行ではクエリの決定的順序のまま、それ以外は ID 順。
     */
    private fun sortRows(
        view: VirtualGridDef,
        rows: List<VirtualRow>,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        fieldAt: FieldResolver,
    ): List<VirtualRow> {
        // sortedWith は安定なので、同じ並びの行は base の順に残る
        val base = if (view.rowsQuery != null) rows else rows.sortedBy { it.id }
        val orderValue = view.orderValue
        if (orderValue != null) {
            val source = view.orderBy
            fun occurrencesOf(row: VirtualRow) = row.occurrences.asSequence()
                .filter { occ -> source == null || source.matches(occ, rel(occ.document)) }
            // 順序の種類: 宣言（type）か、並びの値を読む項目の型（行のどれかの出現から。決まらなければ文字の順）
            val kind = orderValue.kind ?: base.asSequence().flatMap(::occurrencesOf)
                .firstNotNullOfOrNull { occ -> fieldAt(occ.document, occ.path + orderValue.path)?.let { dev.toros.yde.semantic.schema.ValueExpr.OrderKind.ofType(it.type) } }
            // 鍵は、数値なら数・意味の基底型なら値の表現・それ以外は文字（読めない行は鍵なし＝後ろ）
            data class Keyed(val row: VirtualRow, val key: Comparable<Any>?)
            @Suppress("UNCHECKED_CAST")
            fun keyOf(row: VirtualRow): Keyed {
                val text = occurrencesOf(row)
                    .mapNotNull { occ -> tree(occ.document)?.let { scalarText(getAt(it, occ.path + orderValue.path)) } }
                    .firstOrNull()
                val key: Comparable<Any>? = when {
                    text == null -> null
                    kind == null -> text as Comparable<Any>
                    kind.value != null -> kind.value.orderKey(text)
                    else -> SemanticExpression.numberOf(text) as Comparable<Any>?
                }
                return Keyed(row, key)
            }
            val byValue: Comparator<Keyed> = compareBy { it.key }
            val comparator = compareBy<Keyed> { if (it.key == null) 1 else 0 }
                .then(if (orderValue.descending) byValue.reversed() else byValue)
            return base.map(::keyOf).sortedWith(comparator).map { it.row }
        }
        val orderBy = view.orderBy ?: return base
        fun anchor(row: VirtualRow): ScannedId? = row.occurrences.firstOrNull { orderBy.matches(it, rel(it.document)) }
        val comparator = compareBy<Pair<VirtualRow, ScannedId?>>(
            { (_, a) -> if (a == null) 1 else 0 },
            { (_, a) -> a?.let { rel(it.document) } ?: "" },
            { (_, a) -> a?.path?.let(::pathSortKey) ?: "" },
        )
        return base.map { it to anchor(it) }.sortedWith(comparator).map { it.first }
    }

    /** YPath の文書内順を保つソートキー（配列インデックスはゼロ詰めで桁を揃える）。 */
    fun pathSortKey(path: YPath): String =
        path.joinToString("/") { seg -> if (seg is Int) "%08d".format(seg) else seg.toString() }

    // ---------------------------------------------------------------- テキスト表現（RC-03。エージェント・CLI 共用）

    /**
     * 解決済みビューの機械可読テキスト。各セルに原本上の座標（rel :: path）を添えるため、
     * エージェントはこの出力から直接パッチの対象を決められる（6E「仮想文書」のテキスト形）。
     * - columns 非null のときは、ラベルが一致する列だけを描画する（コンテキストレシピの列絞り。R19-02）
     * - ids 非null のときは、ref 値のセルに参照先のタイトルを「ID（タイトル）」で注記する（R19-05）
     */
    fun renderText(
        view: VirtualGridDef,
        rows: List<VirtualRow>,
        columns: List<String>? = null,
        ids: Map<String, List<ScannedId>>? = null,
        rel: (DocumentId) -> String,
        /** 詳細が未作成の行の作り先（filePattern を行の要素で評価したもの。決められなければ null）。 */
        detailCreatePath: (VirtualRow) -> String? = { null },
    ): String = buildString {
        appendLine("ビュー: ${view.name}（${view.id}）")
        view.description?.let { appendLine(it.trim()) }
        val rowsLabel = view.rows?.let { "idPattern: ${it.idPattern}" }
            ?: "matchクエリ・行キー: ${view.rowKey}"
        appendLine("行: ${rows.size} 件（$rowsLabel）")
        view.newRow?.let { nr ->
            // エージェントが「新しい行の作り方」を出力から判断できるようにする（配列＝apply_patch／ファイル＝create_document）
            if (nr.filePattern != null) {
                appendLine("新規行: ${nr.filePattern.text} の {id} を新しいIDに置換したパスへ create_document で文書を作成すると行になる")
            } else {
                appendLine("新規行: ${nr.file} :: ${nr.path.pretty()} へ要素を追加し、IDを設定すると行になる")
            }
        }
        // 来歴（R16-04）: 元値（テンプレート・集計の材料）と入力値の座標。エージェントはここから入力値側も辿れる
        fun appendTrace(trace: VirtualComputedTrace?) {
            if (trace == null || trace.inputs.isEmpty()) return
            append("      導出: ").append(trace.raw)
            trace.inputs.forEach { input ->
                append("  {").append(input.placeholder).append("}=")
                if (input.value == null) append("未解決")
                else {
                    append(input.value)
                    val inputDoc = input.document
                    if (inputDoc != null && input.path != null) {
                        append(" [").append(rel(inputDoc)).append(" :: ").append(input.path.pretty()).append("]")
                    }
                }
            }
            appendLine()
        }
        for (row in rows) {
            append("- id: ").append(row.id)
            row.title?.let { append("  title: ").append(it) }
            appendLine()
            // クエリ行（R22-02）: 結合先の束縛も明示する（エージェント・CLI が行の構成ノードを判別できる）
            row.bindings?.takeIf { it.size > 1 }?.let { b ->
                appendLine("    束縛: " + b.entries.joinToString(", ") { (k, v) -> "$k=$v" })
            }
            for (cell in row.cells.filter { columns == null || it.column.label in columns }) {
                append("    ").append(cell.column.label).append(": ")
                val backlinks = cell.backlinks
                if (backlinks != null) {
                    // バックリンク列: 参照元のIDと座標を列挙する（エージェントはこの座標から参照元へ辿れる）
                    if (backlinks.isEmpty()) appendLine("（被参照なし）")
                    else appendLine(
                        backlinks.joinToString(", ") { b ->
                            listOfNotNull(b.sourceId ?: rel(b.document), b.sourceTitle).joinToString(" ") +
                                (b.attributes?.let { "（$it）" } ?: "") +
                                "  [" + rel(b.document) + " :: " + b.path.pretty() + "]"
                        }
                    )
                } else if (cell.document == null) {
                    val value = cell.value
                    val trace = cell.trace
                    when {
                        // 値の式の列で、値が特定の 1 か所に無いとき（ひな型の展開・合計など）: 値と来歴だけを出す
                        cell.computed && value != null -> {
                            append(refAnnotatedCellText(value, ids)).appendLine("  [計算値（座標なし）]")
                            appendTrace(trace)
                        }
                        // 値が得られない計算列: 理由（来歴の元値）を出す
                        cell.computed && trace != null -> appendLine("（値が得られません: ${trace.raw}）")
                        cell.computed -> appendLine("（導出元なし）")
                        else -> appendLine("（出現なし）")
                    }
                } else {
                    append(refAnnotatedCellText(cell.value, ids))
                    append("  [").append(rel(cell.document)).append(" :: ").append(cell.path!!.pretty())
                    if (cell.column.editable) append("  編集可")
                    // 計算列: 値は評価済みで、座標は値の出どころ（ひな型・読み出した値・選ばれた要素）。書込はこの座標か入力値への通常編集で行う
                    if (cell.computed) append("  計算値（座標は値の出どころ）")
                    appendLine("]")
                    appendTrace(cell.trace)
                }
            }
            if (view.detail != null) {
                val detailDoc = row.detailDocument
                if (detailDoc != null) appendLine("    詳細: ${rel(detailDoc)}")
                else appendLine(
                    "    詳細: 未作成" +
                        (view.detail.filePattern?.let { p -> "（filePattern: ${p.text}" + (detailCreatePath(row)?.let { " → $it" } ?: "") + "）" } ?: "")
                )
            }
        }
    }.trimEnd()

    /**
     * 解決済み文書ビューの機械可読テキスト（エージェント・CLI 共用）。
     * fields セクションは対象座標のみを示し（内容は座標を指定して読む）、
     * backlinks セクションは被参照の一覧を参照値の座標つきで列挙する。
     */
    fun renderDocText(
        view: VirtualDocViewDef,
        rel: String,
        sections: List<VirtualDocSectionResolved>,
        attachments: List<VirtualDocAttachmentResolved> = emptyList(),
        relOf: (DocumentId) -> String,
    ): String = buildString {
        appendLine("文書ビュー: ${view.name}（${view.id}） 対象: $rel")
        view.description?.let { appendLine(it.trim()) }
        sections.forEachIndexed { i, resolved ->
            val section = resolved.section
            val fieldsPath = section.fieldsPath
            val grid = section.grid
            if (section.fieldsIn != null) {
                // 同じ ID の別の出現（側面）のフォーム: 座標は出現の文書と場所（無ければ理由）
                val occ = resolved.occurrence
                append("セクション${i + 1}: ${section.label ?: "フォーム"}（フォーム: 同じ ID の別の出現）")
                if (occ != null) append("  [${relOf(occ.document)} :: ${if (occ.path.isEmpty()) "(root)" else occ.path.pretty()}]")
                else append("  （${resolved.reason ?: "出現がありません"}）")
                if (section.fieldsInclude.isNotEmpty()) append("（選別: ${section.fieldsInclude.joinToString(", ")}）")
                if (section.fieldsExclude.isNotEmpty()) append("（除外: ${section.fieldsExclude.joinToString(", ")}）")
                appendLine()
            } else if (grid != null) {
                // 表の埋め込み: 受け取る値（開いた文書の値）と、その値で解決した行（グリッドの機械可読テキストと同じ形）
                val params = resolved.gridParams.orEmpty().entries.joinToString(", ") { (k, v) -> "$k=$v" }.ifEmpty { "なし" }
                appendLine("セクション${i + 1}: ${section.label ?: "表"}（表: ${grid.view}。受け取る値: $params）")
                val rows = resolved.gridRows
                val def = resolved.gridView
                if (rows == null || def == null) appendLine("    （${resolved.reason ?: "解決できません"}）")
                else appendLine(renderText(def, rows, rel = relOf).prependIndent("    "))
            } else if (fieldsPath != null) {
                val at = if (fieldsPath.isEmpty()) "(root)" else fieldsPath.pretty()
                append("セクション${i + 1}: ${section.label ?: "フォーム"}（フォーム）  [$rel :: $at]")
                if (section.fieldsInclude.isNotEmpty()) append("（選別: ${section.fieldsInclude.joinToString(", ")}）")
                if (section.fieldsExclude.isNotEmpty()) append("（除外: ${section.fieldsExclude.joinToString(", ")}）")
                appendLine()
            } else {
                val embed = section.embed
                if (embed != null) {
                    appendLine("セクション${i + 1}: ${section.label ?: embed.label}（${embed.label}: ${embed.view}） [$rel]")
                } else {
                    appendLine("セクション${i + 1}: ${section.label ?: "被参照"}（被参照表）")
                    appendBacklinkRows(rel, section.backlinks, resolved.rows.orEmpty(), relOf)
                }
            }
        }
        attachments.forEach { resolved ->
            appendLine("アタッチ: ${resolved.attachment.label ?: "被参照"}（at: ${resolved.attachment.atText}）")
            appendBacklinkRows(rel, resolved.attachment.backlinks, resolved.rows, relOf)
        }
    }.trimEnd()

    /** 被参照表の行群のテキスト表現（セクション・アタッチ共用）。 */
    private fun StringBuilder.appendBacklinkRows(
        rel: String,
        def: VirtualDocBacklinksDef?,
        rows: List<VirtualDocBacklinkRow>,
        relOf: (DocumentId) -> String,
    ) {
        if (rows.isEmpty()) appendLine("    （対象IDなし）")
        for (row in rows) {
            append("- ").append(row.id)
            row.title?.let { append("  ").append(it) }
            appendLine("  [$rel :: ${row.path.pretty()}]")
            if (row.backlinks.isEmpty()) appendLine("    （被参照なし）")
            for (b in row.backlinks) {
                append("    ← ")
                append(listOfNotNull(b.sourceId ?: relOf(b.document), b.sourceTitle).joinToString(" "))
                b.attributes?.let { append("（").append(it).append("）") }
                append("  [").append(relOf(b.document)).append(" :: ").append(b.refPath.pretty()).append("]")
                def?.columns?.forEachIndexed { ci, col ->
                    append("  ").append(col.label).append(": ").append(cellText(b.values.getOrNull(ci)))
                }
                appendLine()
            }
        }
    }

    /** セル値の1行要約。 */
    fun cellText(value: YNode?, maxLength: Int = 80): String = when (value) {
        null -> "（未設定）"
        is YNode.Scalar -> {
            val text = value.value?.toString() ?: "null"
            val single = text.replace("\n", " ")
            if (single.length > maxLength) single.take(maxLength) + "…" else single
        }
        is YNode.Seq -> "（配列 ${value.items.size} 件）"
        is YNode.Map -> "（オブジェクト: ${value.entries.keys.take(4).joinToString(", ")}${if (value.entries.size > 4) "…" else ""}）"
    }

    /**
     * ref 値の注記つきセル表示（R19-05）: ids でタイトルへ解決できるIDは「ID（タイトル）」を添える。
     * 従来「（配列 n 件）」だった参照配列も、全要素がスカラーで1件以上IDに解決できるなら中身を展開する。
     * 値そのものはID（括弧はエージェント向けの注記）——パッチにはIDだけを書く前提は変えない。
     */
    private fun refAnnotatedCellText(value: YNode?, ids: Map<String, List<ScannedId>>?): String {
        if (ids == null) return cellText(value)
        fun annotate(scalar: YNode.Scalar): String? {
            val id = scalar.value?.toString() ?: return null
            val title = ids[id]?.firstNotNullOfOrNull { it.title } ?: return null
            return "$id（$title）"
        }
        when (value) {
            is YNode.Scalar -> annotate(value)?.let { return it }
            is YNode.Seq -> {
                val scalars = value.items.filterIsInstance<YNode.Scalar>()
                val resolvable = scalars.size == value.items.size &&
                    scalars.any { !ids[it.value?.toString() ?: ""].isNullOrEmpty() }
                if (resolvable) {
                    val joined = scalars.joinToString(", ") { annotate(it) ?: cellText(it) }
                    return if (joined.length > 200) joined.take(200) + "…" else joined
                }
            }
            else -> {}
        }
        return cellText(value)
    }
}
