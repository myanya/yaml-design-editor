package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.RefEntry
import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.ExtensionViewResult
import dev.toros.yde.semantic.ViewContext
import dev.toros.yde.semantic.VirtualBacklink
import dev.toros.yde.semantic.VirtualBacklinksDef
import dev.toros.yde.semantic.VirtualCell
import dev.toros.yde.semantic.VirtualComputedInput
import dev.toros.yde.semantic.VirtualComputedTrace
import dev.toros.yde.semantic.VirtualDetailDef
import dev.toros.yde.semantic.VirtualDocAttachment
import dev.toros.yde.semantic.VirtualDocAttachmentResolved
import dev.toros.yde.semantic.VirtualDocBacklinkColumn
import dev.toros.yde.semantic.VirtualDocBacklinkItem
import dev.toros.yde.semantic.VirtualDocBacklinkRow
import dev.toros.yde.semantic.VirtualDocBacklinksDef
import dev.toros.yde.semantic.VirtualDocSection
import dev.toros.yde.semantic.VirtualDocSectionResolved
import dev.toros.yde.semantic.VirtualDocViewDef
import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.semantic.ScannedRef
import dev.toros.yde.semantic.VirtualDocEmbed
import dev.toros.yde.semantic.VirtualNewRowDef
import dev.toros.yde.semantic.VirtualRow
import dev.toros.yde.semantic.VirtualRowsDef
import dev.toros.yde.semantic.VirtualViewColumn
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.semantic.VirtualViewSource
import dev.toros.yde.semantic.ResolvedRows
import dev.toros.yde.semantic.VirtualViews
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.app.schema.Display
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.schema.SchemaScaffold
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.DocumentScaffold
import dev.toros.yde.workspace.DocumentScaffolds
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * ビュー機構（P6 / P14）。宣言的なビュー定義（`_views` 配下のYAML）と、その解決エンジン。
 * - `kind: grid`（既定）: 複数文書の一部を「行（＝論理ID）×列（＝出現からの相対YPath）」に射影する横断一覧
 * - `kind: document`（R14-01）: 文書単位の表示合成。適用先文書のフォームを sections（スキーマ駆動フォームの
 *   射影＋被参照表）の並びとして組み立てる。「内容はスキーマで検証し、表示はビューでコントロールする」の実現形
 * - ビューは派生表示であり、データを一切持たない（原本主義）。セルは原本上の座標 (file, YPath) に解決される
 * - 行の集合は索引（IdIndex）から解決する。UI・エージェントはそれぞれの索引（未保存重ね・ステージング
 *   オーバーレイ）と loader を注入することで、同じ定義から同じ射影を得る
 * - 詳細文書のスキャフォールド（filePattern）により「一覧から新しい文書を作る」ペア規約を機械化する
 */

// ビュー定義の型は、カーネルの型（semantic-core の semantic/VirtualViews.kt）の別名（P27 段D の D-c。以前は、同じ形の型を root にも持ち、
// 解決のたびにカーネルの型へ写していた）。定義文書の座標（source）は、ワークスペース相対の文書ID
typealias ViewDefinition = VirtualViewDefinition
typealias ViewSource = VirtualViewSource
typealias ViewBacklinksDef = VirtualBacklinksDef
typealias ViewColumn = VirtualViewColumn
typealias ViewRowsDef = VirtualRowsDef
typealias ViewDetailDef = VirtualDetailDef
typealias ViewNewRowDef = VirtualNewRowDef
typealias ViewDef = VirtualGridDef
typealias DocViewSection = VirtualDocSection
typealias DocViewAttachment = VirtualDocAttachment
typealias DocBacklinkColumn = VirtualDocBacklinkColumn
typealias DocBacklinksDef = VirtualDocBacklinksDef
typealias DocViewDef = VirtualDocViewDef

