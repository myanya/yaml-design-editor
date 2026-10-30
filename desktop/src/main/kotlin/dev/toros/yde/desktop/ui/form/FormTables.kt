package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.app.schema.Display
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.desktop.ui.common.ActionMenuItem
import dev.toros.yde.desktop.ui.common.ColumnWidths
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.ReorderableList
import dev.toros.yde.desktop.ui.common.Slot

/*
 * 表の表示（R12-06）: 配列と map を行 × 列で見せる（ObjectTable・ArrayTable・MapTable）。列の幅・セルの描画・行の操作。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

// ================================================================ テーブル表示（R12-06: 配列・map）

/**
 * 列の型ごとのセルの飾り（入力欄の余白・ボタン）・最小幅・上限（dp）。列幅は「見出しと値の文字の幅＋飾り」を最小と上限の間に収める
 * （tableColumnWidths）。閲覧専用は文字だけ（ID と参照は横ぐしの印の分）。列は `$ref` 解決済み。
 */
internal data class CellSizing(val chrome: Float, val min: Float, val max: Float)

internal fun cellSizing(column: FieldDef, readOnly: Boolean): CellSizing = when {
    column.multiline -> CellSizing(12f, 120f, 320f)       // 複数行の文字列は表では表示だけ（編集は行の展開）
    readOnly -> when (column.type) {
        FieldType.ID, FieldType.REF -> CellSizing(36f, 80f, 300f)   // 横ぐしの印
        else -> CellSizing(12f, 56f, 320f)
    }
    else -> when (column.type) {
        FieldType.BOOLEAN -> CellSizing(16f, 56f, 120f)
        FieldType.INT, FieldType.NUMBER -> CellSizing(32f, 72f, 160f)
        FieldType.DATE -> CellSizing(76f, 150f, 180f)          // ピッカーのボタン分を含む
        FieldType.TIME -> CellSizing(76f, 120f, 160f)
        FieldType.DATETIME -> CellSizing(76f, 190f, 240f)
        FieldType.FILE -> CellSizing(96f, 240f, 360f)          // 選択と OS で開くの 2 つのボタン
        FieldType.ENUM -> CellSizing(48f, 110f, 260f)          // 選択の ▼
        FieldType.ID -> CellSizing(84f, 150f, 300f)            // 採番の ▼ と横ぐしの Hub
        FieldType.REF -> CellSizing(86f, 170f, 320f)           // 候補の ▼ と参照先へ →（横ぐしは → のメニューにある。名前は欄の下）
        else -> CellSizing(32f, 96f, 320f)
    }
}

private val TABLE_EXPAND_WIDTH = 28.dp
private val TABLE_OPS_WIDTH = 56.dp        // 配列の行操作（取っ手と ⋯。上へ・下へ・削除・型の操作は ⋯ のメニュー）
private val TABLE_MENU_WIDTH = 28.dp       // 閲覧専用のときの行の操作メニュー（⋯）だけの列

/** 配列の要素を from から to へ動かす（ドラッグの並べ替え。木の変異だけで、markEdited は呼び出し側）。 */
internal fun moveSeqItem(node: YNode.Seq, from: Int, to: Int) {
    if (from == to || from !in node.items.indices || to !in node.items.indices) return
    val item = node.items.removeAt(from)
    node.items.add(to, item)
}
private val TABLE_KEY_OPS_WIDTH = 28.dp    // map の行操作（キー削除のみ）
private val TABLE_KEY_WIDTH = 150.dp       // map のキー列

/** 余白の配分先になる列（文字系）。数値・真偽・日付は固定幅のままにする。 */
private val FLEX_CELL_TYPES = setOf(
    FieldType.STRING, FieldType.ENUM, FieldType.ID, FieldType.REF,
)

/**
 * 列幅の割付（R12-06）: 列ごとに見出しと値の文字の幅から基本の幅を決め（cellSizing の飾り・最小・上限。values は列ごとの値の文字）、
 * 利用可能幅に余りがあれば文字系の列で等分して表を右端まで使う。文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）。
 * 利用可能幅に収まらない場合は基本の幅のまま＝横スクロールに委ねる。列は `$ref` 解決済みの項目。
 */
