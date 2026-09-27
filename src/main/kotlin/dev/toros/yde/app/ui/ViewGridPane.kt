package dev.toros.yde.app.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.core.ViewBacklink
import dev.toros.yde.app.core.ViewCell
import dev.toros.yde.app.core.ViewDef
import dev.toros.yde.app.core.ViewRow
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.Display
import dev.toros.yde.app.schema.IdSuggest
import dev.toros.yde.workspace.DocumentScaffolds
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files

/** グリッドの列幅。 */
private val ID_WIDTH = 170.dp
private val CELL_WIDTH = 230.dp
private val DETAIL_WIDTH = 150.dp
private val STATUS_WIDTH = 90.dp

/** セルとして直接編集できる型（複合型はジャンプで個別フォームへ）。 */
private val EDITABLE_TYPES = setOf(
    FieldType.STRING, FieldType.TEXT, FieldType.INT, FieldType.NUMBER,
    FieldType.BOOLEAN, FieldType.ENUM, FieldType.DATE, FieldType.ID, FieldType.REF,
)

/**
 * ビューグリッド（P6）。`_views` の宣言的ビュー定義を「行（論理ID）×列（原本上の座標）」の
 * 編集可能な一覧として表示する。
 * - セル編集は該当文書のツリーへの直接編集（未保存編集として積まれ、保存・Undo・差分は通常と同じ）
 * - 行の集合は索引（保存済み状態）基準。セル値は編集中ツリーを優先して表示する
 */
@Composable
fun ViewGridView(state: AppState) {
    state.indexVersion
    val ws = state.workspace ?: return
    val loaded = remember(state.indexVersion, ws) { Views.load(ws, state.schemas) }
    val view = loaded.view(state.viewGridId ?: "") ?: loaded.views.firstOrNull()

    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        HeaderBar(state, loaded, view)
        if (loaded.errors.isNotEmpty()) {
            Text(
                "定義エラー: " + loaded.errors.joinToString(" ／ "),
                fontSize = 11.sp, color = Palette.WarnOrange,
                modifier = Modifier.fillMaxWidth().background(Palette.ConflictBg).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (view == null) {
            Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("ビュー定義がありません", fontSize = 13.sp, color = Palette.SubText)
                Text(
                    "_views/*.yaml に行（IDパターン）と列（出現からの相対パス）を定義すると、複数文書を1つの一覧として参照・編集できます",
                    fontSize = 11.sp, color = Palette.SubText,
                )
            }
        } else {
            GridBody(state, view)
        }
    }
}

