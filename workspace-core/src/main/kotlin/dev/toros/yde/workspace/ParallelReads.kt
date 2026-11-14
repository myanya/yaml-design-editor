package dev.toros.yde.workspace

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ファイルの本文を読む並列。全読込（[FileSystemDocumentStore.capture]）の「読む直前の実パスの確認 → 読み取り」の組を、
 * 文書ごとに並べて流す。読み取りは待ちが長い（Windows のウイルス対策の検査・冷えた OS のキャッシュ）ので、本数は CPU の数より多くてよい。
 *
 * スレッドに渡すのは変わらない値（パス・境界）だけで、結果は入力の順に並べて返す。セッションや文書の木には触れない。
 * スレッドはプロセスで 1 つの置き場を共有する（待つだけの daemon）。同時に複数の全読込が走れば、本数を分け合う。
 * 読み取りの task の中から [mapInOrder] を呼ばない（置き場が埋まると待ち合って止まる）。
 */
object ParallelReads {
    /** 本数を決める環境変数。1 なら並列にせず 1 本で読む（回転式のディスクや、切り分けに使う）。 */
    const val ENV = "YDE_READ_THREADS"
    const val DEFAULT_THREADS = 8
    const val MAX_THREADS = 64

    /** これより少ない件数は、呼び手のスレッドで 1 本で読む（スレッドに渡す手間のほうが大きい）。 */
    const val MIN_ITEMS = 32

    /** 本数（環境変数が無い・数でないときは既定）。プロセスの起動時に決まる。 */
    val threads: Int = threadsOf(System.getenv(ENV))

    internal fun threadsOf(text: String?): Int = text?.trim()?.toIntOrNull()?.coerceIn(1, MAX_THREADS) ?: DEFAULT_THREADS

    private val pool: ExecutorService by lazy {
        val counter = AtomicInteger()
        Executors.newFixedThreadPool(threads.coerceAtLeast(2)) { task ->
            Thread(task, "yde-read-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    /**
     * items を task で写した結果を、items の順で返す。threads が 1 以下か件数が [MIN_ITEMS] より少なければ、呼び手のスレッドで順に行う。
     * 失敗は items の順で最初のものを投げ（順に行ったときと同じ誤り）、残りは取り消す。呼び手が中断されたら、残りを取り消して中断を伝える。
     */
    fun <I, R> mapInOrder(items: List<I>, threads: Int = this.threads, task: (I) -> R): List<R> {
        if (threads <= 1 || items.size < MIN_ITEMS) return items.map(task)
        val futures = items.map { item -> pool.submit(Callable { task(item) }) }
        try {
            return futures.map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        } finally {
            // 終わったものには何もしない。失敗・中断で抜けたときに、残りを止める
            futures.forEach { it.cancel(true) }
        }
    }
}
