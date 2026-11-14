package dev.toros.yde.workspace

/**
 * 処理の段階ごとの時間の記録。ワークスペースを開く処理の内訳を、手元の端末でも測れるようにする（性能の調査用）。
 *
 * 段階（[phase]）は入れ子にでき、同じスレッドの外側の段階の中に並ぶ。点（[mark]）は、始めてからの経過だけを残す。
 * 記録はスレッドをまたいでよい（読み込みは IO のスレッド、切り替えと描画は画面のスレッド）。
 *
 * カーネルとアプリの読み込みは [measure] で段階を区切り、呼び出し側がそのスレッドに置いた記録（[within]）へ書く。
 * 置かれていなければ何もしない（ふだんの処理と、ほかのスレッドの処理には影響しない）。
 * 処理を別のスレッドへ分けるときは、分ける側で [carrier] を取り、分けた先で [carry] する（記録と、分けた時点の深さを渡す）。
 */
class Timings(val title: String, private val clock: () -> Long = System::nanoTime) {

    /** 記録 1 件。nanos が null なら点（経過だけ）。startNanos は記録を始めてからの経過。 */
    class Entry(val name: String, val depth: Int, val startNanos: Long, val nanos: Long?, val thread: String)

    private val origin = clock()
    private val recorded = mutableListOf<Entry>()
    private val depth = ThreadLocal.withInitial { 0 }

    /** 合計で測る処理（[each]）の、名前ごとの最初の始まり・深さ・測ったスレッド・合計・回数。 */
    private class Sum(val startNanos: Long, val depth: Int, val threads: LinkedHashSet<String> = LinkedHashSet(), var nanos: Long = 0, var count: Int = 0)
    private val sums = LinkedHashMap<String, Sum>()

    /** 別のスレッドへ記録を渡す印（記録と、渡した時点の深さ）。[carrier] で取り、[carry] で使う。 */
    class Carrier internal constructor(internal val timings: Timings, internal val level: Int)

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

    /**
     * 何度も呼ぶ小さな処理（文書ごとの処理など）を、名前ごとに合計して測る。表には「名前（n 回の合計）」の 1 行で、
     * 最初に呼んだ時点・そのときの深さに出る。1 回ずつ段階にすると行が文書の数だけ並ぶので、こちらを使う。
     */
    fun <T> each(name: String, block: () -> T): T {
        val start = clock()
        val level = depth.get()
        try {
            return block()
        } finally {
            addSum(name, start, level, clock() - start)
        }
    }

    @Synchronized private fun add(entry: Entry) { recorded += entry }

    @Synchronized private fun addSum(name: String, start: Long, level: Int, nanos: Long) {
        val sum = sums.getOrPut(name) { Sum(start - origin, level) }
        sum.threads += Thread.currentThread().name
        sum.nanos += nanos
        sum.count++
    }

    /** このスレッドの深さを level にして block を行う（別のスレッドで、分けた時点の深さに記録するため）。 */
    private fun <T> atDepth(level: Int, block: () -> T): T {
        val previous = depth.get()
        depth.set(level)
        try {
            return block()
        } finally {
            depth.set(previous)
        }
    }

    /**
     * 始まりの順（同じ時刻なら外側が先）。段階は終わったときに足すので、記録の順のままでは内側が先に並ぶ。
     * 合計は、複数のスレッドで測ったら「n 回の合計・m 本のスレッド」と添える（各スレッドの時間の合計なので、経過より長くなる）。
     */
    @Synchronized fun entries(): List<Entry> =
        (recorded + sums.map { (name, s) ->
            val label = if (s.threads.size <= 1) "$name（${s.count} 回の合計）" else "$name（${s.count} 回の合計・${s.threads.size} 本のスレッド）"
            val thread = if (s.threads.size <= 1) s.threads.firstOrNull().orEmpty() else "${s.threads.first()} ほか"
            Entry(label, s.depth, s.startNanos, s.nanos, thread)
        }).sortedWith(compareBy<Entry>({ it.startNanos }, { it.depth }))

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

        /** このスレッドに置かれた記録があれば、名前ごとの合計として測る（[each]）。無ければ block をそのまま実行する。 */
        fun <T> measureEach(name: String, block: () -> T): T = ambient.get()?.each(name, block) ?: block()

        /** このスレッドに置かれた記録（無ければ null）。 */
        fun current(): Timings? = ambient.get()

        /** 処理を別のスレッドへ分ける前に取る印（このスレッドに記録が無ければ null）。 */
        fun carrier(): Carrier? = ambient.get()?.let { Carrier(it, it.depth.get()) }

        /** 分けた先のスレッドで、印の記録と深さを置いて block を行う（印が null なら、そのまま行う）。 */
        fun <T> carry(carrier: Carrier?, block: () -> T): T =
            if (carrier == null) block() else carrier.timings.atDepth(carrier.level) { within(carrier.timings, block) }
    }
}
