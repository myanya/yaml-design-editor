package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.schema.Display
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape

/** リスト・表・汎用値・設計画面で共有する、配列要素の削除入口（map のキーは下の `MapKeyDeleteButton`）。 */
@Composable
internal fun ArrayDeleteButton(
    doc: OpenDocument,
    array: YNode.Seq,
    index: Int,
    readOnly: Boolean,
    modifier: Modifier = Modifier.size(24.dp),
    iconSize: Dp = 16.dp,
    label: String? = null,
    itemField: FieldDef? = null,
    titleLookup: ((String) -> String?)? = null,
    textButton: Boolean = false,
    afterDelete: () -> Unit = {},
    markEdited: () -> Unit = { doc.markEdited() },
) {
    val request = rememberArrayDeleteRequest(doc, array, index, readOnly, label, itemField, titleLookup, afterDelete, markEdited)
    if (!readOnly) {
        if (textButton) {
            TextButton(onClick = request, shape = PanelShape) {
                Text("削除", color = Palette.ErrorRed, fontSize = 12.sp)
            }
        } else IconButton(onClick = request, modifier = modifier) {
            Icon(Icons.Default.Delete, contentDescription = "配列要素を削除", modifier = Modifier.size(iconSize),
                tint = Palette.SubText)
        }
    }
}

/**
 * 配列要素の削除の確認（ダイアログ）を描き、確認を始める関数を返す。ボタンでもメニューの項目（表の行の ⋯）でも、同じ確認と削除を通す。
 * ダイアログはこの関数を呼んだ場所に描くので、メニューを閉じても消えない場所（メニューの外）で呼ぶ。
 */
@Composable
internal fun rememberArrayDeleteRequest(
    doc: OpenDocument,
    array: YNode.Seq,
    index: Int,
    readOnly: Boolean,
    label: String? = null,
    itemField: FieldDef? = null,
    titleLookup: ((String) -> String?)? = null,
    afterDelete: () -> Unit = {},
    markEdited: () -> Unit = { doc.markEdited() },
): () -> Unit {
    doc.version
    var pending by remember(doc) { mutableStateOf<ArrayDeletion?>(null) }
    var summary by remember(doc) { mutableStateOf("") }
    var location by remember(doc) { mutableStateOf("") }
    val request = {
        val deletion = ArrayDeletion(doc, array, index)
        summary = Display.inline(array.items.getOrNull(index), doc.schema, itemField,
            maxLength = 120, titleLookup = titleLookup).ifBlank { "（空の値）" }
        location = "${label ?: deletion.path?.lastOrNull()?.toString() ?: "配列"}／${index + 1}行目"
        pending = deletion
    }
    pending?.let { deletion ->
        val problem = deletion.problem(doc, readOnly)
        DeleteConfirmDialog(
            title = "配列要素を削除しますか？", location = location, summary = summary,
            place = "${doc.file.fileName}：${deletion.path?.pretty().orEmpty()}",
            note = "この要素と、その中の項目を削除します。他の箇所にある同じIDや参照は削除しません。",
            problem = problem,
            onConfirm = { if (deletion.confirm(doc, readOnly, afterDelete, markEdited)) pending = null },
            onDismiss = { pending = null },
        )
    }
    return request
}

/** map（キー → 値）の 1 件の削除入口。配列の行と同じく、確認を出してから消す（リスト・表・汎用表示で共有）。 */
@Composable
internal fun MapKeyDeleteButton(
    doc: OpenDocument,
    map: YNode.Map,
    key: String,
    readOnly: Boolean,
    modifier: Modifier = Modifier.size(24.dp),
    iconSize: Dp = 16.dp,
    label: String? = null,
    valueField: FieldDef? = null,
    titleLookup: ((String) -> String?)? = null,
    markEdited: () -> Unit = { doc.markEdited() },
) {
    doc.version
    var pending by remember(doc) { mutableStateOf<MapKeyDeletion?>(null) }
    var summary by remember(doc) { mutableStateOf("") }
    var location by remember(doc) { mutableStateOf("") }
    pending?.let { deletion ->
        val problem = deletion.problem(doc, readOnly)
        DeleteConfirmDialog(
            title = "キーを削除しますか？", location = location, summary = summary,
            place = "${doc.file.fileName}：${deletion.path?.let { (it + deletion.key).pretty() }.orEmpty()}",
            note = "このキーと、その中の項目を削除します。他の箇所にある同じIDや参照は削除しません。",
            problem = problem,
            onConfirm = { if (deletion.confirm(doc, readOnly, markEdited)) pending = null },
            onDismiss = { pending = null },
        )
    }
    if (readOnly) return
    IconButton(onClick = {
        val deletion = MapKeyDeletion(doc, map, key)
        summary = Display.inline(map.entries[key], doc.schema, valueField,
            maxLength = 120, titleLookup = titleLookup).ifBlank { "（空の値）" }
        location = "${label ?: deletion.path?.lastOrNull()?.toString() ?: "map"}／キー「$key」"
        pending = deletion
    }, modifier = modifier) {
        Icon(Icons.Default.Delete, contentDescription = "キーを削除", modifier = Modifier.size(iconSize), tint = Palette.SubText)
    }
}

/** 削除の確認（配列の行・map のキーで同じ形）。どこの何を消すか・値の要約・原本の場所・消える範囲を示し、確定できない理由があれば赤で出す。 */
@Composable
private fun DeleteConfirmDialog(
    title: String,
    location: String,
    summary: String,
    place: String,
    note: String,
    problem: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(location)
                Text(summary)
                Text(place, fontSize = 11.sp, color = Palette.SubText)
                Text(note)
                if (problem != null) Text(problem, color = Palette.ErrorRed)
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = onConfirm) {
                Text("削除", color = if (problem == null) Palette.ErrorRed else Palette.SubText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
