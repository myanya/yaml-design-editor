package dev.toros.yde.desktop.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 図の「配置の調整」の 1 項目。選べないときは `note` に理由を出す。 */
internal data class DiagramLayoutChoice(
    val key: String, val label: String, val description: String, val checked: Boolean,
    val enabled: Boolean = true, val note: String? = null,
)

/** 図（フロー・レンズ）の「配置の調整」。いくつでも選べ、押しても閉じない。ボタンには効いている数を出す。 */
@Composable
internal fun DiagramLayoutOptionsMenu(
    choices: List<DiagramLayoutChoice>,
    enabled: Boolean = true,
    onToggle: (key: String, checked: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val count = choices.count { it.checked && it.enabled }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("diagram-layout-options"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(if (count == 0) "配置の調整" else "配置の調整（$count）", fontSize = 11.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.label, fontSize = 12.sp)
                            Text(c.note ?: c.description, fontSize = 10.sp, color = Palette.SubText)
                        }
                    },
                    leadingIcon = { Checkbox(c.checked, onCheckedChange = null, enabled = c.enabled) },
                    onClick = { onToggle(c.key, !c.checked) },
                    enabled = c.enabled,
                )
            }
            Text(
                "自動で並べるときに効きます。選んだ組み合わせごとに位置を覚え、戻すと前の並びに戻ります。",
                fontSize = 10.sp, color = Palette.SubText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 340.dp),
            )
        }
    }
}

/** 図（フロー・レンズ）の「線のラベル」。外すと線の中央のラベルを隠す。描き方だけを変え、箱は動かさない。 */
@Composable
internal fun DiagramLabelToggle(shown: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(shown, onChange, modifier = Modifier.size(26.dp).testTag("diagram-label-toggle"))
        Text("線のラベル", fontSize = 11.sp)
    }
}

/** 配置の調整を、配置の記録を分ける選択条件へ足す。何も選ばないときは足さない（調整の無い記録をそのまま読む）。 */
internal fun withLayoutOptionSelection(selection: Map<String, List<String>>, keys: Collection<String>): Map<String, List<String>> =
    if (keys.isEmpty()) selection else selection + ("options" to keys.distinct().sorted())
