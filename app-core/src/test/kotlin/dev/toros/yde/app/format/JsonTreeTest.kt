package dev.toros.yde.app.format

import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JSON の木の読み書き（skill の木のままの取り込みと、木を組み立てる書き出し）: 数と文字は JavaScript の JSON.stringify と同じ書き方、
 * 3 つの書式、読んで書き戻すと同じ文字になること。
 */
class JsonTreeTest {

    @Test
    fun `数は JavaScript の Number の文字と同じ`() {
        val cases = listOf(
            0.0 to "0", -0.0 to "0", 1.0 to "1", 0.95 to "0.95", -1.5 to "-1.5", 0.1 to "0.1",
            411.55555555555554 to "411.55555555555554",
            1e20 to "100000000000000000000", 123456789012345680000.0 to "123456789012345680000",
            1e21 to "1e+21", 1.5e22 to "1.5e+22",
            0.000001 to "0.000001", 1e-7 to "1e-7", 1.25e-8 to "1.25e-8",
        )
        cases.forEach { (d, expected) -> assertEquals(expected, JsonTree.number(d), "$d") }
        assertFailsWith<IllegalArgumentException> { JsonTree.number(Double.NaN) }
    }

    @Test
    fun `文字は引用符と逆スラッシュと制御文字だけを書き換え 日本語はそのまま`() {
        assertEquals("\"炎の\\\"剣\\\"\\\\\"", JsonTree.quote("炎の\"剣\"\\"))
        assertEquals("\"a\\nb\\tc\\r\\u0001\"", JsonTree.quote("a\nb\tc\r\u0001"))
        assertEquals("\"\uD83D\uDE00\"", JsonTree.quote("\uD83D\uDE00"))  // 対になったサロゲートはそのまま
        assertEquals("\"\\ud83d\"", JsonTree.quote("\uD83D"))              // 対になっていないものは \u で
    }

    @Test
    fun `読むと数の型と null とキーの順を保つ`() {
        val node = JsonTree.parse("\uFEFF{\"b\":1,\"a\":[null,2.5,true,\"x\"],\"big\":12345678901}") as YNode.Map
        assertEquals(listOf("b", "a", "big"), node.entries.keys.toList())
        assertEquals(1, (node.entries["b"] as YNode.Scalar).value)
        assertEquals(12345678901L, (node.entries["big"] as YNode.Scalar).value)
        val items = (node.entries["a"] as YNode.Seq).items.map { (it as YNode.Scalar).value }
        assertEquals(listOf(null, 2.5, true, "x"), items)
    }

    @Test
    fun `書式は 1 行と字下げと要素ごとの 1 行`() {
        val node = JsonTree.parse("[null,{\"id\":1,\"traits\":[],\"p\":{}},{\"id\":2,\"name\":\"b\"}]")
        assertEquals("[null,{\"id\":1,\"traits\":[],\"p\":{}},{\"id\":2,\"name\":\"b\"}]", JsonTree.write(node, TreeStyle.COMPACT, finalNewline = false))
        assertEquals(
            "[\nnull,\n{\"id\":1,\"traits\":[],\"p\":{}},\n{\"id\":2,\"name\":\"b\"}\n]",
            JsonTree.write(node, TreeStyle.LINES, finalNewline = false),
        )
        // JSON.stringify(v, null, 2) と同じ（空の配列と object は 1 行）
        assertEquals(
            "[\n  null,\n  {\n    \"id\": 1,\n    \"traits\": [],\n    \"p\": {}\n  },\n  {\n    \"id\": 2,\n    \"name\": \"b\"\n  }\n]\n",
            JsonTree.write(node, TreeStyle.PRETTY, finalNewline = true),
        )
        assertEquals("{\n\"a\":[1,2],\n\"b\":\"x\"\n}", JsonTree.write(JsonTree.parse("{\"a\":[1,2],\"b\":\"x\"}"), TreeStyle.LINES, false))
    }

    @Test
    fun `読んで同じ書式で書き戻すと同じ文字になる`() {
        val text = "[\nnull,\n{\"id\":1,\"name\":\"改行\\nあり\",\"value\":0.95,\"rate\":1,\"neg\":-0.5,\"note\":\"\"},\n" +
            "{\"id\":2,\"params\":[0,0,10,0],\"flags\":[true,false],\"none\":null}\n]"
        assertEquals(text, JsonTree.write(JsonTree.parse(text), TreeStyle.LINES, finalNewline = false))
        val compact = "{\"advanced\":{\"gameId\":18362392,\"scale\":1},\"terms\":{\"commands\":[\"戦う\",null,\"\"]},\"x\":411.55555555555554}"
        assertEquals(compact, JsonTree.write(JsonTree.parse(compact), TreeStyle.COMPACT, finalNewline = false))
    }

    @Test
    fun `JsonFormat は path の場所の部分木を読む`() {
        val bytes = "{\"data\":{\"items\":[1,2]}}".toByteArray()
        val node = JsonFormat.readTree(bytes, ReadOptions(jsonPath = "data.items"))
        assertEquals(listOf(1, 2), (node as YNode.Seq).items.map { (it as YNode.Scalar).value })
        val e = assertFailsWith<IllegalStateException> { JsonFormat.readTree(bytes, ReadOptions(jsonPath = "data.none")) }
        assertTrue("data.none" in e.message.orEmpty())
    }
}
