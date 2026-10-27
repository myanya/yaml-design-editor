package dev.toros.yde.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableListItemScope

// ================================================================ ドラッグで並べ替え（sh.calvin.reorderable を包む）

/** 並べ替えできる行の中で使える部品。ライブラリの型はこのファイルの外へ出さない。 */
interface ReorderRowScope {
    /**
     * 行の取っ手（⋮⋮）。ここを押してそのまま動かすと並べ替えられる。行の他の場所（入力欄など）からは始まらない。
     */
    @Composable
    fun Handle(modifier: Modifier = Modifier)
}

/**
 * ドラッグで並べ替える縦リスト（画面に全部出す Column。遅延描画ではない）。
 * ドロップしたときだけ `onMove(from, to)` が 1 回呼ばれる（ドラッグ中は呼ばれない＝木を触らず、Undo は 1 回分）。
 * 行の中身は `content` が描き、取っ手は `Handle()` で置く。並べ替えを許さないときは取っ手を置かなければよい。
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    content: @Composable ReorderRowScope.(index: Int, item: T, dragging: Boolean) -> Unit,
) {
    // ライブラリは remember(list) で要素数・位置を保持する。同じ可変リストを渡すと
    // 追加・削除・並べ替えを検知できないため、毎回その時点の要素列をコピーする。
    // remember(items) でコピーを保持すると同じ問題になるので、ここでは保持しない。
    ReorderableColumn(
        list = items.toList(),
        onSettle = { from, to -> if (from != to) onMove(from, to) },
        modifier = modifier,
    ) { index, item, isDragging ->
        key(key?.invoke(item) ?: item) {
            ReorderableItem {
                HandleScope(this).content(index, item, isDragging)
            }
        }
    }
}

private class HandleScope(private val item: ReorderableListItemScope) : ReorderRowScope {
    @Composable
    override fun Handle(modifier: Modifier) {
        with(item) {
            Icon(
                Icons.Default.DragIndicator, contentDescription = "ドラッグで並べ替え",
                modifier = modifier.size(18.dp).draggableHandle(),
                tint = Palette.SubText,
            )
        }
    }
}