// 解決の結果の型も、カーネルの型の別名（P27 段D の D-d・D-e）。場所は、文書ID（document＝ワークスペース相対パス）と、文書の中のパス。
// 以前は、ファイルの場所（file: Path）と相対パス（rel）を持つ同じ形の型を root にも持ち、解決のたびに写していた。
// - ViewRow: 解決済みの1行（＝1論理ID）。detailDocument は詳細出現の文書（なければ null）。key は行の同一性キー（R22-02）
// - ViewCell: 解決済みのセル。document が null のときは対応する出現がない（値なし）。computed=true は評価済みの導出値（読み取り専用）
// - ViewBacklink / DocBacklinkItem: 被参照の1件（参照元要素の ID・タイトルと、参照値の場所＝ジャンプ先）
// - ComputedTrace / ComputedInput: 計算セルの来歴（元値と、プレースホルダごとの解決。R16-04）
typealias ViewBacklink = VirtualBacklink
typealias ComputedInput = VirtualComputedInput
typealias ComputedTrace = VirtualComputedTrace
typealias ViewCell = VirtualCell
typealias ViewRow = VirtualRow
typealias DocBacklinkItem = VirtualDocBacklinkItem
typealias DocBacklinkRow = VirtualDocBacklinkRow
typealias DocSectionResolved = VirtualDocSectionResolved
typealias DocAttachmentResolved = VirtualDocAttachmentResolved

object Views {

    data class LoadResult(
        val views: List<ViewDef>, val docViews: List<DocViewDef>, val errors: List<String>,
        /** レンズの見え方（kind: lens）。レンズは全部の定義を合わせて読む。 */
        val lensViews: List<dev.toros.yde.semantic.VirtualLensDef> = emptyList(),
        val dashboards: List<dev.toros.yde.semantic.VirtualDashboardDef> = emptyList(),
        /** 拡張の種類のビュー（chart など。ADR-0014）。読めるのは入口が登録した種類だけ。 */
        val extensionViews: List<ExtensionViewDef> = emptyList(),
    ) {
        fun dashboard(id: String): dev.toros.yde.semantic.VirtualDashboardDef? =
            (views + docViews + lensViews + dashboards + extensionViews).singleOrNull { it.id == id } as? dev.toros.yde.semantic.VirtualDashboardDef

        fun extensionView(id: String): ExtensionViewDef? =
            (views + docViews + lensViews + dashboards + extensionViews).singleOrNull { it.id == id } as? ExtensionViewDef

        /** 既定指定が重複したときは勝手に選ばず、先頭の一意な定義と診断を表示する。 */
        fun defaultDashboard(): dev.toros.yde.semantic.VirtualDashboardDef? =
            dashboards.singleOrNull { it.default }?.let { dashboard(it.id) }
                ?: dashboards.firstNotNullOfOrNull { dashboard(it.id) }

        fun dashboardGrid(id: String): ViewDef? =
            (views + docViews + lensViews + dashboards + extensionViews).singleOrNull { it.id == id } as? ViewDef

        fun view(id: String): ViewDef? = views.firstOrNull { it.id == id }

        /** 矢印を逆向きに描く関係名（全部のレンズの定義の和）。 */
        val reversedRelations: Set<String> get() = lensViews.flatMap { it.reversedRelations }.toSet()

        fun docView(id: String): DocViewDef? = docViews.firstOrNull { it.id == id }


        /** 文書に適用する文書ビュー。スキーマの match と同じ「定義ファイル名順の先勝ち」。 */
        fun docViewFor(relPath: String, schemaId: String?): DocViewDef? =
            docViews.firstOrNull { it.appliesTo(relPath, schemaId) }

        /** 文書に当たる文書ビューのすべて（定義ファイル名順。画面のビューのタブで選ぶ。先頭が docViewFor と同じ既定）。 */
        fun docViewsFor(relPath: String, schemaId: String?): List<DocViewDef> =
            docViews.filter { it.appliesTo(relPath, schemaId) }
    }

