package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.DerivationDef
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
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

/** ビューの1列。出現要素からの相対YPathで値を指すか、backlinks / computed / of（クエリ束縛）の列にする。 */
data class VirtualViewColumn(
    val label: String,
    val from: VirtualViewSource = VirtualViewSource(),
    val path: YPath,
    val editable: Boolean = false,
    val backlinks: VirtualBacklinksDef? = null,
    val computed: DerivationDef? = null,
    val of: String? = null,
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
    val detail: VirtualDetailDef? = null,
    val newRow: VirtualNewRowDef? = null,
    override val source: DocumentId,
) : VirtualViewDefinition

// ---------------------------------------------------------------- 文書ビューの宣言（カーネル座標。RC-02）

/** アタッチ先パターンの「任意のインデックス」（`[*]`）。 */
object VirtualAnyIndex {
    override fun toString(): String = "[*]"
}

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
)

/** 要素単位アタッチ。at パターンに一致する要素の展開フォーム内へ、その要素のIDの被参照表を差し込む。 */
data class VirtualDocAttachment(
    val label: String? = null,
    val atText: String,
    val at: List<Any>,
    val backlinks: VirtualDocBacklinksDef,
) {
    /** 要素パスがこのアタッチの対象か。`[*]`（VirtualAnyIndex）は任意のインデックスに一致する。 */
    fun matches(path: YPath): Boolean =
        at.size == path.size && at.zip(path).all { (p, seg) -> (p === VirtualAnyIndex && seg is Int) || p == seg }
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

/** 計算セルの来歴: 元値（テンプレート）とプレースホルダごとの解決。 */
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
     */
    fun resolve(
        view: VirtualGridDef,
        ids: Map<String, List<ScannedId>>,
        refs: List<ScannedRef>,
        graph: SemanticEntityGraph,
        rel: (DocumentId) -> String,
        tree: (DocumentId) -> YNode?,
    ): List<VirtualRow> {
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

        fun computedCell(column: VirtualViewColumn, def: DerivationDef, rowId: String, occurrences: List<ScannedId>): VirtualCell {
            // 解決は整合検証と共有の SemanticDerivation。ビューで見えている導出値と検証される期待値を一致させる（architecture/transform.md「計算列と決定論的射影」）
            val result = SemanticDerivation.resolve(def, rowId, ::occurrencesOf, rel, ::cachedTree, occurrences)
            return VirtualCell(
                column = column,
                document = result.sourceDocument,
                path = result.sourcePath,
                value = result.value?.let { YNode.Scalar(it) },
                computed = true,
                trace = result.raw?.let { raw ->
                    VirtualComputedTrace(raw, result.inputs.map { VirtualComputedInput(it.placeholder, it.value, it.document, it.path) })
                },
            )
        }

        // クエリ行（rows: match）: 行 = パターン照合の束縛行。並びはクエリの決定的順序（sortRows は通さない）
        val query = view.rowsQuery
        if (query != null) {
            val rowKeyName = view.rowKey ?: query.binds.first()
            return SemanticQuery.run(ids, refs, graph, query, ::cachedTree).rows.map { qr ->
                val keyNode = qr.bindings.getValue(rowKeyName)
                val occurrences = occurrencesOf(keyNode.id)
                val cells = view.columns.map { column ->
                    val node = qr.bindings[column.of ?: rowKeyName]
                    val backlinksDef = column.backlinks
                    val computedDef = column.computed
                    when {
                        node == null -> VirtualCell(column, null, null, null)
                        backlinksDef != null -> VirtualCell(column, null, null, null, backlinks = backlinksFor(node.id, backlinksDef))
                        computedDef != null -> computedCell(column, computedDef, node.id, occurrencesOf(node.id))
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
                    val computedDef = column.computed
                    val occurrence =
                        if (backlinksDef != null || computedDef != null) null
                        else occurrences.firstOrNull { column.from.matches(it, rel(it.document)) }
                    when {
                        backlinksDef != null -> VirtualCell(column, null, null, null, backlinks = backlinksFor(id, backlinksDef))
                        computedDef != null -> computedCell(column, computedDef, id, occurrences)
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
        return sortRows(view, rows, rel)
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

    /** 行の並び: orderBy 出現の（ファイル→文書内パス）順 → それ以外はID順で後ろに。 */
    private fun sortRows(view: VirtualGridDef, rows: List<VirtualRow>, rel: (DocumentId) -> String): List<VirtualRow> {
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
                    appendLine(if (cell.computed) "（導出元なし）" else "（出現なし）")
                } else {
                    append(refAnnotatedCellText(cell.value, ids))
                    append("  [").append(rel(cell.document)).append(" :: ").append(cell.path!!.pretty())
                    if (cell.column.editable) append("  編集可")
                    // 計算列: 値は評価済みの導出値で、座標は元値（規則）の位置。書込はこの座標か入力値への通常編集で行う
                    if (cell.computed) append("  計算値（座標は導出規則）")
                    appendLine("]")
                    // 来歴（R16-04）: 元値（テンプレート）と入力値の座標。エージェントはここから入力値側も辿れる
                    val trace = cell.trace
                    if (trace != null && trace.inputs.isNotEmpty()) {
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
                appendLine("セクション${i + 1}: ${section.label ?: "被参照"}（被参照表）")
                appendBacklinkRows(rel, section.backlinks, resolved.rows.orEmpty(), relOf)
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