@Composable
private fun HeaderBar(state: AppState, loaded: Views.LoadResult, view: ViewDef?) {
    var selectorOpen by remember { mutableStateOf(false) }
    var newRowDialog by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ビューグリッド", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Box {
            OutlinedButton(
                enabled = loaded.views.isNotEmpty(),
                onClick = { selectorOpen = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(view?.name ?: "（ビューなし）", fontSize = 12.sp)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(selectorOpen, { selectorOpen = false }) {
                loaded.views.forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(v.name, fontSize = 13.sp)
                                Text(
                                    "${v.id} — " + (v.rows?.let { "idPattern: ${it.idPattern}" } ?: "matchクエリ（行キー: ${v.rowKey}）"),
                                    fontSize = 10.sp, color = Palette.SubText,
                                )
                            }
                        },
                        onClick = { state.openViewGrid(v.id); selectorOpen = false },
                    )
                }
            }
        }
        view?.description?.let {
            Spacer(Modifier.width(10.dp))
            Text(it.replace("\n", " ").take(80), fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        val newRow = view?.newRow
        if (newRow != null) {
            OutlinedButton(
                enabled = !state.readOnlyMode,
                onClick = {
                    // ファイルモードはファイル名にIDが要るため、先にID入力ダイアログを挟む
                    if (newRow.filePattern != null) newRowDialog = true
                    else state.addViewRow(view!!)
                        .onSuccess { path ->
                            state.status = "一覧文書に新しい行を追加しました。内容を入力して保存するとグリッドに現れます"
                            newRow.file?.let { f -> state.workspace?.let { state.openFile(it.resolve(f), path) } }
                        }
                        .onFailure { state.status = it.message ?: "行を追加できませんでした" }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("新規行", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
        }
        TextButton(
            enabled = state.hasDirtyDocs(),
            onClick = { state.saveAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            val dirty = state.dirtyDocCount()
            Text(if (dirty > 0) "すべて保存（$dirty）" else "すべて保存", fontSize = 11.sp)
        }
    }
    if (newRowDialog && view != null) {
        NewRowIdDialog(
            state = state,
            view = view,
            onDismiss = { newRowDialog = false },
            onConfirm = { id, withAi ->
                newRowDialog = false
                state.addViewRowFile(view, id)
                    .onSuccess { file ->
                        state.status = "詳細文書を作成して行を追加しました: ${state.relPath(file)}"
                        // 作成→たたき台生成の連結（R24-03）。スキーマが当たらない場合はその旨だけ案内する
                        if (withAi && !requestDocumentDraft(state, file, entityId = id)) {
                            state.status = "詳細文書を作成しました（スキーマが適用されないためAIたたき台は使えません）: ${state.relPath(file)}"
                        }
                    }
                    .onFailure { state.status = it.message ?: "行を追加できませんでした" }
            },
        )
    }
}

/**
 * newRow ファイルモードのID入力ダイアログ。入力IDが filePattern の {id} と新規文書のIDになる。
 * 作成はディスク書込のため、索引の更新でグリッドに行が即座に現れる。
 * 初期値は idPattern と既存IDからの次の連番（UX-64。上書き自由）。
 */
@Composable
private fun NewRowIdDialog(state: AppState, view: ViewDef, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var value by remember {
        mutableStateOf(
            // idPattern 宣言があるビューだけ提案する（宣言なしで全IDから推測すると無関係な系列が出る）
            view.rows?.idPattern?.let { glob ->
                IdSuggest.next(state.overlayIndex().entries.keys, idPatternGlobs = listOf(glob))
                    ?.takeIf { it !in state.overlayIndex().entries }
            }.orEmpty()
        )
    }
    var withAi by remember { mutableStateOf(false) }
    val pattern = view.newRow?.filePattern ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新規行（詳細文書の作成）", fontSize = 16.sp) },
        text = {
            Column {
                // 作成先のプレビュー（filePattern は値の式。newRow で埋まる穴は {id} だけ）
                val idText = value.trim()
                val preview = if (idText.isBlank()) pattern.text
                    else runCatching { DocumentScaffolds.pathFor(pattern, ScaffoldSource.idOnly(idText)) }.getOrElse { pattern.text }
                Text(
                    "入力したIDで $preview を作成します",
                    fontSize = 12.sp, color = Palette.SubText,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value, { value = it }, singleLine = true,
                    label = { Text(view.rows?.let { "ID（idPattern: ${it.idPattern}）" } ?: "ID") },
                    modifier = Modifier.confirmOnEnter(enabled = { value.isNotBlank() }) { onConfirm(value.trim(), withAi) },
                )
                // 作成→たたき台生成の連結（R24-03）。IDは生成対象外（スキャフォールドの値を保持）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withAi, { withAi = it })
                    Text("作成後にAIでたたき台を生成する（IDは変更されません）", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim(), withAi) }) { Text("作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun GridBody(state: AppState, view: ViewDef) {
    val ws = state.workspace ?: return
    var query by remember { mutableStateOf("") }
    // 行の構造（座標）は索引基準で解決し、セル値の表示・編集は編集中ツリーを優先する。
    // editVersion の購読（R16-04 / UX-02）: 計算列が他文書の未保存編集にも追随するための再解決契機
    val rows = remember(view, state.indexVersion, state.editVersion) {
        Views.resolve(view, state.overlayIndex(), state.unsavedFirstTree())
    }
    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { row ->
            row.id.contains(query, true) || row.title?.contains(query, true) == true ||
                row.cells.any { Views.cellText(it.value).contains(query, true) } ||
                row.cells.any { c -> c.backlinks.orEmpty().any { it.sourceId?.contains(query, true) == true } }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("ID・タイトル・値で絞り込み") },
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.weight(1f))
        Text("${filtered.size} / ${rows.size} 行", fontSize = 11.sp, color = Palette.SubText)
    }
    val hScroll = rememberScrollState()
    // ヘッダ行
    // クロス集計（kind: matrix）は列を索引から広げるので、ヘッダは解決と同じ索引で決めた列を使う
    val effectiveView = remember(view, state.indexVersion, state.editVersion) { Views.effective(view, state.overlayIndex()) }
    Row(Modifier.fillMaxWidth().background(Palette.PanelBg).horizontalScroll(hScroll).padding(horizontal = 8.dp, vertical = 5.dp)) {
        GridHeaderCell("ID", ID_WIDTH)
        effectiveView.columns.forEach { column ->
            val suffix = when {
                column.backlinks != null -> "（被参照）"
                column.editable -> ""
                else -> "（参照）"
            }
            GridHeaderCell(column.label + suffix, CELL_WIDTH)
        }
        if (view.detail != null) GridHeaderCell("詳細", DETAIL_WIDTH)
        GridHeaderCell("状態", STATUS_WIDTH)
    }
    Divider(color = Palette.Border)
    Box(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 8.dp),
            contentPadding = PaddingValues(bottom = 14.dp),  // 最終行が横スクロールバーに隠れないように
        ) {
            // 行キーは ViewRow.key（クエリ行の結合ではIDが行間で重複しうるため。R22-02）
            items(filtered, key = { it.key }) { row ->
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(hScroll)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IdCell(state, row)
                    row.cells.forEach { cell -> GridCell(state, cell) }
                    if (view.detail != null) DetailCell(state, view, row)
                    StatusCell(state, row)
                }
                Divider(color = Palette.Border.copy(alpha = .4f))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        // 列の合計幅が画面に収まっているときは表示しない（maxValue の初期値 Int.MAX_VALUE は測定前なので除外）
        if (hScroll.maxValue in 1 until Int.MAX_VALUE) {
            HorizontalScrollbar(
                rememberScrollbarAdapter(hScroll),
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 14.dp),
            )
        }
    }
}

@Composable
private fun GridHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) =
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(width).padding(end = 8.dp))

