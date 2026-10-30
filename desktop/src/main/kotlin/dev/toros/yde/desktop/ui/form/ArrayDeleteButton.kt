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

/** リスト・表・汎用値・設計画面で共有する、配列要素だけの削除入口。 */
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
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("配列要素を削除しますか？") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(location)
                    Text(summary)
                    Text("${doc.file.fileName}：${deletion.path?.pretty().orEmpty()}",
                        fontSize = 11.sp, color = Palette.SubText)
                    Text("この要素と、その中の項目を削除します。他の箇所にある同じIDや参照は削除しません。")
                    if (problem != null) Text(problem, color = Palette.ErrorRed)
                }
            },
            confirmButton = {
                TextButton(enabled = problem == null, onClick = {
                    if (deletion.confirm(doc, readOnly, afterDelete, markEdited)) pending = null
                }) { Text("削除", color = if (problem == null) Palette.ErrorRed else Palette.SubText) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("キャンセル") } },
        )
    }
    return request
}
