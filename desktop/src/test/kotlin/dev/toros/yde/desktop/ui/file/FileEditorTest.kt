package dev.toros.yde.desktop.ui.file

import androidx.compose.ui.unit.IntSize
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileEditorTest {
    /** OS に依存しない絶対パスの根（Windows は `C:\`・Linux は `/`）。CI は Linux で動くので `C:/` を直書きしない。 */
    private val root = Path.of("").toAbsolutePath().root
    private val ws = root.resolve("ws")
    private val doc = ws.resolve("specs/flows/BZFL-0001.yaml")
    private val outside = root.resolve("other").resolve("a.png")

    @Test
    fun `three ways of writing resolve to real locations`() {
        assertEquals(ws.resolve("docs/images/a.png"), resolveFilePath("docs/images/a.png", doc, ws))
        assertEquals(ws.resolve("specs/flows/images/a.png"), resolveFilePath("./images/a.png", doc, ws))
        assertEquals(ws.resolve("specs/shared/a.png"), resolveFilePath("../shared/a.png", doc, ws))
        // OS の書き方の絶対パス（Windows なら `C:\other\a.png`）
        assertEquals(outside, resolveFilePath(outside.toString(), doc, ws))
        assertNull(resolveFilePath("docs/", doc, ws))
        assertNull(resolveFilePath("", doc, ws))
    }

    @Test
    fun `a chosen file inside the workspace becomes a root relative value`() {
        // OS の区切りで組んだ場所が `/` 区切りのルート相対になる
        assertEquals("docs/images/a.png", fileValueFor(ws.resolve("docs").resolve("images").resolve("a.png"), ws))
        // 外のファイルは絶対パスのまま（区切りは `/`。Windows: `C:/other/a.png`・Linux: `/other/a.png`）
        val rootText = root.toString().replace('\\', '/')
        assertEquals("${rootText}other/a.png", fileValueFor(outside, ws))
    }

    @Test
    fun `fit zoom shrinks to the viewport but never enlarges`() {
        assertEquals(0.5f, fitZoom(500, 1000))
        assertEquals(1f, fitZoom(1500, 1000))
        assertEquals(1f, fitZoom(0, 1000))
    }

    @Test
    fun `the viewport shrinks to a small image and stops at the available area`() {
        // 32×32 のアイコンは、使える広さが 800×360 でも 32×32 の領域になる
        assertEquals(IntSize(32, 32), viewportSize(800, 360, 32, 32, 1f))
        // 大きな画像は使える広さで止まる（高さの上限は 360）
        assertEquals(IntSize(800, 360), viewportSize(800, 360, 1600, 1200, 0.5f))
        // 拡大すると、上限まで領域も広がる
        assertEquals(IntSize(64, 64), viewportSize(800, 360, 32, 32, 2f))
        // 高さの上限が無いとき（Int.MAX_VALUE）は画像の高さ
        assertEquals(IntSize(144, 192), viewportSize(800, Int.MAX_VALUE, 144, 192, 1f))
    }
}