@Composable
private fun IdCell(state: AppState, row: ViewRow) {
    Column(Modifier.width(ID_WIDTH).padding(end = 8.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.id, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable {
                    row.occurrences.firstOrNull()?.let { state.navigateToOccurrence(it) }
                },
            )
            // 出現の無い行（クロス集計の合計の行）には横ぐしビューは無い
            if (row.occurrences.isNotEmpty()) {
                IconButton(onClick = { state.openCrossView(row.id) }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Hub, contentDescription = "横ぐしビューで表示", Modifier.size(13.dp), tint = Palette.SubText)
                }
            }
        }
        row.title?.let { Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 1) }
    }
}

/** 1セル。出現があり、スカラー系フィールドで editable 宣言されていれば直接編集できる。 */
@Composable
private fun GridCell(state: AppState, cell: ViewCell) {
    Box(Modifier.width(CELL_WIDTH).padding(end = 8.dp)) {
        val backlinks = cell.backlinks
        if (backlinks != null) {
            BacklinkCell(state, backlinks)
            return@Box
        }
        if (cell.computed) {
            // 計算セル（R16-02 / R16-04）: 評価済みの導出値を表示する読み取り専用セル。
            // 文書キャッシュから再読すると元値（テンプレート）が見えてしまうため、評価結果をそのまま出す。
            // クリックは直接ジャンプではなく、導出の説明と行き先の選択（規則・入力値）を提示する。
            // 値の出どころが特定の 1 か所に無いセル（座標なし）も、値と来歴は出す
            ComputedCellLink(state, cell)
            return@Box
        }
        val file = cell.document
        val path = cell.path
        if (file == null || path == null) {
            Text("—", fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.padding(top = 8.dp))
            return@Box
        }
        // セル編集は通常の編集と同じ経路（キャッシュ上の文書ツリー＋markEdited）を通す
        val doc = remember(file) { state.ensureDoc(file) }
        doc.version
        val root = doc.root
        if (root == null) {
            CellLink(state, cell, "（パース不能）")
            return@Box
        }
        val value = getAt(root, path)
        val fieldDef = remember(doc, doc.version, path) {
            doc.schema?.let { Views.fieldAt(it, root, path) }
        }
        val editableType = fieldDef == null || fieldDef.type in EDITABLE_TYPES
        val complexValue = value is YNode.Seq || value is YNode.Map
        // 閲覧専用モード（R10-04）は全セルをジャンプ可能な表示専用にする
        if (state.readOnlyMode || !cell.column.editable || !editableType || complexValue) {
            // 複合値は表示解決（R10-01）で「ID タイトル」「n件: 要約」に要約する（タイトルは索引から借用。UX-71）
            val titleLookup: (String) -> String? = { id ->
                state.overlayIndex().occurrences(id).firstNotNullOfOrNull { it.title }
            }
            val text = if (complexValue && doc.schema != null && fieldDef != null) {
                when {
                    value is YNode.Map && fieldDef.type == dev.toros.yde.semantic.schema.FieldType.OBJECT ->
                        Display.inline(value, doc.schema, fieldDef, titleLookup = titleLookup)
                    value is YNode.Seq && fieldDef.type == dev.toros.yde.semantic.schema.FieldType.ARRAY ->
                        Display.seqHeaderText(value, doc.schema, fieldDef.item, maxItems = 2, titleLookup = titleLookup)
                    else -> Views.cellText(value)
                }
            } else {
                // 桁区切り（display: grouped）は列の宣言か、項目の側の宣言（見せ方だけ。値は変えない）
                val grouping = cell.column.display ?: fieldDef?.hints?.numberDisplay
                Views.cellText(value).let { t -> grouping?.format(t) ?: t }
            }
            CellLink(state, cell, text)
            return@Box
        }
        val slot = slotAt(root, path)
        if (slot == null) {
            // 途中のオブジェクト・配列が未作成（座標に書き込めない）
            CellLink(state, cell, "—")
            return@Box
        }
        val ctx = remember(doc, doc.version, state.indexVersion) {
            val localIds = if (fieldDef?.type == FieldType.REF) {
                val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
                DocScanner.scan(root, state.documentOf(doc.file), doc.schema, documentId).first
            } else emptyList()
            FormCtx(state, doc, localIds)
        }
        if (fieldDef != null) {
            // 列に display: grouped があれば、編集のエディタにも桁区切りの見せ方を渡す（項目の側の宣言が無いとき）
            val effective = cell.column.display?.takeIf { fieldDef.hints.numberDisplay == null }
                ?.let { fieldDef.copy(hints = fieldDef.hints.copy(numberDisplay = it)) } ?: fieldDef
            ValueEditor(ctx, effective, slot, path)
        } else {
            // スキーマなし文書のセルは元の型を保って編集する
            val text = (value as? YNode.Scalar)?.value?.toString() ?: ""
            CompactTextField(
                value = text,
                onValueChange = { ctx.setGenericScalar(slot, it) },
                singleLine = !text.contains('\n'),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * バックリンク列のセル。行IDへの参照元（1:N詳細化の分裂先など）をIDで列挙し、クリックで参照位置へジャンプする。
 * ビューはデータを持たない派生表示のため、この列に編集経路はない。
 */
@Composable
private fun BacklinkCell(state: AppState, backlinks: List<ViewBacklink>) {
    if (backlinks.isEmpty()) {
        Text("—", fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val shown = backlinks.take(5)
    Column(Modifier.padding(top = 8.dp)) {
        shown.forEach { b ->
            Column(Modifier.clickable { state.openFile(b.document, b.path) }) {
                Text(
                    b.sourceId ?: b.document.value.substringAfterLast('/'),
                    fontSize = 12.sp, color = Palette.Primary, maxLines = 1,
                )
                // 参照元要素のタイトル（表示解決の結果。R10-01）＋属性付き参照の属性注記（R20-03）
                listOfNotNull(b.sourceTitle, b.attributes?.let { "（$it）" }).takeIf { it.isNotEmpty() }
                    ?.let { Text(it.joinToString(" "), fontSize = 10.sp, color = Palette.SubText, maxLines = 1) }
            }
        }
        if (backlinks.size > shown.size) {
            Text("…他 ${backlinks.size - shown.size} 件", fontSize = 10.sp, color = Palette.SubText)
        }
    }
}

/**
 * 計算セル（値の式の列。R16-04 / UX-01）。クリックは直接ジャンプではなく、導出の説明（元値→結果）と
 * 行き先の選択（元の値・各入力値）をポップアップメニューで提示する。ひな型を確認したい場合と
 * 入力値を確認したい場合の両方に応える（architecture/transform.md「計算列と決定論的射影」。モーダルではなくポップアップ）。
 */
@Composable
private fun ComputedCellLink(state: AppState, cell: ViewCell) {
    var menuOpen by remember { mutableStateOf(false) }
    val text = Views.cellText(cell.value).let { t -> cell.column.display?.format(t) ?: t }  // 桁区切りは列の宣言
    Box {
        Text(
            text, fontSize = 12.sp, maxLines = 2,
            color = if (text == "（未設定）") Palette.SubText else Palette.Text,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { menuOpen = true },
        )
        DropdownMenu(menuOpen, { menuOpen = false }) {
            val trace = cell.trace
            // 導出の説明: 元値（テンプレート・集計の材料）→ 評価結果。値が得られなければ、その理由
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("計算値（読み取り専用）", fontSize = 10.sp, color = Palette.SubText)
                if (trace != null && cell.value == null) {
                    Text("値が得られません: ${trace.raw}", fontSize = 11.sp, color = Palette.WarnOrange)
                } else if (trace != null && trace.raw != text) {
                    Text("${trace.raw} → $text", fontSize = 11.sp, color = Palette.SubText)
                }
            }
            val file = cell.document
            if (file != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            // 計算セルの座標は値の出どころ（ひな型・読み出した値・最小と最大で選ばれた要素）
                            Text("元の値へ", fontSize = 12.sp)
                            Text("${cell.document?.value} :: ${cell.path?.pretty()}", fontSize = 10.sp, color = Palette.SubText)
                        }
                    },
                    onClick = { menuOpen = false; state.openFile(file, cell.path) },
                )
            }
            trace?.inputs.orEmpty().forEach { input ->
                val inputFile = input.document
                val inputPath = input.path
                if (inputFile != null && inputPath != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("入力値へ: {${input.placeholder}} = ${input.value}", fontSize = 12.sp)
                                Text("${inputFile.value} :: ${inputPath.pretty()}", fontSize = 10.sp, color = Palette.SubText)
                            }
                        },
                        onClick = { menuOpen = false; state.openFile(inputFile, inputPath) },
                    )
                } else {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("{${input.placeholder}} は未解決（値が見つかりません）", fontSize = 11.sp, color = Palette.WarnOrange)
                    }
                }
            }
        }
    }
}

