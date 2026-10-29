package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
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
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.desktop.FormDisplayMemory
import dev.toros.yde.desktop.FormDisplayMode
import dev.toros.yde.desktop.FormNavigationMemory
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.shell.UiTooltip

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
            ChoiceMenu(open, { open = false }, (listOf<FormDisplayMode?>(null) + FormDisplayMode.entries).map { option ->
                ChoiceOption(option, option?.label ?: "未指定（直前の表示に従う）", selected = specified == option)
            }, onSelect = { option ->
                display.setSpecified(document, option)
                navigation.navigationOpen = false
            }, note = "現在の表示：${mode.label}\n" + if (display.fixed) "固定中はファイルの指定だけを変更します。"
                else "未指定に戻しても、現在の表示は維持します。")
        }
    }
}
