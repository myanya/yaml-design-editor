package dev.toros.yde.desktop.ui

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

/*
 * 表の表示（R12-06）: 配列と map を行 × 列で見せる（ObjectTable・ArrayTable・MapTable）。列の幅・セルの描画・行の操作。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

// ================================================================ テーブル表示（R12-06: 配列・map）

/** 列の型に応じたセルの最小幅（余白があれば tableColumnWidths が広げ、収まらなければ横スクロール）。列は `$ref` 解決済み。 */
private fun tableCellWidth(column: FieldDef): Dp = when {
    column.multiline -> 240.dp                 // 複数行の文字列（ヒント display: multiline。組込みの型 text）
    else -> when (column.type) {
        FieldType.BOOLEAN -> 90.dp
        FieldType.INT, FieldType.NUMBER -> 90.dp
        FieldType.DATE -> 140.dp                 // ピッカーのボタン分を含む
        FieldType.TIME -> 120.dp
        FieldType.DATETIME -> 190.dp
        FieldType.FILE -> 240.dp                 // 選択と OS で開くの 2 つのボタン分を含む
        FieldType.ENUM -> 140.dp
        FieldType.ID -> 180.dp                 // 候補の▼と横ぐしの Hub の 2 つのボタン分を含む
        FieldType.REF -> 240.dp                // 候補の▼・→・横ぐしの Hub の 3 つのボタン分を含む（名前は欄の下）
        else -> 170.dp
    }
}

private val TABLE_EXPAND_WIDTH = 28.dp
private val TABLE_OPS_WIDTH = 120.dp       // 配列の行操作（取っ手・上へ・下へ・削除・操作メニュー）
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
 * 列幅の割付（R12-06）: 型別の最小幅を保証しつつ、利用可能幅に余りがあれば文字系の列で
 * 等分して表を右端まで使う。文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）。
 * 利用可能幅に収まらない場合は最小幅のまま＝横スクロールに委ねる。列は `$ref` 解決済みの項目。
 */
internal fun tableColumnWidths(columns: List<FieldDef>, available: Dp, fixed: Dp): List<Dp> {
    val base = columns.map { tableCellWidth(it) }
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
    // 利用可能幅を測り、余白は文字系の列へ配って表を右端まで使う
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val keyWidth = if (keyHeader != null) TABLE_KEY_WIDTH else 0.dp
        val trailing = if (!ctx.readOnly && ops != null) opsWidth else if (rowMenu != null) TABLE_MENU_WIDTH else 0.dp
        val fixed = TABLE_EXPAND_WIDTH + keyWidth + trailing
        val widths = tableColumnWidths(columns, maxWidth, fixed)
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
                                        rowMenu?.invoke(i)
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

/** 配列のテーブル表示。行操作は並べ替え＋削除。 */
@Composable
internal fun ArrayTable(ctx: FormCtx, field: FieldDef, columns: List<FieldDef>, node: YNode.Seq, path: YPath) {
    val itemDef = ctx.doc.schema?.resolve(field.item) ?: field.item
    val rows = node.items.mapIndexedNotNull { i, item -> (item as? YNode.Map)?.let { i to it } }
    ObjectTable(ctx, itemDef, columns, rows, path, opsWidth = TABLE_OPS_WIDTH,
        onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() },
        ops = { i ->
        IconButton(
            onClick = {
                if (i > 0) {
                    val tmp = node.items[i - 1]; node.items[i - 1] = node.items[i]; node.items[i] = tmp
                    ctx.markEdited()
                }
            },
            enabled = i > 0,
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ", Modifier.size(16.dp)) }
        IconButton(
            onClick = {
                if (i < node.items.size - 1) {
                    val tmp = node.items[i + 1]; node.items[i + 1] = node.items[i]; node.items[i] = tmp
                    ctx.markEdited()
                }
            },
            enabled = i < node.items.size - 1,
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ", Modifier.size(16.dp)) }
        ArrayDeleteButton(ctx.doc, node, i, ctx.readOnly, iconSize = 14.dp,
            label = field.displayLabel, itemField = field.item, titleLookup = ctx.indexTitleLookup(),
            markEdited = { ctx.markEdited() })
    }, rowMenu = { i -> ElementOpsButton(ctx, path + i) })
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
            col.type == FieldType.FILE -> FileEditor(ctx, col, slot, itemPath + key, preview = false)
            else -> ValueEditor(ctx, col, slot, itemPath + key)
        }
    }
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
                Box(Modifier.weight(1f)) { RefScalarEditor(ctx, col, idSlot, titleInField = false) }
                EntityPageButton(ctx, id, enabled = inIndex)
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
