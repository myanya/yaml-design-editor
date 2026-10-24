package dev.toros.yde.app.ui

import androidx.compose.ui.input.key.*
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument

enum class HistoryShortcut { UNDO, REDO }

internal fun historyShortcut(
    key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean,
): HistoryShortcut? = when {
    !ctrl || alt || meta -> null
    key == Key.Z -> if (shift) HistoryShortcut.REDO else HistoryShortcut.UNDO
    key == Key.Y -> HistoryShortcut.REDO
    else -> null
}

internal fun KeyEvent.historyShortcut(): HistoryShortcut? =
    historyShortcut(key, isCtrlPressed, isShiftPressed, isAltPressed, isMetaPressed)

/** 一時的な操作フォームは文書の履歴へ接続しない。同じパスでも別の文書インスタンスなら対象外。 */
internal fun AppState.historyHandler(doc: OpenDocument): ((HistoryShortcut) -> Unit)? =
    if (!ownsDocument(doc)) null else { action ->
        when (action) {
            HistoryShortcut.UNDO -> undoFromUi()
            HistoryShortcut.REDO -> redoFromUi()
        }
    }

/** 変換中の一押しを、変換終了後のリピートやウィンドウへの伝播で文書の Undo に変えない。 */
internal class TextFieldHistory {
    private var pressed = false
    var blocksFallback = false
        private set

    fun preview(
        action: HistoryShortcut?, keyDown: Boolean, composing: Boolean,
        onHistory: ((HistoryShortcut) -> Unit)?,
    ): Boolean {
        if (!keyDown || onHistory == null) { reset(); return false }
        if (action == null) return false
        if (!pressed) blocksFallback = composing
        pressed = true
        blocksFallback = blocksFallback || composing
        if (blocksFallback) return false
        onHistory(action)
        return true
    }

    fun reset() { pressed = false; blocksFallback = false }
}
