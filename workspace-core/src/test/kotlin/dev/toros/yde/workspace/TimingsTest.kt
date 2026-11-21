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
    fun `何度も呼ぶ処理は名前ごとに合計して、最初に呼んだ時点の深さに 1 行で出す`() {
        val t = Timings("開く", steppingClock())
        t.phase("本文") {
            repeat(3) { t.each("読み取り") { } }
            t.each("確認") { }
        }
        val entries = t.entries()
        assertEquals(listOf("本文", "読み取り（3 回の合計）", "確認（1 回の合計）"), entries.map { it.name })
        assertEquals(listOf(0, 1, 1), entries.map { it.depth })
        // 1 回ごとに時計を 2 度読む（始まりと終わり）ので、1 回 1 秒
        assertEquals(3_000_000_000L, entries[1].nanos)
        assertTrue(t.report().contains("読み取り（3 回の合計）"))
    }

    @Test
    fun `ファイルの全読込は、一覧と本文と文書ごとの実パスの確認・読み取りの合計を記録する`() {
        val root = kotlin.io.path.createTempDirectory("yde-timings-capture")
        try {
            java.nio.file.Files.createDirectories(root.resolve("items"))
            java.nio.file.Files.writeString(root.resolve("items/a.yaml"), "id: A-1\n")
            java.nio.file.Files.writeString(root.resolve("items/b.yaml"), "id: B-1\n")
            java.nio.file.Files.writeString(root.resolve("README.md"), "説明\n")
            val t = Timings("開く")
            val read = Timings.within(t) { FileSystemDocumentStore(root).capture() }
            assertEquals(2, read.texts.size)
            val names = t.entries().map { it.name }
            // YAML は読む直前の 1 回だけ確かめ、一覧で確かめるのは読まない Markdown だけ
            listOf(
                "ルートの実パス", "一覧（走査と実パスの確認）", "一覧の実パスの確認（フォルダ）（1 回の合計）", "一覧の実パスの確認（ファイル）（1 回の合計）",
                "本文（読む直前の実パスの確認と読み取り）", "読む直前の実パスの確認（2 回の合計）", "本文の読み取り（2 回の合計）",
            ).forEach { assertTrue(it in names, "$it が無い: $names") }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `別のスレッドへ分けた処理は、分けた時点の深さに合計し、スレッドの本数を添える`() {
        val t = Timings("開く")
        Timings.within(t) {
            t.phase("本文") {
                val carrier = Timings.carrier()
                val workers = (1..2).map { Thread { Timings.carry(carrier) { Timings.measureEach("読み取り") { } } } }
                workers.forEach { it.start() }
                workers.forEach { it.join() }
            }
        }
        val entries = t.entries()
        val sum = entries.single { it.name.startsWith("読み取り") }
        assertEquals("読み取り（2 回の合計・2 本のスレッド）", sum.name)
        assertEquals(entries.single { it.name == "本文" }.depth + 1, sum.depth)
        assertTrue(sum.thread.endsWith(" ほか"), sum.thread)
        // 記録の無いスレッドへの印は null で、そのまま行う
        assertNull(Timings.carrier())
        assertEquals(1, Timings.carry(null) { 1 })
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
        // 保存基準の書き出しと構造の検証は、読まれたときに行うので、読み込みの段には無い（UX-301・UX-302）
        listOf("ファイルの読み取り", "文書の解析（YAML）", "スキーマの組み立て", "スキーマの割当", "意味の導出")
            .forEach { assertTrue(it in names, "$it が無い: $names") }
        assertTrue("構造の検証" !in names, "開くときに全文書の構造を検証しない: $names")
        assertTrue(t.report().contains("意味の導出"))
    }

    @Test
    fun `置いたままの記録は 外すまでそのスレッドの区切りを受け取り 別の記録が置かれていれば外さない`() {
        val first = Timings("開く")
        Timings.attach(first)
        try {
            Timings.measureEach("ビュー定義の読み込み") { }
            Timings.measureEach("ビュー定義の読み込み") { }
            assertTrue(first.entries().any { it.name == "ビュー定義の読み込み（2 回の合計）" }, first.entries().map { it.name }.toString())
            // 次の記録が置かれた後は、前の記録を外そうとしても外れない（次の開く処理の記録を消さない）
            val second = Timings("もう一度開く")
            Timings.attach(second)
            Timings.detach(first)
            assertTrue(Timings.current() === second)
            Timings.detach(second)
            assertTrue(Timings.current() == null)
            // 外した後の区切りは、どの記録にも足されない
            Timings.measureEach("ビュー定義の読み込み") { }
            assertTrue(second.entries().isEmpty())
        } finally {
            Timings.current()?.let(Timings::detach)
        }
    }
}
