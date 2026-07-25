package index

import core.Bench
import core.ChangeSets
import core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R8-02: 索引の増分更新がフル再構築と同じ結果になることを検証する。 */
class IdIndexIncrementalTest {

    private val dir: Path = createTempDirectory("yde-idx")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** フル再構築した索引と内容が一致するか（entries・refs の両方）。 */
    private fun assertMatchesFullRebuild(ws: Workspace) {
        val fresh = IdIndex()
        fresh.rebuild(ws.root, core.WorkspaceScan.yamlFiles(ws.root), ws.schemas)
        assertEquals(fresh.entries, ws.index.entries)
        assertEquals(fresh.refs, ws.index.refs)
    }

    @Test
    fun `update after file change matches full rebuild`() {
        Bench.generate(dir, documents = 4, itemsPerDocument = 3)
        val ws = Workspace.open(dir)
        val target = dir.resolve("docs/doc-0002.yaml")
        Files.writeString(target, Files.readString(target).replace("ITEM-0002-01", "ITEM-0002-01-RENAMED"))
        ws.index.update(ws.root, listOf(target), emptyList(), ws.schemas)
        assertTrue("ITEM-0002-01-RENAMED" in ws.index.entries)
        assertTrue("ITEM-0002-01" !in ws.index.entries)
        assertMatchesFullRebuild(ws)
    }

    @Test
    fun `update after file addition and removal matches full rebuild`() {
        Bench.generate(dir, documents = 3, itemsPerDocument = 2)
        val ws = Workspace.open(dir)
        // 追加
        val added = dir.resolve("docs/doc-added.yaml")
        Files.writeString(added, "id: DOC-ADDED\ntitle: 追加文書\nitems:\n  - id: ITEM-ADDED-01\n    title: 追加要素\n")
        ws.index.update(ws.root, listOf(added), emptyList(), ws.schemas)
        assertTrue("ITEM-ADDED-01" in ws.index.entries)
        assertMatchesFullRebuild(ws)
        // 削除
        Files.delete(added)
        ws.index.update(ws.root, emptyList(), listOf(added), ws.schemas)
        assertTrue("ITEM-ADDED-01" !in ws.index.entries)
        assertMatchesFullRebuild(ws)
    }

    @Test
    fun `workspace apply keeps index consistent via incremental refresh`() {
        Bench.generate(dir, documents = 3, itemsPerDocument = 2)
        val ws = Workspace.open(dir)
        val rel = "docs/doc-0001.yaml"
        val base = ChangeSets.contentHash(Files.readString(dir.resolve(rel)))
        val changeSet = ChangeSets.parse(
            """
            changes:
              - file: $rel
                base: $base
                ops:
                  - { op: set, path: "items[0].title", value: 変更後の名称 }
            """.trimIndent()
        )
        val result = ws.apply(changeSet)
        assertTrue(result.ok)
        assertMatchesFullRebuild(ws)
    }

    @Test
    fun `reserved directories are excluded from incremental update`() {
        Bench.generate(dir, documents = 2, itemsPerDocument = 2)
        val ws = Workspace.open(dir)
        val runs = dir.resolve("_runs")
        Files.createDirectories(runs)
        val runFile = runs.resolve("run-x.yaml")
        Files.writeString(runFile, "id: RUN-X\n")
        ws.index.update(ws.root, listOf(runFile), emptyList(), ws.schemas)
        assertTrue("RUN-X" !in ws.index.entries)
        assertMatchesFullRebuild(ws)
    }

    @Test
    fun `bench measure reports timings on a generated workspace`() {
        Bench.generate(dir, documents = 30, itemsPerDocument = 4)
        val result = Bench.measure(dir, iterations = 1)
        assertTrue(result.ids > 0)
        assertTrue(result.refs > 0)
        assertTrue(result.fullRebuildMillis >= 0.0)
        assertTrue(result.incrementalUpdateMillis >= 0.0)
    }
}
