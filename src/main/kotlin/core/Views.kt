package core

import index.IdEntry
import index.IdIndex
import index.RefEntry
import index.toIdEntry
import index.toKernelView
import index.toScanned
import semantic.VirtualBacklinksDef
import semantic.VirtualDetailDef
import semantic.VirtualGridDef
import semantic.VirtualRowsDef
import semantic.VirtualViewColumn
import semantic.VirtualViewSource
import semantic.VirtualViews
import model.YNode
import model.YPath
import model.parseYPath
import model.pretty
import schema.DerivationDef
import schema.Display
import schema.FieldDef
import schema.FieldType
import schema.QueryNodeKind
import schema.QuerySpec
import schema.SchemaDef
import schema.SchemaLoader
import schema.SchemaScaffold
import schema.effectiveFields
import schema.fieldAt
import schema.globToRegex
import workspace.DocumentScaffold
import workspace.DocumentScaffolds
import workspace.ViewDefinitions
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

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
typealias ViewDefinition = semantic.VirtualViewDefinition
typealias ViewSource = VirtualViewSource
typealias ViewBacklinksDef = VirtualBacklinksDef
typealias ViewColumn = VirtualViewColumn
typealias ViewRowsDef = VirtualRowsDef
typealias ViewDetailDef = VirtualDetailDef
typealias ViewNewRowDef = semantic.VirtualNewRowDef
typealias ViewDef = VirtualGridDef
typealias DocViewSection = semantic.VirtualDocSection
typealias DocViewAttachment = semantic.VirtualDocAttachment
typealias DocBacklinkColumn = semantic.VirtualDocBacklinkColumn
typealias DocBacklinksDef = semantic.VirtualDocBacklinksDef
typealias DocViewDef = semantic.VirtualDocViewDef

/** バックリンク列の1件。参照元要素を含む最も近いID（その位置にIDがなければ null）と、参照値の座標。 */
data class ViewBacklink(
    val sourceId: String?,
    val sourceTitle: String?,  // 参照元要素のタイトル（索引の表示解決結果。R10-01）
    val rel: String,
    val file: Path,
    val path: YPath,        // 参照値（スカラー）の位置
    val relation: String?,
    val attributes: String? = null,  // 属性付き参照（R20-03）の属性注記（例「role: 承認者」）
)

/** 計算セルの入力1件（R16-04）: プレースホルダの解決値と、入力値の原本座標（未解決なら座標なし）。 */
data class ComputedInput(
    val placeholder: String,   // 例 maxLength（{} なし。スコープ要素からの相対YPath）
    val value: String?,        // 解決値（null = 未解決）
    val rel: String?,
    val file: Path?,
    val path: YPath?,          // 入力値の絶対座標（ジャンプ先）
)

/** 計算セルの来歴（R16-04）: 元値（テンプレート）とプレースホルダごとの解決。UIの導出説明・read_view が共有する。 */
data class ComputedTrace(
    val raw: String,                   // 元値（テンプレート。default 使用時はその値）
    val inputs: List<ComputedInput>,   // プレースホルダの解決（初出順・重複除去）
)

/**
 * 解決済みのセル。file が null のときは対応する出現がない（値なし）。backlinks はバックリンク列のみ非null。
 * computed=true のセルは評価済みの導出値（読み取り専用）で、座標は元値（テンプレート）の位置。
 * trace は導出の来歴（元値と入力値の座標）＝ジャンプ先の選択肢（ARCHITECTURE §4.26）。
 */
data class ViewCell(
    val column: ViewColumn,
    val rel: String?,
    val file: Path?,
    val path: YPath?,       // 絶対YPath（出現要素のパス + 列の相対パス）
    val value: YNode?,
    val backlinks: List<ViewBacklink>? = null,
    val computed: Boolean = false,
    val trace: ComputedTrace? = null,
)