/** 読み取り専用セル。クリックで原本の該当位置へジャンプする。 */
@Composable
private fun CellLink(state: AppState, cell: ViewCell, text: String) {
    Text(
        text, fontSize = 12.sp, maxLines = 2,
        color = if (text == "—" || text == "（未設定）") Palette.SubText else Palette.Text,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .clickable { cell.document?.let { state.openFile(it, cell.path) } },
    )
}

@Composable
private fun DetailCell(state: AppState, view: ViewDef, row: ViewRow) {
    Box(Modifier.width(DETAIL_WIDTH).padding(end = 8.dp, top = 4.dp)) {
        val detailRel = row.detailDocument?.value
        if (detailRel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    state.workspace?.let { state.openFile(it.resolve(detailRel), row.detailPath) }
                },
            ) {
                Icon(Icons.Default.ArrowForward, contentDescription = null, Modifier.size(13.dp), tint = Palette.Primary)
                Spacer(Modifier.width(4.dp))
                Text(detailRel.substringAfterLast('/'), fontSize = 11.sp, color = Palette.Primary, maxLines = 1)
            }
        } else if (view.detail?.filePattern != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !state.readOnlyMode,
                    onClick = {
                        state.createViewDetail(view, row.id)
                            .onSuccess { state.status = "詳細文書を作成しました: ${state.relPath(it)}" }
                            .onFailure { state.status = it.message ?: "詳細文書を作成できませんでした" }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("作成", fontSize = 11.sp)
                }
                // 作成→たたき台生成の連結（R24-03）: スキャフォールド作成後にそのままAI生成ダイアログを開く
                if (!state.readOnlyMode) {
                    IconButton(
                        onClick = {
                            state.createViewDetail(view, row.id)
                                .onSuccess { file ->
                                    state.status = "詳細文書を作成しました: ${state.relPath(file)}"
                                    if (!requestDocumentDraft(state, file, entityId = row.id)) {
                                        state.status = "詳細文書を作成しました（スキーマが適用されないためAIたたき台は使えません）: ${state.relPath(file)}"
                                    }
                                }
                                .onFailure { state.status = it.message ?: "詳細文書を作成できませんでした" }
                        },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "作成してAIでたたき台を生成",
                            Modifier.size(14.dp),
                            tint = Palette.Primary,
                        )
                    }
                }
            }
        } else {
            Text("未作成", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** カバレッジ充足の表示（対象スキーマにルールがある行のみ）。 */
@Composable
private fun StatusCell(state: AppState, row: ViewRow) {
    Box(Modifier.width(STATUS_WIDTH).padding(top = 6.dp)) {
        // activeFrom のフェーズ判定には「IDを定義している文書」のルートを渡す（未到達ルールは対象外にする）
        val statuses = remember(row.id, state.indexVersion, state.editVersion) {
            row.occurrences.mapNotNull { e -> state.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                .distinctBy { it.first.id }
                .flatMap { (sc, e) ->
                    Validator.ruleStatuses(
                        row.id, sc, state.overlayIndex(), state.ensureDoc(e.document).root,
                        state.schemas, state.unsavedFirstTree(),
                    )
                }
        }
        // 確かめられなかった検査（保留・対象外）は、充足の数にも母数にも入れない（P27 段A・意図的差分 D-26 ③）
        val undetermined = statuses.count { it.active && it.undetermined }
        val active = statuses.filter { it.active && !it.undetermined }
        if (active.isEmpty()) {
            Text(if (undetermined > 0) "保留" else "—", fontSize = 11.sp, color = Palette.SubText)
        } else {
            val satisfied = active.count { it.satisfied }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (satisfied == active.size) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null, Modifier.size(13.dp),
                    tint = if (satisfied == active.size) Palette.OkGreen else Palette.WarnOrange,
                )
                Spacer(Modifier.width(4.dp))
                Text("$satisfied/${active.size}", fontSize = 11.sp,
                    color = if (satisfied == active.size) Palette.Text else Palette.WarnOrange)
            }
        }
    }
}

