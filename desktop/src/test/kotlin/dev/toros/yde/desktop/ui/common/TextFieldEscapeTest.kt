package dev.toros.yde.desktop.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class TextFieldEscapeTest {
    @Test
    fun `候補を閉じた長押しではフォーカスまで外さず次の一押しで外す`() {
        val escape = TextFieldEscape()
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.CLOSE_POPUP, escape.action(true, false, true))
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.CONSUME, escape.action(true, false, false))
        escape.preview(keyDown = false, composing = false)
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.CLEAR_FOCUS, escape.action(true, false, false))
    }

    @Test
    fun `同じEscの処理中に変換が終了しても候補やフォーカスを操作しない`() {
        val escape = TextFieldEscape()
        escape.preview(keyDown = true, composing = true)
        assertEquals(TextFieldEscapeAction.PASS, escape.action(true, false, true))
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.PASS, escape.action(true, false, false))
        escape.preview(keyDown = false, composing = false)
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.CLOSE_POPUP, escape.action(true, false, true))
    }

    @Test
    fun `キー処理中に始まった変換も優先する`() {
        val escape = TextFieldEscape()
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.PASS, escape.action(true, true, false))
    }

    @Test
    fun `対象外の入力欄は既存のEsc処理へ渡す`() {
        val escape = TextFieldEscape()
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.PASS, escape.action(false, false, true))
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.PASS, escape.action(false, false, false))
    }

    @Test
    fun `フォーカスを失ってキー解放を受け取れなくても次回は通常の一押しになる`() {
        val escape = TextFieldEscape()
        escape.preview(keyDown = true, composing = true)
        escape.reset()
        escape.preview(keyDown = true, composing = false)
        assertEquals(TextFieldEscapeAction.CLEAR_FOCUS, escape.action(true, false, false))
    }
}
