package dev.toros.yde.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/*
 * 汎用エディタ（スキーマの無い文書・スキーマの外のキー）: 任意の YAML を再帰的に編集し、キーの改名は表記と順序を保つ。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

// ================================================================ 汎用エディタ（スキーマなし）

/**
 * 任意の YAML 構造を再帰的に編集する汎用エディタ。
 * refHint = true のとき、値が索引上の ID に一致すればリンク（→）を表示する。
 */
@Composable
fun GenericEditor(ctx: FormCtx, slot: Slot, path: YPath, refHint: Boolean) {
    when (val node = slot.get()) {
        null -> {
            if (ctx.readOnly) {
                ReadOnlyValueText(null)
                return
            }
            CompactTextField(
                clearFocusOnEscape = true,
                onHistory = ctx.historyHandler,
                value = "",
                onValueChange = { ctx.setGenericScalar(slot, it) },
                singleLine = true,
                textStyle = fieldTextStyle,
                placeholder = { Text("（null）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is YNode.Scalar -> {
            val v = node.value
            if (ctx.readOnly) {
                val text = v?.toString() ?: ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReadOnlyValueText(
                        if (v is Boolean) (if (v) "はい" else "いいえ") else text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // ジャンプは閲覧行為として残す（R10-04）
                    if (refHint && ctx.resolvable(text)) {
                        IconButton(onClick = { ctx.state.navigateToId(text) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "定義へ移動",
                                tint = Palette.Primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                return
            }
            if (v is Boolean) {
                Checkbox(
                    checked = v,
                    onCheckedChange = { slot.set(YNode.Scalar(it)); ctx.markEdited() },
                )
            } else {
                val text = v?.toString() ?: ""
                val multiline = text.contains('\n')
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactTextField(
                        clearFocusOnEscape = true,
                        onHistory = ctx.historyHandler,
                        value = text,
                        onValueChange = { ctx.setGenericScalar(slot, it) },
                        singleLine = !multiline,
                        textStyle = fieldTextStyle,
                        isError = refHint && text.isNotBlank() && !ctx.resolvable(text),
                        modifier = Modifier.weight(1f),
                    )
                    if (refHint && ctx.resolvable(text)) {
                        IconButton(onClick = { ctx.state.navigateToId(text) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "定義へ移動",
                                tint = Palette.Primary,
                            )
                        }
                    }
                }
            }
        }
        is YNode.Seq -> {
            FormGroup(nested = path.isNotEmpty()) {
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
                                GenericEditor(ctx, SeqSlot(node, i), path + i, refHint)
                            }
                            if (!ctx.readOnly) {
                                ArrayDeleteButton(ctx.doc, node, i, ctx.readOnly,
                                    modifier = Modifier.size(32.dp).padding(top = 8.dp),
                                    titleLookup = ctx.indexTitleLookup(), markEdited = { ctx.markEdited() })
                            }
                        }
                    }
                }
                if (!ctx.readOnly) {
                    TextButton(onClick = {
                        node.items.add(YNode.Scalar(""))
                        ctx.markEdited()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                        Text("追加", fontSize = 13.sp)
                    }
                }
            }
        }
        is YNode.Map -> {
            Highlightable(ctx.state, path) {
                FormGroup(nested = path.isNotEmpty()) {
                    for (k in node.entries.keys.toList()) {
                        GenericMapEntryRow(ctx, node, k, path)
                    }
                    if (!ctx.readOnly) AddKeyRow(ctx, node)
                }
            }
        }
    }
}

/** 汎用ビューのキー名変更（R8-05: L-05）。順序と値を保って改名する。改名できないときは false。 */
internal fun renameMapKey(map: YNode.Map, oldKey: String, newKey: String): Boolean {
    val trimmed = newKey.trim()
    if (trimmed.isEmpty() || trimmed == oldKey) return false
    if (oldKey !in map.entries || trimmed in map.entries) return false
    val entries = map.entries.toList()
    map.entries.clear()
    for ((k, v) in entries) map.entries[if (k == oldKey) trimmed else k] = v
    return true
}

@Composable
fun GenericMapEntryRow(ctx: FormCtx, map: YNode.Map, key: String, mapPath: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val childPath = mapPath + key
    val childRefHint = DocScanner.isGenericRefKey(key) || DocScanner.isGenericRefListKey(key)
    var renaming by remember(key) { mutableStateOf(false) }
    var renameText by remember(key) { mutableStateOf(key) }
    if (ctx.readOnly) {
        // 閲覧専用モード（R10-04）: キー名変更・削除を出さない高密度の表示行
        Highlightable(ctx.state, childPath) {
            FormFieldLayout(labelWidth = 150.dp, structured = isStructuredFormValue(null, map.entries[key]), label = {
                Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp))
            }) {
                GenericEditor(ctx, MapSlot(map, key), childPath, refHint = childRefHint)
            }
        }
        return
    }
    Highlightable(ctx.state, childPath) {
        FormFieldLayout(
            Modifier.padding(vertical = FormSpacing.FieldGap), labelWidth = if (renaming) 214.dp else 150.dp,
            structured = isStructuredFormValue(null, map.entries[key]), label = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (renaming) {
                    // キー名の変更（R8-05）。順序・値・コメントは保たれる
                    val valid = renameText.trim().let { it.isNotEmpty() && (it == key || it !in map.entries) }
                    CompactTextField(
                        clearFocusOnEscape = true,
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        isError = !valid,
                        textStyle = fieldTextStyle,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = valid,
                        onClick = {
                            if (renameMapKey(map, key, renameText)) ctx.markEdited()
                            renaming = false
                        },
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Check, contentDescription = "キー名を確定", Modifier.size(16.dp), tint = Palette.Primary) }
                    IconButton(
                        onClick = { renaming = false; renameText = key },
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Close, contentDescription = "キー名変更を取消", Modifier.size(16.dp), tint = Palette.SubText) }
                } else {
                    Text(
                        key,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                    )
                    IconButton(
                        onClick = { renameText = key; renaming = true },
                        modifier = Modifier.size(24.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Edit, contentDescription = "キー名を変更", Modifier.size(13.dp), tint = Palette.SubText) }
                }
            }
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    GenericEditor(ctx, MapSlot(map, key), childPath, refHint = childRefHint)
                }
                IconButton(
                    onClick = { map.entries.remove(key); ctx.markEdited() },
                    modifier = Modifier.size(32.dp).padding(top = 8.dp),
                ) { Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(16.dp), tint = Palette.SubText) }
            }
        }
    }
}

@Composable
private fun AddKeyRow(ctx: FormCtx, map: YNode.Map) {
    var newKey by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(
            clearFocusOnEscape = true,
            value = newKey,
            onValueChange = { newKey = it },
            singleLine = true,
            textStyle = fieldTextStyle,
            placeholder = { Text("新しいキー") },
            modifier = Modifier.width(200.dp),
        )
        TextButton(
            enabled = newKey.isNotBlank() && newKey !in map.entries,
            onClick = {
                map.entries[newKey.trim()] = YNode.Scalar("")
                ctx.markEdited()
                newKey = ""
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("キーを追加", fontSize = 13.sp)
        }
    }
}