/** 解決済みの1行（＝1論理ID。クエリ行では行キー束縛のID）。 */
data class ViewRow(
    val id: String,
    val title: String?,
    val occurrences: List<IdEntry>,
    val cells: List<ViewCell>,
    val detailRel: String?,          // 詳細出現のファイル（なければ null）
    val detailPath: YPath?,          // 詳細出現の要素パス
    /**
     * 行の同一性キー（R22-02）。クエリ行（結合）ではIDが行間で重複しうるため、束縛値の組で一意化する。
     * 従来の行では id と同じ。UIの行キー（Compose items の key）もこれを使う。
     */
    val key: String = id,
    /** クエリ行の束縛（束縛名 → ノードID。従来の行では null）。 */
    val bindings: Map<String, String>? = null,
)

/** 文書ビューの被参照1件。参照元要素のID・タイトル（表示解決の結果）と、参照値の座標＝ジャンプ先。 */
data class DocBacklinkItem(
    val sourceId: String?,
    val sourceTitle: String?,
    val rel: String,
    val file: Path,
    val refPath: YPath,              // 参照値（スカラー）の位置
    val relation: String?,
    val attributes: String? = null,  // 属性付き参照（R20-03）の属性注記（例「role: 承認者」）
    val values: List<YNode?> = emptyList(),  // columns に対応する参照元要素からの値（列がなければ空）
)

/** 被参照表の1行（＝文書内の対象ID 1件）。 */
data class DocBacklinkRow(
    val id: String,
    val title: String?,
    val path: YPath,                 // 対象IDを持つ要素の文書内パス
    val backlinks: List<DocBacklinkItem>,
)

/** 解決済みの文書ビューセクション。rows は backlinks セクションのみ非null。 */
data class DocSectionResolved(
    val section: DocViewSection,
    val rows: List<DocBacklinkRow>? = null,
)

/** 解決済みの要素単位アタッチ。rows の各行が「差し込み先の要素（path）とその被参照」。 */
data class DocAttachmentResolved(
    val attachment: DocViewAttachment,
    val rows: List<DocBacklinkRow>,
)

object Views {

    data class LoadResult(val views: List<ViewDef>, val docViews: List<DocViewDef>, val errors: List<String>) {
        fun view(id: String): ViewDef? = views.firstOrNull { it.id == id }

        fun docView(id: String): DocViewDef? = docViews.firstOrNull { it.id == id }

        /** 文書に適用する文書ビュー。スキーマの match と同じ「定義ファイル名順の先勝ち」。 */
        fun docViewFor(relPath: String, schemaId: String?): DocViewDef? =
            docViews.firstOrNull { it.appliesTo(relPath, schemaId) }
    }

    /**
     * views ゾーン（`_views/` または `.yde/views/`）からビュー定義を読み込む（エラー収集つき）。
     * schemas を渡すと、definition 参照の列展開（R14-02）と、ビュー→スキーマの整合検査（リント）が有効になる。
     * 読み取りとリントの実体は、カーネルの workspace.ViewDefinitions（P27 段D の D-c）。ここは、ファイルを読んで渡す窓口。
     */
    fun load(workspace: Path, schemas: List<SchemaDef> = emptyList()): LoadResult {
        val dir = workspace.resolve(schema.WorkspaceLayout.of(workspace).viewsDir)
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val sources = files.map { file -> documentIdOf(workspace, file) to Files.readString(file) }
        val catalog = ViewDefinitions.load(sources, schemas.map { it.kernel })
        return LoadResult(catalog.views, catalog.docViews, catalog.errors)
    }

    /** ビュー定義文書の座標（ワークスペース相対の文書ID。ワークスペースの外のパスは、そのままの文字列）。 */
    private fun documentIdOf(workspace: Path?, file: Path): model.DocumentId {
        val abs = file.toAbsolutePath().normalize()
        val base = workspace?.toAbsolutePath()?.normalize()
        val rel = if (base != null && abs.startsWith(base)) base.relativize(abs) else file
        return model.DocumentId(rel.toString().replace('\\', '/'))
    }

    /**
     * ビュー定義1件（編集中の未保存ツリーでもよい）の検査（UX-43）。
     * パース不能はその内容を1件、パースできればリント（ビュー→スキーマの整合検査）の指摘を返す。
     */
    fun lintDocument(root: YNode?, source: Path, schemas: List<SchemaDef>): List<String> =
        ViewDefinitions.lintDocument(root, documentIdOf(null, source), schemas.map { it.kernel })

