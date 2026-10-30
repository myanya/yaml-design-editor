package dev.toros.yde.desktop.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.desktop.AppState

/** 行・個票・文書ヘッダーで同じ前提と理由を表示する。 */
@Composable
internal fun ActionMenuItem(state: AppState, entry: ActionEntry, target: String?, onClick: () -> Unit) {
    val available = remember(entry, target, state.indexVersion, state.editVersion) { state.actionAvailability(entry, target) }
    DropdownMenuItem(enabled = available.enabled, text = {
        Column {
            Text(entry.label ?: entry.name, fontSize = 12.sp)
            available.reason?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
        }
    }, onClick = onClick)
}
