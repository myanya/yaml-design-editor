package dev.toros.yde.workspace

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelReadsTest {

    @Test
    fun `本数は環境変数の数で、無い・数でないときは既定、範囲の外は端に寄せる`() {
        assertEquals(ParallelReads.DEFAULT_THREADS, ParallelReads.threadsOf(null))
        assertEquals(ParallelReads.DEFAULT_THREADS, ParallelReads.threadsOf("たくさん"))
        assertEquals(1, ParallelReads.threadsOf(" 1 "))
        assertEquals(1, ParallelReads.threadsOf("0"))
        assertEquals(ParallelReads.MAX_THREADS, ParallelReads.threadsOf("1000"))
    }

    @Test
    fun `終わる順がばらばらでも、結果は入力の順に並ぶ`() {
        val items = (0 until 100).toList()
        val result = ParallelReads.mapInOrder(items, threads = 8) { i ->
            Thread.sleep(((i * 7) % 5).toLong())
            i * 2
        }
        assertEquals(items.map { it * 2 }, result)
    }

    @Test
    fun `失敗は入力の順で最初のものを投げる`() {
        val items = (0 until 100).toList()
        val failure = assertFailsWith<IllegalStateException> {
            ParallelReads.mapInOrder(items, threads = 8) { i ->
                when (i) {
                    40 -> { Thread.sleep(30); error("40 で失敗") }
                    70 -> error("70 で失敗")
                    else -> i
                }
            }
        }
        assertEquals("40 で失敗", failure.message)
    }

    @Test
    fun `件数が少ないときと本数が 1 のときは、呼び手のスレッドで順に行う`() {
        val caller = Thread.currentThread().name
        fun threadsUsed(count: Int, threads: Int): Set<String> {
            val names = ConcurrentHashMap.newKeySet<String>()
            ParallelReads.mapInOrder((0 until count).toList(), threads) { names += Thread.currentThread().name }
            return names
        }
        assertEquals(setOf(caller), threadsUsed(ParallelReads.MIN_ITEMS - 1, threads = 8))
        assertEquals(setOf(caller), threadsUsed(100, threads = 1))
        assertTrue(threadsUsed(100, threads = 8).all { it.startsWith("yde-read-") })
    }
}