internal fun tableColumnWidths(
    columns: List<FieldDef>, available: Dp, fixed: Dp,
    values: List<List<String>> = emptyList(), readOnly: Boolean = false,
): List<Dp> {
    val base = columns.mapIndexed { i, column ->
        val sizing = cellSizing(column, readOnly)
        val header = column.displayLabel + if (column.required) " *" else ""
        ColumnWidths.fit(header, values.getOrNull(i).orEmpty(), headerSp = 11f, valueSp = 13f,
            chrome = sizing.chrome, min = sizing.min, max = sizing.max).dp
    }
    val natural = fixed + base.fold(0.dp) { acc, w -> acc + w }
    val flexCount = columns.count { it.type in FLEX_CELL_TYPES }
    if (available <= natural || flexCount == 0) return base
    val extra = (available - natural) / flexCount
    return columns.mapIndexed { i, c -> base[i] + if (c.type in FLEX_CELL_TYPES) extra else 0.dp }
}

/**
 * 適格な配列・map（Display.tableColumns が列を返したもの）の表形式表示の本体。
 * 行は「YPath セグメント（配列=添字、map=キー）× object 要素」。セルはスカラー列のみ
 * （編集は通常の編集経路＝Slot を再利用）。列にならないフィールド（ネスト・enum case の追加分・
 * スキーマ外キー）と長文の編集は、行頭の ▶ で開く展開フォームで扱う。
 */
