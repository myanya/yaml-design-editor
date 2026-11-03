package dev.toros.yde.desktop.ui.file

import dev.toros.yde.desktop.ui.common.LoadedImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ImageCanvasUiTest {
    @Test fun `小さい画像では表示領域が画像の高さまで縮み 大きい画像では上限で止まる`() = runComposeUiTest {
        setContent {
            Column {
                Box(Modifier.testTag("small")) {
                    ImageCanvas(LoadedImage.Raster(ImageBitmap(32, 32)), Modifier.width(800.dp).heightIn(max = 360.dp))
                }
                Box(Modifier.testTag("large")) {
                    ImageCanvas(LoadedImage.Raster(ImageBitmap(1600, 1200)), Modifier.width(800.dp).heightIn(max = 360.dp))
                }
                // 読めない画像は表示領域が 0 なので、高さはツールバーだけ（ボタンの押せる範囲を含む）
                Box(Modifier.testTag("failed")) {
                    ImageCanvas(LoadedImage.Failed("壊れた画像"), Modifier.width(800.dp).heightIn(max = 360.dp))
                }
            }
        }
        val small = onNodeWithTag("small").getBoundsInRoot()
        val large = onNodeWithTag("large").getBoundsInRoot()
        val toolbar = onNodeWithTag("failed").getBoundsInRoot()
        val imageDp = with(density) { 32.toDp() }
        // 小さい画像: ツールバー＋画像の高さ（360 の枠を空けて取らない）
        assertTrue(abs((small.height - toolbar.height - imageDp).value) < 1f, "小さい画像の高さ ${small.height}・ツールバー ${toolbar.height}・画像 $imageDp")
        // 大きい画像: 上限の 360 で止まる
        assertTrue(abs((large.height - 360.dp).value) < 1f, "大きい画像の高さ ${large.height}")
    }
}
