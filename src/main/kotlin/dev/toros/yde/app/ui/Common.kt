package dev.toros.yde.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import kotlinx.coroutines.delay
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt

// ---------------------------------------------------------------- カラーパレット

private data class AppPalette(
    val primary: Color, val secondary: Color, val panel: Color, val editor: Color,
    val surface: Color, val border: Color, val selected: Color, val highlight: Color,
    val error: Color, val warning: Color, val success: Color, val subText: Color,
    val text: Color, val folder: Color, val conflict: Color,
)

object Palette {
    private val light = AppPalette(
        primary = Color(0xFF006C7A), secondary = Color(0xFF6650A4), panel = Color(0xFFF1F6F8),
        editor = Color(0xFFF9FCFD), surface = Color(0xFFFFFFFF), border = Color(0xFFCAD8DD),
        selected = Color(0xFFD6F2F5), highlight = Color(0x5544D7E8), error = Color(0xFFBA1A1A),
        warning = Color(0xFF9A6700), success = Color(0xFF237A4B), subText = Color(0xFF52656B),
        text = Color(0xFF172124), folder = Color(0xFF8B6F3D), conflict = Color(0xFFFFF2CC),
    )
    private val dark = AppPalette(
        primary = Color(0xFF56D6E7), secondary = Color(0xFFCAB7FF), panel = Color(0xFF101A20),
        editor = Color(0xFF0B1217), surface = Color(0xFF152229), border = Color(0xFF30464F),
        selected = Color(0xFF173B44), highlight = Color(0x5544D7E8), error = Color(0xFFFFB4AB),
        warning = Color(0xFFFFD166), success = Color(0xFF72D5A3), subText = Color(0xFF9DB0B7),
        text = Color(0xFFE0F0F4), folder = Color(0xFFD2B36C), conflict = Color(0xFF3B3218),
    )
    private var active = light
    fun useDark(enabled: Boolean) { active = if (enabled) dark else light }
    val Primary get() = active.primary
    val Secondary get() = active.secondary
    val PanelBg get() = active.panel
    val EditorBg get() = active.editor
    val Surface get() = active.surface
    val Border get() = active.border
    val Selected get() = active.selected
    val Highlight get() = active.highlight
    val ErrorRed get() = active.error
    val WarnOrange get() = active.warning
    val OkGreen get() = active.success
    val SubText get() = active.subText
    val Text get() = active.text
    val Folder get() = active.folder
    val ConflictBg get() = active.conflict
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    Palette.useDark(dark)
    val scheme = if (dark) darkColorScheme(
        primary = Palette.Primary, secondary = Palette.Secondary, background = Palette.EditorBg,
        surface = Palette.Surface, surfaceVariant = Palette.PanelBg, outline = Palette.Border,
        onPrimary = Color(0xFF00363D), onSecondary = Color(0xFF35275D),
        onBackground = Palette.Text, onSurface = Palette.Text, onSurfaceVariant = Palette.SubText,
        outlineVariant = Palette.Border.copy(alpha = .65f), error = Palette.ErrorRed,
    ) else lightColorScheme(
        primary = Palette.Primary, secondary = Palette.Secondary, background = Palette.EditorBg,
        surface = Palette.Surface, surfaceVariant = Palette.PanelBg, outline = Palette.Border,
        onPrimary = Color.White, onSecondary = Color.White,
        onBackground = Palette.Text, onSurface = Palette.Text, onSurfaceVariant = Palette.SubText,
        outlineVariant = Palette.Border.copy(alpha = .65f), error = Palette.ErrorRed,
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}

val PanelShape = RoundedCornerShape(6.dp)

/**
 * Material 標準より内部余白を抑えた、フォーム向け入力欄。suffix は枠内の右端（trailingIcon の左）に表示される。
 * プレースホルダーは呼び出し側で装飾しない: 入力テキストと同じサイズ＋SubText 色をここで一律に与える
 * （画面ごとの濃さ・大きさのばらつきを防ぐ。UX-42）。
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    isError: Boolean = false,
    textStyle: TextStyle = TextStyle(fontSize = 13.sp),
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    /** フォーカスが無いときの見せ方（桁区切りなど）。編集中は元の文字のまま。 */
    unfocusedText: ((String) -> String)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var localValue by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != localValue) localValue = value
    }
    val shown = if (!focused && unfocusedText != null) unfocusedText(localValue) else localValue
    val borderColor = when {
        isError -> Palette.ErrorRed
        focused -> Palette.Primary
        else -> Palette.Border
    }
    BasicTextField(
        value = shown,
        onValueChange = {
            localValue = it
            onValueChange(it)
        },
        singleLine = singleLine,
        textStyle = textStyle.merge(TextStyle(color = Palette.Text)),
        interactionSource = interaction,
        modifier = modifier.defaultMinSize(minHeight = if (singleLine) 38.dp else 68.dp),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().border(if (focused || isError) 2.dp else 1.dp, borderColor, PanelShape)
                    .padding(PaddingValues(start = 10.dp, end = if (trailingIcon == null) 10.dp else 2.dp,
                        top = 6.dp, bottom = 6.dp)),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (localValue.isEmpty()) placeholder?.let { ph ->
                        CompositionLocalProvider(
                            LocalTextStyle provides textStyle.merge(TextStyle(color = Palette.SubText)),
                        ) { ph() }
                    }
                    inner()
                }
                suffix?.invoke()
                trailingIcon?.invoke()
            }
        },
    )
}