@Composable
private fun ObjectTable(
    ctx: FormCtx,
    elementDef: FieldDef?,
    columns: List<FieldDef>,
    rows: List<Pair<Any, YNode.Map>>,
    path: YPath,
    keyHeader: String? = null,           // 非 null なら先頭にキー列を出す（map）
    opsWidth: Dp = 0.dp,                 // 編集モードの行操作列の幅（ops とセットで指定）
    ops: (@Composable (Int) -> Unit)? = null,
    onMove: ((from: Int, to: Int) -> Unit)? = null,   // 非 null なら行をドラッグで並べ替えられる（配列）
    rowMenu: (@Composable (Int) -> Unit)? = null,     // 行の操作メニュー（⋯）。閲覧専用でも出す（操作は編集ではない）
) {
    val hScroll = rememberScrollState()
    // 列ごとの値の文字（列幅の見積もり）。入力のたびに列幅が揺れないよう、行数・列・閲覧専用が変わったときだけ読み直す
    val values = remember(columns, rows.size, ctx.readOnly, path) {
        columns.map { col -> rows.asSequence().take(ColumnWidths.SAMPLE_ROWS).mapNotNull { (_, element) -> tableCellText(col, element, ctx.readOnly) }.toList() }
    }
    // 利用可能幅を測り、余白は文字系の列へ配って表を右端まで使う
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val keyWidth = if (keyHeader != null) TABLE_KEY_WIDTH else 0.dp
        val trailing = if (!ctx.readOnly && ops != null) opsWidth else if (rowMenu != null) TABLE_MENU_WIDTH else 0.dp
        val fixed = TABLE_EXPAND_WIDTH + keyWidth + trailing
        val widths = tableColumnWidths(columns, maxWidth, fixed, values, ctx.readOnly)
        val rowWidth = fixed + widths.fold(0.dp) { acc, w -> acc + w }
        Column(Modifier.fillMaxWidth()) {
            // 列だけを横スクロールし、展開フォームには表示領域の幅を渡す。
            // 見出しと各行は同じ列幅・ScrollState を使い、横位置を揃える。
            Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                // ヘッダ行（ラベルはスキーマの表示ラベル。必須マーカーはフォームと同じ流儀）
                Row(Modifier.width(rowWidth).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(TABLE_EXPAND_WIDTH))
                    if (keyHeader != null) {
                        Text(
                            keyHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(keyWidth).padding(horizontal = 4.dp),
                        )
                    }
                    columns.forEachIndexed { c, col ->
                        Row(Modifier.width(widths[c]).padding(horizontal = 4.dp)) {
                            Text(
                                col.displayLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (col.required) Text(" *", color = Palette.ErrorRed, fontSize = 11.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.Border)
            // 配列の行は取っ手からのドラッグで並べ替えられる（onMove がある表だけ取っ手を出す。map の表は出さない）
            ReorderableList(items = rows, onMove = { from, to -> onMove?.invoke(from, to) },
                modifier = Modifier.fillMaxWidth(), key = { it.second }) { i, (segment, element), dragging ->
                val rowPath = path + segment
                // 行の展開は画面の覚えが持つ（既定は畳む）。ジャンプ先が行の中（列にならない項目＝展開フォーム側）なら開く（UX-10）
                val rowExpanded = ctx.expanded(rowPath, default = false)
                Highlightable(ctx.state, rowPath) {
                    Column(Modifier.fillMaxWidth().background(if (dragging) Palette.Selected else Color.Transparent)) {
                        Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                            Row(Modifier.width(rowWidth), verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(TABLE_EXPAND_WIDTH).padding(top = if (ctx.readOnly) 0.dp else 7.dp)) {
                                    IconButton(onClick = { ctx.setExpanded(rowPath, !rowExpanded) }, modifier = Modifier.size(24.dp)) {
                                        Text(if (rowExpanded) "▼" else "▶", fontSize = 10.sp, color = Palette.SubText)
                                    }
                                }
                                if (keyHeader != null) {
                                    // map のキー列は表示のみ（キーの改名・追加は従来の縦リスト表示や汎用ビューの経路で行う）
                                    Box(Modifier.width(keyWidth).padding(horizontal = 4.dp, vertical = if (ctx.readOnly) 2.dp else 8.dp)) {
                                        Text(
                                            segment.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                columns.forEachIndexed { c, col ->
                                    Box(
                                        Modifier.width(widths[c])
                                            .padding(horizontal = 4.dp, vertical = if (ctx.readOnly) 2.dp else 1.dp),
                                    ) { ObjectTableCell(ctx, col, element, rowPath) }
                                }
                                if (!ctx.readOnly && ops != null) {
                                    Row(Modifier.width(opsWidth).padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (onMove != null) Handle(Modifier.padding(end = 2.dp))
                                        ops(i)
                                    }
                                } else if (rowMenu != null) {
                                    Box(Modifier.width(TABLE_MENU_WIDTH)) { rowMenu(i) }
                                }
                            }
                        }
                        if (rowExpanded) {
                            Column(Modifier.fillMaxWidth().padding(start = TABLE_EXPAND_WIDTH, top = 2.dp, bottom = 6.dp)) {
                                ObjectFields(ctx, elementDef?.fields.orEmpty(), element, rowPath)
                            }
                        }
                    }
                }
                HorizontalDivider(color = Palette.Border.copy(alpha = .4f))
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 配列のテーブル表示。行操作は取っ手のドラッグと ⋯ のメニュー（上へ・下へ・削除・型の操作）。 */
@Composable
internal fun ArrayTable(ctx: FormCtx, field: FieldDef, columns: List<FieldDef>, node: YNode.Seq, path: YPath) {
    val itemDef = ctx.doc.schema?.resolve(field.item) ?: field.item
    val rows = node.items.mapIndexedNotNull { i, item -> (item as? YNode.Map)?.let { i to it } }
    ObjectTable(ctx, itemDef, columns, rows, path, opsWidth = TABLE_OPS_WIDTH,
        onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() },
        ops = { i -> ArrayRowMenu(ctx, field, node, i, path) },
        rowMenu = { i -> ElementOpsButton(ctx, path + i) })
}

/**
 * 配列の表の行の ⋯（編集中）: 上へ・下へ・削除（確認つき）と、行の要素に使える操作（型の操作と、その型を受け手にするアクション）を 1 つのメニューに。
 * 以前は上へ・下へ・削除・⋯ の 4 つのボタンで、行操作の列が 120dp あった。
 */
@Composable
private fun ArrayRowMenu(ctx: FormCtx, field: FieldDef, node: YNode.Seq, i: Int, path: YPath) {
    var open by remember { mutableStateOf(false) }
    val requestDelete = rememberArrayDeleteRequest(ctx.doc, node, i, ctx.readOnly,
        label = field.displayLabel, itemField = field.item, titleLookup = ctx.indexTitleLookup(), markEdited = { ctx.markEdited() })
    val entry = ctx.localIds.firstOrNull { it.path == path + i }
    val actions = remember(entry?.id, entry?.entityType, ctx.state.indexVersion) {
        entry?.let { e -> e.entityType?.let { ctx.state.actionsForEntityType(it) } ?: ctx.state.actionsForEntity(e.id) }.orEmpty()
    }
    fun swap(a: Int, b: Int) {
        if (a !in node.items.indices || b !in node.items.indices) return
        val tmp = node.items[a]; node.items[a] = node.items[b]; node.items[b] = tmp
        ctx.markEdited()
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "行の操作", Modifier.size(16.dp), tint = Palette.SubText)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("上へ移動", fontSize = 12.sp) }, enabled = i > 0,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, Modifier.size(16.dp)) },
                onClick = { open = false; swap(i, i - 1) },
            )
            DropdownMenuItem(
                text = { Text("下へ移動", fontSize = 12.sp) }, enabled = i < node.items.size - 1,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, Modifier.size(16.dp)) },
                onClick = { open = false; swap(i, i + 1) },
            )
            DropdownMenuItem(
                text = { Text("削除…", fontSize = 12.sp, color = Palette.ErrorRed) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(16.dp), tint = Palette.ErrorRed) },
                onClick = { open = false; requestDelete() },
            )
            if (entry != null && actions.isNotEmpty()) {
                HorizontalDivider()
                Text("${entry.id} の操作", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 10.sp, color = Palette.SubText)
                actions.forEach { e ->
                    ActionMenuItem(ctx.state, e, entry.id, onClick = { open = false; ctx.state.startAction(e, entry.id) })
                }
            }
        }
    }
}

