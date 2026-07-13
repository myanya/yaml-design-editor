package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

internal data class DiffLine(
    val diskNumber: Int?,
    val disk: String?,
    val editedNumber: Int?,
    val edited: String?,
    val changed: Boolean,
)

private sealed interface DiffOperation {
    data class Equal(val diskNumber: Int, val editedNumber: Int, val text: String) : DiffOperation
    data class Delete(val diskNumber: Int, val text: String) : DiffOperation
    data class Insert(val editedNumber: Int, val text: String) : DiffOperation
}

/** Aligns lines by their longest common subsequence instead of their array index. */
internal fun calculateDiff(disk: String, edited: String): List<DiffLine> {
    val left = disk.lines()
    val right = edited.lines()
    val lcs = Array(left.size + 1) { IntArray(right.size + 1) }
    for (i in left.lastIndex downTo 0) {
        for (j in right.lastIndex downTo 0) {
            lcs[i][j] = if (left[i] == right[j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    val operations = mutableListOf<DiffOperation>()
    var i = 0
    var j = 0
    while (i < left.size || j < right.size) {
        when {
            i < left.size && j < right.size && left[i] == right[j] -> {
                operations += DiffOperation.Equal(i + 1, j + 1, left[i]); i++; j++
            }
            j < right.size && (i == left.size || lcs[i][j + 1] > lcs[i + 1][j]) -> {
                operations += DiffOperation.Insert(j + 1, right[j]); j++
            }
            else -> {
                operations += DiffOperation.Delete(i + 1, left[i]); i++
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var operationIndex = 0
    while (operationIndex < operations.size) {
        val operation = operations[operationIndex]
        if (operation is DiffOperation.Equal) {
            result += DiffLine(operation.diskNumber, operation.text, operation.editedNumber, operation.text, false)
            operationIndex++
            continue
        }

        val deletes = mutableListOf<DiffOperation.Delete>()
        val inserts = mutableListOf<DiffOperation.Insert>()
        while (operationIndex < operations.size && operations[operationIndex] !is DiffOperation.Equal) {
            when (val changed = operations[operationIndex++]) {
                is DiffOperation.Delete -> deletes += changed
                is DiffOperation.Insert -> inserts += changed
                is DiffOperation.Equal -> error("unreachable")
            }
        }
        repeat(maxOf(deletes.size, inserts.size)) { index ->
            val deleted = deletes.getOrNull(index)
            val inserted = inserts.getOrNull(index)
            result += DiffLine(deleted?.diskNumber, deleted?.text, inserted?.editedNumber, inserted?.text, true)
        }
    }
    return result
}

@Composable
fun DiffPane(doc: OpenDocument) {
    doc.version
    val disk = remember(doc, doc.version, doc.diskModifiedMillis) { runCatching { Files.readString(doc.file) }.getOrDefault("") }
    val edited = doc.canonicalText()
    val lines = remember(disk, edited) { calculateDiff(disk, edited) }
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
                items(lines) { line ->
                    Row(Modifier.fillMaxWidth().background(if (line.changed) Palette.Highlight else androidx.compose.ui.graphics.Color.Transparent)) {
                        DiffCell(line.diskNumber, line.disk, Modifier.weight(1f))
                        DiffCell(line.editedNumber, line.edited, Modifier.weight(1f))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun DiffCell(number: Int?, text: String?, modifier: Modifier) {
    Row(modifier.padding(vertical = 1.dp)) {
        Text(number?.toString().orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.SubText,
            modifier = Modifier.width(36.dp).padding(end = 6.dp))
        Text(text.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Text,
            modifier = Modifier.weight(1f))
    }
}
