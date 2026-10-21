package dev.toros.yde.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/** 候補の並び順を維持し、検索・説明・キーボードの移動先と確定値を分離する。 */
@Composable
internal fun <T> ChoiceMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<ChoiceOption<T>>,
    onSelect: (T) -> Unit,
    note: String? = null,
) {
    if (!expanded) return
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) { filterChoiceOptions(options, query) }
    val initialIndex = filtered.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    var active by remember { mutableIntStateOf(initialIndex) }
    var keyboardActive by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ChoiceOption<T>?>(null) }
    var composing by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val escape = remember { ChoiceKeyPress() }
    val enter = remember { ChoiceKeyPress() }
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    val searchable = options.size > 10
    // 検索中は先頭へ、検索を消したら現在値へ。手動スクロールには追従しない。
    LaunchedEffect(query) {
        active = if (query.isBlank()) initialIndex else 0
        keyboardActive = false
        listState.scrollToItem(active)
    }
    fun select(option: ChoiceOption<T>) {
        onSelect(option.value)
        onDismissRequest()
    }
    Popup(
        popupPositionProvider = FieldHelpPosition,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        onPreviewKeyEvent = { event ->
            val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed
            val down = event.type == KeyEventType.KeyDown
            when {
                plain && event.key == Key.Escape -> {
                    if (down) { escape.press(composing); !composing }
                    else {
                        if (escape.release(composing)) {
                            if (detail != null) detail = null else onDismissRequest()
                        }
                        true
                    }
                }
                plain && event.key == Key.Enter -> {
                    if (down) { enter.press(composing); !composing && detail == null && (keyboardActive || searchFocused) }
                    else {
                        val allowed = enter.release(composing)
                        if (allowed && detail == null && (keyboardActive || searchFocused)) filtered.getOrNull(active)?.let(::select)
                        // Tab で移動した行や説明ボタンの Enter は、そのボタンに任せる。
                        allowed && detail == null && (keyboardActive || searchFocused)
                    }
                }
                plain && down && !composing && detail == null &&
                    (event.key == Key.DirectionDown || event.key == Key.DirectionUp) -> {
                    if (filtered.isNotEmpty()) {
                        active = (if (!keyboardActive) active
                            else active + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(0, filtered.lastIndex)
                        keyboardActive = true
                        scope.launch { listState.scrollToItem(active) }
                    }
                    true
                }
                event.key == Key.Tab -> { keyboardActive = false; false }
                else -> false
            }
        },
    ) {
        Surface(
            modifier = Modifier.width(360.dp).heightIn(max = 320.dp),
            shape = PanelShape, color = Palette.Surface, contentColor = Palette.Text,
            border = BorderStroke(1.dp, Palette.Border), shadowElevation = 6.dp,
        ) {
            val shownDetail = detail
            if (shownDetail != null) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { detail = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "候補一覧へ戻る", Modifier.size(18.dp))
                        }
                        Text("候補の詳細", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        Column(Modifier.verticalScroll(scroll).padding(end = 12.dp)) {
                            Text(shownDetail.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (shownDetail.subtitle.isNotBlank()) Text(shownDetail.subtitle, fontSize = 12.sp, color = Palette.SubText)
                            shownDetail.description?.let { MarkdownView(it, compact = true) }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
                    }
                }
            } else Column(Modifier.padding(vertical = 4.dp)) {
                if (searchable) {
                    CompactTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("候補を検索（名前・値・ID）") },
                        onCompositionChange = { composing = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            .focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused },
                    )
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                }
                note?.let {
                    Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 11.sp, color = Palette.SubText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (filtered.isEmpty()) {
                    Text("候補なし", Modifier.padding(12.dp), fontSize = 13.sp, color = Palette.SubText)
                } else Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                        itemsIndexed(filtered) { index, option ->
                            ChoiceMenuItem(
                                selected = option.selected,
                                text = {
                                    Column {
                                        Text(option.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val summary = listOfNotNull(option.subtitle.takeIf { it.isNotBlank() }, option.description)
                                            .joinToString(" — ").replace(Regex("\\s+"), " ")
                                        if (summary.isNotBlank()) Text(summary, fontSize = 11.sp, color = Palette.SubText,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                onClick = { select(option) },
                                modifier = Modifier.fillMaxWidth().then(
                                    if (keyboardActive && index == active) Modifier.border(1.dp, Palette.Primary, PanelShape)
                                    else Modifier),
                                information = {
                                    IconButton(onClick = {
                                        keyboardActive = false
                                        composing = false
                                        searchFocused = false
                                        detail = option
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Outlined.Info, "${option.label}の全文を表示", Modifier.size(16.dp), tint = Palette.SubText)
                                    }
                                },
                            )
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}
