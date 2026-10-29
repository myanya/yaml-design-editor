package dev.toros.yde.desktop.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChoiceOptionsTest {
    @Test
    fun `名前と保存値と補足を大文字小文字を区別せず検索する`() {
        val options = listOf(
            ChoiceOption("done", "完了", "担当: 山田", searchText = "DONE"),
            ChoiceOption("todo", "未着手", "担当: 佐藤", searchText = "TODO"),
        )
        assertEquals(listOf(options[0]), filterChoiceOptions(options, " 完了　山田 "))
        assertEquals(listOf(options[1]), filterChoiceOptions(options, "toDo"))
        assertTrue(filterChoiceOptions(options, "完了 佐藤").isEmpty())
    }

    @Test
    fun `同名候補の検索は並び順と値と選択印を保ち候補数を打ち切らない`() {
        val options = (1..150).map { ChoiceOption(it, "同名", "ID-$it", selected = it == 149) }
        val found = filterChoiceOptions(options, "同名")
        assertEquals(options, found)
        assertEquals(listOf(options[148]), filterChoiceOptions(options, "ID-149"))
        assertSame(options, filterChoiceOptions(options, "  \n  "))
        assertTrue(options[148].selected)
    }

    @Test
    fun `未設定と空文字の値を検索でも同一視しない`() {
        val options = listOf(ChoiceOption<String?>(null, "未設定", selected = true), ChoiceOption<String?>("", "空文字"))
        assertEquals(listOf(options[1]), filterChoiceOptions(options, "空文字"))
        assertEquals(null, options[0].value)
        assertTrue(options[0].selected)
    }

    @Test
    fun `変換を終了したEnterやEscの長押しでは選択も閉鎖も行わない`() {
        val press = ChoiceKeyPress()
        press.press(composing = true)
        press.press(composing = false)
        assertFalse(press.release())
        press.press(composing = false)
        assertTrue(press.release())
    }

    @Test
    fun `キーを離すまで操作せず長押しでも一度だけ確定する`() {
        val press = ChoiceKeyPress()
        assertFalse(press.release())
        repeat(4) { press.press(composing = false) }
        assertTrue(press.release())
        assertFalse(press.release())
    }

    @Test
    fun `キー処理中に変換が始まっても候補を確定しない`() {
        val press = ChoiceKeyPress()
        press.press(composing = false)
        assertFalse(press.release(composing = true))
    }
}
