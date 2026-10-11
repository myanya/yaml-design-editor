package dev.toros.yde.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.semantic.schema.FilePath
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path

// ================================================================ 画像の表示（file 型の値。フロービューと同じ操作）

/** 「幅に合わせる」の拡大率（純ロジック）。拡大はしない（1 を上限）。測れていなければ 1。 */
internal fun fitZoom(viewportWidthPx: Int, imageWidthPx: Int): Float =
    if (viewportWidthPx <= 0 || imageWidthPx <= 0) 1f else (viewportWidthPx.toFloat() / imageWidthPx).coerceAtMost(1f)

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

/**
 * 画像の表示面。既定は「幅に合わせる」（拡大はしない）。「100%」「−」「＋」と Ctrl＋ホイールで拡大率を変え、ドラッグで動かす。
 * 拡大率と位置は画面の中の状態で、YAML には書かない。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ImageCanvas(image: LoadedImage, modifier: Modifier = Modifier) {
    var zoom by remember(image) { mutableStateOf<Float?>(null) }   // null = 幅に合わせる
    var pan by remember(image) { mutableStateOf(Offset.Zero) }
    var viewportPx by remember { mutableStateOf(0) }
    val current = zoom ?: fitZoom(viewportPx, image.widthPx)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { zoom = null; pan = Offset.Zero }) { Text("幅に合わせる", fontSize = 11.sp) }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("100%", fontSize = 11.sp) }
            TextButton(onClick = { zoom = (current / 1.25f).coerceAtLeast(0.05f) }) { Text("−") }
            TextButton(onClick = { zoom = (current * 1.25f).coerceAtMost(8f) }) { Text("＋") }
            Text("${(current * 100).toInt()}%", fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (image is LoadedImage.Failed) "読み込めません: ${image.reason}" else "${image.widthPx}×${image.heightPx} px　Ctrl＋ホイールで拡大縮小・ドラッグで移動",
                fontSize = 10.sp, color = if (image is LoadedImage.Failed) Palette.ErrorRed else Palette.SubText,
            )
        }
        val density = LocalDensity.current
        val wDp = with(density) { image.widthPx.toDp() }
        val hDp = with(density) { image.heightPx.toDp() }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Palette.PanelBg)
                .onSizeChanged { viewportPx = it.width }
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); pan += drag } }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    if (event.keyboardModifiers.isCtrlPressed) {
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) {
                            zoom = (if (dy < 0) current * 1.1f else current / 1.1f).coerceIn(0.05f, 8f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            Box(
                Modifier.graphicsLayer(
                    scaleX = current, scaleY = current, translationX = pan.x, translationY = pan.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            ) {
                when (image) {
                    is LoadedImage.Raster -> Image(image.bitmap, contentDescription = null, Modifier.requiredSize(wDp, hDp), contentScale = ContentScale.FillBounds)
                    is LoadedImage.Vector -> Image(image.painter, contentDescription = null, Modifier.requiredSize(wDp, hDp), contentScale = ContentScale.FillBounds)
                    is LoadedImage.Failed -> {}
                }
            }
        }
    }
}

/** フォームの file 欄の下に埋め込む画像（フロービューの埋め込みと同じ流儀。「拡大表示」で編集スペースの全面へ）。 */
@Composable
internal fun EmbeddedImagePane(state: AppState, file: Path) {
    val image = rememberLoadedImage(file)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(file.fileName.toString(), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.openFile(file) }) { Text("拡大表示") }
        }
        ImageCanvas(image, Modifier.fillMaxWidth().height(360.dp))
    }
}

/** 画像の全面表示（編集スペース。file 欄の「拡大表示」から。「戻る」で戻る）。 */
@Composable
fun ImagePane(state: AppState) {
    val file = state.openImage ?: return
    val image = rememberLoadedImage(file)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.relPath(file), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("画像（表示のみ）", fontSize = 10.sp, color = Palette.SubText)
            }
            OutlinedButton(onClick = { openFileWithOs(file) }) { Text("OS で開く", fontSize = 12.sp) }
        }
        ImageCanvas(image, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
    }
}