/**
 * 配列の行の操作メニュー（⋯）: 行の要素に使える操作（型の操作と、その型を受け手にするアクション。architecture/actions.md）。
 * 選ぶとその要素を対象にしたパネルが開く。ID の無い要素・操作の無い型には出ない。型は走査した ID の型で見るので、未保存の行にも出る。
 */
@Composable
internal fun ElementOpsButton(ctx: FormCtx, rowPath: YPath, modifier: Modifier = Modifier) {
    val entry = ctx.localIds.firstOrNull { it.path == rowPath } ?: return
    val actions = remember(entry.id, entry.entityType, ctx.state.indexVersion) {
        entry.entityType?.let { ctx.state.actionsForEntityType(it) } ?: ctx.state.actionsForEntity(entry.id)
    }
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "操作", Modifier.size(16.dp), tint = Palette.SubText)
        }
        DropdownMenu(open, { open = false }) {
            Text("${entry.id} の操作", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 10.sp, color = Palette.SubText)
            actions.forEach { e ->
                ActionMenuItem(ctx.state, e, entry.id,
                    onClick = { open = false; ctx.state.startAction(e, entry.id) },
                )
            }
        }
    }
}

/** map（キー→object）のテーブル表示。先頭にキー列を出し、行操作はキー削除のみ（キー追加は表の下の共通行）。 */
@Composable
internal fun MapTable(ctx: FormCtx, field: FieldDef, columns: List<FieldDef>, node: YNode.Map, path: YPath) {
    val valueDef = ctx.doc.schema?.resolve(field.value) ?: field.value
    val rows = node.entries.entries.mapNotNull { (k, v) -> (v as? YNode.Map)?.let { k as Any to it } }
    ObjectTable(ctx, valueDef, columns, rows, path, keyHeader = "キー", opsWidth = TABLE_KEY_OPS_WIDTH, ops = { i ->
        IconButton(
            onClick = { node.entries.remove(rows[i].first.toString()); ctx.markEdited() },
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(14.dp), tint = Palette.SubText) }
    })
}

/**
 * テーブルの1セル。スカラー編集は通常の ValueEditor（Slot 経由＝Undo・差分・readOnly 拒否が同じに効く）。
 * 長文（text）はセル内編集で行高が崩れるため表示のみとし、編集は行の展開フォームで行う。
 */
@Composable
private fun ObjectTableCell(ctx: FormCtx, col: FieldDef, map: YNode.Map, itemPath: YPath) {
    val key = col.key ?: return
    val slot = MapSlot(map, key)
    val value = slot.get()
    // 検証指摘のあるセルはテーブル表示でも枠を色づけする（UX-14。展開フォーム側の FieldRow と同じ規則）
    val issueColor = when (ctx.issueAt(itemPath + key)) {
        Severity.ERROR -> Palette.ErrorRed
        Severity.WARNING -> Palette.WarnOrange
        else -> null
    }
    Box(
        Modifier.fillMaxWidth().then(
            if (issueColor != null) Modifier.border(1.dp, issueColor, PanelShape) else Modifier
        )
    ) {
        when {
            // エンティティ参照のセルは ID・参照先の名前・横ぐしへの Hub（属性付き参照の {to, 属性} も to を見せる）
            col.type == FieldType.REF && col.refTarget != RefTarget.DOCUMENT &&
                (value == null || !structureMismatch(col, value)) -> RefCell(ctx, col, slot)
            // スキーマとずれた複合値はセルで編集せず要約表示に留める（編集は行の展開フォーム）
            value != null && value !is YNode.Scalar -> TableCellText(Display.inline(value, ctx.doc.schema, col))
            // 閲覧専用の ID セルも横ぐしへ直行できる（編集時は IdEditor の Hub ボタンが同じ役を担う）
            ctx.readOnly && col.type == FieldType.ID -> ReadOnlyIdCell(ctx, value as? YNode.Scalar)
            ctx.readOnly || col.multiline -> TableCellText(readOnlyCellText(col, value as? YNode.Scalar))
            // ファイルの場所はセルでは画像を出さない（編集は行の展開フォームで画像つき）
            col.type == FieldType.FILE -> LocalFormSlots.current.fileField(ctx, col, slot, itemPath + key, false)
            else -> ValueEditor(ctx, col, slot, itemPath + key)
        }
    }
}

