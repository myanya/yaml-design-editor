import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.delay
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.AppState
import index.IdEntry
import ui.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import java.util.prefs.Preferences

private val uiPreferences: Preferences = Preferences.userRoot().node("yaml-design-editor/ui")

fun main(args: Array<String>) = application {
    val state = remember { AppState() }
    var confirmExit by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(uiPreferences.getBoolean("darkMode", false)) }

    LaunchedEffect(Unit) {
        // 起動引数でワークスペース指定可。未指定ならカレントの sample-workspace を試す。
        val initial = args.firstOrNull()?.let { Path.of(it) } ?: Path.of("sample-workspace")
        if (Files.isDirectory(initial)) state.openWorkspace(initial)
    }

    Window(
        onCloseRequest = { if (state.hasDirtyDocs()) confirmExit = true else exitApplication() },
        title = "YAML Design Editor" + if (state.hasDirtyDocs()) " ●" else "",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
    ) {
        AppTheme(darkMode) {
            Surface(Modifier.fillMaxSize(), color = Palette.EditorBg) {
                AppRoot(state, darkMode) {
                    darkMode = !darkMode
                    uiPreferences.putBoolean("darkMode", darkMode)
                }
            }
            if (confirmExit) AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("未保存の変更があります") },
                text = { Text("変更を保存せずに終了しますか？") },
                confirmButton = { TextButton(onClick = { exitApplication() }) { Text("保存せず終了", color = Palette.ErrorRed) } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("キャンセル") } },
            )
        }
    }
}

