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
}