// ---------------------------------------------------------------- Slot 抽象

/**
 * ツリー上の「値の置き場所」の抽象。
 * マップのキー・シーケンスの要素・ドキュメントルートを同じインターフェースで編集できるようにする。
 */
interface Slot {
    fun get(): YNode?
    fun set(n: YNode)
    fun remove()
}

class MapSlot(private val map: YNode.Map, val key: String) : Slot {
    override fun get(): YNode? = map.entries[key]
    override fun set(n: YNode) {
        n.inheritMetaFrom(map.entries[key])  // 置換してもコメント・表記スタイルを保持する（R8-01）
        map.entries[key] = n
    }
    override fun remove() { map.entries.remove(key) }
}

class SeqSlot(private val seq: YNode.Seq, val idx: Int) : Slot {
    override fun get(): YNode? = seq.items.getOrNull(idx)
    override fun set(n: YNode) {
        if (idx in seq.items.indices) {
            n.inheritMetaFrom(seq.items[idx])  // 置換してもコメント・表記スタイルを保持する（R8-01）
            seq.items[idx] = n
        }
    }
    override fun remove() { if (idx in seq.items.indices) seq.items.removeAt(idx) }
}

class RootSlot(private val doc: OpenDocument) : Slot {
    override fun get(): YNode? = doc.root
    override fun set(n: YNode) {
        n.inheritMetaFrom(doc.root)  // 置換してもコメント・表記スタイルを保持する（R8-01）
        doc.root = n
    }
    override fun remove() { /* ルートは削除不可 */ }
}

/**
 * Enter（テンキー含む）で確定する入力欄用の修飾。ダイアログの確定ボタンまでマウスを運ばせず、
 * キーボードだけで操作を完結させる（UX-21）。enabled が false の間は何もしない。
 */
fun Modifier.confirmOnEnter(enabled: () -> Boolean = { true }, onConfirm: () -> Unit): Modifier =
    onPreviewKeyEvent { ev ->
        val enter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
        if (ev.type == KeyEventType.KeyDown && enter && enabled()) { onConfirm(); true } else false
    }

/** ツリー上の絶対パスに対応する Slot。親コンテナが無ければ null。 */
fun slotAt(root: YNode, path: YPath): Slot? {
    if (path.isEmpty()) return null
    val parent = getAt(root, path.dropLast(1)) ?: return null
    return when (val last = path.last()) {
        is String -> (parent as? YNode.Map)?.let { MapSlot(it, last) }
        is Int -> (parent as? YNode.Seq)?.let { SeqSlot(it, last) }
        else -> null
    }
}

// ---------------------------------------------------------------- ハイライト

/**
 * highlightPath と一致する要素を強調表示し、ビューポート内へスクロールする。
 * 一定時間後にハイライトを自動解除する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Highlightable(
    state: AppState,
    path: YPath,
    content: @Composable () -> Unit,
) {
    val highlighted = state.highlightPath == path
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 対象を画面端でなく中央寄りに出すための余白（UX-10）。bringIntoView は「矩形が見える最小のスクロール」の
    // ため、素の呼び出しだと対象が画面の上下端に張り付く。要素の上下に余白を含めた矩形を可視化要求することで、
    // どちら向きのスクロールでも対象が端から余白ぶん内側（≒中央寄り）に収まる
    val centerMargin = with(LocalDensity.current) { 280.dp.toPx() }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            requester.bringIntoView(
                Rect(0f, -centerMargin, size.width.toFloat().coerceAtLeast(1f), size.height + centerMargin)
            )
            delay(2500)
            if (state.highlightPath == path) state.highlightPath = null
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onSizeChanged { size = it }
            .background(if (highlighted) Palette.Highlight else Color.Transparent, PanelShape)
    ) {
        content()
    }
}
