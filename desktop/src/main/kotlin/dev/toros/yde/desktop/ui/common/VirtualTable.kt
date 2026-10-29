package dev.toros.yde.desktop.ui.common

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.toros.yde.desktop.TableScrollPosition

// ================================================================ 仮想化した表（共通部品）

/** 表の列。幅は固定。`pinned` の列は左に固定され、横スクロールしても見える。 */
class TableColumn<K>(val key: K, val width: Dp, val pinned: Boolean = false)

/**
 * 見える列の範囲（純ロジック）。`widthsPx` はスクロールする列の幅、`scrollPx` は横スクロール量、`viewportPx` は表示幅。
 * 前後 `overscan` 列ずつ余分に含める（スクロール中のちらつき防止）。表示幅が未計測（0 以下）なら全部。
 */
internal fun visibleColumnRange(widthsPx: List<Int>, scrollPx: Int, viewportPx: Int, overscan: Int = 1): IntRange {
    if (widthsPx.isEmpty()) return IntRange.EMPTY
    if (viewportPx <= 0) return widthsPx.indices
    var x = 0
    var first = -1
    var last = widthsPx.lastIndex
    for ((i, w) in widthsPx.withIndex()) {
        if (x >= scrollPx + viewportPx) { last = i - 1; break }
        if (first < 0 && x + w > scrollPx) first = i
        x += w
    }
    if (first < 0) first = widthsPx.lastIndex
    if (last < first) last = first
    return (first - overscan).coerceAtLeast(0)..(last + overscan).coerceAtMost(widthsPx.lastIndex)
}

/**
 * 仮想化した表。見出し行と左の固定列は動かず、残りの列は横スクロールする。
 * 行は遅延描画（LazyColumn）、列は見える範囲だけ組み立てる（見えない列は幅だけ空ける）ので、列が多くても軽い。
 * セルの中身は呼び出し側が描く（編集経路はセル側のまま。表は見せ方だけ）。
 */
@Composable
fun <R, K> VirtualTable(
    columns: List<TableColumn<K>>,
    rows: List<R>,
    rowKey: (R) -> Any,
    modifier: Modifier = Modifier,
    headerBackground: Color = Palette.PanelBg,
    rowPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    rowDivider: Boolean = true,
    rowModifier: @Composable (R) -> Modifier = { Modifier },
    position: TableScrollPosition? = null,
    header: @Composable (TableColumn<K>) -> Unit,
    cell: @Composable (row: R, column: TableColumn<K>) -> Unit,
) {
    val pinned = remember(columns) { columns.filter { it.pinned } }
    val scrolling = remember(columns) { columns.filter { !it.pinned } }
    val hScroll = rememberScrollState(position?.horizontal ?: 0)
    val listState = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0)
    LaunchedEffect(position, listState, hScroll) {
        if (position != null) snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, hScroll.value)
        }.collect { (index, offset, horizontal) ->
            position.index = index; position.offset = offset; position.horizontal = horizontal
        }
    }
    val density = LocalDensity.current
    val widthsPx = remember(scrolling, density) { scrolling.map { with(density) { it.width.roundToPx() } } }
    var viewportPx by remember { mutableStateOf(0) }
    val range by remember(widthsPx) { derivedStateOf { visibleColumnRange(widthsPx, hScroll.value, viewportPx) } }
    val leftPad = with(density) { widthsPx.take(range.first.coerceAtLeast(0)).sum().toDp() }
    val rightPad = with(density) { widthsPx.drop(range.last + 1).sum().toDp() }

    @Composable
    fun ScrollingCells(content: @Composable (TableColumn<K>) -> Unit) {
        Row(Modifier.horizontalScroll(hScroll), verticalAlignment = Alignment.Top) {
            if (leftPad > 0.dp) Spacer(Modifier.width(leftPad))
            for (c in range) Box(Modifier.width(scrolling[c].width)) { content(scrolling[c]) }
            if (rightPad > 0.dp) Spacer(Modifier.width(rightPad))
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().background(headerBackground).padding(rowPadding), verticalAlignment = Alignment.Top) {
            pinned.forEach { col -> Box(Modifier.width(col.width)) { header(col) } }
            Box(Modifier.weight(1f).onSizeChanged { viewportPx = it.width }) { ScrollingCells { header(it) } }
        }
        HorizontalDivider(color = Palette.Border)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                contentPadding = PaddingValues(bottom = 14.dp),  // 最終行が横スクロールバーに隠れないように
            ) {
                items(rows, key = rowKey) { row ->
                    Row(rowModifier(row).fillMaxWidth().padding(rowPadding), verticalAlignment = Alignment.Top) {
                        pinned.forEach { col -> Box(Modifier.width(col.width)) { cell(row, col) } }
                        Box(Modifier.weight(1f)) { ScrollingCells { cell(row, it) } }
                    }
                    if (rowDivider) HorizontalDivider(color = Palette.Border.copy(alpha = .4f))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            // 列の合計幅が画面に収まっているときは表示しない（maxValue の初期値 Int.MAX_VALUE は測定前なので除外）
            if (hScroll.maxValue in 1 until Int.MAX_VALUE) {
                HorizontalScrollbar(
                    rememberScrollbarAdapter(hScroll),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 14.dp),
                )
            }
        }
    }
}
