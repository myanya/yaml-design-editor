package dev.toros.yde.desktop.ui

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
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
    /** グラフの系列色（準備完了＝青・未完＝橙）。色覚の差も含めて見分けられる組を検証して選んだ（明暗で別の値） */
    val chartReady: Color, val chartPending: Color,
)

object Palette {
    private val light = AppPalette(
        primary = Color(0xFF475F68), secondary = Color(0xFF626959), panel = Color(0xFFF5F4F0),
        editor = Color(0xFFFCFBF8), surface = Color(0xFFFFFEFB), border = Color(0xFFDCDCD4),
        selected = Color(0xFFE2E7E6), highlight = Color(0x556F939B), error = Color(0xFFAE3530),
        warning = Color(0xFF906000), success = Color(0xFF37734E), subText = Color(0xFF696A64),
        text = Color(0xFF30312F), folder = Color(0xFF897044), conflict = Color(0xFFF3EACF),
        chartReady = Color(0xFF1B6F9E), chartPending = Color(0xFFB36B00),
    )
    private val dark = AppPalette(
        primary = Color(0xFFA6B6BA), secondary = Color(0xFFB4BBA8), panel = Color(0xFF292A2C),
        editor = Color(0xFF252627), surface = Color(0xFF2E3031), border = Color(0xFF454647),
        selected = Color(0xFF3B4548), highlight = Color(0x556F939B), error = Color(0xFFE5A09A),
        warning = Color(0xFFD6B673), success = Color(0xFF92B9A0), subText = Color(0xFFAAA9A3),
        text = Color(0xFFDDDCD7), folder = Color(0xFFBBA477), conflict = Color(0xFF443E30),
        chartReady = Color(0xFF3A86C8), chartPending = Color(0xFFB07A1A),
    )
    private var active by mutableStateOf(light)
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
    val ChartReady get() = active.chartReady
    val ChartPending get() = active.chartPending
    /** 色分けの追加色（グラフレンズの型。基本色 4 つの後に使う。明暗で共通） */
    val Accents: List<Color> = listOf(Color(0xFF9C27B0), Color(0xFF00838F), Color(0xFF6D4C41), Color(0xFF546E7A))
}

// ---------------------------------------------------------------- フォント

/**
 * アプリが同梱するフォント。等幅は Cica（OFL 1.1。ビルド時に取得して資源 fonts/cica/ に入れる＝build.gradle.kts の downloadFonts）。
 * YAML ソース・差分・ログなど等幅で見せる文字は `FontFamily.Monospace`（OS 任せで日本語の幅が揃わない）でなく、ここを使う。
 */
object AppFonts {
    /** 等幅。Regular と Bold を同梱し、斜体は描画時の合成に任せる。資源が無ければ OS の等幅に戻す（開発時に取得を飛ばした場合） */
    val Mono: FontFamily by lazy {
        val faces = listOf(
            "fonts/cica/Cica-Regular.ttf" to FontWeight.Normal,
            "fonts/cica/Cica-Bold.ttf" to FontWeight.Bold,
        )
        val loader = Thread.currentThread().contextClassLoader ?: AppFonts::class.java.classLoader
        if (faces.all { (path, _) -> loader.getResource(path) != null }) {
            FontFamily(faces.map { (path, weight) -> Font(resource = path, weight = weight, style = FontStyle.Normal) })
        } else {
            System.err.println("同梱フォント（fonts/cica/）が見つからないため OS の等幅フォントを使います。./gradlew processResources で取得できます")
            FontFamily.Monospace
        }
    }
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    Palette.useDark(dark)
    val scheme = if (dark) darkColorScheme(
        primary = Palette.Primary, secondary = Palette.Secondary, background = Palette.EditorBg,
        surface = Palette.Surface, surfaceVariant = Palette.PanelBg, outline = Palette.Border,
        onPrimary = Palette.EditorBg, onSecondary = Palette.EditorBg,
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
        colorScheme = scheme.copy(
            primaryContainer = Palette.Selected, onPrimaryContainer = Palette.Text,
            secondaryContainer = Palette.Selected, onSecondaryContainer = Palette.Text,
            surfaceTint = Color.Transparent, surfaceDim = Palette.PanelBg, surfaceBright = Palette.Surface,
            surfaceContainerLowest = Palette.EditorBg, surfaceContainerLow = Palette.PanelBg,
            surfaceContainer = Palette.PanelBg, surfaceContainerHigh = Palette.Surface,
            surfaceContainerHighest = Palette.Selected,
        ),
        typography = androidx.compose.material3.Typography(
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(8.dp),
        ),
        content = content,
    )
}

