package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.UiTooltip

/** 編集元への移動後も、複数文書への影響と保存状態を確認できる通知。 */
@Composable
internal fun HistoryNoticeBanner(state: AppState) {
    val notice = state.historyNotice?.takeIf { it.crossDocument } ?: return
    val targets = state.historyNoticeTargets()
    var expanded by remember(notice) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Palette.ConflictBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                val verb = if (notice.undo) "変更を元に戻しました" else "変更をやり直しました"
                Text(if (notice.returnedToOrigin) "編集した画面に戻って、$verb" else verb,
                    color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                UiTooltip(notice.summary) {
                    Text(notice.summary, color = Palette.SubText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                OutlinedButton(onClick = {
                    if (targets.size == 1 && targets.single().canOpen) state.openHistoryTarget(targets.single().document)
                    else expanded = true
                }, shape = PanelShape) {
                    Text(if (targets.size == 1 && targets.single().canOpen) "対象の文書を開く" else "対象 ${targets.size} 件を確認", fontSize = 12.sp)
                }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp).widthIn(max = 560.dp)) {
                    targets.forEach { target ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(target.document.value, fontSize = 12.sp)
                                    Text(when { target.dirty -> "● 未保存"; !target.canOpen -> "現在ファイルなし"; else -> "保存済み" },
                                        fontSize = 11.sp, color = Palette.SubText)
                                }
                            },
                            enabled = target.canOpen,
                            onClick = { expanded = false; state.openHistoryTarget(target.document) },
                        )
                    }
                }
            }
            TextButton(onClick = state::dismissHistoryNotice) { Text("閉じる", fontSize = 12.sp) }
        }
        val dirty = targets.count { it.dirty }
        Text(if (dirty > 0) "対象 ${targets.size} 件のうち $dirty 件が未保存（●）です。保存で確定します。"
            else "対象 ${targets.size} 件に未保存の変更はありません。",
            color = Palette.SubText, fontSize = 11.sp)
    }
}