    /**
     * ビュー定義1件のパース（kind で分岐）。ステージングの定義ゲート（Staging）からも使う。
     * schemas は definition 参照の解決に使う（未指定時は definition を使う定義がパースエラーになる）。
     */
    fun parseView(raw: Map<*, *>, source: Path, schemas: List<SchemaDef> = emptyList()): ViewDefinition =
        ViewDefinitions.parseView(raw, documentIdOf(null, source), schemas.map { it.kernel })

    // ---------------------------------------------------------------- フィールド改名の追随（R25-01）

    // スキーマの静的な解決（リントと共用）の実体は、カーネルの workspace.ViewDefinitions
    private fun idElements(schema: SchemaDef): List<FieldDef> = ViewDefinitions.idElements(schema.kernel)
    private fun staticChildren(schema: SchemaDef, def: FieldDef): List<FieldDef> = ViewDefinitions.staticChildren(schema.kernel, def)
    private fun staticFieldAt(schema: SchemaDef, start: FieldDef, path: List<Any>): FieldDef? =
        ViewDefinitions.staticFieldAt(schema.kernel, start, path)


    /** フィールド改名のビュー追随の結果。ops はビュー定義ファイルごとの書換、warnings は自動書換できない参照箇所。 */
    data class FieldRenameImpact(val ops: Map<Path, List<edit.YamlOperation>>, val warnings: List<String>)

