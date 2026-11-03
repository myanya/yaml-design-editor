package dev.toros.yde.desktop.ui.common

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import dev.toros.yde.semantic.schema.FilePath
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path

/** 読み込んだ画像。ラスタ（PNG など）はビットマップ、SVG は描画器、読めなければ理由。 */
internal sealed class LoadedImage {
    class Raster(val bitmap: ImageBitmap) : LoadedImage()
    class Vector(val painter: Painter) : LoadedImage()
    class Failed(val reason: String) : LoadedImage()

    val widthPx: Int get() = when (this) {
        is Raster -> bitmap.width
        is Vector -> painter.intrinsicSize.width.toInt()
        is Failed -> 0
    }
    val heightPx: Int get() = when (this) {
        is Raster -> bitmap.height
        is Vector -> painter.intrinsicSize.height.toInt()
        is Failed -> 0
    }
}

internal fun loadImage(file: Path, density: Density): LoadedImage = runCatching {
    if (FilePath.extensionOf(file.fileName.toString()) == "svg") {
        Files.newInputStream(file).use { LoadedImage.Vector(loadSvgPainter(it, density)) }
    } else {
        Files.newInputStream(file).use { LoadedImage.Raster(loadImageBitmap(it)) }
    }
}.getOrElse { LoadedImage.Failed(it.message ?: it::class.simpleName.orEmpty()) }

/** ファイルの更新時刻を 1 秒ごとに見て、変わったら読み直す（外部で画像を差し替えたときに追随する）。 */
@Composable
internal fun rememberLoadedImage(file: Path): LoadedImage {
    var stamp by remember(file) { mutableStateOf(runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)) }
    LaunchedEffect(file) {
        while (true) {
            delay(1000)
            val now = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
            if (now != stamp) stamp = now
        }
    }
    val density = LocalDensity.current
    return remember(file, stamp, density) { loadImage(file, density) }
}

