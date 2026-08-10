package core

import index.Derivation
import index.IdEntry
import index.IdIndex
import index.Query
import index.RefEntry
import index.idMatchesPattern
import model.YNode
import model.YPath
import model.getAt
import model.parseYPath
import model.pretty
import model.scalarText
import schema.DerivationDef
import schema.Display
import schema.FieldDef
import schema.FieldType
import schema.QueryNodeKind
import schema.QuerySpec
import schema.SchemaDef
import schema.SchemaLoader
import schema.effectiveFields
import schema.fieldAt
import schema.globToRegex
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

/** ビュー定義の共通部。kind により横断グリッド（ViewDef）と文書ビュー（DocViewDef）に分かれる。 */
sealed interface ViewDefinition {
    val id: String
    val name: String
    val description: String?
    val source: Path
}

/** 列が値を取り出す出現の選択条件。schemaId / fileGlob の AND（null は制限なし）。 */
data class ViewSource(
    val schemaId: String? = null,
    val fileGlob: String? = null,
) {
    private val regex: Regex? by lazy { fileGlob?.let { runCatching { globToRegex(it) }.getOrNull() } }

    fun matches(entry: IdEntry, rel: String): Boolean =
        (schemaId == null || entry.schemaId == schemaId) &&
            (regex == null || regex!!.matches(rel))

    val isEmpty: Boolean get() = schemaId == null && fileGlob == null
}

/**
 * バックリンク列の条件。行IDへの参照（被参照）を関係種別・参照元スキーマで絞る。
 * 1:N詳細化（新ID＋親への ref。R7-02）の「親から見た分裂先の一覧」を表示する。
 */
data class ViewBacklinksDef(
    val relation: String? = null,
    val schemaIds: List<String> = emptyList(),
) {
    fun matches(ref: RefEntry): Boolean =
        (relation == null || ref.relation == relation) &&
            (schemaIds.isEmpty() || ref.sourceSchemaId in schemaIds)
}

/** ビューの1列。出現要素からの相対YPathで値を指すか、backlinks / computed で派生表示の列にする。 */
data class ViewColumn(
    val label: String,
    val from: ViewSource = ViewSource(),
    val path: YPath,
    val editable: Boolean = false,
    val backlinks: ViewBacklinksDef? = null,  // 非null のとき被参照一覧の列（path / editable とは排他）
    /**
     * 非null のとき計算列（path / backlinks / editable とは排他。R16-02）。
     * 宣言モデル（lookup / source / path / values / map / default）と解決は整合検証と共有する
     * （schema/Derivation.kt・index/Derivation.kt。ARCHITECTURE §4.26）。
     */
    val computed: DerivationDef? = null,
    /**
     * クエリ行（rows: match）の値元の束縛名（R22-02。省略時は行キーの束縛。ARCHITECTURE §4.32）。
     * 結合先ノードの値をセル化する——セルの座標は束縛ノードの原本座標で、編集経路は従来のまま。
     */
    val of: String? = null,
)

/** 行の集合の定義。idPattern に一致する論理IDが行になる。 */
data class ViewRowsDef(
    val idPattern: String,
    val schemaIds: List<String> = emptyList(),  // 空でなければ、いずれかのスキーマに出現するIDのみ行にする
)

/** 詳細文書の扱い。source に一致する出現を「詳細」と見なし、filePattern から新規作成できる。 */
data class ViewDetailDef(
    val source: ViewSource = ViewSource(),
    val filePattern: String? = null,   // 例 "designs/{id}.yaml"（{id} が行のIDで置換される）
)

/**
 * 新規行の入口。2モードのどちらか一方を持つ（パースで排他を強制）。
 * - 配列モード（file + path）: 一覧文書内の配列へ新しい要素を追加する
 * - ファイルモード（filePattern）: `{id}` を入力IDで置換したパスへ詳細文書そのものを新規作成する
 *   （一覧文書を持たず詳細文書だけでIDを運用するレイアウト向け。作成されるのは通常の原本文書）
 */
data class ViewNewRowDef(
    val file: String? = null,
    val path: YPath = emptyList(),
    val filePattern: String? = null,
)

data class ViewDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    /** 従来の行定義（idPattern×schemaIds）。クエリ行（rowsQuery）とは排他で、どちらか一方が非null。 */
    val rows: ViewRowsDef? = null,
    /** クエリ行（rows: match。R22-02 / ARCHITECTURE §4.32）: 行 = パターン照合の束縛行。 */
    val rowsQuery: QuerySpec? = null,
    /** クエリ行の行キーの束縛名（rowsQuery 非null のとき非null。kind: entity 限定）。 */
    val rowKey: String? = null,
    val columns: List<ViewColumn>,
    val orderBy: ViewSource? = null,   // この条件に一致する出現の文書内順で行を並べる（省略時はID順。クエリ行では併用不可）
    val detail: ViewDetailDef? = null,
    val newRow: ViewNewRowDef? = null,
    override val source: Path,
) : ViewDefinition

// ---------------------------------------------------------------- 文書ビュー（R14-01）

/**
 * 文書ビューのセクション1件。fields（スキーマ駆動フォームの射影）か backlinks（被参照表）のどちらか一方を持つ。
 * fields セクションの編集は既存のフォーム経路（FormCtx / Slot）に委譲され、backlinks セクションは
 * 読み取り専用の派生表示（ジャンプのみ）——ビュー専用の書込経路は作らない（ARCHITECTURE §4.14）。
 */
data class DocViewSection(
    val label: String? = null,
    val fieldsPath: YPath? = null,           // 非null = fields セクション（空リスト = ルート全体）
    val fieldsDefinition: String? = null,    // fields が参照する named definition（module#name。リントで実使用を検査）
    val fieldsInclude: List<String> = emptyList(),  // object の表示キーの選別（この順で表示。exclude と排他。R14-03）
    val fieldsExclude: List<String> = emptyList(),  // object の表示キーの除外（R14-03）
    val backlinks: DocBacklinksDef? = null,  // 非null = backlinks セクション（fieldsPath とは排他）
)

/** アタッチ先パターンの「任意のインデックス」（`[*]`）。 */
private object AnyIndex {
    override fun toString(): String = "[*]"
}

/**
 * 要素単位アタッチ（R14-03）: `at` パターンに一致する要素の展開フォーム内に、その要素のIDへの
 * 被参照表を差し込む。セクション（文書レベルの合成）と違い、フォーム中の要素の位置に描画される。
 * 対象要素にIDがなければ何も差し込まれない（被参照はIDに対して解決されるため）。
 */
data class DocViewAttachment(
    val label: String? = null,
    val atText: String,              // 定義に書かれたパターン（表示用）
    val at: List<Any>,               // 要素パスのパターン（キー / インデックス / AnyIndex）
    val backlinks: DocBacklinksDef,
) {
    /** 要素パスがこのアタッチの対象か。`[*]` は任意のインデックスに一致する。 */
    fun matches(path: YPath): Boolean =
        at.size == path.size && at.zip(path).all { (p, seg) -> (p === AnyIndex && seg is Int) || p == seg }
}

/** 被参照表の1列。参照元要素からの相対YPathで値を取り出す（表示専用）。 */
data class DocBacklinkColumn(val label: String, val path: YPath)

/**
 * 被参照表の定義。文書内の対象ID（idPattern。null = 文書内の全ID）ごとに、
 * そのIDへの参照（被参照）を relation・参照元スキーマで絞って一覧する。
 */
