package dev.toros.yde.desktop.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.OpenDocument
import java.nio.file.Files

internal data class DiffLine(
    val diskNumber: Int?,
    val disk: String?,
    val editedNumber: Int?,
    val edited: String?,
    val changed: Boolean,
) {
    val blankOnlyChange: Boolean
        get() = changed &&
            (disk == null || disk.isBlank()) &&
            (edited == null || edited.isBlank())
}

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
                items(lines) { line -> DiffLineRow(line) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * 変更行ペアの文字単位の差分範囲（R8-04）。
 * 共通の接頭辞・接尾辞を除いた中央部を「変わった箇所」として返す（左が disk 側、右が edited 側）。
 */
internal fun charDiffRanges(disk: String, edited: String): Pair<IntRange, IntRange> {
    var prefix = 0
    while (prefix < disk.length && prefix < edited.length && disk[prefix] == edited[prefix]) prefix++
    var suffix = 0
    while (suffix < disk.length - prefix && suffix < edited.length - prefix &&
        disk[disk.length - 1 - suffix] == edited[edited.length - 1 - suffix]
    ) suffix++
    return (prefix until disk.length - suffix) to (prefix until edited.length - suffix)
}

/** 左右整列済みの差分1行。DiffPane と AI プレビューで共用する。 */
@Composable
internal fun DiffLineRow(line: DiffLine) {
    val background = when {
        line.blankOnlyChange -> Palette.PanelBg.copy(alpha = .55f)
        line.changed -> Palette.Highlight
        else -> Color.Transparent
    }
    // 両側にテキストがある変更行だけ、文字単位で変わった箇所を強調する（R8-04）
    val emphasis = if (line.changed && !line.blankOnlyChange && line.disk != null && line.edited != null) {
        charDiffRanges(line.disk, line.edited)
    } else null
    Row(Modifier.fillMaxWidth().background(background)) {
        DiffCell(line.diskNumber, line.disk, line.blankOnlyChange, emphasis?.first, Modifier.weight(1f))
        DiffCell(line.editedNumber, line.edited, line.blankOnlyChange, emphasis?.second, Modifier.weight(1f))
    }
}

@Composable
private fun DiffCell(number: Int?, text: String?, blankOnlyChange: Boolean, emphasis: IntRange?, modifier: Modifier) {
    Row(modifier.padding(vertical = 1.dp)) {
        Text(number?.toString().orEmpty(), fontFamily = AppFonts.Mono, fontSize = 11.sp, color = Palette.SubText,
            modifier = Modifier.width(36.dp).padding(end = 6.dp))
        val displayedText = if (blankOnlyChange && text != null && text.isBlank()) "· 空行" else text.orEmpty()
        val annotated = if (emphasis != null && !emphasis.isEmpty() && displayedText == text) {
            buildAnnotatedString {
                append(displayedText)
                addStyle(
                    SpanStyle(background = Palette.Primary.copy(alpha = .28f)),
                    emphasis.first.coerceIn(0, displayedText.length),
                    (emphasis.last + 1).coerceIn(0, displayedText.length),
                )
            }
        } else AnnotatedString(displayedText)
        Text(annotated, fontFamily = AppFonts.Mono, fontSize = 11.sp,
            color = if (blankOnlyChange) Palette.SubText.copy(alpha = .75f) else Palette.Text,
            modifier = Modifier.weight(1f))
    }
}
