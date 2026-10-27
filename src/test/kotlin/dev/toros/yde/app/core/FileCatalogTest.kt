package dev.toros.yde.app.core

import dev.toros.yde.app.FileTreePreferences
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.*

class FileCatalogTest {
    private val root = Files.createTempDirectory("file-catalog")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `一般ファイルの表示設定と隠し項目を独立に扱う`() {
        Files.writeString(root.resolve("a.kt"), "class A")
        Files.writeString(root.resolve("a.yaml"), "name: A")
        Files.createDirectory(root.resolve(".git"))
        assertEquals(listOf("a.yaml"), FileCatalog.children(root, root, FileCatalog.Options()).map { it.path.fileName.toString() })
        assertEquals(2, FileCatalog.children(root, root, FileCatalog.Options(allFiles = true)).size)
        assertEquals(3, FileCatalog.children(root, root, FileCatalog.Options(true, true)).size)
    }

    @Test fun `名前検索は未展開の枝と複数語と結果上限を扱う`() {
        val nested = Files.createDirectories(root.resolve("src/main"))
        repeat(20) { Files.writeString(nested.resolve("Class$it.kt"), "class A") }
        val result = FileCatalog.search(root, "main class", FileCatalog.Options(true), limit = 7)
        assertEquals(7, result.entries.size)
        assertTrue(result.truncated)
        assertTrue(FileCatalog.search(root, "class", FileCatalog.Options(true, excludes = "src")).entries.isEmpty())
        assertTrue(FileCatalog.search(root, "class", FileCatalog.Options(true), cancelled = { true }).truncated)
    }

    @Test fun `閲覧は容量とバイナリと文字コードで制限する`() {
        val file = root.resolve("code.kt")
        Files.writeString(file, "class 日本語")
        assertEquals("class 日本語", FileCatalog.preview(file).text)
        assertNull(FileCatalog.preview(file, 3).text)
        Files.write(file, byteArrayOf(0, 1, 2))
        assertNull(FileCatalog.preview(file).text)
        Files.write(file, byteArrayOf(-1, -1))
        assertNull(FileCatalog.preview(file).text)
    }

    @Test fun `展開状態と設定を別インスタンスから復元できる`() {
        val node = Preferences.userRoot().node("yaml-design-editor/test-tree-${System.nanoTime()}")
        try {
            val first = FileTreePreferences(node)
            first.expand("資料/ソース", true)
            first.expand("docs", false)
            first.options(FileCatalog.Options(true, true, "build;**/target"))
            val second = FileTreePreferences(node)
            assertEquals(mapOf("資料/ソース" to true, "docs" to false), second.expanded())
            assertTrue(second.options().allFiles)
            second.clearExpansion()
            assertTrue(first.expanded().isEmpty())
        } finally { node.removeNode() }
    }
}