    /**
     * views ゾーン（`_views/` または `.yde/views/`）からビュー定義を読み込む（エラー収集つき）。
     * schemas を渡すと、definition 参照の列展開（R14-02）と、ビュー→スキーマの整合検査（リント）が有効になる。
     * 読み取りとリントの実体は、カーネルの workspace.ViewDefinitions（P27 段D の D-c）。ここは、ファイルを読んで渡す窓口。
     */
    fun load(
        workspace: Path, schemas: List<SchemaDef> = emptyList(),
        loader: ((DocumentId) -> YNode?)? = null,
    ): LoadResult {
        val dir = runCatching { dev.toros.yde.workspace.WorkspacePaths.resolve(workspace, WorkspaceLayout.of(workspace).viewsDir) }
            .getOrElse { return LoadResult(emptyList(), emptyList(), listOf(it.message ?: "ビュー定義の場所を解決できません")) }
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
                    && runCatching { dev.toros.yde.workspace.WorkspacePaths.resolve(workspace, workspace.toAbsolutePath().relativize(p).toString()) }.isSuccess
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val sources = files.map { file ->
            val document = documentIdOf(workspace, file)
            document to if (loader == null) Files.readString(file) else loader(document)?.let(YamlIO::dumpPlain).orEmpty()
        }
        val catalog = ViewDefinitions.load(sources, schemas.map { it.kernel }, Extensions.viewKinds)
        return LoadResult(catalog.views, catalog.docViews, catalog.errors, catalog.lensViews, catalog.dashboards, catalog.extensionViews)
    }

    /**
     * 拡張の種類のビュー（チャート・フローなど。ADR-0014）を解決する: 元の表（グリッドかクロス集計）・対象の文書・項目・参照を、
     * 同じ索引・原本（未保存の編集を重ねたもの）で答える。文書に縛られる種類（perDocument）は document が要る。
     * 解決できなければ誤りの文で例外（元の表が無いなど）。描画は画面だけで、CLI と read_view は結果の値と文を返す。
     */
    fun resolveExtension(
        def: ExtensionViewDef, loaded: LoadResult?, index: IdIndex, loader: (DocumentId) -> YNode?,
        schemaFor: (DocumentId) -> SchemaDef? = { null }, document: DocumentId? = null,
    ): ExtensionViewResult {
        val refsByDocument: Map<DocumentId, Map<YPath, List<ScannedRef>>> by lazy { index.refs.groupBy { it.document }.mapValues { (_, r) -> r.groupBy { it.path } } }
        return def.resolve(object : ViewContext {
            override fun grid(id: String): VirtualGridDef? = loaded?.view(id)
            override fun rows(grid: VirtualGridDef): List<VirtualRow> = resolve(grid, index, loader, schemaFor)
            override val document: DocumentId? = document
            override fun tree(document: DocumentId): YNode? = loader(document)
            override fun schemaId(document: DocumentId): String? = schemaFor(document)?.id
            override fun fieldAt(document: DocumentId, path: YPath): FieldDef? = loader(document)?.let { schemaFor(document)?.fieldAt(it, path) }
            override fun refsAt(document: DocumentId, path: YPath): List<ScannedRef> = refsByDocument[document]?.get(path).orEmpty()
            override fun resolves(ref: ScannedRef): Boolean = index.resolve(ref).isNotEmpty()
        })
    }

    /**
     * 文書ビューの区画に埋め込んだ拡張の種類のビュー（`flow: {view}`・`chart: {view}`）を、その文書で解決する。
     * 埋め込み先が一意なその種類のビューでなければ誤りの文で例外。
     */
    fun resolveEmbedded(
        embed: VirtualDocEmbed, document: DocumentId, loaded: LoadResult, index: IdIndex, loader: (DocumentId) -> YNode?,
        schemaFor: (DocumentId) -> SchemaDef? = { null },
    ): ExtensionViewResult {
        val def = loaded.extensionView(embed.view)?.takeIf { it.viewKind == embed.kind }
            ?: error("埋め込み先「${embed.view}」は一意な${embed.label}（kind: ${embed.kind}）を参照してください")
        return resolveExtension(def, loaded, index, loader, schemaFor, document)
    }

