package ui

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
import app.AppState
import app.OpenDocument
import core.ViewCell
import core.ViewDef
import core.ViewRow
import core.Views
import index.DocScanner
import index.IdIndex
import model.YNode
import model.YPath
import model.getAt
import schema.FieldType
import validate.Validator
import yaml.YamlIO
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
    val loaded = remember(state.indexVersion, ws) { Views.load(ws) }
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
                                Text("${v.id} — idPattern: ${v.rows.idPattern}", fontSize = 10.sp, color = Palette.SubText)
                            }
                        },
                        onClick = { state.viewGridId = v.id; selectorOpen = false },
                    )
                }
            }
        }
        view?.description?.let {
            Spacer(Modifier.width(10.dp))
            Text(it.replace("\n", " ").take(80), fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (view?.newRow != null) {
            OutlinedButton(
                enabled = !state.readOnlyMode,
                onClick = {
                    state.addViewRow(view)
                        .onSuccess { path ->
                            state.status = "一覧文書に新しい行を追加しました。内容を入力して保存するとグリッドに現れます"
                            state.workspace?.let { state.openFile(it.resolve(view.newRow!!.file), path) }
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
        ) { Text("すべて保存", fontSize = 11.sp) }
    }
}

@Composable
private fun GridBody(state: AppState, view: ViewDef) {
    val ws = state.workspace ?: return
    var query by remember { mutableStateOf("") }
    // 行の構造（座標）は索引基準で解決し、セル値の表示・編集は編集中ツリーを優先する
    val rows = remember(view, state.indexVersion) {
        Views.resolve(view, ws, state.index) { f ->
            state.docFor(f)?.root ?: runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull()
        }
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
            placeholder = { Text("ID・タイトル・値で絞り込み", fontSize = 11.sp, color = Palette.SubText) },
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.weight(1f))
        Text("${filtered.size} / ${rows.size} 行", fontSize = 11.sp, color = Palette.SubText)
    }
    val hScroll = rememberScrollState()
    // ヘッダ行
    Row(Modifier.fillMaxWidth().background(Palette.PanelBg).horizontalScroll(hScroll).padding(horizontal = 8.dp, vertical = 5.dp)) {
        GridHeaderCell("ID", ID_WIDTH)
        view.columns.forEach { column ->
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
            items(filtered, key = { it.id }) { row ->
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
            IconButton(onClick = { state.openCrossView(row.id) }, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Default.Hub, contentDescription = "横ぐしビューで表示", Modifier.size(13.dp), tint = Palette.SubText)
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
        val file = cell.file
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
            // 複合値は表示解決（R10-01）で「ID タイトル」「n件: 要約」に要約する
            val text = if (complexValue && doc.schema != null && fieldDef != null) {
                when {
                    value is YNode.Map && fieldDef.type == schema.FieldType.OBJECT ->
                        schema.Display.inline(value, doc.schema, fieldDef)
                    value is YNode.Seq && fieldDef.type == schema.FieldType.ARRAY ->
                        schema.Display.seqHeaderText(value, doc.schema, fieldDef.item, maxItems = 2)
                    else -> Views.cellText(value)
                }
            } else Views.cellText(value)
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
                DocScanner.scan(root, doc.file, doc.schema, documentId).first
            } else emptyList()
            FormCtx(state, doc, localIds)
        }
        if (fieldDef != null) {
            ValueEditor(ctx, fieldDef, slot, path)
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
private fun BacklinkCell(state: AppState, backlinks: List<core.ViewBacklink>) {
    if (backlinks.isEmpty()) {
        Text("—", fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val shown = backlinks.take(5)
    Column(Modifier.padding(top = 8.dp)) {
        shown.forEach { b ->
            Column(Modifier.clickable { state.openFile(b.file, b.path) }) {
                Text(
                    b.sourceId ?: b.rel.substringAfterLast('/'),
                    fontSize = 12.sp, color = Palette.Primary, maxLines = 1,
                )
                // 参照元要素のタイトル（表示解決の結果。R10-01）
                b.sourceTitle?.let { Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 1) }
            }
        }
        if (backlinks.size > shown.size) {
            Text("…他 ${backlinks.size - shown.size} 件", fontSize = 10.sp, color = Palette.SubText)
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
            .clickable { cell.file?.let { state.openFile(it, cell.path) } },
    )
}

@Composable
private fun DetailCell(state: AppState, view: ViewDef, row: ViewRow) {
    Box(Modifier.width(DETAIL_WIDTH).padding(end = 8.dp, top = 4.dp)) {
        val detailRel = row.detailRel
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
        val statuses = remember(row.id, state.indexVersion) {
            row.occurrences.mapNotNull { e -> state.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                .distinctBy { it.first.id }
                .flatMap { (sc, e) -> Validator.coverageStatuses(row.id, sc, state.index, state.ensureDoc(e.file).root) }
        }
        val active = statuses.filter { it.active }
        if (active.isEmpty()) {
            Text("—", fontSize = 11.sp, color = Palette.SubText)
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

