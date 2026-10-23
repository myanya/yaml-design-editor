package dev.toros.yde.app.ui

import java.nio.file.Path
import kotlin.test.*

class FileTreeSelectionTest {
    @Test fun `範囲選択と追加選択と親子の重複を扱う`() {
        val paths = listOf("docs", "docs/a.yaml", "docs/b.yaml", "src").map(Path::of)
        assertEquals(paths.take(3).toSet(), FileTreeSelection.select(emptySet(), paths[2], paths[0], paths, false, true))
        assertEquals(setOf(paths[0], paths[3]), FileTreeSelection.select(setOf(paths[0]), paths[3], paths[0], paths, true, false))
        assertEquals(listOf(paths[0], paths[3]), FileTreeSelection.roots(paths))
        assertEquals(emptySet(), FileTreeSelection.select(setOf(paths[0]), paths[0], null, paths, true, false))
    }

    @Test fun `移動候補は直下と別フォルダーだけを許し複数選択でも同じ条件を使う`() {
        val root = Path.of("workspace")
        val source = root.resolve("docs/a.yaml")
        val folder = root.resolve("docs")
        val target = root.resolve("other")
        assertEquals(target, FileTreeSelection.dropDestination(root, listOf(source), target))
        assertEquals(root, FileTreeSelection.dropDestination(root, listOf(source), root))
        assertNull(FileTreeSelection.dropDestination(root, listOf(source), source.parent))
        assertNull(FileTreeSelection.dropDestination(root, listOf(folder), folder))
        assertNull(FileTreeSelection.dropDestination(root, listOf(folder), folder.resolve("child")))
        assertNull(FileTreeSelection.dropDestination(root, listOf(source), null))
        assertNull(FileTreeSelection.dropDestination(root, listOf(source), Path.of("outside")))
        assertNull(FileTreeSelection.dropDestination(root, listOf(source, target.resolve("b.yaml")), target))
        assertNull(FileTreeSelection.dropDestination(root, emptyList(), target))
    }
}
