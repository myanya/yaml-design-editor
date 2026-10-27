package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormDisplayMemoryTest {
    private val a = DocumentId("a.yaml")
    private val b = DocumentId("b.yaml")
    private val c = DocumentId("c.yaml")

    @Test
    fun `初期状態は可変で未指定のファイルは選択項目になる`() {
        val display = FormDisplayMemory()
        assertFalse(display.fixed)
        assertEquals(FormDisplayMode.SELECTED, display.modeFor(a))
        repeat(3) { display.show(a) }
        assertNull(display.specified(a))
    }

    @Test
    fun `未指定は訪問するたびに直前の表示を引き継ぎ指定を作らない`() {
        val display = FormDisplayMemory()
        display.choose(a, FormDisplayMode.WHOLE)
        display.show(b)
        assertEquals(FormDisplayMode.WHOLE, display.modeFor(b))
        assertNull(display.specified(b))
        display.choose(c, FormDisplayMode.LINKED)
        display.show(b)
        assertEquals(FormDisplayMode.LINKED, display.modeFor(b))
        assertNull(display.specified(b))
    }

    @Test
    fun `指定されたファイルの表示も次の未指定ファイルへ引き継ぐ`() {
        val display = FormDisplayMemory()
        display.choose(a, FormDisplayMode.LINKED)
        display.choose(b, FormDisplayMode.WHOLE)
        display.show(a)
        display.show(c)
        assertEquals(FormDisplayMode.LINKED, display.currentMode)
        assertNull(display.specified(c))
    }

    @Test
    fun `固定はファイル指定を無視して表示し解除すると現在ファイルの指定へ戻る`() {
        val display = FormDisplayMemory()
        display.choose(b, FormDisplayMode.SELECTED)
        display.choose(a, FormDisplayMode.LINKED)
        display.toggleFixed(a)
        display.show(b)
        assertTrue(display.fixed)
        assertEquals(FormDisplayMode.LINKED, display.modeFor(b))
        assertEquals(FormDisplayMode.SELECTED, display.specified(b))
        display.toggleFixed(b)
        assertEquals(FormDisplayMode.SELECTED, display.modeFor(b))
        assertEquals(FormDisplayMode.LINKED, display.specified(a))
    }

    @Test
    fun `固定中の方式ボタンと閉じる操作は固定値とそのファイルの指定を変更する`() {
        val display = FormDisplayMemory()
        display.show(a)
        display.toggleFixed(a)
        display.choose(b, FormDisplayMode.WHOLE)
        display.show(c)
        assertEquals(FormDisplayMode.WHOLE, display.modeFor(c))
        assertEquals(FormDisplayMode.WHOLE, display.specified(b))
        assertNull(display.specified(a))
        assertNull(display.specified(c))
        display.toggleFixed(c)
        assertEquals(FormDisplayMode.WHOLE, display.modeFor(c))
        assertNull(display.specified(c))
    }

    @Test
    fun `固定中の指定パネルは表示を変えず可変に戻すと指定を反映する`() {
        val display = FormDisplayMemory()
        display.toggleFixed(a)
        display.setSpecified(a, FormDisplayMode.LINKED)
        assertEquals(FormDisplayMode.SELECTED, display.modeFor(a))
        assertEquals(FormDisplayMode.LINKED, display.specified(a))
        display.toggleFixed(a)
        assertEquals(FormDisplayMode.LINKED, display.modeFor(a))
    }

    @Test
    fun `同じ方式の選び直しも明示指定で解除しても表示を維持する`() {
        val display = FormDisplayMemory()
        display.show(a)
        display.choose(a, FormDisplayMode.SELECTED)
        assertEquals(FormDisplayMode.SELECTED, display.specified(a))
        display.setSpecified(a, FormDisplayMode.WHOLE)
        assertEquals(FormDisplayMode.WHOLE, display.currentMode)
        display.setSpecified(a, null)
        assertNull(display.specified(a))
        assertEquals(FormDisplayMode.WHOLE, display.modeFor(a))
        display.choose(b, FormDisplayMode.LINKED)
        display.show(a)
        assertEquals(FormDisplayMode.LINKED, display.modeFor(a))
    }

    @Test
    fun `表示候補を読むだけでは引き継ぎを変えず固定中の指定解除も表示を維持する`() {
        val display = FormDisplayMemory()
        display.choose(a, FormDisplayMode.LINKED)
        display.choose(b, FormDisplayMode.WHOLE)
        assertEquals(FormDisplayMode.LINKED, display.modeFor(a))
        assertEquals(FormDisplayMode.WHOLE, display.currentMode)
        display.toggleFixed(b)
        display.setSpecified(b, null)
        assertEquals(FormDisplayMode.WHOLE, display.modeFor(b))
        assertNull(display.specified(b))
    }

    @Test
    fun `ディレクトリの改名と削除は指定に追随しワークスペース切替は固定も忘れる`() {
        val memory = ScreenMemory()
        val display = memory.formDisplay
        val old = DocumentId("sub/a.yaml")
        val moved = DocumentId("moved/a.yaml")
        display.choose(old, FormDisplayMode.WHOLE)
        display.choose(b, FormDisplayMode.LINKED)
        display.toggleFixed(b)
        memory.renameDocument(DocumentId("sub"), DocumentId("moved"))
        assertNull(display.specified(old))
        assertEquals(FormDisplayMode.WHOLE, display.specified(moved))
        memory.forgetDocument(DocumentId("moved"))
        assertNull(display.specified(moved))
        assertEquals(FormDisplayMode.LINKED, display.specified(b))
        memory.clear()
        assertNull(display.specified(b))
        assertFalse(display.fixed)
        assertEquals(FormDisplayMode.SELECTED, display.currentMode)
    }
}
