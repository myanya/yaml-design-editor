package dev.toros.yde.app.core

import java.nio.file.Files
import kotlin.test.*

class ReferenceToolsTest {
    @Test fun `本文検索は範囲と容量と結果上限を守る`() {
        val root = Files.createTempDirectory("reference-search")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(root.resolve("src/a.kt"), "class Alpha\nfun target() {}\n// target again\n")
            Files.writeString(root.resolve("b.kt"), "target outside")
            val options = FileCatalog.Options(true)
            val result = ReferenceSearch.search(root, "target", options, root.resolve("src"))
            assertEquals(listOf(2, 3), result.hits.map { it.line })
            assertFalse(result.incomplete)
            assertTrue(ReferenceSearch.search(root, "target", options, byteBudget = 1).incomplete)
            assertTrue(ReferenceSearch.search(root, "target", options, maxHits = 1).incomplete)
            assertTrue(ReferenceSearch.search(root, "target", options, cancelled = { true }).incomplete)
            var nanos = 0L
            val timed = ReferenceSearch.search(root, "target", options, timeBudgetMillis = 1,
                nanoTime = { nanos.also { nanos += 1_000_000 } })
            assertTrue(timed.incomplete)
            assertEquals(0, timed.visited)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `Kotlinのコメントや文字列にある偽の宣言を抽出しない`() {
        val result = SourceOutline.extract("a.kt", """
            // class Fake {}
            class Real {
                val text = "fun fake() {}"
                /* fun hidden() {} */
                fun run() {}
            }
            fun outside() {}
        """.trimIndent())
        assertEquals(listOf("Real", "run", "outside"), result.symbols.map { it.name })
        assertEquals("Real", result.symbols[1].owner)
        assertNull(result.symbols[2].owner)
        assertEquals(5, result.symbols[1].line)
    }

    @Test fun `JavaとPythonとTypeScriptの宣言位置を抽出する`() {
        val java = SourceOutline.extract("A.java", "class A {\n public void run() {\n other();\n }\n}")
        assertEquals(listOf("A", "run"), java.symbols.map { it.name })
        val python = SourceOutline.extract("a.py", "class A:\n    def run(self):\n        pass\ndef outside():\n    pass\n")
        assertEquals(listOf("A", "run", "outside"), python.symbols.map { it.name })
        assertNull(python.symbols.last().owner)
        val ts = SourceOutline.extract("a.ts", "export function run() {}\nconst next = () => 1\n")
        assertEquals(listOf("run", "next"), ts.symbols.map { it.name })
        assertTrue(SourceOutline.extract("a.bin", "class Unknown {}").symbols.isEmpty())
    }
}
