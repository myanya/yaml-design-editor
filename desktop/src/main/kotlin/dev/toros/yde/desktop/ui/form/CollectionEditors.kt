package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.app.schema.Display
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.FormSpacing
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.ReorderableList
import dev.toros.yde.desktop.ui.common.SeqSlot
import dev.toros.yde.desktop.ui.common.Slot

/*
 * 入れ物の編集欄: 配列（ArrayEditor。縦の並びか表）・入れ子のオブジェクト（NestedObjectEditor）・map（MapEditor）。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

@Composable
internal fun ArrayEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開し、深い階層は畳んで要約を見せる（編集・閲覧共通）。
    // 開閉は画面の覚え（ScreenMemory）が文書とパスで持ち、モード切替や画面の行き来でも保つ。ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            // 要素の作り方は型の create と配列の insert の決まり（architecture/actions.md）。受け取る値があればカードが開く
            ctx.state.addArrayElement(ctx.doc, path, field)
                .onSuccess { ctx.setExpanded(path, true) }
                .onFailure { ctx.state.status = "追加できません: ${it.message}" }
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("配列を作成して要素を追加", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Seq) {
        Text("（配列ではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // 畳み込みサマリ（R10-02）: 表示解決による「n件: 先頭要素の要約, …」。タイトルは索引から借用（UX-71）
    val header = Display.seqHeaderText(node, ctx.doc.schema, field.item, titleLookup = ctx.indexTitleLookup())
    // テーブル表示（R12-06）: 列はスキーマの適否判定（自動＋display ヒント）で決まり、
    // 実データが全て map のときだけ表にする（型がずれた要素は縦リストの汎用表示で扱う）
    val columns = Display.tableColumns(ctx.doc.schema, field)
    val tabular = columns != null && node.items.isNotEmpty() && node.items.all { it is YNode.Map }
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader(header, expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) {
        if (tabular) {
            ArrayTable(ctx, field, columns.orEmpty(), node, path)
        } else {
        // 取っ手（⋮⋮）からのドラッグで並べ替え。ドロップで 1 回だけ木を動かす（Undo 1 回分）。上下ボタンも残す
        ReorderableList(items = node.items, onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() }) { i, _, dragging ->
            Highlightable(ctx.state, path + i) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(if (dragging) Palette.Selected else Color.Transparent, PanelShape),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!ctx.readOnly) Handle(Modifier.padding(top = 12.dp, end = 2.dp))
                    Text(
                        "${i + 1}.",
                        fontSize = 12.sp, color = Palette.SubText,
                        modifier = Modifier.padding(top = if (ctx.readOnly) 2.dp else 12.dp, end = 6.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        ValueEditor(ctx, field.item ?: FieldDef(), SeqSlot(node, i), path + i)
                    }
                    if (!ctx.readOnly) {
                    Column {
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
                    }
                    ArrayDeleteButton(ctx.doc, node, i, ctx.readOnly,
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                        label = field.displayLabel, itemField = field.item, titleLookup = ctx.indexTitleLookup(),
                        markEdited = { ctx.markEdited() })
                    }
                    // 操作メニューは閲覧専用でも出す（操作は編集ではない。architecture/actions.md）
                    ElementOpsButton(ctx, path + i, Modifier.padding(top = if (ctx.readOnly) 0.dp else 8.dp))
                }
            }
        }
        }
        if (!ctx.readOnly) {
        TextButton(onClick = {
            // 要素の作り方は型の create と配列の insert の決まり（採番つき。architecture/actions.md）。受け取る値があればカードが開く
            ctx.state.addArrayElement(ctx.doc, path, field).onFailure { ctx.state.status = "追加できません: ${it.message}" }
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("追加", fontSize = 13.sp)
        }
        TextButton(onClick = { ctx.state.requestAi(ctx.doc, field, path, relatedFiles = ctx.aiRelatedFiles) }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("AIで候補追加", fontSize = 12.sp)
        }
        }
        }
    }
}

@Composable
internal fun NestedObjectEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開し、深い階層は畳んで要約を見せる（編集・閲覧共通）。
    // 開閉は画面の覚え（ScreenMemory）が文書とパスで持ち、モード切替や画面の行き来でも保つ。ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            slot.set(defaultNode(field, ctx.doc.schema))
            ctx.setExpanded(path, true)
            ctx.markEdited()
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("作成", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Map) {
        Text("（オブジェクトではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // 畳み込みサマリ（R10-02 / UX-71）: 「ID タイトル — サブタイトル（n項目）」。タイトルは索引から借用
    val header = Display.headerText(node, ctx.doc.schema, field, titleLookup = ctx.indexTitleLookup())
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader(header, expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) ObjectFields(ctx, field.fields, node, path)
    }
}

@Composable
internal fun MapEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val node = slot.get()
    // 既定は浅い階層のみ展開（編集・閲覧共通）。開閉は画面の覚えが持ち、ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = { slot.set(YNode.Map()); ctx.setExpanded(path, true); ctx.markEdited() }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("Mapを作成", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Map) {
        Text("（Mapではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // テーブル表示（R12-06）: 適否判定は配列と共有し、値が全て map のときだけキー列つきの表にする
    val columns = Display.tableColumns(ctx.doc.schema, field)
    val tabular = columns != null && node.entries.isNotEmpty() && node.entries.values.all { it is YNode.Map }
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader("${node.entries.size} 件", expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) {
            if (tabular) {
                MapTable(ctx, field, columns.orEmpty(), node, path)
            } else {
            node.entries.keys.toList().forEach { key ->
                val childPath = path + key
                Highlightable(ctx.state, childPath) {
                    FormFieldLayout(
                        Modifier.padding(vertical = FormSpacing.FieldGap), labelWidth = 150.dp,
                        structured = isStructuredFormValue(
                            field.value?.let { ctx.doc.schema?.resolve(it) ?: it }, node.entries[key],
                        ),
                        label = {
                            Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = if (ctx.readOnly) 2.dp else 10.dp))
                        },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(Modifier.weight(1f)) {
                                ValueEditor(ctx, field.value ?: FieldDef(), MapSlot(node, key), childPath)
                            }
                            if (!ctx.readOnly) {
                                IconButton(onClick = { node.entries.remove(key); ctx.markEdited() },
                                    modifier = Modifier.size(32.dp).padding(top = 8.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(16.dp), tint = Palette.SubText)
                                }
                            }
                        }
                    }
                }
            }
            }
            if (!ctx.readOnly) AddTypedMapKeyRow(ctx, node, field.value)
        }
    }
}

@Composable
private fun AddTypedMapKeyRow(ctx: FormCtx, map: YNode.Map, valueDef: FieldDef?) {
    var newKey by remember { mutableStateOf("") }
    val trimmed = newKey.trim()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(clearFocusOnEscape = true, value = newKey, onValueChange = { newKey = it }, singleLine = true,
            textStyle = fieldTextStyle, placeholder = { Text("新しいキー") },
            modifier = Modifier.width(200.dp))
        TextButton(enabled = trimmed.isNotEmpty() && trimmed !in map.entries, onClick = {
            map.entries[trimmed] = defaultNode(valueDef, ctx.doc.schema)
            ctx.markEdited()
            newKey = ""
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("キーを追加", fontSize = 13.sp)
        }
    }
}