    /**
     * スキーマのフィールド改名（R25-01 / R25-02。ARCHITECTURE §4.35）にビュー定義を追随させる。
     * 自動書換は「パスの静的解決が**全て改名対象を指す**場合」に限る——通常列 `path`・計算列の
     * `computed.path` / `computed.lookup.via`・`newRow.path`・文書ビューの `sections[].fields.path` と
     * include / exclude・被参照表の列 `path` が対象。多義（同名の別フィールドにも解決しうる）は
     * 書き換えず警告する（黙って壊さない）。アタッチ（`at`）等その他の参照箇所は旧名の言及として警告に列挙する。
     */
    internal fun fieldRenameImpact(
        workspace: Path,
        schemas: List<SchemaDef>,
        reachableIds: Set<String>,
        target: FieldDef,
        oldName: String,
        newName: String,
    ): FieldRenameImpact {
        // 計画（内部の正準＝型付き操作）へそのまま合成できる形で生成する（P29 Phase 4・RP-02: 交換形式の PatchOp を
        // 内部の計画生成では作らない）
        val ops = LinkedHashMap<Path, MutableList<edit.YamlOperation>>()
        val warnings = mutableListOf<String>()
        val docSchemas = schemas.filter { it.kind == schema.SchemaKind.DOCUMENT }
        val byId = docSchemas.associateBy { it.id }
        fun representative(glob: String): String = glob
            .replace(Regex("\\{([^}]*)\\}")) { it.groupValues[1].substringBefore(',') }
            .replace("**", "X").replace("*", "X").replace("?", "X")
        fun contexts(schemaId: Any?, fileGlob: Any?): List<SchemaDef> = when {
            schemaId != null -> listOfNotNull(byId[schemaId.toString()])
            fileGlob != null -> representative(fileGlob.toString()).let { rel -> docSchemas.filter { it.matches(rel) } }
            else -> docSchemas.filter { it.id in reachableIds }
        }
        for (file in viewFiles(workspace)) {
            val raw = runCatching { YamlIO.parseToRaw(Files.readString(file)) }.getOrNull() as? Map<*, *> ?: continue
            val handled = mutableSetOf<String>()  // 書換済み・多義警告済みの座標（collectMentions と同じ crumb 形式）

            /** パスを contexts×starts の静的解決で書き換える。全解決が対象を指すときだけ書換、多義は警告。 */
            fun tryRewrite(pathValue: Any?, crumb: String, yamlPath: YPath, ctx: List<SchemaDef>, starts: (SchemaDef) -> List<FieldDef>) {
                val text = pathValue?.toString() ?: return
                val path = runCatching { parseYPath(text) }.getOrNull() ?: return
                if (path.none { it == oldName }) return
                val results = ctx.flatMap { s -> starts(s).mapNotNull { start -> staticRewrite(s, start, path, target, newName) } }
                val rewritten = results.filter { it.second }.map { it.first }.distinct()
                when {
                    rewritten.size == 1 && results.all { it.second } -> {
                        ops.getOrPut(file) { mutableListOf() } += edit.YamlOperation.SetScalar(yamlPath, rewritten.single().pretty())
                        handled += crumb
                    }
                    rewritten.isNotEmpty() -> {
                        warnings += "${file.fileName}: $crumb の path「$text」は同名の別フィールドにも解決しうるため書き換えません（要確認）"
                        handled += crumb
                    }
                    else -> Unit
                }
            }

            /** include / exclude の選別キーを、セクションパス先の子フィールド解決で書き換える。 */
            fun tryRewriteSelection(fm: Map<*, *>, listKey: String, crumb: String, yamlBase: YPath, ctx: List<SchemaDef>) {
                val list = fm[listKey] as? List<*> ?: return
                val sectionPath = runCatching { parseYPath(fm["path"]?.toString() ?: "") }.getOrNull() ?: return
                list.forEachIndexed { j, v ->
                    if (v?.toString() != oldName) return@forEachIndexed
                    val hits = ctx.mapNotNull { s ->
                        val sectionDef = staticFieldAt(s, s.root, sectionPath) ?: return@mapNotNull null
                        val resolved = s.resolve(sectionDef) ?: sectionDef
                        val child = staticChildren(s, resolved).firstOrNull { it.key == oldName } ?: return@mapNotNull null
                        child === target
                    }
                    when {
                        hits.isNotEmpty() && hits.all { it } -> {
                            ops.getOrPut(file) { mutableListOf() } += edit.YamlOperation.SetScalar(yamlBase + listKey + j, newName)
                            handled += crumb
                        }
                        hits.any { it } -> {
                            warnings += "${file.fileName}: $crumb の「$oldName」は同名の別フィールドにも解決しうるため書き換えません（要確認）"
                            handled += crumb
                        }
                        else -> Unit
                    }
                }
            }

            if (raw["kind"]?.toString() != "document") {
                (raw["columns"] as? List<*>)?.forEachIndexed { i, entry ->
                    val m = entry as? Map<*, *> ?: return@forEachIndexed
                    when {
                        m.containsKey("definition") || m.containsKey("backlinks") -> Unit
                        m.containsKey("computed") -> {
                            val comp = m["computed"] as? Map<*, *> ?: return@forEachIndexed
                            val src = comp["source"] as? Map<*, *>
                            tryRewrite(
                                comp["path"], "columns[${i + 1}].computed.path", listOf("columns", i, "computed", "path"),
                                contexts(src?.get("schemaId"), src?.get("fileGlob")), ::idElements,
                            )
                            (comp["lookup"] as? Map<*, *>)?.let { lk ->
                                tryRewrite(
                                    lk["via"], "columns[${i + 1}].computed.lookup.via", listOf("columns", i, "computed", "lookup", "via"),
                                    contexts(lk["schemaId"], lk["fileGlob"]), ::idElements,
                                )
                            }
                        }
                        else -> tryRewrite(
                            m["path"], "columns[${i + 1}].path", listOf("columns", i, "path"),
                            contexts(m["schemaId"], m["fileGlob"]), ::idElements,
                        )
                    }
                }
                (raw["newRow"] as? Map<*, *>)?.let { nr ->
                    val nrFile = nr["file"]?.toString()
                    if (nr["path"] != null && nrFile != null) {
                        // 配列モードの追加先パスは一覧文書のルートから解決する
                        val ctx = docSchemas.filter { it.matches(nrFile.replace('\\', '/')) }
                        tryRewrite(nr["path"], "newRow.path", listOf("newRow", "path"), ctx) { s -> listOf(s.root) }
                    }
                }
            } else {
                val docCtx = when {
                    raw["schemaId"] != null -> listOfNotNull(byId[raw["schemaId"].toString()])
                    raw["match"] != null -> representative(raw["match"].toString()).let { rel -> docSchemas.filter { it.matches(rel) } }
                    else -> emptyList()
                }
                (raw["sections"] as? List<*>)?.forEachIndexed { i, entry ->
                    val m = entry as? Map<*, *> ?: return@forEachIndexed
                    (m["fields"] as? Map<*, *>)?.let { fm ->
                        tryRewrite(
                            fm["path"], "sections[${i + 1}].fields.path", listOf("sections", i, "fields", "path"),
                            docCtx,
                        ) { s -> listOf(s.root) }
                        tryRewriteSelection(fm, "include", "sections[${i + 1}].fields.include", listOf("sections", i, "fields"), docCtx)
                        tryRewriteSelection(fm, "exclude", "sections[${i + 1}].fields.exclude", listOf("sections", i, "fields"), docCtx)
                    }
                    (m["backlinks"] as? Map<*, *>)?.let { bm ->
                        val ctx = stringListOf(bm["schemaIds"]).mapNotNull { byId[it] }
                        (bm["columns"] as? List<*>)?.forEachIndexed { j, c ->
                            val cm = c as? Map<*, *> ?: return@forEachIndexed
                            tryRewrite(
                                cm["path"], "sections[${i + 1}].backlinks.columns[${j + 1}].path",
                                listOf("sections", i, "backlinks", "columns", j, "path"), ctx, ::idElements,
                            )
                        }
                    }
                }
            }
            collectMentions(raw, oldName).filterNot { it in handled }.forEach {
                warnings += "${file.fileName}: $it に「$oldName」の言及があります（自動書換の対象外・要確認）"
            }
        }
        return FieldRenameImpact(ops, warnings)
    }

