package dev.toros.yde.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.FilePath
import dev.toros.yde.semantic.schema.FilePathKind
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import java.nio.file.Files
import java.nio.file.Path

// ================================================================ file 型の入力欄

/**
 * file 型の値を実際のファイルの場所へ直す（純ロジック）。書き方の判定は FilePath（接頭辞なし＝ワークスペースのルートから・
 * `./` か `../`＝この文書の場所から・絶対）。読めない形は null。
 */
internal fun resolveFilePath(text: String, documentFile: Path, workspace: Path): Path? {
    val t = FilePath.normalize(text)
    if (!FilePath.isWellFormed(t)) return null
    return runCatching {
        when (FilePath.kindOf(t)) {
            FilePathKind.ABSOLUTE -> Path.of(t.removePrefix("file:///").removePrefix("file:")).normalize()
            FilePathKind.DOCUMENT -> (documentFile.parent ?: workspace).resolve(t).normalize()
            FilePathKind.WORKSPACE -> workspace.resolve(t).normalize()
        }
    }.getOrNull()
}

/** 選択ダイアログで選んだファイルを値の書き方にする: ワークスペースの中ならルートからの相対、外なら絶対（区切りは `/`）。 */
internal fun fileValueFor(chosen: Path, workspace: Path): String {
    val c = chosen.toAbsolutePath().normalize()
    val w = workspace.toAbsolutePath().normalize()
    return (if (c.startsWith(w)) w.relativize(c).toString() else c.toString()).replace('\\', '/')
}

/**
 * file 型の入力欄: 文字で書く（3 通りの書き方）か、「選ぶ」で OS 標準のダイアログから入れる。解決した場所と有無を 1 行で示し、
 * 画像なら下に表示する（`preview`。表のセルでは出さない）。書込は通常の Slot 経路（FormCtx.setScalar）。
 */
@Composable
internal fun FileEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath, preview: Boolean = true) {
    val text = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    val workspace = ctx.state.workspace
    val resolved = remember(text, ctx.doc.file, workspace) {
        if (text.isBlank() || workspace == null) null else resolveFilePath(text, ctx.doc.file, workspace)
    }
    val exists = remember(resolved, ctx.state.indexVersion) { resolved != null && Files.isRegularFile(resolved) }
    val kind = if (text.isBlank()) null else FilePath.kindOf(text)
    Column(Modifier.fillMaxWidth()) {
        if (ctx.readOnly) {
            Text(text.ifBlank { "—" }, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        } else {
            val choose = rememberFileChooser(
                FilePickers.Purpose.FILE_FIELD, "ファイルを選択",
                extensions = field.extensions.takeIf { it.isNotEmpty() }?.toSet(),
                fallback = ctx.doc.file.parent,
                onError = { ctx.state.status = it },
            ) { chosen -> workspace?.let { ctx.setScalar(slot, fileValueFor(chosen, it), FieldType.FILE) } }
            CompactTextField(
                clearFocusOnEscape = true,
                value = text,
                onValueChange = { ctx.setScalar(slot, it, FieldType.FILE) },
                singleLine = true,
                isError = text.isNotBlank() && resolved == null,
                placeholder = {
                    val ext = field.extensions.takeIf { it.isNotEmpty() }?.joinToString(" / ")?.let { "（$it）" }.orEmpty()
                    Text(field.placeholder ?: "ルートからのパス（./ でこの文書から）$ext")
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = choose, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.FolderOpen, "ファイルを選ぶ", Modifier.size(16.dp), tint = Palette.SubText)
                        }
                        if (exists) IconButton(onClick = { resolved?.let(::openFileWithOs) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "OS で開く", Modifier.size(16.dp), tint = Palette.SubText)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (kind != null) {
            val shown = resolved?.let { r -> workspace?.let { w -> if (r.startsWith(w)) w.relativize(r).toString().replace('\\', '/') else null } ?: r.toString() }
            val (note, warn) = when {
                resolved == null -> "場所が読めません" to true
                !exists -> "${kind.label}: $shown（ファイルがありません）" to true
                else -> "${kind.label}: $shown" to false
            }
            Text(note, fontSize = 10.sp, color = if (warn) Palette.WarnOrange else Palette.SubText, modifier = Modifier.padding(top = 2.dp))
        }
        if (preview && exists && resolved != null && FilePath.isImage(text)) {
            EmbeddedImagePane(ctx.state, resolved)
        }
    }
}

/** OS の既定のアプリでファイルを開く（失敗しても黙る）。 */
internal fun openFileWithOs(file: Path) {
    runCatching { java.awt.Desktop.getDesktop().open(file.toFile()) }
}
