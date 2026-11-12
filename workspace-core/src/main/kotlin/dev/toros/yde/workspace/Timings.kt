package dev.toros.yde.workspace

/**
 * 処理の段階ごとの時間の記録。ワークスペースを開く処理の内訳を、手元の端末でも測れるようにする（性能の調査用）。
 *
 * 段階（[phase]）は入れ子にでき、同じスレッドの外側の段階の中に並ぶ。点（[mark]）は、始めてからの経過だけを残す。
 * 記録はスレッドをまたいでよい（読み込みは IO のスレッド、切り替えと描画は画面のスレッド）。
 *
 * カーネルとアプリの読み込みは [measure] で段階を区切り、呼び出し側がそのスレッドに置いた記録（[within]）へ書く。
 * 置かれていなければ何もしない（ふだんの処理と、ほかのスレッドの処理には影響しない）。
 */
class Timings(val title: String, private val clock: () -> Long = System::nanoTime) {

    /** 記録 1 件。nanos が null なら点（経過だけ）。startNanos は記録を始めてからの経過。 */
    class Entry(val name: String, val depth: Int, val startNanos: Long, val nanos: Long?, val thread: String)

    private val origin = clock()
    private val recorded = mutableListOf<Entry>()
    private val depth = ThreadLocal.withInitial { 0 }

    /** 始めてからの経過（ナノ秒）。 */
    val elapsedNanos: Long get() = clock() - origin

    /** 段階を測る。例外で抜けても、そこまでの時間を残す。 */
    fun <T> phase(name: String, block: () -> T): T {
        val level = depth.get()
        val start = clock()
        depth.set(level + 1)
        try {
            return block()
        } finally {
            depth.set(level)
            add(Entry(name, level, start - origin, clock() - start, Thread.currentThread().name))
        }
    }

    /** 点を残す（描画が済んだ、など）。 */
    fun mark(name: String) {
        add(Entry(name, depth.get(), elapsedNanos, null, Thread.currentThread().name))
    }

    @Synchronized private fun add(entry: Entry) { recorded += entry }

    /** 始まりの順（同じ時刻なら外側が先）。段階は終わったときに足すので、記録の順のままでは内側が先に並ぶ。 */
    @Synchronized fun entries(): List<Entry> = recorded.sortedWith(compareBy<Entry>({ it.startNanos }, { it.depth }))

    /**
     * 人が読む表。各行は「始めてからの経過・段階の時間・名前」で、入れ子は字下げで表す。
     * 末尾に、記録を書いた時点の合計（始めてからの経過）を置く。
     */
    fun report(): String = buildString {
        appendLine(title)
        appendLine("  経過      時間      段階（スレッド）")
        for (e in entries()) {
            val duration = e.nanos?.let { "%7.3f 秒".format(it / 1e9) } ?: "     ─   "
            val indent = "  ".repeat(e.depth)
            appendLine("  +%6.3f  %s  %s%s（%s）".format(e.startNanos / 1e9, duration, indent, e.name, e.thread))
        }
        append("  合計 %.3f 秒".format(elapsedNanos / 1e9))
    }

    companion object {
        private val ambient = ThreadLocal<Timings?>()

        /** このスレッドに記録を置いて block を実行する（[measure] がそこへ書く）。 */
        fun <T> within(timings: Timings?, block: () -> T): T {
            val previous = ambient.get()
            ambient.set(timings)
            try {
                return block()
            } finally {
                ambient.set(previous)
            }
        }

        /** このスレッドに置かれた記録があれば、段階として測る。無ければ block をそのまま実行する。 */
        fun <T> measure(name: String, block: () -> T): T = ambient.get()?.phase(name, block) ?: block()

        /** このスレッドに置かれた記録（無ければ null）。 */
        fun current(): Timings? = ambient.get()
    }
}
