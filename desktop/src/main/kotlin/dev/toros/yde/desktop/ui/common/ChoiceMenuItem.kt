package dev.toros.yde.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 現在値を持つ選択メニュー用。ポインターやキーボードの移動先と、確定済みの値を区別する。 */
@Composable
internal fun ChoiceMenuItem(
    selected: Boolean,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    information: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(if (selected) Palette.Selected else Color.Transparent)
            .semantics { this.selected = selected },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                information?.invoke()
                // 非選択行にも同じ幅を確保し、選択状態だけで文字位置やメニュー幅が変わらないようにする。
                Box(Modifier.size(18.dp)) {
                    if (selected) Icon(Icons.Default.Check, "選択中", tint = Palette.Primary)
                }
            }
        },
    )
}
