package dev.toros.yde.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimingsTest {

    /** 呼ぶたびに 1 秒進む時計。 */
    private fun steppingClock(): () -> Long {
        var now = 0L
        return { now.also { now += 1_000_000_000L } }
    }

    @Test
    fun `段階は入れ子で記録し、始まりの順と外側を先に並べる`() {
        val t = Timings("開く", steppingClock())
        t.phase("読み込み") {
            t.phase("解析") { }
            t.mark("途中")
        }
        val entries = t.entries()
        assertEquals(listOf("読み込み", "解析", "途中"), entries.map { it.name })
        assertEquals(listOf(0, 1, 1), entries.map { it.depth })
        assertEquals(null, entries[2].nanos)
        assertTrue(entries[0].nanos!! > entries[1].nanos!!)
    }

    @Test
    fun `例外で抜けても段階の時間を残す`() {
        val t = Timings("開く", steppingClock())
        runCatching { t.phase("失敗する段階") { error("x") } }
        assertEquals(listOf("失敗する段階"), t.entries().map { it.name })
    }

    @Test
    fun `measure は、このスレッドに置いた記録にだけ書く`() {
        val t = Timings("開く", steppingClock())
        assertEquals(1, Timings.measure("置く前") { 1 })
        Timings.within(t) {
            assertEquals(t, Timings.current())
            Timings.measure("置いた後") { }
        }
        assertNull(Timings.current())
        assertEquals(listOf("置いた後"), t.entries().map { it.name })
    }

    @Test
    fun `読み込みの段階を記録する`() {
        val store = InMemoryDocumentStore(
            mapOf(
                "_schemas/item.yaml" to "id: item\nmatch: \"items/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }\n",
                "items/a.yaml" to "id: A-1\n",
            ),
        )
        val t = Timings("開く")
        Timings.within(t) { WorkspaceReader.load(store) }
        val names = t.entries().map { it.name }
        listOf("ファイルの読み取り", "文書の解析（YAML）", "スキーマの組み立て", "スキーマの割当と保存基準の書き出し", "構造の検証", "意味の導出")
            .forEach { assertTrue(it in names, "$it が無い: $names") }
        assertTrue(t.report().contains("構造の検証"))
    }
}
