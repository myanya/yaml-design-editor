package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePathTest {
    private val doc = DocumentId("10_specs/40_business_flows/BZFL-0001.yaml")

    @Test
    fun `kind is decided by the first characters`() {
        assertEquals(FilePathKind.WORKSPACE, FilePath.kindOf("docs/images/a.png"))
        assertEquals(FilePathKind.DOCUMENT, FilePath.kindOf("./images/a.png"))
        assertEquals(FilePathKind.DOCUMENT, FilePath.kindOf("../shared/a.png"))
        assertEquals(FilePathKind.ABSOLUTE, FilePath.kindOf("C:/work/a.png"))
        assertEquals(FilePathKind.ABSOLUTE, FilePath.kindOf("C:\\work\\a.png"))
        assertEquals(FilePathKind.ABSOLUTE, FilePath.kindOf("/home/x/a.png"))
        assertEquals(FilePathKind.ABSOLUTE, FilePath.kindOf("file:///C:/a.png"))
    }

    @Test
    fun `document relative paths resolve from the document's folder`() {
        assertEquals("10_specs/40_business_flows/images/a.png", FilePath.toWorkspaceRelative("./images/a.png", doc))
        assertEquals("10_specs/shared/a.png", FilePath.toWorkspaceRelative("../shared/a.png", doc))
        assertEquals("docs/a.png", FilePath.toWorkspaceRelative("docs/./a.png", doc))
        assertEquals("a.png", FilePath.toWorkspaceRelative("./a.png", DocumentId("root.yaml")))
    }

    @Test
    fun `absolute paths and paths escaping the root are not workspace relative`() {
        assertNull(FilePath.toWorkspaceRelative("C:/work/a.png", doc))
        assertNull(FilePath.toWorkspaceRelative("../../../outside.png", doc))
        assertNull(FilePath.toWorkspaceRelative("../x.png", DocumentId("root.yaml")))
    }

    @Test
    fun `well formed, extension and image detection`() {
        assertTrue(FilePath.isWellFormed("docs/a.png"))
        assertFalse(FilePath.isWellFormed(""))
        assertFalse(FilePath.isWellFormed("docs/"))
        assertFalse(FilePath.isWellFormed("docs/.."))
        assertEquals("png", FilePath.extensionOf("docs/A.PNG"))
        assertNull(FilePath.extensionOf("docs/README"))
        assertNull(FilePath.extensionOf(".gitignore"))
        assertTrue(FilePath.isImage("x/y.svg"))
        assertFalse(FilePath.isImage("x/y.pdf"))
    }

    @Test
    fun `allowed roots match by prefix ignoring case`() {
        val roots = listOf("C:/shared/diagrams", "//fileserver/design/")
        assertTrue(FilePath.underAny("c:/Shared/Diagrams/a.png", roots))
        assertTrue(FilePath.underAny("//fileserver/design/x/y.png", roots))
        assertFalse(FilePath.underAny("C:/shared/diagrams2/a.png", roots))
        assertFalse(FilePath.underAny("C:/other/a.png", emptyList()))
    }
}
