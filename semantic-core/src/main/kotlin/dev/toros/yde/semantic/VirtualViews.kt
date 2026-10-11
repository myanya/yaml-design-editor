package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.NumberDisplay
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.idGlobToRegex
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
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
)

/** 行の集合の定義。idPattern に一致する論理IDが行になる。 */
data class VirtualRowsDef(
    val idPattern: String,
    val schemaIds: List<String> = emptyList(),
)

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

/** 文書ビューのセクション1件。fields（フォーム射影）か backlinks（被参照表）のどちらか一方を持つ。 */
data class VirtualDocSection(
    val label: String? = null,
    val fieldsPath: YPath? = null,
    val fieldsDefinition: String? = null,
    val fieldsInclude: List<String> = emptyList(),
    val fieldsExclude: List<String> = emptyList(),
    val backlinks: VirtualDocBacklinksDef? = null,
    val flowView: String? = null,
)

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

/** 解決済みの文書ビューセクション。rows は backlinks セクションのみ非null。 */
data class VirtualDocSectionResolved(
    val section: VirtualDocSection,
    val rows: List<VirtualDocBacklinkRow>? = null,
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
    ): List<VirtualRow> {
        val view = lower(viewDef, ids, rel)  // クロス集計は、列を広げたグリッドとして解決する
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun cachedTree(document: DocumentId): YNode? = trees.getOrPut(document) { tree(document) }
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

        // クエリ行（rows: match）: 行 = パターン照合の束縛行。並びはクエリの決定的順序（sortRows は通さない）
        val query = view.rowsQuery
        if (query != null) {
            val rowKeyName = view.rowKey ?: query.binds.first()
            return SemanticQuery.run(ids, refs, graph, query, fieldAt, ::cachedTree).rows.map { qr ->
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
        return withTotals(view, sortRows(view, rows, rel, ::cachedTree, fieldAt))
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
        view.matrix?.let { return lowerMatrix(view, it, ids, rel) }
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
        }
        val keyPath = when (key) { is VirtualMatrixKey.Ref -> key.via; is VirtualMatrixKey.Value -> key.pathText }
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
     * 文書ビューを解決する（RC-02）。fields セクションは描画側が文書ツリーから直接描くため、
     * ここでは backlinks セクションの被参照表だけを原本座標つきで解決する。
     * - localIds: 未保存編集を含む文書内のID一覧（null なら ids から引く）。
     *   被参照は refs 基準＝入力索引の状態。行（文書内ID）だけが編集中ツリーへ追随する
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
    ): List<VirtualDocSectionResolved> {
        val resolver = DocResolver(document, ids, refs, graph, localIds, rel, tree)
        return view.sections.map { section ->
            val def = section.backlinks ?: return@map VirtualDocSectionResolved(section)
            VirtualDocSectionResolved(section, resolver.rowsFor(def))
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
        document: DocumentId,
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
     * （type: number / date か文字の順。読めない行は向きによらず後ろ。同じ値は ID 順）。
     * 無ければ orderBy 出現の（ファイル→文書内パス）順 → それ以外はID順で後ろに。
     */
    private fun sortRows(
        view: VirtualGridDef,
        rows: List<VirtualRow>,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
        fieldAt: FieldResolver,
    ): List<VirtualRow> {
        val orderValue = view.orderValue
        if (orderValue != null) {
            val source = view.orderBy
            fun occurrencesOf(row: VirtualRow) = row.occurrences.asSequence()
                .filter { occ -> source == null || source.matches(occ, rel(occ.document)) }
            // 順序の種類: 宣言（type）か、並びの値を読む項目の型（行のどれかの出現から。決まらなければ文字の順）
            val kind = orderValue.kind ?: rows.asSequence().flatMap(::occurrencesOf)
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
                .thenBy { it.row.id }
            return rows.map(::keyOf).sortedWith(comparator).map { it.row }
        }
        val orderBy = view.orderBy ?: return rows.sortedBy { it.id }
        fun anchor(row: VirtualRow): ScannedId? = row.occurrences.firstOrNull { orderBy.matches(it, rel(it.document)) }
        val comparator = compareBy<Pair<VirtualRow, ScannedId?>>(
            { (_, a) -> if (a == null) 1 else 0 },
            { (_, a) -> a?.let { rel(it.document) } ?: "" },
            { (_, a) -> a?.path?.let(::pathSortKey) ?: "" },
            { (row, _) -> row.id },
        )
        return rows.map { it to anchor(it) }.sortedWith(comparator).map { it.first }
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
            if (fieldsPath != null) {
                val at = if (fieldsPath.isEmpty()) "(root)" else fieldsPath.pretty()
                append("セクション${i + 1}: ${section.label ?: "フォーム"}（フォーム）  [$rel :: $at]")
                if (section.fieldsInclude.isNotEmpty()) append("（選別: ${section.fieldsInclude.joinToString(", ")}）")
                if (section.fieldsExclude.isNotEmpty()) append("（除外: ${section.fieldsExclude.joinToString(", ")}）")
                appendLine()
            } else {
                if (section.flowView != null) {
                    appendLine("セクション${i + 1}: ${section.label ?: "フロー"}（フロー: ${section.flowView}） [$rel]")
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