    /** 埋め込みの CLI・read_view の値（解決できなければ誤りの値）。 */
    fun embeddedReport(
        embed: VirtualDocEmbed, document: DocumentId, loaded: LoadResult, index: IdIndex, loader: (DocumentId) -> YNode?,
        schemaFor: (DocumentId) -> SchemaDef? = { null },
    ): Map<String, Any?> = runCatching { resolveEmbedded(embed, document, loaded, index, loader, schemaFor).report() }.getOrElse {
        linkedMapOf("view" to embed.view, "kind" to embed.kind, "file" to document.value, "errors" to listOf(it.message ?: "${embed.label}を解決できません"))
    }

    /** 解決した行の列（軸を値の式で広げたクロス集計は、列が解決のときに決まる）。ヘッダと書き出しはこれを使う。 */
    fun columnsOf(view: ViewDef, rows: List<ViewRow>): List<ViewColumn> = VirtualViews.columnsOf(view, rows)

    /** ビュー定義文書の座標（ワークスペース相対の文書ID。ワークスペースの外のパスは、そのままの文字列）。 */
    private fun documentIdOf(workspace: Path?, file: Path): DocumentId {
        val abs = file.toAbsolutePath().normalize()
        val base = workspace?.toAbsolutePath()?.normalize()
        val rel = if (base != null && abs.startsWith(base)) base.relativize(abs) else file
        return DocumentId(rel.toString().replace('\\', '/'))
    }

    /**
     * ビュー定義1件（編集中の未保存ツリーでもよい）の検査（UX-43）。
     * パース不能はその内容を1件、パースできればリント（ビュー→スキーマの整合検査）の指摘を返す。
     */
    fun lintDocument(root: YNode?, source: Path, schemas: List<SchemaDef>): List<String> =
        ViewDefinitions.lintDocument(root, documentIdOf(null, source), schemas.map { it.kernel }, Extensions.viewKinds)

    /**
     * ビュー定義1件のパース（kind で分岐）。ステージングの定義ゲート（Staging）からも使う。
     * schemas は definition 参照の解決に使う（未指定時は definition を使う定義がパースエラーになる）。
     */
    fun parseView(raw: Map<*, *>, source: Path, schemas: List<SchemaDef> = emptyList()): ViewDefinition =
        ViewDefinitions.parseView(raw, documentIdOf(null, source), schemas.map { it.kernel }, Extensions.viewKinds)

    // ---------------------------------------------------------------- 解決
    // 解決の本体はカーネル（semantic.VirtualViews。RC-01・RC-02）。索引も結果もカーネルの型そのものなので、ここは呼ぶだけの窓口

    /**
     * ビューを行×セルに解決する。
     * - index: 行の集合と出現の解決に使う（UIは未保存の編集を重ねた索引、エージェントはステージングを重ねた索引を渡す）
     * - loader: セル値の読み取り（文書ID → 文書の中身。UIは未保存編集を優先する loader、エージェントはステージングの tree）
     */
    fun resolve(
        view: ViewDef,
        index: IdIndex,
        loader: (DocumentId) -> YNode?,
        /** 文書のスキーマ（値の式の順序の種類と行の並びの種類を項目の型から決める。省くと `type` の宣言だけ）。 */
        schemaFor: (DocumentId) -> SchemaDef? = { null },
        /** 受け取る値（表の params へ。ダッシュボードの入力やビュータブの欄から）。 */
        params: Map<String, Any?> = emptyMap(),
    ): List<ViewRow> = resolveDetailed(view, index, loader, schemaFor, params).rows

    /** resolve と同じ。クエリ行に `limit` を書いたときの打ち切り（全行数）も返す。 */
    fun resolveDetailed(
        view: ViewDef,
        index: IdIndex,
        loader: (DocumentId) -> YNode?,
        schemaFor: (DocumentId) -> SchemaDef? = { null },
        params: Map<String, Any?> = emptyMap(),
    ): ResolvedRows {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun treeOf(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }
        return VirtualViews.resolveDetailed(
            view, index.entries, index.refs, index.graph, { it.value }, ::treeOf,
            fieldAt = { d, path -> schemaFor(d)?.let { s -> treeOf(d)?.let { root -> s.fieldAt(root, path) } } },
            params = params,
        )
    }

