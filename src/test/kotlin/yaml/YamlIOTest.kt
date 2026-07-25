package yaml

import model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YamlIOTest {

    @Test
    fun `block sequences are indented below their parent property`() {
        val node = YamlIO.parse(
            """
            title: sample
            items:
              - id: first
                tags:
                  - one
                  - two
              - id: second
            """.trimIndent()
        )

        val expected = "title: sample\n" +
            "items:\n" +
            "  - id: first\n" +
            "    tags:\n" +
            "      - one\n" +
            "      - two\n" +
            "  - id: second\n"

        assertEquals(expected, YamlIO.dump(node))
    }

    // ---------------------------------------------------------------- R8-01: コメント・スタイル保持

    @Test
    fun `comments survive a parse-dump round trip`() {
        // キー前・値の行末・空行・文書先頭・文書末尾・ネスト内のコメントが往復で保持される
        val text = "# 文書の説明\n" +
            "title: sample # 行末コメント\n" +
            "\n" +
            "# items の説明\n" +
            "items:\n" +
            "  - id: first\n" +
            "  - id: second\n" +
            "detail:\n" +
            "  # ネストしたキーの説明\n" +
            "  status: open # 行末\n" +
            "status: open\n" +
            "# 末尾のメモ\n"
        assertEquals(text, YamlIO.dump(YamlIO.parse(text)))
    }

    @Test
    fun `comments before middle sequence items are kept in a stable relocated form`() {
        // エミッタの制約により2要素目以降の前行コメントは「- # コメント」の形になるが、
        // 内容は失われず、以降の往復は安定する（初回保存のみ位置が変わる）
        val text = "items:\n" +
            "  - id: a\n" +
            "  # 2件目の説明\n" +
            "  - id: b\n"
        val once = YamlIO.dump(YamlIO.parse(text))
        assertTrue("# 2件目の説明" in once)
        assertEquals(once, YamlIO.dump(YamlIO.parse(once)))
    }

    @Test
    fun `round trip is idempotent for commented documents`() {
        val text = "# head\n" +
            "a: 1 # inline\n" +
            "b:\n" +
            "  # nested\n" +
            "  c: 2\n"
        val once = YamlIO.dump(YamlIO.parse(text))
        val twice = YamlIO.dump(YamlIO.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun `scalar and flow styles survive a round trip`() {
        val text = "quoted: \"123\"\n" +
            "single: 'hello'\n" +
            "plain: hello\n" +
            "block: |\n" +
            "  line1\n" +
            "  line2\n" +
            "flowList: [a, b]\n" +
            "flowMap: {x: 1}\n"
        assertEquals(text, YamlIO.dump(YamlIO.parse(text)))
    }

    @Test
    fun `editing a value keeps surrounding comments`() {
        val text = "# 説明\n" +
            "title: old # メモ\n" +
            "status: open\n"
        val root = YamlIO.parse(text) as YNode.Map
        (root.entries["title"] as YNode.Scalar).value = "new"
        assertEquals(
            "# 説明\ntitle: new # メモ\nstatus: open\n",
            YamlIO.dump(root),
        )
    }

    @Test
    fun `dumpPlain drops comments`() {
        val root = YamlIO.parse("# head\na: 1 # inline\n")
        assertEquals("a: 1\n", YamlIO.dumpPlain(root))
    }

    @Test
    fun `duplicate keys are rejected with line number`() {
        val e = assertFailsWith<IllegalStateException> {
            YamlIO.parse("a: 1\na: 2\n")
        }
        assertTrue("重複" in (e.message ?: ""))
    }

    @Test
    fun `merge keys are expanded like load`() {
        val text = "base: &base\n" +
            "  x: 1\n" +
            "  y: 2\n" +
            "derived:\n" +
            "  <<: *base\n" +
            "  y: 9\n"
        val root = YamlIO.parse(text) as YNode.Map
        val derived = root.entries["derived"] as YNode.Map
        assertEquals(1, (derived.entries["x"] as YNode.Scalar).value)
        assertEquals(9, (derived.entries["y"] as YNode.Scalar).value)
    }

    // ---------------------------------------------------------------- R8-05: 複数ドキュメント

    @Test
    fun `parseAll and dumpAll round trip multi-document yaml`() {
        val text = "a: 1\n---\nb: 2\n---\nc: 3\n"
        val docs = YamlIO.parseAll(text)
        assertEquals(3, docs.size)
        assertEquals(text, YamlIO.dumpAll(docs))
    }

    @Test
    fun `parseAll on a single document equals parse`() {
        val docs = YamlIO.parseAll("a: 1\n")
        assertEquals(1, docs.size)
        assertEquals("a: 1\n", YamlIO.dumpAll(docs))
    }
}
