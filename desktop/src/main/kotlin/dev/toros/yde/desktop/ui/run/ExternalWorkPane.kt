package dev.toros.yde.desktop.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.service.WorkRecord
import dev.toros.yde.app.service.WorkState
import dev.toros.yde.desktop.ExternalWorkController
import dev.toros.yde.desktop.label
import dev.toros.yde.desktop.ui.common.DiffLineRow
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.calculateDiff

/** 受信箱に外部提出の一覧と保存結果を射影する。本文や採否の原本は app-core が持つ。 */
@Composable
internal fun ExternalWorkList(controller: ExternalWorkController, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("外部からの提出（${controller.pendingCount} 件の判断待ち）", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        TextButton(onClick = controller::refresh, enabled = !controller.busy) { Text("提出を再読込") }
    }
    controller.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 12.sp) }
    controller.records.sortedByDescending { it.submittedAt ?: it.createdAt }.forEach { record ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${record.caller.callerId} ／ ${record.state.label()} ／ ${record.documents.size} ファイル", fontSize = 11.sp)
            }
            TextButton(onClick = { onOpen(record.id) }) { Text("内容と結果") }
        }
    }
}

/** 全ファイルを一つの提出として承認する。現在の検証結果と自動値込みの差分を確認してから保存する。 */
@Composable
internal fun ExternalWorkPane(controller: ExternalWorkController, record: WorkRecord, onBack: () -> Unit) {
    val preview = controller.preview.takeIf { controller.reviewedId == record.id }
    val documents = preview?.documents ?: record.documents
    var rejectConfirm by remember(record.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("受信箱へ戻る") }
        Text(record.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${record.caller.callerId} ／ ${record.state.label()}", fontSize = 12.sp)
        Text("作業 ID: ${record.id}", fontSize = 11.sp, color = Palette.SubText)
        record.message?.let { Text(it, fontSize = 12.sp, color = Palette.WarnOrange) }
        controller.error?.let { Text(it, fontSize = 12.sp, color = Palette.ErrorRed) }
        if (record.state == WorkState.AWAITING_APPROVAL) {
            Text("承認すると全ファイルを保存します。外部変更は Undo に追加されず、対象文書の過去の履歴は戻せなくなります。",
                fontSize = 12.sp, color = Palette.SubText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !controller.busy, onClick = { controller.review(record.id) }) { Text("差分と検証を再確認") }
                Button(enabled = !controller.busy && preview?.canCommit == true, onClick = { controller.approve(record.id) }) {
                    Text("承認して保存")
                }
                TextButton(enabled = !controller.busy, onClick = { rejectConfirm = true }) { Text("却下", color = Palette.ErrorRed) }
            }
        }
        if (preview != null && (preview.failures.isNotEmpty() || preview.issues.values.any { it.isNotEmpty() })) {
            Column(Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                preview.failures.forEach { (file, message) -> Text("$file: $message", fontSize = 12.sp, color = Palette.ErrorRed) }
                preview.issues.forEach { (file, issues) -> issues.forEach { issue ->
                    Text("${file.value}: ${issue.message}", fontSize = 12.sp)
                } }
            }
        }
        Text(when {
            preview != null -> "確認済みの保存内容（自動値を含む）"
            record.state == WorkState.AWAITING_APPROVAL -> "提出時の内容（承認前に再確認してください）"
            else -> "記録に残っている変更内容"
        }, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            documents.forEach { document ->
                item {
                    Text(document.id.value, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    record.persistence[document.id.value]?.let { Text(when (it) {
                        "WRITTEN" -> "保存済み"
                        "UNCHANGED" -> "変更なし"
                        "NOT_WRITTEN" -> "未保存"
                        else -> "保存結果不明"
                    }, fontSize = 11.sp) }
                }
                items(calculateDiff(document.before.orEmpty(), document.after)) { DiffLineRow(it) }
            }
        }
    }
    if (rejectConfirm) AlertDialog(onDismissRequest = { rejectConfirm = false }, title = { Text("提出を却下しますか") },
        text = { Text("原本は変更せず、提出内容と却下の結果を記録に残します。") },
        confirmButton = { TextButton(onClick = { rejectConfirm = false; controller.reject(record.id) }) { Text("却下する", color = Palette.ErrorRed) } },
        dismissButton = { TextButton(onClick = { rejectConfirm = false }) { Text("戻る") } })
}
