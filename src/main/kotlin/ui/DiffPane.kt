package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.OpenDocument
import java.nio.file.Files

private data class DiffLine(val number: Int, val disk: String?, val edited: String?, val changed: Boolean)

@Composable
fun DiffPane(doc: OpenDocument) {
    doc.version
    val disk = remember(doc, doc.version, doc.diskModifiedMillis) { runCatching { Files.readString(doc.file) }.getOrDefault("") }
    val edited = doc.canonicalText()
    val lines = remember(disk, edited) {
        val left = disk.lines(); val right = edited.lines(); val size = maxOf(left.size, right.size)
        (0 until size).map { i -> DiffLine(i + 1, left.getOrNull(i), right.getOrNull(i), left.getOrNull(i) != right.getOrNull(i)) }
    }
    Column(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(8.dp)) {
            Text(if (lines.any { it.changed }) "ディスク上の内容との差分" else "差分はありません",
                fontSize = 12.sp, color = if (lines.any { it.changed }) Palette.WarnOrange else Palette.OkGreen)
        }
        Row(Modifier.fillMaxWidth().background(Palette.PanelBg).padding(vertical = 5.dp)) {
            Text("保存済み", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 42.dp))
            Text("編集中", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 42.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                itemsIndexed(lines) { _, line ->
                    Row(Modifier.fillMaxWidth().background(if (line.changed) Palette.Highlight else androidx.compose.ui.graphics.Color.Transparent)) {
                        DiffCell(line.number, line.disk, Modifier.weight(1f))
                        DiffCell(line.number, line.edited, Modifier.weight(1f))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun DiffCell(number: Int, text: String?, modifier: Modifier) {
    Row(modifier.padding(vertical = 1.dp)) {
        Text(number.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.SubText,
            modifier = Modifier.width(36.dp).padding(end = 6.dp))
        Text(text ?: "", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Text,
            modifier = Modifier.weight(1f))
    }
}
