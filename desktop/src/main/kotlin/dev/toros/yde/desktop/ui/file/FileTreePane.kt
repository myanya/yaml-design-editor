package dev.toros.yde.desktop.ui.file

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.NoteAdd
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
import java.nio.file.Files
import java.nio.file.Path
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.FileTreePreferences
import dev.toros.yde.desktop.requestDocumentDraft
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PaneHeader
import dev.toros.yde.desktop.ui.common.UiTooltip
import dev.toros.yde.desktop.ui.common.confirmOnEnter
import dev.toros.yde.desktop.ui.common.openExternally

private data class TreeRow(
    val path: Path, val depth: Int, val isDir: Boolean,
    val editorOnly: Boolean = false,   // 台帳外（薄色）
    val markdown: Boolean = false,     // Markdown 文書（表示のみ。薄色・別アイコン）
    val line: Int? = null,
    val excerpt: String? = null,
)

/**
 * ワークスペースのファイル一覧。ディレクトリは必要な枝だけ読み、展開を永続化する。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun FileTreePane(state: AppState, width: androidx.compose.ui.unit.Dp = 280.dp, onClose: () -> Unit = {}) {
    // 選択中の場所は画面の覚え（台帳・実行・設計を経由しても残る）。無くなった場所は読み捨てる
    val selectedPath = state.memory.treeSelection
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }
    var inferTarget by remember(state.workspace) { mutableStateOf<Path?>(null) }
    var settings by remember { mutableStateOf(false) }
    var moveTargets by remember { mutableStateOf<List<Path>>(emptyList()) }
    var renameMove by remember { mutableStateOf(false) }
    var moveDestination by remember { mutableStateOf<Path?>(null) }
    val focus = remember { FocusRequester() }
    val rowBounds = remember(state.workspace) { mutableStateMapOf<Pair<Path, Int?>, Rect>() }
    var paneBounds by remember { mutableStateOf(Rect.Zero) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var dragSources by remember { mutableStateOf<List<Path>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val prefs = remember(state.workspace) { state.workspace?.let(FileTreePreferences::forWorkspace) }
    val (browserRows, listingStatus) = browserRows(state)
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState(state.memory.treeScrollIndex, state.memory.treeScrollOffset)
    var reveal by remember { mutableStateOf<Path?>(null) }
    val selected = state.memory.treeSelectedPaths.ifEmpty { setOfNotNull(selectedPath) }
    fun targets(path: Path? = selectedPath): List<Path> = FileTreeSelection.roots(if (path in selected) selected else listOfNotNull(path))
    fun select(path: Path, toggle: Boolean = false, range: Boolean = false, line: Int? = null) {
        state.memory.treeFocusedLine = line
        state.memory.treeSelectedPaths = FileTreeSelection.select(selected, path, state.memory.treeAnchor,
            browserRows.map { it.entry.path }.distinct(), toggle, range)
        state.memory.treeSelection = path.takeIf { it in state.memory.treeSelectedPaths }
            ?: state.memory.treeSelectedPaths.lastOrNull()
        if (!range) state.memory.treeAnchor = path
    }
    fun expand(path: Path, value: Boolean) {
        state.memory.treeExpanded[path] = value
        prefs?.expand(state.relPath(path), value)
    }
    LaunchedEffect(browserRows, reveal) {
        reveal?.let { path -> browserRows.indexOfFirst { it.entry.path == path }.takeIf { it >= 0 }?.let {
            listState.scrollToItem(it); reveal = null
        } }
    }
    DisposableEffect(state.workspace, listState) {
        onDispose { state.memory.treeScrollIndex = listState.firstVisibleItemIndex; state.memory.treeScrollOffset = listState.firstVisibleItemScrollOffset }
    }
    Column(
        Modifier.width(width).fillMaxHeight().background(Palette.PanelBg)
    ) {
        PaneHeader("ファイル", onClose)
        CompactTextField(state.memory.treeQuery, { state.memory.treeSearchStop = false; state.memory.treeQuery = it }, singleLine = true,
            placeholder = { Text(if (state.memory.treeContentSearch) "本文を検索（全語一致）" else "名前・パスで絞り込み") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
        FlowRow {
            TextButton(onClick = { settings = !settings }) { Text("表示") }
            TextButton(enabled = selectedPath != null, onClick = { renameMove = false; moveDestination = null; moveTargets = targets() }) { Text("移動") }
            TextButton(onClick = {
                state.memory.treeExpanded.clear(); prefs?.clearExpansion(); state.memory.treeQuery = ""
            }) { Text("畳む") }
            TextButton(enabled = state.activeFile != null, onClick = {
                state.memory.treeQuery = ""
                state.activeFile?.let { file ->
                    var parent = file.parent
                    while (parent != null && parent != state.workspace) { expand(parent, true); parent = parent.parent }
                    select(file); reveal = file
                }
            }) { Text("現在地") }
        }
        if (settings) {
            fun options(value: dev.toros.yde.app.core.FileCatalog.Options) {
                state.memory.treeSearchStop = false
                state.memory.treeOptions = value; prefs?.options(value)
            }
            val opt = state.memory.treeOptions
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.memory.treeContentSearch, { state.memory.treeContentSearch = it; state.memory.treeSearchStop = false })
                Text("本文を検索", fontSize = 12.sp)
            }
            if (state.memory.treeContentSearch) {
                Text("範囲: ${state.memory.treeSearchScope?.let(state::relPath) ?: "ワークスペース全体"}", fontSize = 11.sp)
                FlowRow {
                    TextButton(enabled = selectedPath != null, onClick = {
                        state.memory.treeSearchScope = selectedPath?.let { if (Files.isDirectory(it)) it else it.parent }
                        state.memory.treeSearchStop = false
                    }) { Text("選択先の配下") }
                    TextButton(onClick = { state.memory.treeSearchScope = null; state.memory.treeSearchStop = false }) { Text("全体") }
                    TextButton(onClick = {
                        state.memory.treeSearchBudgetMiB = when (state.memory.treeSearchBudgetMiB) { 16 -> 64; 64 -> 256; else -> 16 }
                        state.memory.treeSearchStop = false
                    }) { Text("上限 ${state.memory.treeSearchBudgetMiB} MiB") }
                    TextButton(onClick = {
                        state.memory.treeSearchSeconds = when (state.memory.treeSearchSeconds) { 30 -> 120; 120 -> 0; else -> 30 }
                        state.memory.treeSearchStop = false
                    }) { Text(if (state.memory.treeSearchSeconds == 0) "時間制限なし" else "時間 ${state.memory.treeSearchSeconds} 秒") }
                }
                Text("UTF-8・1ファイル2 MiBまで。上限・中止時は一部の結果です", fontSize = 10.sp, color = Palette.SubText)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(opt.allFiles, { options(opt.copy(allFiles = it)) }); Text("すべての形式", fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(opt.hidden, { options(opt.copy(hidden = it)) }); Text("隠し項目も表示", fontSize = 12.sp)
            }
            CompactTextField(opt.excludes, { options(opt.copy(excludes = it)) }, singleLine = true,
                placeholder = { Text("除外 glob（; 区切り）") }, modifier = Modifier.fillMaxWidth().padding(6.dp))
            TextButton(onClick = { state.refreshFileTree() }) { Text("一覧を更新") }
        }
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(modifier = Modifier.size(30.dp), onClick = { dialog = TreeDialog.NewFile }) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, "新規 YAML", Modifier.size(17.dp))
            }
            IconButton(modifier = Modifier.size(30.dp), onClick = { dialog = TreeDialog.NewDirectory }) {
                Icon(Icons.Default.CreateNewFolder, "新規フォルダー", Modifier.size(17.dp))
            }
            // スキーマ推測（R11-01）: 選択した業務文書を起点に、同種の文書群からスキーマドラフトを作る
            val canInfer = selectedPath?.let { p ->
                p in state.yamlFiles && state.layout.inLedger(state.relPath(p))
            } == true
            IconButton(modifier = Modifier.size(30.dp), enabled = canInfer, onClick = { inferTarget = selectedPath }) {
                Icon(Icons.Default.AutoFixHigh, "スキーマ推測（既存文書からドラフト作成）", Modifier.size(16.dp))
            }
            IconButton(modifier = Modifier.size(30.dp), enabled = selectedPath != null, onClick = { renameMove = true; moveTargets = listOfNotNull(selectedPath) }) {
                Icon(Icons.Default.Edit, "名前を変更", Modifier.size(16.dp))
            }
            IconButton(modifier = Modifier.size(30.dp), enabled = selectedPath != null, onClick = { dialog = TreeDialog.Delete }) {
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
        val expanded = state.memory.treeExpanded  // ワークスペースを開くと永続化した展開を復元する
        val rows = browserRows.map { b -> TreeRow(b.entry.path, b.depth, b.entry.directory,
            editorOnly = !state.layout.inLedger(state.relPath(b.entry.path)),
            markdown = b.entry.path.fileName.toString().endsWith(".md", true), line = b.line, excerpt = b.excerpt) }
        val resolveDrop: (Offset?, List<Path>) -> Path? = { position, sources ->
            val candidate = if (position != null && paneBounds.contains(position)) {
                val hit = rowBounds.entries.firstOrNull { it.value.contains(position) }?.key
                if (hit == null) ws else rows.firstOrNull { it.path == hit.first && it.isDir }?.path
            } else null
            FileTreeSelection.dropDestination(ws, sources, candidate)
        }
        // pointerInput を再起動せず、スクロールや一覧更新後も最新の候補で判定する。
        val latestResolveDrop by rememberUpdatedState(resolveDrop)
        val dropTarget = resolveDrop(dragPosition, dragSources)
        val dragging = dragSources.isNotEmpty()
        val statusText = when {
            dragging && dropTarget != null -> "${dragSources.size} 件 → ${if (dropTarget == ws) "ワークスペース直下" else state.relPath(dropTarget)}"
            dragging -> "${dragSources.size} 件：ここには移動できません"
            selected.size > 1 -> "${selected.size} 件選択・$listingStatus"
            else -> listingStatus
        }
        // 選択件数・ドラッグ案内・検索進捗を同じ高さに収め、操作中の行位置を固定する。
        Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(statusText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (!dragging) Palette.SubText else if (dropTarget != null) Palette.Primary else Palette.WarnOrange)
        }
        if (state.memory.treeQuery.isNotBlank()) Row {
            TextButton(onClick = { state.memory.treeSearchStop = true }) { Text("検索を中止") }
            TextButton(onClick = { state.memory.treeQuery = ""; state.memory.treeSearchStop = false }) { Text("解除") }
        }

        // 行以外の空白クリックで選択を解除できるようにする（未選択＝ルート直下が作成先になる。UX-40）
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (dropTarget == ws) Modifier.border(2.dp, Palette.Primary) else Modifier)
                .onGloballyPositioned { paneBounds = it.boundsInWindow() }.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { state.memory.treeSelection = null; state.memory.treeSelectedPaths = emptySet() },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val index = rows.indexOfFirst { it.path == selectedPath && it.line == state.memory.treeFocusedLine }
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val next = (index + if (event.key == Key.DirectionUp) -1 else 1).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                        rows.getOrNull(next)?.let { select(it.path, range = event.isShiftPressed, line = it.line); scope.launch { listState.animateScrollToItem(next) } }; true
                    }
                    Key.DirectionRight -> { rows.getOrNull(index)?.takeIf { it.isDir }?.let { expand(it.path, true) }; true }
                    Key.DirectionLeft -> { rows.getOrNull(index)?.let { if (it.isDir && expanded[it.path] == true) expand(it.path, false) else if (it.path.parent != ws) select(it.path.parent) }; true }
                    Key.Enter -> { rows.getOrNull(index)?.let { if (it.isDir) expand(it.path, expanded[it.path] != true) else if (it.line != null) state.openReference(it.path, it.line) else state.openFile(it.path) }; true }
                    Key.F2 -> { renameMove = true; moveTargets = listOfNotNull(selectedPath); true }
                    Key.Delete -> { if (selectedPath != null) dialog = TreeDialog.Delete; true }
                    Key.C -> if (event.isCtrlPressed) { clipboard.setText(AnnotatedString(targets().joinToString("\n", transform = state::relPath))); true } else false
                    else -> false
                }
            }.focusable()) {
                items(rows, key = { it.path.toString() + ":" + it.line }) { row ->
                    val rowKey = row.path to row.line
                    var toggle by remember { mutableStateOf(false) }
                    var range by remember { mutableStateOf(false) }
                    DisposableEffect(rowKey) { onDispose { rowBounds.remove(rowKey) } }
                    // 台帳外（editorOnly）のディレクトリは既定で畳む（buildRows と同じ既定）
                    val isOpen = expanded[row.path] == true
                    ContextMenuArea(items = { listOf(
                        ContextMenuItem("開く") { if (row.isDir) expand(row.path, !isOpen) else if (row.line != null) state.openReference(row.path, row.line) else state.openFile(row.path) },
                        ContextMenuItem("名前を変更…") { state.memory.treeSelection = row.path; renameMove = true; moveTargets = listOf(row.path) },
                        ContextMenuItem("移動…") { state.memory.treeSelection = row.path; renameMove = false; moveDestination = null; moveTargets = targets(row.path) },
                        ContextMenuItem("削除…") { state.memory.treeSelection = row.path; dialog = TreeDialog.Delete },
                        ContextMenuItem("相対パスをコピー") { clipboard.setText(AnnotatedString(state.relPath(row.path))) },
                        ContextMenuItem("フォルダーを外部で開く") { openExternally((if (row.isDir) row.path else row.path.parent).toUri().toString()) },
                    ) }) {
                        TreeRowItem(state, row, isOpen, row.path in selected, dropTarget = row.path == dropTarget,
                            modifier = Modifier.onGloballyPositioned { rowBounds[rowKey] = it.boundsInWindow() }
                                .onPointerEvent(PointerEventType.Press) { event -> toggle = event.keyboardModifiers.isCtrlPressed; range = event.keyboardModifiers.isShiftPressed }
                                .pointerInput(rowKey, selected) {
                                    detectDragGestures(onDragStart = { position ->
                                        dragSources = targets(row.path); dragPosition = rowBounds[rowKey]?.topLeft?.plus(position)
                                    }, onDragCancel = { dragSources = emptyList(); dragPosition = null }, onDragEnd = {
                                        latestResolveDrop(dragPosition, dragSources)?.let { target ->
                                            renameMove = false; moveDestination = target; moveTargets = dragSources
                                        }
                                        dragSources = emptyList(); dragPosition = null
                                    }) { change, _ -> change.consume(); dragPosition = rowBounds[rowKey]?.topLeft?.plus(change.position) }
                                },
                            onExpand = { expand(row.path, !isOpen) }) {
                            select(row.path, toggle, range, row.line); focus.requestFocus()
                            if (!row.isDir && !toggle && !range) {
                                if (row.line != null) state.openReference(row.path, row.line) else state.openFile(row.path)
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        inferTarget?.let { target ->
            InferSchemaDialog(state, target, onDismiss = { inferTarget = null })
        }
        if (moveTargets.isNotEmpty()) FileMoveDialog(state, moveTargets, rename = renameMove, initialDestination = moveDestination) { moveTargets = emptyList() }
        dialog?.let { kind ->
            // 作成先は「選択中の場所の配下」。どこに作られるかをダイアログに明示し、直下への切替も出す（UX-22 / UX-40）
            val selectedParent = selectedPath?.let { if (Files.isDirectory(it)) it else it.parent } ?: ws
            val creating = kind == TreeDialog.NewFile || kind == TreeDialog.NewDirectory
            TreeOperationDialog(
                kind = kind,
                initial = if (kind == TreeDialog.Rename) selectedPath?.fileName?.toString().orEmpty() else if (kind == TreeDialog.Delete) targets().joinToString("\n", transform = state::relPath) else "",
                destination = if (creating) {
                    state.relPath(selectedParent).takeIf { it.isNotEmpty() } ?: "（ワークスペース直下）"
                } else null,
                allowRootSwitch = creating && selectedParent != ws,
                // 改名の影響プレビュー（R21-03）: 文書参照の書き換え・スキーマ／ビューの当たりの変化
                impact = if (kind == TreeDialog.Rename) {
                    { name -> selectedPath?.let { state.renameImpact(it, name) }.orEmpty() }
                } else null,
                onDismiss = { dialog = null },
                onConfirm = { value, atRoot, withAi ->
                    run {
                    val parent = if (atRoot) ws else selectedParent
                    val result = when (kind) {
                        TreeDialog.NewFile -> state.createYaml(parent, value).onSuccess { file ->
                            // 作成→たたき台生成の連結（R24-03）。スキーマが当たらない場合はその旨だけ案内する
                            if (withAi && !requestDocumentDraft(state, file)) {
                                state.status = "作成しました（スキーマが適用されないためAIたたき台は使えません）: ${state.relPath(file)}"
                            }
                        }.map { Unit }
                        TreeDialog.NewDirectory -> state.createDirectory(parent, value).map { Unit }
                        TreeDialog.Rename -> selectedPath?.let { state.renamePath(it, value).map { Unit } }
                            ?: Result.failure(IllegalStateException("対象がありません"))
                        TreeDialog.Delete -> selectedPath?.let { state.deletePaths(targets()) }
                            ?: Result.failure(IllegalStateException("対象がありません"))
                    }
                    if (result.isSuccess) { dialog = null; if (kind != TreeDialog.NewFile) state.memory.treeSelection = null }
                    // 失敗はダイアログ内に表示する（ステータスバーだけでは気づけない。UX-41）
                    result.exceptionOrNull()?.let { it.message ?: "操作に失敗しました" }
                    }
                },
            )
        }
    }
}

@Composable
private fun TreeRowItem(state: AppState, row: TreeRow, isExpanded: Boolean, treeSelected: Boolean, dropTarget: Boolean = false, modifier: Modifier = Modifier, onExpand: () -> Unit, onClick: () -> Unit) {
    val selected = !row.isDir && state.activeFile == row.path
    val cached = if (row.isDir) null else state.docFor(row.path)
    val dirty = cached?.let { it.dirty || it.draftDirty } == true
    val textOnly = cached?.textOnly == true   // 壊れたまま保存／解析できない＝索引・検証の対象外（R43-04）
    Row(
        modifier
            .fillMaxWidth()
            .background(if (dropTarget) Palette.Primary.copy(alpha = 0.12f) else if (treeSelected) Palette.Selected else Color.Transparent)
            .then(if (dropTarget) Modifier.border(2.dp, Palette.Primary) else if (selected) Modifier.border(1.dp, Palette.Primary) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = (8 + row.depth * 16).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isDir) {
            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "折り畳む" else "展開する",
                Modifier.size(20.dp).clickable(onClick = onExpand),
                tint = Palette.SubText,
            )
            Icon(
                if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = if (row.editorOnly) Palette.SubText else Palette.Folder,
            )
        } else {
            Spacer(Modifier.width(16.dp))
            Icon(
                if (row.markdown) Icons.AutoMirrored.Filled.Article else Icons.Default.Description,
                contentDescription = if (row.markdown) "Markdown" else null,
                Modifier.size(16.dp),
                tint = if (row.editorOnly) Palette.SubText else Palette.Primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
        UiTooltip(state.relPath(row.path)) { Text(
            (if (state.memory.treeQuery.isNotBlank()) state.relPath(row.path) else row.path.fileName.toString()) + (row.line?.let { ":$it" } ?: ""),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 台帳外（索引・検証・エージェントの対象外）は薄色で区別する（R13-04）
            color = if (row.editorOnly) Palette.SubText else Color.Unspecified,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
        ) }
        row.excerpt?.let { Text(it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.SubText) }
        }
        if (textOnly) {
            Spacer(Modifier.width(4.dp))
            Text("解析不可", fontSize = 9.sp, color = Palette.ErrorRed)
        }
        if (dirty) {
            Spacer(Modifier.width(4.dp))
            Text("●", fontSize = 9.sp, color = Palette.WarnOrange)
        }
    }
}

/**
 * スキーマ推測ダイアログ（R11-01）: glob とスキーマIDを確認して `_schemas/<ID>.yaml` のドラフトを作成する。
 * 初期値は選択ファイルのディレクトリ配下（同種文書の集まり）を対象にする。
 */