    /** views ゾーンの定義ファイル一覧（ファイル名順）。スキーマ・リファクタリングの横断書換からも使う。 */
    internal fun viewFiles(workspace: Path): List<Path> {
        val dir = workspace.resolve(schema.WorkspaceLayout.of(workspace).viewsDir)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
    }

    private fun stringListOf(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    /**
     * フィールド名の言及があるビュー定義の警告を列挙する（移動・削除など、パス書換を伴わない
     * リファクタリング操作の影響提示用。R25-02）。
     */
    internal fun fieldMentionWarnings(workspace: Path, fieldName: String, note: String): List<String> =
        viewFiles(workspace).flatMap { file ->
            val raw = runCatching { YamlIO.parseToRaw(Files.readString(file)) }.getOrNull() as? Map<*, *>
                ?: return@flatMap emptyList()
            collectMentions(raw, fieldName).map { "${file.fileName}: $it に「$fieldName」の言及があります（$note）" }
        }

    /** 列パスをID要素起点で静的に辿り、target を指すセグメントを newName へ置換したパスと到達有無を返す。 */
    private fun staticRewrite(
        schema: SchemaDef,
        start: FieldDef,
        path: List<Any>,
        target: FieldDef,
        newName: String,
    ): Pair<YPath, Boolean>? {
        var current = start
        val out = mutableListOf<Any>()
        var hit = false
        for (seg in path) {
            val def = schema.resolve(current) ?: return null
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String -> staticChildren(schema, def).firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && (seg is Int || seg === semantic.VirtualAnyIndex) -> def.item
                else -> null
            }
            if (next == null) return null
            if (next === target && seg is String) { out += newName; hit = true } else out += seg
            current = next
        }
        return out to hit
    }

