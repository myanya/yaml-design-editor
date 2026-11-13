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