    /**
     * 列の並びが決まったビュー定義。クロス集計（kind: matrix）は列を索引から広げるので、ヘッダを描く側は
     * resolve と同じ索引でこれを呼ぶ（ふつうのグリッドはそのまま）。
     */
    fun effective(view: ViewDef, index: IdIndex): ViewDef = VirtualViews.lower(view, index.entries) { it.value }

    /**
     * 文書ビューを解決する。開いた文書の fields セクションは描画側（フォーム／テキスト表現）が文書ツリーから直接
     * 描くため、ここでは backlinks セクションの被参照表と、複合文書ビューの区画（`fields.in`＝同じ ID の別の出現・
     * `grid`＝受け取る値つきの表）を座標つきで解決する。
     * - document: 適用先の文書
     * - localIds: 未保存編集を含む文書内のID一覧（UIは DocScanner の結果を渡す。null なら索引から引く）
     * - 被参照は索引（refs）基準＝保存済み状態。行（文書内ID）だけが編集中ツリーへ追随する
     * - schemaFor / catalog: 表の区画の解決に使う（文書のスキーマと、表の定義を引くビューの目録）。catalog を省くと表の区画は理由つきで解決しない
     */
    fun resolveDocument(
        view: DocViewDef,
        document: DocumentId,
        index: IdIndex,
        localIds: List<IdEntry>? = null,
        loader: (DocumentId) -> YNode?,
        schemaFor: (DocumentId) -> SchemaDef? = { null },
        catalog: LoadResult? = null,
    ): List<DocSectionResolved> {
        val trees = mutableMapOf<DocumentId, YNode?>()
        fun treeOf(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }
        return VirtualViews.resolveDocument(
            view, document, index.entries, index.refs, index.graph, localIds, { it.value }, ::treeOf,
            gridDef = { id -> catalog?.view(id) },
            gridRows = { grid, params -> resolve(grid, index, ::treeOf, schemaFor, params) },
        )
    }

    /**
     * 要素単位アタッチ（R14-03）の解決。各アタッチについて「at パターンに一致し、IDを持つ要素」を
     * 行にし、その要素のIDへの被参照を解決する（差し込み先の座標は行の path）。
     */
    fun resolveAttachments(
        view: DocViewDef,
        document: DocumentId,
        index: IdIndex,
        localIds: List<IdEntry>? = null,
        loader: (DocumentId) -> YNode?,
    ): List<DocAttachmentResolved> {
        if (view.attachments.isEmpty()) return emptyList()
        val trees = mutableMapOf<DocumentId, YNode?>()
        return VirtualViews.resolveAttachments(view, document, index.entries, index.refs, index.graph, localIds, { it.value }) { d ->
            trees.getOrPut(d) { loader(d) }
        }
    }

    // ---------------------------------------------------------------- スキーマフィールドの解決

    /**
     * データパスに対応するフィールド定義を、スキーマを辿って解決する（セルエディタの型決定に使う）。
     * 実装は schema 層の `SchemaDef.fieldAt` へ移設した（R23-01。重複所有の検証＝validate 層からも
     * 使うため。architecture/graph-query.md「エンティティページ」）。この委譲は既存呼び出しの互換維持。
     */
    fun fieldAt(schema: SchemaDef, root: YNode, path: YPath): FieldDef? = schema.fieldAt(root, path)

    // ---------------------------------------------------------------- 詳細文書のスキャフォールド
    // 実体はカーネル（workspace-core の DocumentScaffolds・semantic-core の SchemaScaffold。P27 段D の D-c）。ここは窓口

