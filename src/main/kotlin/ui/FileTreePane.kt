package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import java.nio.file.Files
import java.nio.file.Path

private data class TreeRow(val path: Path, val depth: Int, val isDir: Boolean)

/**
 * ワークスペース内の YAML ファイルをツリー表示する左ペイン。
 * ディレクトリは展開／折りたたみ可能（初期状態は展開）。
 */
@Composable
fun FileTreePane(state: AppState, width: androidx.compose.ui.unit.Dp = 280.dp) {
    var selectedPath by remember(state.workspace) { mutableStateOf<Path?>(null) }
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }
    Column(
        Modifier.width(width).fillMaxHeight().background(Palette.PanelBg)
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ファイル", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Palette.SubText,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { dialog = TreeDialog.NewFile }) {
                Icon(Icons.Default.NoteAdd, "新規 YAML", Modifier.size(17.dp))
            }
            IconButton(onClick = { dialog = TreeDialog.NewDirectory }) {
                Icon(Icons.Default.CreateNewFolder, "新規フォルダー", Modifier.size(17.dp))
            }
            IconButton(enabled = selectedPath != null, onClick = { dialog = TreeDialog.Rename }) {
                Icon(Icons.Default.Edit, "名前を変更", Modifier.size(16.dp))
            }
            IconButton(enabled = selectedPath != null, onClick = { dialog = TreeDialog.Delete }) {
                Icon(Icons.Default.Delete, "削除", Modifier.size(16.dp))
            }
        }
        val ws = state.workspace
        if (ws == null) {
            Text(
                "ワークスペース未選択",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
                color = Palette.SubText,
            )
            return@Column
        }
        val expanded = remember(ws) { mutableStateMapOf<Path, Boolean>() }
        val rows = buildRows(ws, state.yamlFiles, state.directories, expanded)

        Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.path.toString() }) { row ->
                    TreeRowItem(state, row, expanded[row.path] != false, selectedPath == row.path) {
                        selectedPath = row.path
                        if (row.isDir) {
                            expanded[row.path] = !(expanded[row.path] != false)
                        } else {
                            state.openFile(row.path)
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        dialog?.let { kind ->
            TreeOperationDialog(
                kind = kind,
                initial = if (kind == TreeDialog.Rename) selectedPath?.fileName?.toString().orEmpty() else "",
                onDismiss = { dialog = null },
                onConfirm = { value ->
                    val parent = selectedPath?.let { if (Files.isDirectory(it)) it else it.parent } ?: ws
                    val result = when (kind) {
                        TreeDialog.NewFile -> state.createYaml(parent, value).map { Unit }
                        TreeDialog.NewDirectory -> state.createDirectory(parent, value).map { Unit }
                        TreeDialog.Rename -> selectedPath?.let { state.renamePath(it, value).map { Unit } }
                            ?: Result.failure(IllegalStateException("対象がありません"))
                        TreeDialog.Delete -> selectedPath?.let { state.deletePath(it) }
                            ?: Result.failure(IllegalStateException("対象がありません"))
                    }
                    result.onFailure { state.status = it.message ?: "操作に失敗しました" }
                    if (result.isSuccess) { dialog = null; if (kind != TreeDialog.NewFile) selectedPath = null }
                },
            )
        }
    }
}

@Composable
private fun TreeRowItem(state: AppState, row: TreeRow, isExpanded: Boolean, treeSelected: Boolean, onClick: () -> Unit) {
    val selected = !row.isDir && state.openDoc?.file == row.path
    val dirty = !row.isDir && state.docFor(row.path)?.dirty == true
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected || treeSelected) Palette.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (8 + row.depth * 16).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isDir) {
            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = Palette.SubText,
            )
            Icon(
                if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = Palette.Folder,
            )
        } else {
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = Palette.Primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            row.path.fileName.toString(),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (dirty) {
            Spacer(Modifier.width(4.dp))
            Text("●", fontSize = 9.sp, color = Palette.WarnOrange)
        }
    }
}

/**
 * yamlFiles のみから表示行を組み立てる（コンポーズ中にディスク I/O を行わない）。
 */
private fun buildRows(
    ws: Path,
    files: List<Path>,
    workspaceDirs: List<Path>,
    expanded: Map<Path, Boolean>,
): List<TreeRow> {
    val filesByParent = files.groupBy { it.parent }
    val dirs = workspaceDirs.toMutableSet()
    for (f in files) {
        var p: Path? = f.parent
        while (p != null && p != ws && p.startsWith(ws)) {
            dirs.add(p)
            p = p.parent
        }
    }
    val dirsByParent = dirs.groupBy { it.parent }
    val out = mutableListOf<TreeRow>()

    fun rec(dir: Path, depth: Int) {
        dirsByParent[dir].orEmpty()
            .sortedBy { it.fileName.toString().lowercase() }
            .forEach { d ->
                out.add(TreeRow(d, depth, isDir = true))
                if (expanded[d] != false) rec(d, depth + 1)
            }
        filesByParent[dir].orEmpty()
            .sortedBy { it.fileName.toString().lowercase() }
            .forEach { f -> out.add(TreeRow(f, depth, isDir = false)) }
    }
    rec(ws, 0)
    return out
}

private enum class TreeDialog { NewFile, NewDirectory, Rename, Delete }

@Composable
private fun TreeOperationDialog(
    kind: TreeDialog,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(kind, initial) { mutableStateOf(initial) }
    val title = when (kind) {
        TreeDialog.NewFile -> "YAML ファイルを作成"
        TreeDialog.NewDirectory -> "フォルダーを作成"
        TreeDialog.Rename -> "名前を変更"
        TreeDialog.Delete -> "削除の確認"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            if (kind == TreeDialog.Delete) Text("選択した項目を削除します。この操作は取り消せません。")
            else OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("名前") })
        },
        confirmButton = {
            TextButton(enabled = kind == TreeDialog.Delete || value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(if (kind == TreeDialog.Delete) "削除" else "実行",
                    color = if (kind == TreeDialog.Delete) Palette.ErrorRed else Palette.Primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
