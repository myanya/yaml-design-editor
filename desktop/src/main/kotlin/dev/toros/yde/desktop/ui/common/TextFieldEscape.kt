package dev.toros.yde.desktop.ui.common

internal enum class TextFieldEscapeAction { PASS, CONSUME, CLOSE_POPUP, CLEAR_FOCUS }

/**
 * Esc 一押しで扱う層を一つに限る。入力部品が変換を終了する前の状態を preview で覚え、
 * 同じキーの処理中や長押し中に IME → メニュー → フォーカスと連続して抜けないようにする。
 */
internal class TextFieldEscape {
    private var pressed = false
    private var composingAtPress = false
    private var repeated = false

    fun preview(keyDown: Boolean, composing: Boolean) {
        if (!keyDown) {
            reset()
            return
        }
        repeated = pressed
        if (!pressed) composingAtPress = composing
        pressed = true
    }

    fun action(enabled: Boolean, composing: Boolean, popupOpen: Boolean): TextFieldEscapeAction = when {
        !enabled || composingAtPress || composing -> TextFieldEscapeAction.PASS
        repeated -> TextFieldEscapeAction.CONSUME
        popupOpen -> TextFieldEscapeAction.CLOSE_POPUP
        else -> TextFieldEscapeAction.CLEAR_FOCUS
    }

    fun reset() {
        pressed = false
        composingAtPress = false
        repeated = false
    }
}