    /**
     * 行の要素から詳細文書の新規作成内容を組み立てる（作成自体は呼び出し側が行う:
     * UIはファイル書き込み、エージェントは create_document）。source は行の要素（ID の定義出現。P31）。
     */
    fun detailScaffold(view: ViewDef, source: ScaffoldSource, schemas: List<SchemaDef>): DocumentScaffold {
        val pattern = view.detail?.filePattern
            ?: error("このビューには detail.filePattern が定義されていません")
        return scaffold(pattern, source, schemas)
    }

    /** 詳細スキーマの match（glob）から `{id}` 置換のファイルパターンを導く（R28-05a・UX-97。決められなければ null）。 */
    fun detailPatternFromMatch(match: String): String? = DocumentScaffolds.detailPatternFromMatch(match)

    /** filePattern（値の式。detail / newRow ファイルモード・カバレッジの対応が共用）と元の要素から新規文書の内容を組み立てる。 */
    fun scaffold(pattern: ExpressionValue, source: ScaffoldSource, schemas: List<SchemaDef>): DocumentScaffold =
        DocumentScaffolds.scaffold(pattern, source, schemas.map { it.kernel })

    /** 文字列のパターンと ID だけから（newRow のファイルモード・テスト）。 */
    fun scaffold(pattern: String, id: String, schemas: List<SchemaDef>): DocumentScaffold =
        DocumentScaffolds.scaffold(pattern, id, schemas.map { it.kernel })

    /**
     * スキーマ定義から必須フィールドの初期値ツリーを生成する（default 尊重・cases 対応）。
     * now を渡すと、保存時に自動で入る値（`auto`）の項目を新しい要素の値（今の日付・時刻／版 1）で埋める。
     */
    fun defaultTree(field: FieldDef, schema: SchemaDef, now: LocalDateTime? = null): YNode =
        SchemaScaffold.defaultTree(schema.tree, field, now = now)

    // ---------------------------------------------------------------- テキスト表現（エージェント・CLI 共用）

    /**
     * 解決済みビューの機械可読テキスト。各セルに原本上の座標（file / path）を添えるため、
     * エージェントはこの出力から直接 apply_patch の対象を決められる。
     * columns 非null のときは、ラベルが一致する列だけを描画する（コンテキストレシピの列絞り。R19-02）。
     * index 非null のときは、ref 値のセルに参照先のタイトルを「ID（タイトル）」で注記する（R19-05）。
     * 描画の本体はカーネル（semantic.VirtualViews.renderText。RC-03）。
     */
    fun renderText(
        view: ViewDef,
        rows: List<ViewRow>,
        columns: List<String>? = null,
        index: IdIndex? = null,
        /** 文書の木の読み手（詳細が未作成の行の作り先を、filePattern の式で決めるのに使う。P31）。 */
        tree: (DocumentId) -> YNode? = { null },
    ): String =
        VirtualViews.renderText(
            view, rows, columns, ids = index?.entries, rel = { it.value },
            detailCreatePath = { row ->
                val pattern = view.detail?.filePattern ?: return@renderText null
                runCatching {
                    DocumentScaffolds.pathFor(pattern, ScaffoldSource.of(row.id, index?.occurrences(row.id).orEmpty(), tree, index?.graph))
                }.getOrNull()
            },
        )

    /**
     * 解決済み文書ビューの機械可読テキスト（エージェント・CLI 共用）。
     * fields セクションは対象座標のみを示し（内容は read_node 等で座標を指定して読む）、
     * backlinks セクションは被参照の一覧を参照値の座標つきで列挙する。描画の本体はカーネル（RC-03）。
     */
    fun renderDocText(
        view: DocViewDef,
        rel: String,
        sections: List<DocSectionResolved>,
        attachments: List<DocAttachmentResolved> = emptyList(),
    ): String = VirtualViews.renderDocText(view, rel, sections, attachments, relOf = { it.value })

    /** セル値の1行要約（本体はカーネル。UI・CLI の既存呼び出しの互換維持）。 */
    fun cellText(value: YNode?, maxLength: Int = 80): String = VirtualViews.cellText(value, maxLength)
}