@Composable
private fun InferSchemaDialog(state: AppState, target: Path, onDismiss: () -> Unit) {
    val rel = state.relPath(target)
    val dir = rel.substringBeforeLast('/', "")
    var glob by remember(target) {
        mutableStateOf(if (dir.isEmpty()) "*.{yaml,yml}" else "$dir/**/*.{yaml,yml}")
    }
    var id by remember(target) {
        mutableStateOf(if (dir.isEmpty()) rel.substringBeforeLast('.') else dir.substringAfterLast('/'))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("スキーマ推測", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "glob に一致する既存文書から _schemas/<スキーマID>.yaml のドラフトを推測します。\n" +
                        "型・必須・enum は実例からの候補のため、作成後にスキーマ設計画面で仕上げてください。\n" +
                        "enum は「短い文字列値が3件以上あり、同じ値が反復している」ときに候補になります（IDらしい値は enum にせず参照（ref）または string になります）。",
                    fontSize = 12.sp, color = Palette.SubText,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(glob, { glob = it }, singleLine = true, label = { Text("対象文書（glob）") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(id, { id = it }, singleLine = true, label = { Text("スキーマID") })
            }
        },
        confirmButton = {
            TextButton(enabled = glob.isNotBlank() && id.isNotBlank(), onClick = {
                state.inferSchema(glob.trim(), id.trim())
                    .onSuccess {
                        state.status = "スキーマドラフトを作成しました: ${state.relPath(it)}"
                        onDismiss()
                    }
                    .onFailure { state.status = it.message ?: "スキーマ推測に失敗しました" }
            }) { Text("推測して作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private enum class TreeDialog { NewFile, NewDirectory, Rename, Delete }

@Composable
private fun TreeOperationDialog(
    kind: TreeDialog,
    initial: String,
    destination: String?,
    allowRootSwitch: Boolean,
    impact: ((String) -> List<String>)? = null,  // 改名の影響プレビュー（R21-03。入力値から都度計算）
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean, Boolean) -> String?,  // (名前, 直下作成, AIたたき台) → エラーメッセージ（null=成功。UX-41）
) {
    var value by remember(kind, initial) { mutableStateOf(initial) }
    var atRoot by remember(kind) { mutableStateOf(false) }
    var withAi by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun confirm() {
        if (busy) return
        busy = true
        scope.launch {
            try { error = withContext(Dispatchers.IO) { onConfirm(value, atRoot, withAi) } }
            finally { busy = false }
        }
    }
    val title = when (kind) {
        TreeDialog.NewFile -> "YAML ファイルを作成"
        TreeDialog.NewDirectory -> "フォルダーを作成"
        TreeDialog.Rename -> "名前を変更"
        TreeDialog.Delete -> "削除の確認"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                if (kind == TreeDialog.Delete) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) { items(initial.lines()) { Text(it) } }
                    Text("フォルダー内の非表示ファイルも含め、.yde-trash に退避します。Ctrl+Z で復元できます。")
                }
                else {
                    OutlinedTextField(
                        value, { value = it; error = null }, enabled = !busy, singleLine = true, label = { Text("名前") },
                        modifier = Modifier.confirmOnEnter(enabled = { value.isNotBlank() }) { confirm() },
                    )
                    destination?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "作成先: ${if (atRoot) "（ワークスペース直下）" else it}",
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                    if (allowRootSwitch) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(atRoot, { atRoot = it }, enabled = !busy)
                            Text("ワークスペース直下に作成する", fontSize = 12.sp)
                        }
                    }
                    // 作成→たたき台生成の連結（R24-03）。スキーマが当たるファイル名のときだけ意味を持つ
                    if (kind == TreeDialog.NewFile) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(withAi, { withAi = it }, enabled = !busy)
                            Text("作成後にAIでたたき台を生成する（スキーマが適用される場合）", fontSize = 12.sp)
                        }
                    }
                    // 改名の影響（R21-03）: 書き換え対象の文書参照・スキーマ／ビューの当たりの変化
                    impact?.invoke(value).orEmpty().takeIf { value != initial }?.forEach { line ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            line, fontSize = 11.sp,
                            color = if (line.startsWith("注意:")) Palette.WarnOrange else Palette.SubText,
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (kind == TreeDialog.Delete || value.isNotBlank()), onClick = { confirm() }) {
                Text(if (busy) "処理中…" else if (kind == TreeDialog.Delete) "削除" else "実行",
                    color = if (kind == TreeDialog.Delete) Palette.ErrorRed else Palette.Primary)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") } },
    )
}
