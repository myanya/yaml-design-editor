package dev.toros.yde.app.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownLinkTest {
    private val ws = Path.of("").toAbsolutePath().root.resolve("ws")
    private val base = ws.resolve("docs/README.md")
    private val existing = setOf(ws.resolve("docs/guide.md"), ws.resolve("specs/a.yaml"))
    private fun resolve(href: String, help: String? = null) =
        resolveMarkdownLink(href, base, ws, help, exists = { it in existing })

    @Test
    fun `relative links resolve inside the workspace and ignore the fragment`() {
        assertEquals(MarkdownLink.WorkspaceFile(ws.resolve("docs/guide.md")), resolve("guide.md#intro"))
        assertEquals(MarkdownLink.WorkspaceFile(ws.resolve("specs/a.yaml")), resolve("../specs/a.yaml"))
    }

    @Test
    fun `links outside the workspace or to missing files are refused`() {
        assertIs<MarkdownLink.Unsupported>(resolve("../../elsewhere.md"))
        assertIs<MarkdownLink.Unsupported>(resolve("missing.md"))
        assertIs<MarkdownLink.Unsupported>(resolve("#only-a-heading"))
    }

    @Test
    fun `schemes are external`() {
        assertEquals(MarkdownLink.External("https://example.com/x"), resolve("https://example.com/x"))
        assertEquals(MarkdownLink.External("mailto:a@b"), resolve("mailto:a@b"))
    }

    @Test
    fun `inside help, relative md links move between topics`() {
        assertEquals(MarkdownLink.HelpTopic("views.md"), resolve("views.md#grid", help = "README.md"))
        assertIs<MarkdownLink.Unsupported>(resolve("../CONVENTIONS.md", help = "README.md"))
        assertEquals(MarkdownLink.External("https://example.com"), resolve("https://example.com", help = "README.md"))
    }
}