@Composable
fun AppRoot(state: AppState, darkMode: Boolean, toggleDarkMode: () -> Unit) {
    var leftWidth by remember { mutableStateOf(uiPreferences.getFloat("leftWidth", 240f).dp) }
    var rightWidth by remember { mutableStateOf(uiPreferences.getFloat("rightWidth", 280f).dp) }
    var leftOpen by remember { mutableStateOf(uiPreferences.getBoolean("leftOpen", true)) }
    var rightOpen by remember { mutableStateOf(uiPreferences.getBoolean("rightOpen", true)) }
    var commandOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.workspace) {
        while (true) {
            delay(1000)
            state.pollExternalChanges()
        }
    }
    Column(Modifier.fillMaxSize().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onKeyEvent false
        when {
            event.key == Key.S && event.isShiftPressed -> { state.saveAll(); true }
            event.key == Key.S -> { state.saveCurrent(); true }
            event.key == Key.Z && event.isShiftPressed -> { state.redo(); true }
            event.key == Key.Z -> { state.undo(); true }
            event.key == Key.Y -> { state.redo(); true }
            event.key == Key.K -> { commandOpen = true; true }
            else -> false
        }
    }) {
        Toolbar(state, leftOpen, rightOpen, darkMode, toggleDarkMode, { commandOpen = true },
            { leftOpen = !leftOpen; uiPreferences.putBoolean("leftOpen", leftOpen) },
            { rightOpen = !rightOpen; uiPreferences.putBoolean("rightOpen", rightOpen) })
        Divider(color = Palette.Border)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (leftOpen) {
                FileTreePane(state, leftWidth)
                ResizeHandle { delta ->
                    leftWidth = (leftWidth + delta).coerceIn(170.dp, 480.dp)
                    uiPreferences.putFloat("leftWidth", leftWidth.value)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.workspaceView) {
                    1 -> WorkspaceTableView(state)
                    2 -> ReferenceGraphView(state)
                    else -> EditorArea(state)
                }
            }
            if (rightOpen) {
                ResizeHandle { delta ->
                    rightWidth = (rightWidth - delta).coerceIn(210.dp, 520.dp)
                    uiPreferences.putFloat("rightWidth", rightWidth.value)
                }
                SidePanel(state, rightWidth)
            }
        }
        Divider(color = Palette.Border)
        StatusBar(state)
    }
    if (commandOpen) CommandPalette(state) { commandOpen = false }
    if (state.navigationCandidates.isNotEmpty()) {
        val candidates = state.navigationCandidates
        AlertDialog(
            onDismissRequest = state::dismissNavigationCandidates,
            title = { Text("「${candidates.first().id}」の出現箇所") },
            text = {
                Column(Modifier.width(520.dp).heightIn(max = 460.dp)) {
                    Text("同じ論理IDを持つ要素が ${candidates.size} 件あります。", fontSize = 12.sp, color = Palette.SubText)
                    Spacer(Modifier.height(8.dp))
                    candidates.forEach { entry ->
                        TextButton(
                            onClick = { state.navigateToOccurrence(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(entry.title ?: entry.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${state.relPath(entry.file)} · ${entry.documentId}",
                                    fontSize = 11.sp, color = Palette.SubText,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = state::dismissNavigationCandidates) { Text("閉じる") } },
        )
    }
}

@Composable
private fun ResizeHandle(onDrag: (androidx.compose.ui.unit.Dp) -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.width(5.dp).fillMaxHeight().background(Palette.Border.copy(alpha = .45f))
        .pointerInput(Unit) {
            detectDragGestures { change, amount ->
                change.consume()
                onDrag(with(density) { amount.x.toDp() })
            }
        })
}

// ================================================================ ツールバー

@Composable
fun Toolbar(state: AppState, leftOpen: Boolean, rightOpen: Boolean, darkMode: Boolean, toggleDarkMode: () -> Unit,
            openCommands: () -> Unit,
            toggleLeft: () -> Unit, toggleRight: () -> Unit) {
    var pendingWorkspace by remember { mutableStateOf<Path?>(null) }
    var recentOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Palette.PanelBg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = {
            chooseDirectory()?.let { if (state.hasDirtyDocs()) pendingWorkspace = it else state.openWorkspace(it) }
        }) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("開く", fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = state.workspace != null,
            onClick = { state.reload() },
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("再読込", fontSize = 13.sp)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { state.workspaceView = 0 }) { Icon(Icons.Default.Description, "文書ビュー", Modifier.size(18.dp),
            tint = if (state.workspaceView == 0) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.workspaceView = 1 }) { Icon(Icons.Default.TableRows, "一覧ビュー", Modifier.size(18.dp),
            tint = if (state.workspaceView == 1) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.workspaceView = 2 }) { Icon(Icons.Default.AccountTree, "参照グラフ", Modifier.size(18.dp),
            tint = if (state.workspaceView == 2) Palette.Primary else Palette.SubText) }
        Box {
            IconButton(onClick = { recentOpen = true }) { Icon(Icons.Default.History, "最近開いたファイル", Modifier.size(18.dp)) }
            DropdownMenu(recentOpen, { recentOpen = false }) {
                if (state.recentFiles.isEmpty()) DropdownMenuItem(text = { Text("履歴なし") }, onClick = {}, enabled = false)
                state.recentFiles.forEach { file -> DropdownMenuItem(text = { Text(state.relPath(file), fontSize = 11.sp) }, onClick = {
                    state.openFile(file); recentOpen = false
                }) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        IdSearchBox(state)
        IconButton(onClick = openCommands) { Icon(Icons.Default.Terminal, "コマンドパレット (Ctrl+K)", Modifier.size(18.dp)) }
        IconButton(onClick = toggleDarkMode) {
            Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                if (darkMode) "ライトモード" else "ダークモード", Modifier.size(18.dp))
        }
        IconButton(onClick = toggleLeft) { Text(if (leftOpen) "◀" else "▶", fontSize = 11.sp) }
        IconButton(onClick = toggleRight) { Text(if (rightOpen) "▶" else "◀", fontSize = 11.sp) }
    }
    pendingWorkspace?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingWorkspace = null },
            title = { Text("未保存の変更があります") },
            text = { Text("変更を破棄してワークスペースを切り替えますか？") },
            confirmButton = { TextButton(onClick = { pendingWorkspace = null; state.openWorkspace(next) }) {
                Text("破棄して切り替え", color = Palette.ErrorRed)
            } },
            dismissButton = { TextButton(onClick = { pendingWorkspace = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun CommandPalette(state: AppState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val files = remember(query, state.yamlFiles) {
        state.yamlFiles.filter { state.relPath(it).contains(query, true) }.take(10)
    }
    val ids = remember(query, state.indexVersion) {
        if (query.isBlank()) emptyList() else state.index.entries.keys.filter { it.contains(query, true) }.sorted().take(8)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("コマンドパレット") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 460.dp)) {
                CompactTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("コマンドまたはファイルを検索…", color = Palette.SubText) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CommandItem("ワークスペース一覧を表示") { state.workspaceView = 1; onDismiss() }
                CommandItem("参照グラフを表示") { state.workspaceView = 2; onDismiss() }
                CommandItem("すべて保存") { state.saveAll(); onDismiss() }
                ids.forEach { id -> CommandItem("IDへ移動: $id") { state.navigateToId(id); onDismiss() } }
                files.forEach { file -> CommandItem("開く: ${state.relPath(file)}") { state.openFile(file); onDismiss() } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun CommandItem(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp))
}

/** Swing のディレクトリ選択ダイアログ。 */
private fun chooseDirectory(): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "ワークスペース（ディレクトリ）を選択"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else null
}

// ================================================================ ID 検索

@Composable
fun IdSearchBox(state: AppState) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    state.indexVersion

    val matches: List<IdEntry> = remember(query, state.indexVersion) {
        if (query.isBlank()) emptyList()
        else state.index.entries.values.asSequence()
            .mapNotNull { it.firstOrNull() }
            .filter {
                it.id.contains(query, ignoreCase = true) ||
                        (it.title?.contains(query, ignoreCase = true) == true)
            }
            .sortedBy { it.id }
            .take(12)
            .toList()
    }

    Box {
        Row(
            Modifier.width(300.dp).height(34.dp).background(Palette.Surface, PanelShape)
                .border(1.dp, Palette.Border, PanelShape).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(16.dp), tint = Palette.SubText)
            Spacer(Modifier.width(7.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("ID・タイトルで検索…", fontSize = 12.sp, color = Palette.SubText)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; open = it.isNotBlank() },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = Palette.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        DropdownMenu(
            expanded = open && matches.isNotEmpty(),
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false),
        ) {
            matches.forEach { e ->
                DropdownMenuItem(text = {
                    Column {
                        Text(e.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(e.title, state.relPath(e.file)).joinToString(" — "),
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                }, onClick = {
                    state.navigateToId(e.id)
                    open = false
                    query = ""
                })
            }
        }
    }
}

// ================================================================ エディタ領域

@Composable
fun EditorArea(state: AppState) {
    val doc = state.openDoc
    if (doc == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("ファイルツリーから YAML を選択してください", color = Palette.SubText, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "スキーマ（_schemas/*.yaml）にマッチするファイルはフォームで、それ以外は汎用ビューで編集できます",
                color = Palette.SubText, fontSize = 12.sp,
            )
        }
        return
    }

    val isSchemaFile = state.relPath(doc.file).startsWith("_schemas/")
    Column(Modifier.fillMaxSize()) {
        if (doc.externalConflict) {
            Row(
                Modifier.fillMaxWidth().background(Palette.ConflictBg).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("このファイルは外部で変更されました。未保存の編集内容を保持しています。", fontSize = 12.sp,
                    color = Palette.WarnOrange, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.acceptDiskVersion() }) { Text("ディスク版を読み込む", fontSize = 12.sp) }
                TextButton(onClick = { state.keepLocalVersion() }) { Text("編集内容を維持", fontSize = 12.sp) }
            }
        }
        // ヘッダ: ファイル名・スキーマ・ダーティ表示
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.relPath(doc.file), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (doc.dirty) {
                Spacer(Modifier.width(6.dp))
                Text("●未保存", fontSize = 11.sp, color = Palette.WarnOrange)
            }
            Spacer(Modifier.width(12.dp))
            doc.schema?.let {
                Text(
                    "スキーマ: ${it.name}",
                    fontSize = 11.sp,
                    color = Palette.Primary,
                    modifier = Modifier
                        .background(Palette.Selected, PanelShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } ?: Text("スキーマなし（汎用ビュー）", fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.weight(1f))
            IconButton(enabled = doc.canUndo(), onClick = state::undo, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Undo, "元に戻す (Ctrl+Z)", Modifier.size(17.dp))
            }
            IconButton(enabled = doc.canRedo(), onClick = state::redo, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Redo, "やり直す (Ctrl+Y)", Modifier.size(17.dp))
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(enabled = doc.dirty, onClick = state::saveCurrent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Save, contentDescription = null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            TextButton(enabled = state.hasDirtyDocs(), onClick = state::saveAll,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text("すべて保存", fontSize = 11.sp)
            }
        }
        TabRow(
            selectedTabIndex = state.editorTab,
            containerColor = Palette.Surface,
            contentColor = Palette.Primary,
        ) {
            Tab(
                selected = state.editorTab == 0,
                onClick = { state.editorTab = 0 },
                text = { Text(if (isSchemaFile) "スキーマ設計" else "フォーム", fontSize = 13.sp) },
            )
            Tab(
                selected = state.editorTab == 1,
                onClick = { state.editorTab = 1 },
                text = { Text("ソース", fontSize = 13.sp) },
            )
            Tab(
                selected = state.editorTab == 2,
                onClick = { state.editorTab = 2 },
                text = { Text("差分", fontSize = 13.sp) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.editorTab == 0) {
                if (isSchemaFile) SchemaEditor(state, doc) else FormView(state, doc)
            } else if (state.editorTab == 1) SourcePane(state, doc) else DiffPane(doc)
        }
    }
}

// ================================================================ ステータスバー

@Composable
fun StatusBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(Palette.PanelBg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.status, fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
    }
}
