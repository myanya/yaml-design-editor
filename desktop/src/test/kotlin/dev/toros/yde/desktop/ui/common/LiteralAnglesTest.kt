package dev.toros.yde.desktop.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 項目や文書の説明で、山括弧を文字として出す（Markdown の描画が HTML として落とさないように）。
 * コードの印の中・逃がし済み・URL の山括弧はそのまま。
 */
class LiteralAnglesTest {
    @Test
    fun `コードの外の山括弧だけを逃がす`() {
        assertEquals("ファイル名（backlog/\\<ID>.yaml）", literalAngles("ファイル名（backlog/<ID>.yaml）"))
        assertEquals("`backlog/<ID>.yaml` と \\<名前>", literalAngles("`backlog/<ID>.yaml` と <名前>"))
        // 2 つ並びの印の中に 1 つの印があっても、閉じるまでがコード
        assertEquals("``a ` <b>`` \\<c>", literalAngles("``a ` <b>`` <c>"))
        // 閉じない印は文字として扱い、その後ろも逃がす
        assertEquals("` \\<x>", literalAngles("` <x>"))
    }

    @Test
    fun `逃がし済みと URL の山括弧は変えない`() {
        assertEquals("a \\< b", literalAngles("a \\< b"))
        assertEquals("<https://example.com/a> と \\<b>", literalAngles("<https://example.com/a> と <b>"))
        assertEquals("1 \\< 2", literalAngles("1 < 2"))
        assertEquals("山括弧なし", literalAngles("山括弧なし"))
    }
}
