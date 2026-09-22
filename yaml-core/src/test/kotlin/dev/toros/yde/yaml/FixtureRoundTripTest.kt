package dev.toros.yde.yaml

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * test-fixtures/yaml のround-trip検証（Phase 2）。期待結果は test-fixtures/README.md。
 * fixtureは読み取り専用（docs/CONVENTIONS.md §8）。ファイルI/Oはfixtureの読込だけに使う。
 */
class FixtureRoundTripTest {

    private fun fixture(name: String): String {
        val root = System.getProperty("fixtures.root")
            ?: error("システムプロパティ fixtures.root が未設定（yaml-core/build.gradle.kts が渡す）")
        return Files.readString(Path.of(root, "yaml", name))
    }

    @Test
    fun `comments fixture はコメント・引用符・型を保ってround-tripする`() {
        val text = fixture("comments.yaml")
        val docs = YamlIO.parseAll(text)
        val out = YamlIO.dumpAll(docs)
        // plain treeの一致（構造とスカラー値の同一性）
        assertEquals(YamlIO.dumpPlain(docs.first()), YamlIO.dumpPlain(YamlIO.parseAll(out).first()))
        // コメントの保持
        assertTrue("ファイル先頭のコメント" in out)
        assertTrue("行内コメント" in out)
        assertTrue("ファイル末尾のコメント" in out)
        // 日付らしい文字列は素の文字列のまま（引用符もDate化もされない。PlainDateResolver）
        assertTrue("date_like: 2026-08-16" in out)
        // 引用符付きスカラーのstyleが保持され、再parseで型が変わらない
        assertTrue("\"true\"" in out)
        assertTrue("\"2026-08-16\"" in out)
        assertTrue("\"007\"" in out)
        assertTrue("'シングルクォート'" in out)
        val reparsed = YamlIO.parseAll(out).first() as YNode.Map
        assertEquals("true", (reparsed.entries["truthy_string"] as YNode.Scalar).value)
        assertEquals("2026-08-16", (reparsed.entries["date_like"] as YNode.Scalar).value)
        assertEquals("007", (reparsed.entries["numeric_string"] as YNode.Scalar).value)
    }

    @Test
    fun `nested fixture はフロー表記を保ってround-tripする`() {
        val text = fixture("nested.yaml")
        val docs = YamlIO.parseAll(text)
        val out = YamlIO.dumpAll(docs)
        assertEquals(YamlIO.dumpPlain(docs.first()), YamlIO.dumpPlain(YamlIO.parseAll(out).first()))
        assertTrue("[fast, safe]" in out)
    }

    @Test
    fun `multidoc fixture は後続ドキュメントを失わない`() {
        val text = fixture("multidoc.yaml")
        val docs = YamlIO.parseAll(text)
        assertEquals(3, docs.size)
        val out = YamlIO.dumpAll(docs)
        val reparsed = YamlIO.parseAll(out)
        assertEquals(3, reparsed.size)
        docs.zip(reparsed).forEach { (a, b) ->
            assertEquals(YamlIO.dumpPlain(a), YamlIO.dumpPlain(b))
        }
    }
}