/** 列幅の見積もりに使うセルの文字（値が無ければ null）。閲覧専用は表示の文字、属性付き参照は to の ID、複合値は数えない。 */
private fun tableCellText(col: FieldDef, element: YNode.Map, readOnly: Boolean): String? {
    val key = col.key ?: return null
    val node = element.entries[key]
    val scalar = (node as? YNode.Map)?.entries?.get("to") as? YNode.Scalar ?: node as? YNode.Scalar ?: return null
    return if (readOnly) readOnlyCellText(col, scalar) else scalar.value?.toString()?.replace("\n", " ")
}

/** 閲覧セルの表示文字列（null = 未設定）。enum は表示ラベル、boolean は はい／いいえ。 */
private fun readOnlyCellText(col: FieldDef, node: YNode.Scalar?): String? {
    val raw = node?.value ?: return null
    return when (col.type) {
        FieldType.BOOLEAN -> if (raw == true) "はい" else if (raw == false) "いいえ" else raw.toString()
        FieldType.ENUM -> col.optionFor(raw.toString())?.displayLabel ?: raw.toString()
        else -> raw.toString()
    }.replace("\n", " ").ifBlank { null }
}

/** 閲覧専用のテーブルの ID セル。値の右に横ぐしへの Hub ボタンを出す（上書き索引に出現がある ID だけ）。 */
@Composable
private fun ReadOnlyIdCell(ctx: FormCtx, node: YNode.Scalar?) {
    val id = node?.value?.toString().orEmpty()
    val inIndex = id.isNotBlank() && ctx.state.overlayIndex().occurrences(id).isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) { TableCellText(id.ifBlank { null }) }
        if (inIndex) {
            EntityPageButton(ctx, id, iconButtonSize = 24.dp, iconSize = 14.dp)
        }
    }
}

/**
 * テーブルの参照セル（エンティティ参照）。上段に ID、下段に参照先の名前（表示解決の結果）、右端に横ぐしへの Hub ボタン
 * （ID セルと同じ印。上書き索引に出現がある ID だけ）。編集モードも通常の参照欄（候補の▼・→ の 2 択メニュー）のまま、
 * 名前だけ欄の中でなく欄の下に出す（欄の中の右端はセル幅で ID を押しのける。ビューグリッドと同じ流儀）。
 * 属性付き参照（{to, 属性}）は to を表示だけにし、編集は行の展開フォームで行う。
 */
@Composable
private fun RefCell(ctx: FormCtx, col: FieldDef, slot: Slot) {
    val node = slot.get()
    val attributed = node is YNode.Map
    val idSlot = if (node is YNode.Map) MapSlot(node, "to") else slot
    val id = (idSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
    val title = ctx.refTargetTitle(col, id)
    val inIndex = id.isNotBlank() && ctx.state.overlayIndex().occurrences(id).isNotEmpty()
    val displayOnly = ctx.readOnly || attributed
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (displayOnly) {
                Box(Modifier.weight(1f, fill = false)) { TableCellText(id.ifBlank { null }) }
                if (inIndex) EntityPageButton(ctx, id, iconButtonSize = 24.dp, iconSize = 14.dp)
            } else {
                // 横ぐしは → のメニュー（エンティティページで開く）にあるので、表のセルでは Hub を出さない（セルの幅を空ける）
                Box(Modifier.weight(1f)) { RefScalarEditor(ctx, col, idSlot, titleInField = false, jumpButtonSize = 30.dp) }
            }
        }
        if (title != null) {
            Text(title, fontSize = 10.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (displayOnly && id.isNotBlank() && !ctx.resolvable(ctx.refConstraint(col, id))) {
            // 編集モードでは参照欄が同じ注記を出す
            Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
        }
    }
}

@Composable
private fun TableCellText(text: String?) {
    Text(
        text ?: "（未設定）",
        fontSize = 13.sp,
        color = if (text == null) Palette.SubText else Palette.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
