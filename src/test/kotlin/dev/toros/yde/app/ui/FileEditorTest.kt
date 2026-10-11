package dev.toros.yde.app.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileEditorTest {
    private val ws = Path.of("C:/ws").toAbsolutePath().normalize()
    private val doc = ws.resolve("specs/flows/BZFL-0001.yaml")

    @Test
    fun `three ways of writing resolve to real locations`() {
        assertEquals(ws.resolve("docs/images/a.png"), resolveFilePath("docs/images/a.png", doc, ws))
        assertEquals(ws.resolve("specs/flows/images/a.png"), resolveFilePath("./images/a.png", doc, ws))
        assertEquals(ws.resolve("specs/shared/a.png"), resolveFilePath("../shared/a.png", doc, ws))
        assertEquals(Path.of("C:/other/a.png").normalize(), resolveFilePath("C:\\other\\a.png", doc, ws))
        assertNull(resolveFilePath("docs/", doc, ws))
        assertNull(resolveFilePath("", doc, ws))
    }

    @Test
    fun `a chosen file inside the workspace becomes a root relative value`() {
        assertEquals("docs/images/a.png", fileValueFor(ws.resolve("docs\\images\\a.png"), ws))
        assertEquals("C:/other/a.png", fileValueFor(Path.of("C:/other/a.png"), ws))
    }

    @Test
    fun `fit zoom shrinks to the viewport but never enlarges`() {
        assertEquals(0.5f, fitZoom(500, 1000))
        assertEquals(1f, fitZoom(1500, 1000))
        assertEquals(1f, fitZoom(0, 1000))
    }
}