val PanelShape = RoundedCornerShape(6.dp)

/** フォームのまとまりを、枠の入れ子ではなく余白と字下げで表す。 */
object FormSpacing {
    val FieldGap = 4.dp
    val GroupGap = 16.dp
    val Indent = 16.dp
    val MaxIndent = 48.dp
}

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
    readOnly: Boolean = false,
    /** フォーム入力で有効にする。検索欄やダイアログは既存の Esc 操作を持つ。 */
    clearFocusOnEscape: Boolean = false,
    /** フォーカスを残す候補が開いている間だけ渡す。Esc は候補を閉じるだけで消費する。 */
    onDismissPopup: (() -> Unit)? = null,
    /** 検索を持つ選択一覧が、IME の確定・取消を先取りしないための通知。 */
    onCompositionChange: ((Boolean) -> Unit)? = null,
    /** 文書へ直接反映する欄だけ指定する。検索・追加前の値などは欄の文字列履歴を使う。 */
    onHistory: ((HistoryShortcut) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var localValue by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != localValue) localValue = value
    }
    val shown = if (!focused && unfocusedText != null) unfocusedText(localValue) else localValue
    // String 版 BasicTextField と同じく選択・変換状態を保持し、文字が変わったときだけ外へ通知する。
    var editingValue by remember { mutableStateOf(TextFieldValue(value)) }
    val shownValue = editingValue.copy(text = shown)
    SideEffect {
        if (shownValue.selection != editingValue.selection || shownValue.composition != editingValue.composition) {
            editingValue = shownValue
        }
    }
    var lastText by remember(shown) { mutableStateOf(shown) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val escape = remember { TextFieldEscape() }
    val history = remember { TextFieldHistory() }
    LaunchedEffect(focused) { if (!focused) { escape.reset(); history.reset() } }
    val borderColor = when {
        isError -> Palette.ErrorRed
        focused -> Palette.Primary
        else -> Palette.Border
    }
    BasicTextField(
        value = shownValue,
        readOnly = readOnly,
        onValueChange = {
            editingValue = it
            onCompositionChange?.invoke(it.composition != null)
            if (lastText != it.text) {
                lastText = it.text
                localValue = it.text
                onValueChange(it.text)
            }
        },
        singleLine = singleLine,
        textStyle = textStyle.merge(TextStyle(color = Palette.Text)),
        interactionSource = interaction,
        modifier = modifier.defaultMinSize(minHeight = if (singleLine) 38.dp else 68.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape) {
                    escape.preview(event.type == KeyEventType.KeyDown, editingValue.composition != null)
                }
                history.preview(event.historyShortcut(), event.type == KeyEventType.KeyDown,
                    editingValue.composition != null, onHistory)
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.historyShortcut() != null && history.blocksFallback) {
                    return@onKeyEvent true
                }
                // 子と IME が扱ったキーはここへ来ない。KeyUp や修飾キー付き Esc は操作しない。
                if (event.key != Key.Escape || event.type != KeyEventType.KeyDown || event.isCtrlPressed ||
                    event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return@onKeyEvent false
                when (escape.action(
                    enabled = clearFocusOnEscape && !readOnly && (focused || onDismissPopup != null),
                    composing = editingValue.composition != null,
                    popupOpen = onDismissPopup != null,
                )) {
                    TextFieldEscapeAction.PASS -> false
                    TextFieldEscapeAction.CONSUME -> true
                    TextFieldEscapeAction.CLOSE_POPUP -> {
                        onDismissPopup?.invoke()
                        focusRequester.requestFocus()
                        true
                    }
                    TextFieldEscapeAction.CLEAR_FOCUS -> {
                        focusManager.clearFocus()
                        true
                    }
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, PanelShape)
                    .border(if (focused || isError) 2.dp else 1.dp, borderColor, PanelShape)
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
    val formPositions = LocalFormPositions.current
    LaunchedEffect(highlighted) {
        if (highlighted) {
            if (formPositions == null) requester.bringIntoView(
                Rect(0f, -centerMargin, size.width.toFloat().coerceAtLeast(1f), size.height + centerMargin)
            )
            delay(2500)
            if (state.highlightPath == path) state.highlightPath = null
        }
    }
    val selectTarget by rememberUpdatedState(LocalActionTargetSelection.current)
    FormAnchor(path) {
        Box(
            Modifier
                .pointerInput(path) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) selectTarget?.invoke(path)
                        }
                    }
                }
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .onSizeChanged { size = it }
                .background(if (highlighted) Palette.Highlight else Color.Transparent, PanelShape)
        ) { content() }
    }
}