data class DocBacklinksDef(
    val idPattern: String? = null,
    val filter: ViewBacklinksDef = ViewBacklinksDef(),
    val columns: List<DocBacklinkColumn> = emptyList(),
)

/**
 * 文書ビュー（kind: document）。match（glob）／schemaId で適用先文書を決め、sections を上から順に描画する。
 * マッチしない文書は従来のスキーマ駆動フォーム＝暗黙の既定ビューで表示される（opt-in）。
 */
data class DocViewDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    val match: String? = null,      // 適用先ファイルの glob（schemaId と併記時は AND）
    val schemaId: String? = null,   // 適用先文書のスキーマID
    val sections: List<DocViewSection>,
    val attachments: List<DocViewAttachment> = emptyList(),  // 要素単位アタッチ（R14-03）
    override val source: Path,
) : ViewDefinition {
    private val regex: Regex? by lazy { match?.let { runCatching { globToRegex(it) }.getOrNull() } }

    /** ワークスペース相対パス（区切りは '/'）の文書にこのビューを適用するか。 */
    fun appliesTo(relPath: String, schemaId: String?): Boolean =
        (match == null || regex?.matches(relPath) == true) &&
            (this.schemaId == null || this.schemaId == schemaId)
}

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
        val all = mutableListOf<ViewDefinition>()
        val errors = mutableListOf<String>()
        files.forEach { file ->
            runCatching {
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseView(raw, file, schemas)
            }.onSuccess { all += it }
                .onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }
        }
        all.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "ビューID「$id」が重複しています（${duplicates.joinToString(", ") { it.source.fileName.toString() }}）"
        }
        val views = all.filterIsInstance<ViewDef>()
        val docViews = all.filterIsInstance<DocViewDef>()
        if (schemas.isNotEmpty()) errors += lint(views, docViews, schemas)
        return LoadResult(views = views, docViews = docViews, errors = errors)
    }

    /**
     * ビュー定義1件（編集中の未保存ツリーでもよい）の検査（UX-43）。
     * パース不能はその内容を1件、パースできればリント（ビュー→スキーマの整合検査）の指摘を返す。
     * 検証パネルが `_views` の文書を開いている間、保存を待たずにその場で指摘を出すために使う。
     */
    fun lintDocument(root: YNode?, source: Path, schemas: List<SchemaDef>): List<String> {
        if (root == null) return emptyList()  // パースエラーは検証本体（Validator）が報告する
        val raw = runCatching { YamlIO.parseToRaw(YamlIO.dumpPlain(root)) }.getOrNull() as? Map<*, *>
            ?: return listOf("ビュー定義のルートはobjectである必要があります")
        val def = runCatching { parseView(raw, source, schemas) }
            .getOrElse { return listOf(it.message ?: (it::class.simpleName ?: "定義を解析できません")) }
        return when (def) {
            is ViewDef -> lint(listOf(def), emptyList(), schemas)
            is DocViewDef -> lint(emptyList(), listOf(def), schemas)
            else -> emptyList()
        }
    }

    /**
     * ビュー定義1件のパース（kind で分岐）。ステージングの定義ゲート（Staging）からも使う。
     * schemas は definition 参照の解決に使う（未指定時は definition を使う定義がパースエラーになる）。
     */
    fun parseView(raw: Map<*, *>, source: Path, schemas: List<SchemaDef> = emptyList()): ViewDefinition =
        when (val kind = raw["kind"]?.toString()) {
            null, "grid" -> parseGridView(raw, source, schemas)
            "document" -> parseDocView(raw, source, schemas)
            else -> error("kind は grid / document のいずれかである必要があります: $kind")
        }

    /** 横断グリッド（kind: grid）のパース。 */
    private fun parseGridView(raw: Map<*, *>, source: Path, schemas: List<SchemaDef>): ViewDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        if (raw.containsKey("sections")) error("sections は kind: document のビューでのみ使えます")
        val rowsMap = raw["rows"] as? Map<*, *> ?: error("rows が必要です")
        // 行の定義: 従来（idPattern×schemaIds）とクエリ行（match。R22-02 / ARCHITECTURE §4.32）の排他
        var rowsDef: ViewRowsDef? = null
        var rowsQuery: QuerySpec? = null
        var rowKey: String? = null
        if (rowsMap.containsKey("match")) {
            listOf("idPattern", "schemaIds").firstOrNull { rowsMap.containsKey(it) }
                ?.let { error("rows: match と $it は併用できません（従来の行定義とクエリ行は排他）") }
            if (raw.containsKey("order")) error("rows: match のビューに order は使えません（並びはクエリの決定的順序）")
            val spec = QuerySpec.parse(rowsMap, "rows", ignoreKeys = setOf("rowKey"))
            require(spec.binds.isNotEmpty()) { "rows.match には bind（束縛名）が1つ以上必要です" }
            val keyName = rowsMap["rowKey"]?.toString()?.takeIf { it.isNotBlank() } ?: spec.binds.first()
            require(keyName in spec.binds) { "rows.rowKey の束縛「$keyName」が match に見つかりません" }
            require(spec.bindStep(keyName)?.kind == QueryNodeKind.ENTITY) {
                "rows.rowKey の束縛「$keyName」は kind: entity である必要があります（行キーは論理ID）"
            }
            rowsQuery = spec
            rowKey = keyName
        } else {
            val idPattern = rowsMap["idPattern"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("rows.idPattern か rows.match が必要です")
            rowsDef = ViewRowsDef(idPattern = idPattern, schemaIds = stringList(rowsMap["schemaIds"]))
        }
        val binds = rowsQuery?.binds
        val columnsRaw = raw["columns"] as? List<*> ?: error("columns が必要です")
        require(columnsRaw.isNotEmpty()) { "columns には1列以上が必要です" }
        val columns = columnsRaw.flatMapIndexed { i: Int, entry: Any? ->
            val m = entry as? Map<*, *> ?: error("columns[$i] はobjectである必要があります")
            // of（R22-02）: クエリ行の値元の束縛。従来の行定義では使えない
            val of = m["of"]?.toString()?.takeIf { it.isNotBlank() }
            if (of != null) {
                if (binds == null) error("columns[$i]: of は rows: match のビューでのみ使えます")
                require(of in binds) { "columns[$i]: of の束縛「$of」が rows.match に見つかりません" }
            }
            if (m.containsKey("definition")) {
                if (of != null) error("columns[$i]: definition と of は併用できません")
                // definition 参照列（R14-02）: named definition のセル型フィールドを列集合に展開する
                return@flatMapIndexed expandDefinitionColumns(m, i, schemas)
            }
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$i].label が必要です")
            if (m.containsKey("computed")) {
                // 計算列（R16-02）: 読み取り専用の派生表示のため、出現の選択・編集・他の派生列とは排他。
                // 宣言のパースは整合検証（consistency.expected）と共有する（SchemaLoader.parseDerivation）
                listOf("path", "backlinks", "schemaId", "fileGlob").firstOrNull { m.containsKey(it) }
                    ?.let { error("columns[$i]: computed と $it は併用できません") }
                if (m["editable"] == true) error("columns[$i]: computed 列は editable にできません（読み取り専用の派生表示）")
                return@flatMapIndexed listOf(
                    ViewColumn(
                        label = label,
                        path = emptyList(),
                        computed = SchemaLoader.parseDerivation(m["computed"], "columns[$i].computed"),
                        of = of,
                    )
                )
            }
            if (m.containsKey("backlinks")) {
                // バックリンク列: 被参照の派生表示のため、出現の選択（path / schemaId / fileGlob）や編集とは排他
                val b = m["backlinks"] as? Map<*, *> ?: error("columns[$i].backlinks はobjectである必要があります")
                if (m["path"] != null) error("columns[$i]: path と backlinks は併用できません")
                if (m["editable"] == true) error("columns[$i]: backlinks 列は editable にできません")
                if (m["schemaId"] != null || m["fileGlob"] != null) {
                    error("columns[$i]: backlinks 列に schemaId / fileGlob は指定できません（絞り込みは backlinks 配下に書きます）")
                }
                listOf(
                    ViewColumn(
                        label = label,
                        path = emptyList(),
                        backlinks = ViewBacklinksDef(
                            relation = b["relation"]?.toString(),
                            schemaIds = stringList(b["schemaIds"]),
                        ),
                        of = of,
                    )
                )
            } else listOf(
                ViewColumn(
                    label = label,
                    from = parseSource(m),
                    path = parseYPath(m["path"]?.toString() ?: error("columns[$i].path が必要です")),
                    editable = m["editable"] as? Boolean ?: false,
                    of = of,
                )
            )
        }
        val detail = (raw["detail"] as? Map<*, *>)?.let { m ->
            val pattern = m["filePattern"]?.toString()
            pattern?.let { validateFilePattern("detail", it) }
            ViewDetailDef(source = parseSource(m), filePattern = pattern)
        }
        val newRow = (raw["newRow"] as? Map<*, *>)?.let { m ->
            val pattern = m["filePattern"]?.toString()?.takeIf { it.isNotBlank() }
            if (pattern != null) {
                // ファイルモード: 配列モードのキーとは排他（どちらの意図か曖昧な定義を弾く）
                listOf("file", "path").firstOrNull { m.containsKey(it) }
                    ?.let { error("newRow: filePattern と $it は併用できません（配列モードとファイルモードは排他）") }
                validateFilePattern("newRow", pattern)
                ViewNewRowDef(filePattern = pattern)
            } else ViewNewRowDef(
                file = m["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("newRow.file か newRow.filePattern が必要です"),
                path = parseYPath(m["path"]?.toString() ?: error("newRow.path が必要です")),
            )
        }
        return ViewDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            rows = rowsDef,
            rowsQuery = rowsQuery,
            rowKey = rowKey,
            columns = columns,
            orderBy = (raw["order"] as? Map<*, *>)?.let { parseSource(it) }?.takeIf { !it.isEmpty },
            detail = detail,
            newRow = newRow,
            source = source,
        )
    }

    /** filePattern（detail / newRow 共通）の形式検査。`{id}` 置換でYAMLファイル名になること。 */
    private fun validateFilePattern(where: String, pattern: String) {
        require(pattern.contains("{id}")) { "$where.filePattern には {id} を含めてください: $pattern" }
        require(pattern.endsWith(".yaml") || pattern.endsWith(".yml")) { "$where.filePattern はYAMLファイルである必要があります: $pattern" }
    }

    /**
     * definition 参照列（R14-02）の展開。named definition（object）のセル型フィールド
     * （`Display.tableColumns` と同じ規則。ID型は行キーと重複するため除く）を列に展開し、
     * ラベル・パスをスキーマから導出する（列定義の重複記載をなくす）。
     */
    private fun expandDefinitionColumns(m: Map<*, *>, index: Int, schemas: List<SchemaDef>): List<ViewColumn> {
        listOf("label", "path", "backlinks").firstOrNull { m.containsKey(it) }
            ?.let { error("columns[$index]: definition と $it は併用できません（ラベル・パスは定義から導出されます）") }
        val from = parseSource(m)
        val ref = m["definition"]?.toString()?.takeIf { it.isNotBlank() } ?: error("columns[$index].definition が空です")
        val (schema, target) = resolveDefinition(ref, from.schemaId, schemas)
            ?: error("columns[$index]: definition「$ref」が見つかりません")
        val resolved = schema.resolve(target) ?: target
        require(resolved.type == FieldType.OBJECT) { "columns[$index]: definition「$ref」は object である必要があります" }
        val editable = m["editable"] as? Boolean ?: false
        val columns = resolved.fields.mapNotNull { field ->
            val key = field.key ?: return@mapNotNull null
            val eff = schema.resolve(field) ?: field
            if (eff.type !in Display.CELL_TYPES || eff.type == FieldType.ID) return@mapNotNull null
            ViewColumn(label = eff.label ?: key, from = from, path = listOf(key), editable = editable)
        }
        require(columns.isNotEmpty()) { "columns[$index]: definition「$ref」に列にできるセル型フィールドがありません" }
        return columns
    }

    /**
     * definition 参照（`module#name`。非修飾は fallbackModule の definitions から引く）を解決する。
     * schemas 未指定（定義ゲート以外からの素のパース）では解決できないため null。
     */
    private fun resolveDefinition(
        ref: String,
        fallbackModule: String?,
        schemas: List<SchemaDef>,
    ): Pair<SchemaDef, FieldDef>? {
        if (schemas.isEmpty()) error("definition「$ref」の解決にはスキーマ情報が必要です")
        val hash = ref.indexOf('#')
        val moduleId = if (hash >= 0) ref.substring(0, hash)
        else fallbackModule ?: error("definition「$ref」には module#name 形式か、schemaId の指定が必要です")
        val name = if (hash >= 0) ref.substring(hash + 1) else ref
        val schema = schemas.firstOrNull { it.id == moduleId } ?: return null
        val target = schema.definitions[name] ?: return null
        return schema to target
    }

    /** 文書ビュー（kind: document。R14-01）のパース。 */
    private fun parseDocView(raw: Map<*, *>, source: Path, schemas: List<SchemaDef>): DocViewDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        listOf("rows", "columns", "order", "detail", "newRow").firstOrNull { raw.containsKey(it) }
            ?.let { error("$it は kind: grid のビューでのみ使えます") }
        val match = raw["match"]?.toString()?.takeIf { it.isNotBlank() }
        val schemaId = raw["schemaId"]?.toString()?.takeIf { it.isNotBlank() }
        if (match == null && schemaId == null) error("文書ビューには match（適用先glob）か schemaId が必要です")
        match?.let { runCatching { globToRegex(it) }.getOrElse { e -> error("match が不正です: ${e.message}") } }
        val sectionsRaw = raw["sections"] as? List<*> ?: error("sections が必要です")
        require(sectionsRaw.isNotEmpty()) { "sections には1つ以上のセクションが必要です" }
        val sections = sectionsRaw.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("sections[$i] はobjectである必要があります")
            val label = m["label"]?.toString()?.takeIf { it.isNotBlank() }
            val fieldsRaw = m["fields"]
            val backlinksRaw = m["backlinks"]
            if ((fieldsRaw != null) == (backlinksRaw != null)) {
                error("sections[$i]: fields か backlinks のどちらか一方が必要です")
            }
            if (fieldsRaw != null) {
                val fm = fieldsRaw as? Map<*, *> ?: error("sections[$i].fields はobjectである必要があります（空なら {}）")
                // definition（R14-02）: このセクションが参照する named definition の宣言。修飾名へ正規化して保持し、
                // 存在（パース・ゲート）と実使用（リント）を検査する
                val definition = fm["definition"]?.toString()?.takeIf { it.isNotBlank() }?.let { ref ->
                    val qualified = if (ref.contains('#')) ref
                    else "${schemaId ?: error("sections[$i].fields.definition: 非修飾名「$ref」を使うにはビューの schemaId が必要です")}#$ref"
                    if (schemas.isNotEmpty() && resolveDefinition(qualified, null, schemas) == null) {
                        error("sections[$i].fields.definition「$qualified」が見つかりません")
                    }
                    qualified
                }
                // 表示キーの選別（R14-03）: include はこの順で表示、exclude は除外（両立不可）
                val include = stringList(fm["include"])
                val exclude = stringList(fm["exclude"])
                if (include.isNotEmpty() && exclude.isNotEmpty()) {
                    error("sections[$i].fields: include と exclude は併用できません")
                }
                DocViewSection(
                    label = label,
                    fieldsPath = parseYPath(fm["path"]?.toString() ?: ""),
                    fieldsDefinition = definition,
                    fieldsInclude = include,
                    fieldsExclude = exclude,
                )
            } else {
                val bm = backlinksRaw as? Map<*, *> ?: error("sections[$i].backlinks はobjectである必要があります")
                DocViewSection(label = label, backlinks = parseDocBacklinks(bm, "sections[$i].backlinks"))
            }
        }
        val attachments = (raw["attachments"] as? List<*>)?.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("attachments[$i] はobjectである必要があります")
            val atText = m["at"]?.toString()?.takeIf { it.isNotBlank() } ?: error("attachments[$i].at が必要です")
            val bm = m["backlinks"] as? Map<*, *> ?: error("attachments[$i].backlinks が必要です")
            DocViewAttachment(
                label = m["label"]?.toString()?.takeIf { it.isNotBlank() },
                atText = atText,
                at = runCatching { parseAttachPattern(atText) }
                    .getOrElse { error("attachments[$i].at が不正です: ${it.message}") },
                backlinks = parseDocBacklinks(bm, "attachments[$i].backlinks"),
            )
        } ?: emptyList()
        return DocViewDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            match = match,
            schemaId = schemaId,
            sections = sections,
            attachments = attachments,
            source = source,
        )
    }

    /** 被参照表の定義（セクション・アタッチ共用）のパース。 */
    private fun parseDocBacklinks(bm: Map<*, *>, where: String): DocBacklinksDef {
        val columns = (bm["columns"] as? List<*>)?.mapIndexed { ci, c ->
            val cm = c as? Map<*, *> ?: error("$where.columns[$ci] はobjectである必要があります")
            DocBacklinkColumn(
                label = cm["label"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.columns[$ci].label が必要です"),
                path = parseYPath(cm["path"]?.toString() ?: error("$where.columns[$ci].path が必要です")),
            )
        } ?: emptyList()
        return DocBacklinksDef(
            idPattern = bm["idPattern"]?.toString()?.takeIf { it.isNotBlank() },
            filter = ViewBacklinksDef(
                relation = bm["relation"]?.toString(),
                schemaIds = stringList(bm["schemaIds"]),
            ),
            columns = columns,
        )
    }

    /**
     * アタッチ先のパスパターン。YPath 構文＋ `[*]`（任意のインデックス）。
     * 実装は `[*]` を仮のインデックス -1 として parseYPath に通し、あとから AnyIndex へ戻す
     * （リテラルの `[-1]` はデータのパスとして成立しないため衝突しない）。
     */
    private fun parseAttachPattern(text: String): List<Any> =
        parseYPath(text.replace("[*]", "[-1]")).map { seg -> if (seg == -1) AnyIndex else seg }

    /** 出現の選択条件（schemaId / fileGlob）を、定義の当該オブジェクト直下のキーから読む。 */
    private fun parseSource(m: Map<*, *>): ViewSource = ViewSource(
        schemaId = m["schemaId"]?.toString(),
        fileGlob = m["fileGlob"]?.toString(),
    )

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    // ---------------------------------------------------------------- ビュー定義リント（R14-02）

    /**
     * ビュー→スキーマの参照（schemaId・パス・definition）の整合検査。スキーマ変更でビューが黙って
     * 壊れる事態をロード時に検出する。指摘はビューを無効化しない（解決・表示は従来どおり行われる）。
     * パスの検査は「スキーマのID要素（ID型フィールドを持つ object）のいずれかから静的に解決できるか」で
     * 判定する（出現要素は必ずID要素のため。ID要素が見つからないスキーマでは判定しない＝誤検知を避ける）。
     */
    private fun lint(views: List<ViewDef>, docViews: List<DocViewDef>, schemas: List<SchemaDef>): List<String> {
        val errors = mutableListOf<String>()
        val ids = schemas.map { it.id }.toSet()
        val byId = schemas.associateBy { it.id }
        val idElementsCache = mutableMapOf<String, List<FieldDef>>()
        fun idElementsOf(s: SchemaDef): List<FieldDef> = idElementsCache.getOrPut(s.id) { idElements(s) }
        fun unresolvableFromIdElements(s: SchemaDef, path: YPath): Boolean {
            if (path.isEmpty()) return false
            val candidates = idElementsOf(s)
            return candidates.isNotEmpty() && candidates.none { staticFieldAt(s, it, path) != null }
        }
        // fileGlob だけの出現選択は、代表パス（glob のワイルドカードを仮埋め）にマッチするスキーマで検査する
        // （newRow.filePattern の検査と同じ流儀。マッチするスキーマが無ければ判定しない＝誤検知を避ける。UX-36）
        fun representative(glob: String): String = glob
            .replace(Regex("\\{([^}]*)\\}")) { it.groupValues[1].substringBefore(',') }
            .replace("**", "X").replace("*", "X").replace("?", "X")
        fun schemasBy(schemaId: String?, fileGlob: String?): List<SchemaDef> = when {
            schemaId != null -> listOfNotNull(byId[schemaId])
            fileGlob != null -> representative(fileGlob).let { rel -> schemas.filter { it.matches(rel) } }
            else -> emptyList()
        }
        fun schemasFor(sel: ViewSource): List<SchemaDef> = schemasBy(sel.schemaId, sel.fileGlob)
        fun names(sels: List<SchemaDef>): String = sels.joinToString("、") { it.id }

        views.forEach { v ->
            fun report(msg: String) { errors += "${v.source.fileName}: $msg" }
            v.rows?.schemaIds?.filterNot { it in ids }?.forEach { report("rows.schemaIds のスキーマ「$it」が見つかりません") }
            v.orderBy?.schemaId?.let { if (it !in ids) report("order のスキーマ「$it」が見つかりません") }
            v.detail?.source?.schemaId?.let { if (it !in ids) report("detail のスキーマ「$it」が見つかりません") }
            v.columns.forEach { col ->
                col.backlinks?.schemaIds?.filterNot { it in ids }?.forEach {
                    report("列「${col.label}」の参照元スキーマ「$it」が見つかりません")
                }
                if (col.backlinks != null) return@forEach
                col.computed?.let { comp ->
                    // 計算列（R16-02）: 参照スキーマの存在と、via / path がID要素から静的に解決できるかを検査する
                    (listOfNotNull(comp.lookup?.from?.schemaId, comp.source.schemaId) + comp.values.mapNotNull { it.schemaId })
                        .filterNot { it in ids }.distinct()
                        .forEach { report("列「${col.label}」の計算列のスキーマ「$it」が見つかりません") }
                    comp.lookup?.let { lk ->
                        val lkSchemas = schemasBy(lk.from.schemaId, lk.from.fileGlob)
                        if (lkSchemas.isNotEmpty() && lkSchemas.all { unresolvableFromIdElements(it, lk.via) }) {
                            report("列「${col.label}」の lookup.via「${lk.via.pretty()}」はスキーマ「${names(lkSchemas)}」のID要素から解決できません")
                        }
                    }
                    val srcSchemas = schemasBy(comp.source.schemaId, comp.source.fileGlob)
                    if (srcSchemas.isNotEmpty() && srcSchemas.all { unresolvableFromIdElements(it, comp.path) }) {
                        report("列「${col.label}」の computed.path「${comp.path.pretty()}」はスキーマ「${names(srcSchemas)}」のID要素から解決できません")
                    }
                    return@forEach
                }
                col.from.schemaId?.let { sid ->
                    if (sid !in ids) { report("列「${col.label}」のスキーマ「$sid」が見つかりません"); return@forEach }
                }
                val fromSchemas = schemasFor(col.from)
                if (fromSchemas.isNotEmpty() && fromSchemas.all { unresolvableFromIdElements(it, col.path) }) {
                    report("列「${col.label}」のパス「${col.path.pretty()}」はスキーマ「${names(fromSchemas)}」のID要素から解決できません")
                }
            }
            v.newRow?.filePattern?.let { pattern ->
                // 代表ID（idPattern の * を仮埋め。クエリ行では行キー束縛の id パターン・なければ "X"）で
                // 作成先を解決し、スキーマなし・detail 認定外の事故を検出する
                val representativeId = (v.rows?.idPattern ?: v.rowKey?.let { v.rowsQuery?.bindStep(it)?.idPattern })
                    ?.replace("*", "X") ?: "X"
                val rel = pattern.replace("{id}", representativeId)
                val matched = schemas.firstOrNull { it.matches(rel) }
                if (matched == null) {
                    report("newRow.filePattern「$pattern」の作成先にマッチするスキーマがありません（作成される文書が空になります）")
                }
                v.detail?.source?.let { src ->
                    val globOk = src.fileGlob?.let { g ->
                        runCatching { globToRegex(g).matches(rel) }.getOrDefault(true)
                    } ?: true
                    val schemaOk = src.schemaId == null || matched == null || matched.id == src.schemaId
                    if (!globOk || !schemaOk) {
                        report("newRow.filePattern「$pattern」の作成先が detail の認定条件に一致しません（作成直後の行が「未作成」になります）")
                    }
                }
            }
        }

        docViews.forEach { v ->
            fun report(msg: String) { errors += "${v.source.fileName}: $msg" }
            val docSchema = v.schemaId?.let { sid ->
                byId[sid] ?: run { report("適用先スキーマ「$sid」が見つかりません"); null }
            }
            v.sections.forEachIndexed { i, s ->
                val where = s.label ?: "sections[${i + 1}]"
                val fieldsPath = s.fieldsPath
                if (fieldsPath != null && docSchema != null) {
                    val field = staticFieldAt(docSchema, docSchema.root, fieldsPath)
                    if (fieldsPath.isNotEmpty() && field == null) {
                        report("セクション「$where」のパス「${fieldsPath.pretty()}」はスキーマ「${docSchema.id}」から解決できません")
                    } else if (s.fieldsDefinition != null && field != null) {
                        // 宣言した definition を、パス先のフィールド（配列は要素・map は値）が実際に参照しているか
                        val resolved = docSchema.resolve(field) ?: field
                        val actual = listOfNotNull(field, resolved.item, resolved.value)
                            .firstNotNullOfOrNull { docSchema.definitionRefOf(it) }
                        if (actual != s.fieldsDefinition) {
                            report(
                                "セクション「$where」のパス「${fieldsPath.pretty()}」は definition「${s.fieldsDefinition}」を参照していません" +
                                    (actual?.let { "（実際: $it）" } ?: "")
                            )
                        }
                    }
                    // 選別キー（include / exclude）がスキーマの子フィールドに存在するか（R14-03）
                    val selection = s.fieldsInclude + s.fieldsExclude
                    if (selection.isNotEmpty() && field != null) {
                        val resolvedField = docSchema.resolve(field) ?: field
                        if (resolvedField.type == FieldType.OBJECT) {
                            val keys = staticChildren(docSchema, resolvedField).mapNotNull { it.key }.toSet()
                            selection.filterNot { it in keys }.forEach {
                                report("セクション「$where」の選別キー「$it」はスキーマにありません")
                            }
                        }
                    }
                }
                s.backlinks?.let { b ->
                    b.filter.schemaIds.filterNot { it in ids }.forEach {
                        report("セクション「$where」の参照元スキーマ「$it」が見つかりません")
                    }
                    val sourceSchemas = b.filter.schemaIds.mapNotNull { byId[it] }
                    if (b.columns.isNotEmpty() && sourceSchemas.isNotEmpty()) {
                        b.columns.forEach { col ->
                            val ok = sourceSchemas.any { !unresolvableFromIdElements(it, col.path) }
                            if (!ok) {
                                report("セクション「$where」の列「${col.label}」のパス「${col.path.pretty()}」は参照元スキーマのID要素から解決できません")
                            }
                        }
                    }
                }
            }
            v.attachments.forEachIndexed { i, a ->
                val where = a.label ?: "attachments[${i + 1}]"
                a.backlinks.filter.schemaIds.filterNot { it in ids }.forEach {
                    report("アタッチ「$where」の参照元スキーマ「$it」が見つかりません")
                }
                val sourceSchemas = a.backlinks.filter.schemaIds.mapNotNull { byId[it] }
                if (a.backlinks.columns.isNotEmpty() && sourceSchemas.isNotEmpty()) {
                    a.backlinks.columns.forEach { col ->
                        val ok = sourceSchemas.any { !unresolvableFromIdElements(it, col.path) }
                        if (!ok) {
                            report("アタッチ「$where」の列「${col.label}」のパス「${col.path.pretty()}」は参照元スキーマのID要素から解決できません")
                        }
                    }
                }
                if (docSchema != null) {
                    val target = staticFieldAt(docSchema, docSchema.root, a.at)
                    if (target == null) {
                        report("アタッチ「$where」の at「${a.atText}」はスキーマ「${docSchema.id}」から解決できません")
                    } else {
                        // アタッチ先の要素にIDがなければ被参照を解決できず、何も表示されない
                        val resolvedTarget = docSchema.resolve(target) ?: target
                        val hasId = resolvedTarget.type == FieldType.OBJECT &&
                            staticChildren(docSchema, resolvedTarget).any { (docSchema.resolve(it) ?: it).type == FieldType.ID }
                        if (!hasId) {
                            report("アタッチ「$where」の at「${a.atText}」の要素にID型フィールドがありません（被参照はIDに対して解決されます）")
                        }
                    }
                }
            }
        }
        return errors
    }

    /** スキーマ内の「ID要素」（ID型フィールドを持つ object）の候補。出現要素からのパス検査の起点に使う。 */
    private fun idElements(schema: SchemaDef): List<FieldDef> {
        val result = mutableListOf<FieldDef>()
        fun walk(field: FieldDef?, depth: Int, stack: Set<String>) {
            if (field == null || depth > 24) return
            // 再帰スキーマ（$ref の循環）は definition の修飾名で打ち切る（generateSample と同じ規則）
            val refKey = schema.definitionRefOf(field)
            if (refKey != null && refKey in stack) return
            val nextStack = if (refKey == null) stack else stack + refKey
            val f = schema.resolve(field) ?: return
            when (f.type) {
                FieldType.OBJECT -> {
                    val children = staticChildren(schema, f)
                    if (children.any { (schema.resolve(it) ?: it).type == FieldType.ID }) result += f
                    children.forEach { walk(it, depth + 1, nextStack) }
                }
                FieldType.ARRAY -> walk(f.item, depth + 1, nextStack)
                FieldType.MAP -> walk(f.value, depth + 1, nextStack)
                else -> Unit
            }
        }
        walk(schema.root, 0, emptySet())
        return result
    }

    /** object の静的な子フィールド候補（enum cases は全ケースの和＝「存在しうる子」。ノードなしの検査用）。 */
    private fun staticChildren(schema: SchemaDef, def: FieldDef): List<FieldDef> {
        val out = mutableListOf<FieldDef>()
        fun add(field: FieldDef, depth: Int) {
            out += field
            if (depth > 8) return
            val eff = schema.resolve(field) ?: field
            if (eff.type == FieldType.ENUM) eff.cases.values.flatten().forEach { add(it, depth + 1) }
        }
        def.fields.forEach { add(it, 0) }
        return out
    }

    /**
     * データノードなしでスキーマ定義だけを辿るフィールド解決（リント用の `fieldAt` の静的版）。
     * 戻り値は**未解決**のフィールド（definition 判定に `$ref` を残す）。配列は任意インデックス
     * （アタッチパターンの `[*]` 含む）、map は任意キーとして辿る。解決できなければ null。
     */
    private fun staticFieldAt(schema: SchemaDef, start: FieldDef, path: List<Any>): FieldDef? {
        var current = start
        for (seg in path) {
            val def = schema.resolve(current) ?: return null
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String -> staticChildren(schema, def).firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && (seg is Int || seg === AnyIndex) -> def.item
                else -> null
            }
            current = next ?: return null
        }
        return current
    }

    // ---------------------------------------------------------------- 解決

    /**
     * ビューを行×セルに解決する。
     * - index: 行の集合と出現の解決に使う（UIはディスク索引、エージェントはオーバーレイ索引を渡す）
     * - loader: セル値の読み取り（UIは未保存編集を優先する loader、エージェントはステージングの tree）
     */
    fun resolve(
        view: ViewDef,
        workspace: Path,
        index: IdIndex,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<ViewRow> {
        val trees = mutableMapOf<Path, YNode?>()
        fun tree(file: Path): YNode? = trees.getOrPut(file) { loader(file) }
        fun rel(file: Path): String = IdIndex.relPathOf(workspace, file)

        // バックリンク列の「参照元要素のID」解決に使う。参照位置を含む最も長いパスの出現を最内のIDとみなす
        val entriesByFile: Map<Path, List<IdEntry>> by lazy { index.entries.values.flatten().groupBy { it.file } }
        fun backlinksFor(id: String, def: ViewBacklinksDef): List<ViewBacklink> =
            index.refs.filter { it.targetId == id && def.matches(it) }
                .map { ref ->
                    val source = entriesByFile[ref.file].orEmpty()
                        .filter { it.path.size <= ref.path.size && ref.path.subList(0, it.path.size) == it.path }
                        .maxByOrNull { it.path.size }
                    ViewBacklink(source?.id, source?.title, rel(ref.file), ref.file, ref.path, ref.relation, ref.attributeSummary())
                }
                .sortedWith(compareBy({ it.rel }, { pathSortKey(it.path) }))

        // クエリ行（rows: match。R22-02 / ARCHITECTURE §4.32）: 行 = パターン照合の束縛行。
        // 並びはクエリの決定的順序（sortRows は通さない）、セルは束縛ノード（of。省略時は行キー）の原本座標へ解決する
        val query = view.rowsQuery
        if (query != null) {
            val rowKeyName = view.rowKey ?: query.binds.first()
            return Query.run(index, query, ::tree).rows.map { qr ->
                val keyNode = qr.bindings.getValue(rowKeyName)
                val occurrences = index.occurrences(keyNode.id)
                val cells = view.columns.map { column ->
                    val node = qr.bindings[column.of ?: rowKeyName]
                    val backlinksDef = column.backlinks
                    val computedDef = column.computed
                    when {
                        node == null -> ViewCell(column, null, null, null, null)
                        backlinksDef != null ->
                            ViewCell(column, null, null, null, null, backlinks = backlinksFor(node.id, backlinksDef))
                        computedDef != null ->
                            computedCell(column, computedDef, node.id, index.occurrences(node.id), index, ::tree, ::rel)
                        node.kind == QueryNodeKind.DOCUMENT -> {
                            // 文書ノードのセル: 文書ルートからの相対パス（座標はその文書＝通常の原本編集経路）
                            val file = node.file
                            if (file == null) ViewCell(column, null, null, null, null)
                            else ViewCell(column, rel(file), file, column.path, tree(file)?.let { getAt(it, column.path) })
                        }
                        else -> {
                            val occurrence = index.occurrences(node.id).firstOrNull { column.from.matches(it, rel(it.file)) }
                            if (occurrence == null) ViewCell(column, null, null, null, null)
                            else {
                                val absolute = occurrence.path + column.path
                                ViewCell(
                                    column = column,
                                    rel = rel(occurrence.file),
                                    file = occurrence.file,
                                    path = absolute,
                                    value = tree(occurrence.file)?.let { getAt(it, absolute) },
                                )
                            }
                        }
                    }
                }
                val detailOccurrence = view.detail?.let { d ->
                    occurrences.firstOrNull { o -> d.source.matches(o, rel(o.file)) }
                }
                ViewRow(
                    id = keyNode.id,
                    title = keyNode.title ?: occurrences.firstNotNullOfOrNull { it.title },
                    occurrences = occurrences,
                    cells = cells,
                    detailRel = detailOccurrence?.let { rel(it.file) },
                    detailPath = detailOccurrence?.path,
                    key = qr.bindings.entries.joinToString("|") { (k, v) -> "$k=${v.id}" },
                    bindings = qr.bindings.mapValues { it.value.id },
                )
            }
        }

        val rowsDef = view.rows ?: error("ビュー「${view.id}」に行の定義（rows）がありません")
        val rows = index.entries.asSequence()
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
                        else occurrences.firstOrNull { column.from.matches(it, rel(it.file)) }
                    if (backlinksDef != null) ViewCell(column, null, null, null, null, backlinks = backlinksFor(id, backlinksDef))
                    else if (computedDef != null) computedCell(column, computedDef, id, occurrences, index, ::tree, ::rel)
                    else if (occurrence == null) ViewCell(column, null, null, null, null)
                    else {
                        val absolute = occurrence.path + column.path
                        ViewCell(
                            column = column,
                            rel = rel(occurrence.file),
                            file = occurrence.file,
                            path = absolute,
                            value = tree(occurrence.file)?.let { getAt(it, absolute) },
                        )
                    }
                }
                val detailOccurrence = view.detail?.let { d ->
                    occurrences.firstOrNull { o -> d.source.matches(o, rel(o.file)) }
                }
                ViewRow(
                    id = id,
                    title = occurrences.firstNotNullOfOrNull { it.title },
                    occurrences = occurrences,
                    cells = cells,
                    detailRel = detailOccurrence?.let { rel(it.file) },
                    detailPath = detailOccurrence?.path,
                )
            }
            .toList()
        return sortRows(view, rows) { rel(it) }
    }

    /**
     * 計算セル（R16-02）の解決。対象ID（lookup があれば ref を1ホップ辿った先、なければ行のID）の
     * source 出現から元値（テンプレート）を読み、`schema/Projection` でプレースホルダを評価する。
     * - スコープ連鎖は「source 要素 → values で選んだ対象IDの出現要素 → lookup の起点要素」の近い順（1段評価）
     * - セルの座標は元値の位置＝ジャンプ先。評価できないプレースホルダは `{...}` のまま残す（ARCHITECTURE §4.26）
     */
    private fun computedCell(
        column: ViewColumn,
        def: DerivationDef,
        rowId: String,
        occurrences: List<IdEntry>,
        index: IdIndex,
        tree: (Path) -> YNode?,
        rel: (Path) -> String,
    ): ViewCell {
        // 解決は整合検証と共有の Derivation（index/Derivation.kt）。ビューで見えている導出値と
        // 検証される期待値を常に一致させる（ARCHITECTURE §4.26）
        val result = Derivation.resolve(def, rowId, index, rel, tree, occurrences)
        return ViewCell(
            column = column,
            rel = result.sourceFile?.let(rel),
            file = result.sourceFile,
            path = result.sourcePath,
            value = result.value?.let { YNode.Scalar(it) },
            computed = true,
            trace = result.raw?.let { raw ->
                ComputedTrace(
                    raw,
                    result.inputs.map { ComputedInput(it.placeholder, it.value, it.file?.let(rel), it.file, it.path) },
                )
            },
        )
    }

    /** 行の並び: orderBy 出現の（ファイル→文書内パス）順 → それ以外はID順で後ろに。 */
    private fun sortRows(view: ViewDef, rows: List<ViewRow>, rel: (Path) -> String): List<ViewRow> {
        val orderBy = view.orderBy ?: return rows.sortedBy { it.id }
        fun anchor(row: ViewRow): IdEntry? = row.occurrences.firstOrNull { orderBy.matches(it, rel(it.file)) }
        val comparator = compareBy<Pair<ViewRow, IdEntry?>>(
            { (_, a) -> if (a == null) 1 else 0 },
            { (_, a) -> a?.let { rel(it.file) } ?: "" },
            { (_, a) -> a?.path?.let(::pathSortKey) ?: "" },
            { (row, _) -> row.id },
        )
        return rows.map { it to anchor(it) }.sortedWith(comparator).map { it.first }
    }

    /** YPath の文書内順を保つソートキー（配列インデックスはゼロ詰めで桁を揃える）。 */
    private fun pathSortKey(path: YPath): String =
        path.joinToString("/") { seg -> if (seg is Int) "%08d".format(seg) else seg.toString() }

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
        val resolver = DocResolver(workspace, file, index, localIds, loader)
        return view.sections.map { section ->
            val def = section.backlinks ?: return@map DocSectionResolved(section)
            DocSectionResolved(section, resolver.rowsFor(def))
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
        val resolver = DocResolver(workspace, file, index, localIds, loader)
        return view.attachments.map { attachment ->
            DocAttachmentResolved(
                attachment,
                resolver.rowsFor(attachment.backlinks) { attachment.matches(it.path) },
            )
        }
    }

    /** 文書ビュー解決の共有部（文書内ID・参照元ツリーのキャッシュ・被参照行の構築）。 */
    private class DocResolver(
        private val workspace: Path,
        file: Path,
        private val index: IdIndex,
        localIds: List<IdEntry>?,
        private val loader: (Path) -> YNode?,
    ) {
        private val trees = mutableMapOf<Path, YNode?>()
        private val docIds: List<IdEntry> = localIds ?: index.entries.values.flatten().filter { it.file == file }

        private fun tree(f: Path): YNode? = trees.getOrPut(f) { loader(f) }

        fun rowsFor(def: DocBacklinksDef, elementFilter: (IdEntry) -> Boolean = { true }): List<DocBacklinkRow> =
            docIds.asSequence()
                .filter(elementFilter)
                .filter { def.idPattern == null || idMatchesPattern(it.id, def.idPattern) }
                .sortedBy { pathSortKey(it.path) }
                .distinctBy { it.id }
                .map { row(it, def) }
                .toList()

        private fun row(entry: IdEntry, def: DocBacklinksDef): DocBacklinkRow {
            val backlinks = index.refs
                .filter { it.targetId == entry.id && def.filter.matches(it) }
                .map { ref ->
                    // 参照元要素＝参照位置を含む最内のID出現（グリッドのバックリンク列と同じ解決）
                    val sourceEntry = index.sourceEntryOf(ref)
                    val values =
                        if (def.columns.isEmpty() || sourceEntry == null) def.columns.map { null }
                        else def.columns.map { col ->
                            tree(ref.file)?.let { getAt(it, sourceEntry.path + col.path) }
                        }
                    DocBacklinkItem(
                        sourceId = sourceEntry?.id,
                        sourceTitle = sourceEntry?.title,
                        rel = IdIndex.relPathOf(workspace, ref.file),
                        file = ref.file,
                        refPath = ref.path,
                        relation = ref.relation,
                        attributes = ref.attributeSummary(),
                        values = values,
                    )
                }
                .sortedWith(compareBy({ it.rel }, { pathSortKey(it.refPath) }))
            return DocBacklinkRow(id = entry.id, title = entry.title, path = entry.path, backlinks = backlinks)
        }
    }

    // ---------------------------------------------------------------- スキーマフィールドの解決

    /**
     * データパスに対応するフィールド定義を、スキーマを辿って解決する（セルエディタの型決定に使う）。
     * 実装は schema 層の `SchemaDef.fieldAt` へ移設した（R23-01。重複所有の検証＝validate 層からも
     * 使うため。ARCHITECTURE §4.33）。この委譲は既存呼び出しの互換維持。
     */
    fun fieldAt(schema: SchemaDef, root: YNode, path: YPath): FieldDef? = schema.fieldAt(root, path)

    // ---------------------------------------------------------------- 詳細文書のスキャフォールド

    data class Scaffold(val rel: String, val root: YNode)

    /**
     * 行のIDから詳細文書の新規作成内容を組み立てる（作成自体は呼び出し側が行う:
     * UIはファイル書き込み、エージェントは create_document）。
     */
    fun detailScaffold(view: ViewDef, id: String, schemas: List<SchemaDef>): Scaffold {
        val pattern = view.detail?.filePattern
            ?: error("このビューには detail.filePattern が定義されていません")
        return scaffold(pattern, id, schemas)
    }

    /**
     * filePattern（detail / newRow ファイルモード共用）とIDから新規文書の内容を組み立てる。
     * スキーマは `{id}` 置換後のパスにマッチするものを使い、必須フィールドの初期値と
     * ルート直下の ID フィールドへIDを設定する。
     */
    fun scaffold(pattern: String, id: String, schemas: List<SchemaDef>): Scaffold {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "ファイル名に使用できないIDです: $id"
        }
        val rel = pattern.replace("{id}", id)
        val schema = schemas.firstOrNull { it.matches(rel) }
        val root = schema?.let { defaultTree(it.resolve(it.root) ?: it.root, it) } ?: YNode.Map()
        (root as? YNode.Map)?.let { map ->
            val idKey = schema?.let { s ->
                (s.resolve(s.root) ?: s.root).fields.firstOrNull { (s.resolve(it) ?: it).type == FieldType.ID }?.key
            } ?: "id"
            map.entries[idKey] = YNode.Scalar(id)
        }
        return Scaffold(rel, root)
    }

    /** スキーマ定義から必須フィールドの初期値ツリーを生成する（default 尊重・cases 対応）。 */
    fun defaultTree(field: FieldDef, schema: SchemaDef, depth: Int = 0): YNode {
        if (depth > 24) return YNode.Map()
        val f = schema.resolve(field) ?: field
        if (f.default != null) return YamlIO.fromAny(f.default)
        return when (f.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    f.fields.filter { it.required && it.key != null }
                        .forEach { put(it.key!!, defaultTree(it, schema, depth + 1)) }
                }
                val map = YNode.Map(entries)
                schema.effectiveFields(f.fields, map).fields.forEach { child ->
                    val key = child.key ?: return@forEach
                    if (child.required && key !in entries) entries[key] = defaultTree(child, schema, depth + 1)
                }
                map
            }
            FieldType.ARRAY -> YNode.Seq()
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            else -> YNode.Scalar("")
        }
    }

    // ---------------------------------------------------------------- テキスト表現（エージェント・CLI 共用）

    /**
     * 解決済みビューの機械可読テキスト。各セルに原本上の座標（file / path）を添えるため、
     * エージェントはこの出力から直接 apply_patch の対象を決められる。
     */
    /**
     * columns 非null のときは、ラベルが一致する列だけを描画する（コンテキストレシピの列絞り。R19-02）。
     * index 非null のときは、ref 値のセルに参照先のタイトルを「ID（タイトル）」で注記する（R19-05）。
     */
    fun renderText(view: ViewDef, rows: List<ViewRow>, columns: List<String>? = null, index: IdIndex? = null): String = buildString {
        appendLine("ビュー: ${view.name}（${view.id}）")
        view.description?.let { appendLine(it.trim()) }
        val rowsLabel = view.rows?.let { "idPattern: ${it.idPattern}" }
            ?: "matchクエリ・行キー: ${view.rowKey}"
        appendLine("行: ${rows.size} 件（$rowsLabel）")
        view.newRow?.let { nr ->
            // エージェントが「新しい行の作り方」を出力から判断できるようにする（配列＝apply_patch／ファイル＝create_document）
            if (nr.filePattern != null) {
                appendLine("新規行: ${nr.filePattern} の {id} を新しいIDに置換したパスへ create_document で文書を作成すると行になる")
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
                            listOfNotNull(b.sourceId ?: b.rel, b.sourceTitle).joinToString(" ") +
                                (b.attributes?.let { "（$it）" } ?: "") +
                                "  [" + b.rel + " :: " + b.path.pretty() + "]"
                        }
                    )
                } else if (cell.file == null) {
                    appendLine(if (cell.computed) "（導出元なし）" else "（出現なし）")
                } else {
                    append(refAnnotatedCellText(cell.value, index))
                    append("  [").append(cell.rel).append(" :: ").append(cell.path!!.pretty())
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
                                if (input.rel != null && input.path != null) {
                                    append(" [").append(input.rel).append(" :: ").append(input.path.pretty()).append("]")
                                }
                            }
                        }
                        appendLine()
                    }
                }
            }
            if (view.detail != null) {
                if (row.detailRel != null) appendLine("    詳細: ${row.detailRel}")
                else appendLine(
                    "    詳細: 未作成" +
                        (view.detail.filePattern?.let { "（filePattern: $it → ${it.replace("{id}", row.id)}）" } ?: "")
                )
            }
        }
    }.trimEnd()

    /**
     * 解決済み文書ビューの機械可読テキスト（エージェント・CLI 共用）。
     * fields セクションは対象座標のみを示し（内容は read_node 等で座標を指定して読む）、
     * backlinks セクションは被参照の一覧を参照値の座標つきで列挙する。
     */
    fun renderDocText(
        view: DocViewDef,
        rel: String,
        sections: List<DocSectionResolved>,
        attachments: List<DocAttachmentResolved> = emptyList(),
    ): String = buildString {
        appendLine("文書ビュー: ${view.name}（${view.id}） 対象: $rel")
        view.description?.let { appendLine(it.trim()) }
        sections.forEachIndexed { i, resolved ->
            val section = resolved.section
            if (section.fieldsPath != null) {
                val at = if (section.fieldsPath.isEmpty()) "(root)" else section.fieldsPath.pretty()
                append("セクション${i + 1}: ${section.label ?: "フォーム"}（フォーム）  [$rel :: $at]")
                if (section.fieldsInclude.isNotEmpty()) append("（選別: ${section.fieldsInclude.joinToString(", ")}）")
                if (section.fieldsExclude.isNotEmpty()) append("（除外: ${section.fieldsExclude.joinToString(", ")}）")
                appendLine()
            } else {
                appendLine("セクション${i + 1}: ${section.label ?: "被参照"}（被参照表）")
                appendBacklinkRows(rel, section.backlinks, resolved.rows.orEmpty())
            }
        }
        attachments.forEach { resolved ->
            appendLine("アタッチ: ${resolved.attachment.label ?: "被参照"}（at: ${resolved.attachment.atText}）")
            appendBacklinkRows(rel, resolved.attachment.backlinks, resolved.rows)
        }
    }.trimEnd()

    /** 被参照表の行群のテキスト表現（セクション・アタッチ共用）。 */
    private fun StringBuilder.appendBacklinkRows(rel: String, def: DocBacklinksDef?, rows: List<DocBacklinkRow>) {
        if (rows.isEmpty()) appendLine("    （対象IDなし）")
        for (row in rows) {
            append("- ").append(row.id)
            row.title?.let { append("  ").append(it) }
            appendLine("  [$rel :: ${row.path.pretty()}]")
            if (row.backlinks.isEmpty()) appendLine("    （被参照なし）")
            for (b in row.backlinks) {
                append("    ← ")
                append(listOfNotNull(b.sourceId ?: b.rel, b.sourceTitle).joinToString(" "))
                b.attributes?.let { append("（").append(it).append("）") }
                append("  [").append(b.rel).append(" :: ").append(b.refPath.pretty()).append("]")
                def?.columns?.forEachIndexed { ci, col ->
                    append("  ").append(col.label).append(": ").append(cellText(b.values.getOrNull(ci)))
                }
                appendLine()
            }
        }
    }

    /** セル値の1行要約。 */
    /**
     * ref 値の注記つきセル表示（R19-05）: 索引でタイトルへ解決できるIDは「ID（タイトル）」を添える。
     * 従来「（配列 n 件）」だった参照配列も、全要素がスカラーで1件以上IDに解決できるなら中身を展開する。
     * 値そのものはID（括弧はエージェント向けの注記）——パッチにはIDだけを書く前提は変えない。
     */
    private fun refAnnotatedCellText(value: YNode?, index: IdIndex?): String {
        if (index == null) return cellText(value)
        fun annotate(scalar: YNode.Scalar): String? {
            val id = scalar.value?.toString() ?: return null
            val title = index.occurrences(id).firstNotNullOfOrNull { it.title } ?: return null
            return "$id（$title）"
        }
        when (value) {
            is YNode.Scalar -> annotate(value)?.let { return it }
            is YNode.Seq -> {
                val scalars = value.items.filterIsInstance<YNode.Scalar>()
                val resolvable = scalars.size == value.items.size &&
                    scalars.any { index.occurrences(it.value?.toString() ?: "").isNotEmpty() }
                if (resolvable) {
                    val joined = scalars.joinToString(", ") { annotate(it) ?: cellText(it) }
                    return if (joined.length > 200) joined.take(200) + "…" else joined
                }
            }
            else -> {}
        }
        return cellText(value)
    }

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
}
