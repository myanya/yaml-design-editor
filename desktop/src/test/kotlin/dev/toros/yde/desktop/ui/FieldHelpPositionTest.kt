package dev.toros.yde.desktop.ui

import androidx.compose.ui.unit.*
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldHelpPositionTest {
    @Test
    fun `説明は項目の下に出し下端では上に出す`() {
        val window = IntSize(1000, 800)
        val popup = IntSize(420, 360)
        assertEquals(IntOffset(100, 150), FieldHelpPosition.calculatePosition(
            IntRect(100, 100, 248, 150), window, LayoutDirection.Ltr, popup,
        ))
        assertEquals(IntOffset(580, 340), FieldHelpPosition.calculatePosition(
            IntRect(800, 700, 948, 750), window, LayoutDirection.Ltr, popup,
        ))
    }

    @Test
    fun `小さな画面と画面外のアンカーでも負の座標を返さない`() {
        assertEquals(IntOffset.Zero, FieldHelpPosition.calculatePosition(
            IntRect(-100, -100, 48, -50), IntSize(300, 200), LayoutDirection.Ltr, IntSize(420, 360),
        ))
    }

    @Test
    fun `右から左の表示では項目の右端に揃える`() {
        assertEquals(IntOffset(280, 150), FieldHelpPosition.calculatePosition(
            IntRect(552, 100, 700, 150), IntSize(1000, 800), LayoutDirection.Rtl, IntSize(420, 360),
        ))
    }
}
