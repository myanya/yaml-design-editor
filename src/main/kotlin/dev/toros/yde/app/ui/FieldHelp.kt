package dev.toros.yde.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** ホバーは項目名と説明ボタンだけで短い予告を出し、ボタンで全文を固定する。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FieldHelp(
    label: String,
    description: String?,
    showPreview: Boolean,
    heading: @Composable (labelModifier: Modifier, helpButton: @Composable () -> Unit) -> Unit,
) {
    if (description.isNullOrBlank()) {
        heading(Modifier) {}
        return
    }
    // 同じ描画位置が別の項目になっても、開いていた説明を持ち越さない。
    key(label, description) {
        var labelHovered by remember { mutableStateOf(false) }
        var buttonHovered by remember { mutableStateOf(false) }
        var labelBounds by remember { mutableStateOf(IntRect.Zero) }
        var buttonBounds by remember { mutableStateOf(IntRect.Zero) }
        var popupHovered by remember { mutableStateOf(false) }
        var pinned by remember { mutableStateOf(false) }
        var visible by remember { mutableStateOf(false) }
        val dismiss = {
            visible = false
            pinned = false
            labelHovered = false
            buttonHovered = false
            popupHovered = false
        }
        LaunchedEffect(labelHovered, buttonHovered, popupHovered, pinned) {
            if (pinned) {
                visible = true
            } else if (labelHovered || buttonHovered || popupHovered) {
                if (!visible) delay(500)
                visible = true
            } else {
                // 項目名から説明へマウスを移す間は閉じない。
                delay(200)
                visible = false
            }
        }
        Box {
            Column {
                // 列の余白や説明文、AI ボタンは判定に含めない。配置の基準も項目名に揃える。
                val labelModifier = Modifier
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        labelBounds = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                            bounds.right.roundToInt(), bounds.bottom.roundToInt())
                    }
                    .onPointerEvent(PointerEventType.Enter) { labelHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { labelHovered = false }
                    .padding(2.dp)
                heading(labelModifier) {
                    IconButton(onClick = { pinned = true; visible = true }, modifier = Modifier.size(22.dp)
                        .onGloballyPositioned {
                            val bounds = it.boundsInWindow()
                            buttonBounds = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                                bounds.right.roundToInt(), bounds.bottom.roundToInt())
                        }
                        .onPointerEvent(PointerEventType.Enter) { buttonHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { buttonHovered = false }) {
                        Icon(
                            Icons.Outlined.Info, contentDescription = "${label}の説明を開く",
                            modifier = Modifier.size(14.dp), tint = Palette.SubText,
                        )
                    }
                }
                if (showPreview) {
                    // 全文はポップアップで読む。長い説明が入力行の高さを支配しないようにする。
                    Box(Modifier.heightIn(max = 36.dp).clipToBounds()) {
                        MarkdownView(description, compact = true)
                    }
                }
            }
            if (visible) {
                Popup(
                    popupPositionProvider = remember(labelBounds, buttonBounds) {
                        // 説明ボタンが次の行へ折り返されても、予告でボタンを覆わない。
                        val headingBounds = IntRect(
                            minOf(labelBounds.left, buttonBounds.left), minOf(labelBounds.top, buttonBounds.top),
                            maxOf(labelBounds.right, buttonBounds.right), maxOf(labelBounds.bottom, buttonBounds.bottom),
                        )
                        object : PopupPositionProvider {
                            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                                FieldHelpPosition.calculatePosition(headingBounds, windowSize, layoutDirection, popupContentSize)
                        }
                    },
                    onDismissRequest = dismiss,
                    properties = PopupProperties(focusable = pinned),
                ) {
                    Surface(
                        modifier = (if (pinned) Modifier.width(420.dp).heightIn(max = 360.dp)
                            else Modifier.widthIn(max = 320.dp))
                            .onPointerEvent(PointerEventType.Enter) { popupHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { popupHovered = false },
                        shape = PanelShape, color = Palette.Surface, contentColor = Palette.Text,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Border),
                        shadowElevation = 6.dp,
                    ) {
                        if (!pinned) {
                            FieldHelpPreview(description)
                        } else Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = dismiss, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, "説明を閉じる", Modifier.size(16.dp))
                                }
                            }
                            val scroll = rememberScrollState()
                            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                                MarkdownView(
                                    description,
                                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp),
                                )
                                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 本文約 5 行分に収める。全文のスクロールやリンク操作は固定表示に任せる。 */
@Composable
private fun FieldHelpPreview(description: String) {
    val scroll = rememberScrollState()
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownView(
            description,
            Modifier.heightIn(max = 84.dp).verticalScroll(scroll, enabled = false),
            compact = true,
            onOpenLink = {},
        )
        Text(
            if (scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) "… ⓘで全文表示" else "ⓘで全文表示",
            fontSize = 11.sp, color = Palette.SubText,
        )
    }
}

/** 項目の下を優先し、画面の端では上か画面内へ寄せる。 */
internal object FieldHelpPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val y = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) anchorBounds.bottom
            else anchorBounds.top - popupContentSize.height
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
