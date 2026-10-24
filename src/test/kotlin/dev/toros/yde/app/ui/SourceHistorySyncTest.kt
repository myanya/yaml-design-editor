package dev.toros.yde.app.ui

import dev.toros.yde.yaml.YamlIO
import kotlin.test.*

class SourceHistorySyncTest {
    @Test
    fun `隣接した書式変更を分けて履歴に関係しない行とカーソルを保つ`() {
        val base = "title: 入力\nnote: 維持 # コメント\n"
        val source = "title:    入力\nnote:    維持 # コメント\n"
        val target = "title: A\nnote: 維持 # コメント\n"
        val result = SourceHistorySync.sync(base, source, target, source.indexOf("維持"))
        assertEquals("title: A\nnote:    維持 # コメント\n", result.text)
        assertEquals(result.text.indexOf("維持"), result.caret)
        assertFalse(result.normalized)
    }

    @Test
    fun `解析できても複数行の値が変わる書式は持ち越さない`() {
        val base = "text: |\n  A\n  B\n"
        val source = "text: |\n    A\n    B\n"
        val target = "text: |\n  C\n  B\n"
        // 一行だけインデントを戻すと解析は成功するが、二行目の値に空白が入る。
        assertNotEquals(YamlIO.dumpPlain(YamlIO.parseFirst(target)!!),
            YamlIO.dumpPlain(YamlIO.parseFirst("text: |\n  C\n    B\n")!!))
        val result = SourceHistorySync.sync(base, source, target, source.length)
        assertEquals(target, result.text)
        assertTrue(result.normalized)
        assertTrue(result.caret in 0..target.length)
    }

    @Test
    fun `構造を壊すインデントの混在は履歴の表現へ戻す`() {
        val base = "items:\n- name: A\n  value: X\n"
        val source = "items:\n  - name: A\n    value: X\n"
        val target = "items:\n- name: B\n  value: X\n"
        val result = SourceHistorySync.sync(base, source, target, 0)
        assertEquals(target, result.text)
        assertTrue(result.normalized)
    }

    @Test
    fun `複数文書の後続だけの値の相違も見逃さない`() {
        val target = "title: A\n---\ntext: |\n  C\n  B\n"
        val base = target.replace("  C", "  A")
        val source = base.replace("  A", "    A").replace("  B", "    B")
        val result = SourceHistorySync.sync(base, source, target, 0)
        assertEquals(target, result.text)
        assertTrue(result.normalized)
    }

    @Test
    fun `末尾改行を増やさず履歴のコメント変更を反映する`() {
        val base = "title: 入力 # 新しいコメント\n"
        val source = "title:    入力 # 新しいコメント"
        val target = "title: A # 元のコメント\n"
        val result = SourceHistorySync.sync(base, source, target, 0)
        assertEquals("title: A # 元のコメント", result.text)
        assertFalse(result.normalized)
    }
}