    /**
     * ビュー定義の生YAMLから、パス系のキー（path / via / at / include / exclude）に含まれる旧名の言及を集める。
     * 戻り値は座標（例 `columns[2].path`）。fieldRenameImpact の書換済み座標フィルタと同じ形式。
     */
    private fun collectMentions(raw: Any?, oldName: String): List<String> {
        val out = mutableListOf<String>()
        fun containsOld(text: String): Boolean =
            runCatching { parseYPath(text.replace("[*]", "[-1]")) }.getOrNull()?.any { it == oldName } ?: false
        fun walk(node: Any?, crumb: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) ->
                    val key = k.toString()
                    val here = if (crumb.isEmpty()) key else "$crumb.$key"
                    when {
                        key in setOf("path", "via", "at") && v != null && v !is Map<*, *> && v !is List<*> ->
                            if (containsOld(v.toString())) out += here
                        key in setOf("include", "exclude") && v is List<*> ->
                            if (v.any { it?.toString() == oldName }) out += here
                        else -> walk(v, here)
                    }
                }
                is List<*> -> node.forEachIndexed { i, v -> walk(v, "$crumb[${i + 1}]") }
                else -> Unit
            }
        }
        walk(raw, "")
        return out
    }

    // ---------------------------------------------------------------- 解決

    /**
     * ビューを行×セルに解決する。
     * - index: 行の集合と出現の解決に使う（UIはディスク索引、エージェントはオーバーレイ索引を渡す）
     * - loader: セル値の読み取り（UIは未保存編集を優先する loader、エージェントはステージングの tree）
     *
     * 6E 第1弾（RC-01）: 解決の本体はカーネル（semantic.VirtualViews）へ移し、ここは
     * Path↔DocumentId を写す変換シェル。旧テスト（ViewsTest）がシェル経由で緑＝カーネル一致の回帰証明。
     */
    fun resolve(
        view: ViewDef,
        workspace: Path,
        index: IdIndex,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<ViewRow> {
        val kernel = index.toKernelView()
        fun fileOf(document: model.DocumentId): Path = Path.of(document.value)
        val rel = { document: model.DocumentId -> IdIndex.relPathOf(workspace, fileOf(document)) }
        val trees = mutableMapOf<Path, YNode?>()
        val tree = { document: model.DocumentId -> trees.getOrPut(fileOf(document)) { loader(fileOf(document)) } }
        return VirtualViews.resolve(view, kernel.entries, kernel.refs, kernel.graph, rel, tree)
            .map { row ->
                ViewRow(
                    id = row.id,
                    title = row.title,
                    occurrences = row.occurrences.map { it.toIdEntry() },
                    cells = row.cells.mapIndexed { i, cell ->
                        ViewCell(
                            column = view.columns[i],
                            rel = cell.document?.let(rel),
                            file = cell.document?.let(::fileOf),
                            path = cell.path,
                            value = cell.value,
                            backlinks = cell.backlinks?.map { b ->
                                ViewBacklink(b.sourceId, b.sourceTitle, rel(b.document), fileOf(b.document), b.path, b.relation, b.attributes)
                            },
                            computed = cell.computed,
                            trace = cell.trace?.let { t ->
                                ComputedTrace(
                                    t.raw,
                                    t.inputs.map { ComputedInput(it.placeholder, it.value, it.document?.let(rel), it.document?.let(::fileOf), it.path) },
                                )
                            },
                        )
                    },
                    detailRel = row.detailDocument?.let(rel),
                    detailPath = row.detailPath,
                    key = row.key,
                    bindings = row.bindings,
                )
            }
    }

    // ---------------------------------------------------------------- 文書ビューの解決（R14-01）

    /**
     * 文書ビューを解決する。fields セクションは描画側（フォーム／テキスト表現）が文書ツリーから直接
     * 描くため、ここでは backlinks セクションの被参照表だけを座標つきで解決する。
     * - localIds: 未保存編集を含む文書内のID一覧（UIは DocScanner の結果を渡す。null なら索引から引く）
     * - 被参照は索引（refs）基準＝保存済み状態。行（文書内ID）だけが編集中ツリーへ追随する
     */
    fun resolveDocument(
        view: DocViewDef,
        workspace: Path,
        file: Path,
        index: IdIndex,
        localIds: List<IdEntry>? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<DocSectionResolved> {
        // RC-02: 解決の本体はカーネル（semantic.VirtualViews.resolveDocument）。ここは変換シェル
        val kernel = index.toKernelView()
        val rel = { document: model.DocumentId -> IdIndex.relPathOf(workspace, Path.of(document.value)) }
        val trees = mutableMapOf<Path, YNode?>()
        val tree = { document: model.DocumentId ->
            val f = Path.of(document.value)
            trees.getOrPut(f) { loader(f) }
        }
        return VirtualViews.resolveDocument(
            view, model.DocumentId(file.toString()),
            kernel.entries, kernel.refs, kernel.graph,
            localIds?.map { it.toScanned() }, rel, tree,
        ).mapIndexed { i, resolved ->
            DocSectionResolved(view.sections[i], resolved.rows?.map { toDocRow(it, rel) })
        }
    }

    /**
     * 要素単位アタッチ（R14-03）の解決。各アタッチについて「at パターンに一致し、IDを持つ要素」を
     * 行にし、その要素のIDへの被参照を解決する（差し込み先の座標は行の path）。
     */
    fun resolveAttachments(
        view: DocViewDef,
        workspace: Path,
        file: Path,
        index: IdIndex,
        localIds: List<IdEntry>? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<DocAttachmentResolved> {
        if (view.attachments.isEmpty()) return emptyList()
        val kernel = index.toKernelView()
        val rel = { document: model.DocumentId -> IdIndex.relPathOf(workspace, Path.of(document.value)) }
        val trees = mutableMapOf<Path, YNode?>()
        val tree = { document: model.DocumentId ->
            val f = Path.of(document.value)
            trees.getOrPut(f) { loader(f) }
        }
        return VirtualViews.resolveAttachments(
            view, model.DocumentId(file.toString()),
            kernel.entries, kernel.refs, kernel.graph,
            localIds?.map { it.toScanned() }, rel, tree,
        ).mapIndexed { i, resolved ->
            DocAttachmentResolved(view.attachments[i], resolved.rows.map { toDocRow(it, rel) })
        }
    }

    // ---------------------------------------------------------------- カーネル宣言への写し（文書ビュー。RC-02）

    private fun toDocRow(row: semantic.VirtualDocBacklinkRow, rel: (model.DocumentId) -> String): DocBacklinkRow =
        DocBacklinkRow(
            id = row.id,
            title = row.title,
            path = row.path,
            backlinks = row.backlinks.map { b ->
                DocBacklinkItem(
                    sourceId = b.sourceId,
                    sourceTitle = b.sourceTitle,
                    rel = rel(b.document),
                    file = Path.of(b.document.value),
                    refPath = b.refPath,
                    relation = b.relation,
                    attributes = b.attributes,
                    values = b.values,
                )
            },
        )

    // ---------------------------------------------------------------- スキーマフィールドの解決

    /**
     * データパスに対応するフィールド定義を、スキーマを辿って解決する（セルエディタの型決定に使う）。
     * 実装は schema 層の `SchemaDef.fieldAt` へ移設した（R23-01。重複所有の検証＝validate 層からも
     * 使うため。ARCHITECTURE §4.33）。この委譲は既存呼び出しの互換維持。
     */
    fun fieldAt(schema: SchemaDef, root: YNode, path: YPath): FieldDef? = schema.fieldAt(root, path)

    // ---------------------------------------------------------------- 詳細文書のスキャフォールド
    // 実体はカーネル（workspace-core の DocumentScaffolds・semantic-core の SchemaScaffold。P27 段D の D-c）。ここは窓口

    /**
     * 行のIDから詳細文書の新規作成内容を組み立てる（作成自体は呼び出し側が行う:
     * UIはファイル書き込み、エージェントは create_document）。
     */
    fun detailScaffold(view: ViewDef, id: String, schemas: List<SchemaDef>): DocumentScaffold {
        val pattern = view.detail?.filePattern
            ?: error("このビューには detail.filePattern が定義されていません")
        return scaffold(pattern, id, schemas)
    }

    /** 詳細スキーマの match（glob）から `{id}` 置換のファイルパターンを導く（R28-05a・UX-97。決められなければ null）。 */
    fun detailPatternFromMatch(match: String): String? = DocumentScaffolds.detailPatternFromMatch(match)

    /** filePattern（detail / newRow ファイルモード共用）とIDから新規文書の内容を組み立てる。 */
    fun scaffold(pattern: String, id: String, schemas: List<SchemaDef>): DocumentScaffold =
        DocumentScaffolds.scaffold(pattern, id, schemas.map { it.kernel })

    /** スキーマ定義から必須フィールドの初期値ツリーを生成する（default 尊重・cases 対応）。 */
    fun defaultTree(field: FieldDef, schema: SchemaDef): YNode = SchemaScaffold.defaultTree(schema.tree, field)

    // ---------------------------------------------------------------- テキスト表現（エージェント・CLI 共用）

    /**
     * 解決済みビューの機械可読テキスト。各セルに原本上の座標（file / path）を添えるため、
     * エージェントはこの出力から直接 apply_patch の対象を決められる。
     * columns 非null のときは、ラベルが一致する列だけを描画する（コンテキストレシピの列絞り。R19-02）。
     * index 非null のときは、ref 値のセルに参照先のタイトルを「ID（タイトル）」で注記する（R19-05）。
     *
     * RC-03: 描画の本体はカーネル（semantic.VirtualViews.renderText）。ここは解決済み行を
     * 読み出しモデルへ写す変換シェル（rel は行が既に持つ相対パス文字列を再利用する）。
     */
    fun renderText(view: ViewDef, rows: List<ViewRow>, columns: List<String>? = null, index: IdIndex? = null): String {
        val relByDoc = mutableMapOf<model.DocumentId, String>()
        fun doc(file: Path?, rel: String?): model.DocumentId? =
            file?.let { f -> model.DocumentId(f.toString()).also { d -> rel?.let { relByDoc[d] = it } } }
        val kernelRows = rows.map { row ->
            semantic.VirtualRow(
                id = row.id,
                title = row.title,
                occurrences = row.occurrences.map { it.toScanned() },
                cells = row.cells.map { cell ->
                    semantic.VirtualCell(
                        column = cell.column,
                        document = doc(cell.file, cell.rel),
                        path = cell.path,
                        value = cell.value,
                        backlinks = cell.backlinks?.map { b ->
                            semantic.VirtualBacklink(b.sourceId, b.sourceTitle, doc(b.file, b.rel)!!, b.path, b.relation, b.attributes)
                        },
                        computed = cell.computed,
                        trace = cell.trace?.let { t ->
                            semantic.VirtualComputedTrace(
                                t.raw,
                                t.inputs.map { semantic.VirtualComputedInput(it.placeholder, it.value, doc(it.file, it.rel), it.path) },
                            )
                        },
                    )
                },
                detailDocument = row.detailRel?.let { r -> model.DocumentId(r).also { relByDoc[it] = r } },
                detailPath = row.detailPath,
                key = row.key,
                bindings = row.bindings,
            )
        }
        return VirtualViews.renderText(
            view, kernelRows, columns,
            ids = index?.toKernelView()?.entries,
            rel = { relByDoc[it] ?: it.value },
        )
    }

    /**
     * 解決済み文書ビューの機械可読テキスト（エージェント・CLI 共用）。
     * fields セクションは対象座標のみを示し（内容は read_node 等で座標を指定して読む）、
     * backlinks セクションは被参照の一覧を参照値の座標つきで列挙する。
     *
     * RC-03: 描画の本体はカーネル（semantic.VirtualViews.renderDocText）。ここは変換シェル。
     */
    fun renderDocText(
        view: DocViewDef,
        rel: String,
        sections: List<DocSectionResolved>,
        attachments: List<DocAttachmentResolved> = emptyList(),
    ): String {
        val relByDoc = mutableMapOf<model.DocumentId, String>()
        fun toKernelRow(row: DocBacklinkRow): semantic.VirtualDocBacklinkRow = semantic.VirtualDocBacklinkRow(
            id = row.id,
            title = row.title,
            path = row.path,
            backlinks = row.backlinks.map { b ->
                val d = model.DocumentId(b.file.toString()).also { relByDoc[it] = b.rel }
                semantic.VirtualDocBacklinkItem(b.sourceId, b.sourceTitle, d, b.refPath, b.relation, b.attributes, b.values)
            },
        )
        return VirtualViews.renderDocText(
            view, rel,
            sections.map { s -> semantic.VirtualDocSectionResolved(s.section, s.rows?.map(::toKernelRow)) },
            attachments.map { a -> semantic.VirtualDocAttachmentResolved(a.attachment, a.rows.map(::toKernelRow)) },
            relOf = { relByDoc[it] ?: it.value },
        )
    }

    /** セル値の1行要約（本体はカーネル。UI・CLI の既存呼び出しの互換維持）。 */
    fun cellText(value: YNode?, maxLength: Int = 80): String = VirtualViews.cellText(value, maxLength)
}
