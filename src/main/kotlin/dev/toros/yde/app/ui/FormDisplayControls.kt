package dev.toros.yde.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.FormDisplayMemory
import dev.toros.yde.app.FormDisplayMode
import dev.toros.yde.app.FormNavigationMemory
import dev.toros.yde.yaml.model.DocumentId

private val FormDisplayMode.description: String
    get() = when (this) {
        FormDisplayMode.SELECTED -> "ツリーで選択した項目だけを編集"
        FormDisplayMode.LINKED -> "全体フォームとツリーをスクロール連動"
        FormDisplayMode.WHOLE -> "ツリーを隠して全体フォームを表示"
    }

/** 方式・固定・ファイル指定を、編集方式の既存の行に収める。 */
@Composable
internal fun FormDisplayControls(display: FormDisplayMemory, document: DocumentId, navigation: FormNavigationMemory) {
    val mode = display.modeFor(document)
    val specified = display.specified(document)
    // ソース・差分を開くだけでは、フォームの引き継ぎを変えない。
    SideEffect { display.show(document) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormDisplayMode.entries.forEach { option ->
            val active = mode == option
            UiTooltip("${option.label}：${option.description}") {
                IconButton(onClick = {
                    display.choose(document, option)
                    navigation.navigationOpen = false
                }, modifier = Modifier.size(32.dp)
                    .semantics { selected = active }) {
                    Box(
                        Modifier.size(26.dp).background(
                            if (active) Palette.Selected else androidx.compose.ui.graphics.Color.Transparent, PanelShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(when (option) {
                            FormDisplayMode.SELECTED -> Icons.AutoMirrored.Outlined.ViewSidebar
                            FormDisplayMode.LINKED -> Icons.AutoMirrored.Outlined.ViewList
                            FormDisplayMode.WHOLE -> Icons.AutoMirrored.Outlined.Article
                        }, option.label, Modifier.size(18.dp), tint = if (active) Palette.Primary else Palette.SubText)
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        UiTooltip(if (display.fixed) "固定中：${mode.label}（クリックで可変に戻す）" else "可変：ファイル指定または直前の表示に従う（クリックで固定）") {
            IconToggleButton(checked = display.fixed, onCheckedChange = {
                display.toggleFixed(document)
                navigation.navigationOpen = false
            }, modifier = Modifier.size(32.dp)) {
                Icon(if (display.fixed) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    if (display.fixed) "表示方式の固定を解除" else "表示方式を固定", Modifier.size(18.dp),
                    tint = if (display.fixed) Palette.Primary else Palette.SubText)
            }
        }
        var open by remember(document) { mutableStateOf(false) }
        Box {
            UiTooltip("このファイルの表示指定：${specified?.label ?: "未指定"}") {
                IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
                    Box {
                        Icon(Icons.Outlined.Tune, "ファイルの表示指定を確認・変更", Modifier.size(18.dp), tint = Palette.SubText)
                        if (specified != null) Box(Modifier.align(Alignment.TopEnd).size(5.dp).background(Palette.Primary, CircleShape))
                    }
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(340.dp)) {
                val source = when {
                    display.fixed -> "固定"
                    specified != null -> "ファイル指定"
                    else -> "引き継ぎ・未指定"
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("現在の表示：${mode.label}", fontSize = 13.sp)
                    Text(source, fontSize = 12.sp, color = Palette.SubText)
                    Spacer(Modifier.height(8.dp))
                    Text("このファイルの指定：${specified?.label ?: "未指定"}", fontSize = 13.sp)
                }
                HorizontalDivider(color = Palette.Border)
                (listOf<FormDisplayMode?>(null) + FormDisplayMode.entries).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option?.label ?: "未指定（直前の表示に従う）", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(if (specified == option) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                null, Modifier.size(18.dp), tint = if (specified == option) Palette.Primary else Palette.SubText)
                        },
                        modifier = Modifier.semantics { selected = specified == option },
                        onClick = {
                            display.setSpecified(document, option)
                            navigation.navigationOpen = false
                        },
                    )
                }
                Text(if (display.fixed) "固定中はファイルの指定だけを変更します。"
                    else "未指定に戻しても、現在の表示は維持します。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Palette.SubText)
            }
        }
    }
}
