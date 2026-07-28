import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ImportExport
import agent.AutomationEngine
import ai.AiProvider
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Visibility
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
import index.IdIndex
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
        state.readOnlyMode = uiPreferences.getBoolean("readOnly", false)
        // 起動引数でワークスペース指定可。未指定ならスタート画面（履歴からの選択。R10-05）を表示する。
        args.firstOrNull()?.let { Path.of(it) }?.takeIf(Files::isDirectory)?.let { state.openWorkspace(it) }
    }

    Window(
        onCloseRequest = { if (state.hasDirtyDocs()) confirmExit = true else exitApplication() },
        title = "YAML Design Editor" + if (state.hasDirtyDocs()) " ●" else "",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
    ) {
        AppTheme(darkMode) {
            Surface(Modifier.fillMaxSize(), color = Palette.EditorBg) {
                if (state.workspace == null) {
                    StartScreen(state)
                } else AppRoot(state, darkMode) {
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

/** ワークスペース未選択時のスタート画面（R10-05）。履歴からの選択で起動引数なしの切替を完結させる。 */
@Composable
fun StartScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("YAML Design Editor", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "スキーマ駆動のYAML編集・検証・エージェント実行環境",
            fontSize = 12.sp, color = Palette.SubText,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { chooseDirectory()?.let { state.openWorkspace(it) } }) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("ワークスペースを開く…", fontSize = 13.sp)
        }
        val sample = Path.of("sample-workspace")
        if (Files.isDirectory(sample) && state.recentWorkspaces.none { it.fileName?.toString() == "sample-workspace" }) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { state.openWorkspace(sample) }) {
                Text("sample-workspace を開く", fontSize = 12.sp)
            }
        }
        if (state.recentWorkspaces.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("最近開いたワークスペース", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText)
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.width(560.dp).background(Palette.PanelBg, PanelShape)
                    .border(1.dp, Palette.Border, PanelShape).padding(vertical = 4.dp),
            ) {
                state.recentWorkspaces.forEach { ws ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.openWorkspace(ws) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(15.dp), tint = Palette.Folder)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ws.fileName?.toString() ?: ws.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(ws.toString(), fontSize = 10.sp, color = Palette.SubText, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "開いたワークスペースはここに履歴として表示されます",
                fontSize = 11.sp, color = Palette.SubText,
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
    // ワークスペース切替（R10-05）: ツールバー・コマンドパレット共通。未保存確認（W-07）を一元化する
    var pendingWorkspace by remember { mutableStateOf<Path?>(null) }
    val switchWorkspace: (Path) -> Unit = { dir ->
        if (state.hasDirtyDocs()) pendingWorkspace = dir else state.openWorkspace(dir)
    }
    var agentOpen by remember { mutableStateOf(false) }
    var automationOpen by remember { mutableStateOf(false) }
    var improvementOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var automationVersion by remember { mutableStateOf(0) }
    // 自動化エンジン（ワークスペースごとに生成。既定は停止状態で、パネルから有効化する）
    val automation = remember(state.workspace) {
        state.workspace?.let { wsDir ->
            AutomationEngine(
                wsDir,
                credentials = {
                    AutomationEngine.Credentials(
                        provider = AiSettings.provider,
                        models = AiProvider.entries.associateWith { AiSettings.modelFor(it) },
                        apiKeys = AiProvider.entries.associateWith { AiSettings.apiKeyFor(it) }
                            .filterValues { it.isNotBlank() },
                    )
                },
                dirtyChecker = { p -> state.docFor(p)?.dirty == true },
            )
        }
    }
    LaunchedEffect(automation) {
        automation?.let { engine ->
            engine.onUpdate = { automationVersion++ }
            engine.start(this)
        }
    }
    DisposableEffect(automation) {
        onDispose { automation?.stopAll() }
    }
    LaunchedEffect(state.workspace) {
        // 外部変更検出: WatchService によるOSネイティブ監視を優先し、
        // 使えない環境（ネットワークドライブ等）では従来の1秒ポーリングへフォールバックする（R8-03）
        val watcher = state.workspace?.let { core.FileWatcher.open(it) }
        try {
            while (true) {
                if (watcher != null) {
                    delay(250)
                    if (watcher.consumeChanged()) state.pollExternalChanges()
                } else {
                    delay(1000)
                    state.pollExternalChanges()
                }
            }
        } finally {
            watcher?.close()
        }
    }
    Column(Modifier.fillMaxSize().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (event.isAltPressed) {
            return@onKeyEvent when (event.key) {
                Key.DirectionLeft -> { state.goBack(); true }
                Key.DirectionRight -> { state.goForward(); true }
                else -> false
            }
        }
        if (!event.isCtrlPressed) return@onKeyEvent false
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
        automationVersion  // エンジン状態の変化でツールバー（監視インジケータ）を再描画する
        Toolbar(state, leftOpen, rightOpen, darkMode, toggleDarkMode, { commandOpen = true }, { agentOpen = true },
            openAutomation = { automationOpen = true },
            openImprovement = { improvementOpen = true },
            openSkills = { skillsOpen = true },
            automationActive = automation?.enabled == true,
            automationPending = automation?.pendingSnapshot()?.size ?: 0,
            switchWorkspace = switchWorkspace,
            toggleReadOnly = {
                state.readOnlyMode = !state.readOnlyMode
                uiPreferences.putBoolean("readOnly", state.readOnlyMode)
            },
            toggleLeft = { leftOpen = !leftOpen; uiPreferences.putBoolean("leftOpen", leftOpen) },
            toggleRight = { rightOpen = !rightOpen; uiPreferences.putBoolean("rightOpen", rightOpen) })
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
                    3 -> IdCrossView(state)
                    4 -> ViewGridView(state)
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
    if (commandOpen) CommandPalette(state, switchWorkspace) { commandOpen = false }
    if (agentOpen) AgentRunHost(state, automation) { agentOpen = false }
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
    if (automationOpen && automation != null) AutomationPanel(state, automation, automationVersion) { automationOpen = false }
    if (improvementOpen) ImprovementPanel(state) { improvementOpen = false }
    if (skillsOpen) SkillsPanel(state) { skillsOpen = false }
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
            confirmButton = {
                TextButton(onClick = {
                    state.openCrossView(candidates.first().id)
                    state.dismissNavigationCandidates()
                }) { Text("横ぐしビューで表示") }
            },
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
            openCommands: () -> Unit, openAgent: () -> Unit,
            openAutomation: () -> Unit, openImprovement: () -> Unit, openSkills: () -> Unit,
            automationActive: Boolean, automationPending: Int,
            switchWorkspace: (Path) -> Unit, toggleReadOnly: () -> Unit,
            toggleLeft: () -> Unit, toggleRight: () -> Unit) {
    var recentOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Palette.PanelBg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = {
            chooseDirectory()?.let(switchWorkspace)
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
        // ナビゲーション履歴: 参照ジャンプ・ファイル切替の移動を戻る／進む
        IconButton(enabled = state.canGoBack, onClick = state::goBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る (Alt+←)", Modifier.size(18.dp),
                tint = if (state.canGoBack) Palette.SubText else Palette.SubText.copy(alpha = .35f))
        }
        IconButton(enabled = state.canGoForward, onClick = state::goForward) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "進む (Alt+→)", Modifier.size(18.dp),
                tint = if (state.canGoForward) Palette.SubText else Palette.SubText.copy(alpha = .35f))
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { state.workspaceView = 0 }) { Icon(Icons.Default.Description, "文書ビュー", Modifier.size(18.dp),
            tint = if (state.workspaceView == 0) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.workspaceView = 1 }) { Icon(Icons.Default.TableRows, "一覧ビュー", Modifier.size(18.dp),
            tint = if (state.workspaceView == 1) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.workspaceView = 2 }) { Icon(Icons.Default.AccountTree, "参照グラフ", Modifier.size(18.dp),
            tint = if (state.workspaceView == 2) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.openCrossView() }) { Icon(Icons.Default.Hub, "ID横ぐしビュー", Modifier.size(18.dp),
            tint = if (state.workspaceView == 3) Palette.Primary else Palette.SubText) }
        IconButton(onClick = { state.openViewGrid() }) { Icon(Icons.Default.GridOn, "ビューグリッド", Modifier.size(18.dp),
            tint = if (state.workspaceView == 4) Palette.Primary else Palette.SubText) }
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
        // 閲覧専用モード（R10-04）: フォーム・グリッドの編集UIを表示専用に切り替える
        IconButton(onClick = toggleReadOnly) {
            Icon(
                if (state.readOnlyMode) Icons.Default.EditOff else Icons.Default.Visibility,
                if (state.readOnlyMode) "閲覧専用モードを解除" else "閲覧専用モード",
                Modifier.size(18.dp),
                tint = if (state.readOnlyMode) Palette.WarnOrange else Palette.SubText,
            )
        }
        IconButton(enabled = state.workspace != null, onClick = openAgent) {
            Icon(Icons.Default.SmartToy, "アクティビティを実行", Modifier.size(18.dp))
        }
        Box {
            IconButton(enabled = state.workspace != null, onClick = openAutomation) {
                Icon(Icons.Default.Autorenew, "自動実行（トリガ・承認待ち）", Modifier.size(18.dp),
                    tint = if (automationActive) Palette.OkGreen else Palette.SubText)
            }
            if (automationPending > 0) {
                Text(
                    "$automationPending", fontSize = 9.sp, color = Palette.WarnOrange, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp),
                )
            }
        }
        IconButton(enabled = state.workspace != null, onClick = openImprovement) {
            Icon(Icons.Default.Insights, "自己改善（メトリクス・評価セット）", Modifier.size(18.dp))
        }
        IconButton(enabled = state.workspace != null, onClick = openSkills) {
            Icon(Icons.Default.ImportExport, "skill（インポート・エクスポート）", Modifier.size(18.dp))
        }
        IconButton(onClick = openCommands) { Icon(Icons.Default.Terminal, "コマンドパレット (Ctrl+K)", Modifier.size(18.dp)) }
        IconButton(onClick = toggleDarkMode) {
            Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                if (darkMode) "ライトモード" else "ダークモード", Modifier.size(18.dp))
        }
        IconButton(onClick = toggleLeft) { Text(if (leftOpen) "◀" else "▶", fontSize = 11.sp) }
        IconButton(onClick = toggleRight) { Text(if (rightOpen) "▶" else "◀", fontSize = 11.sp) }
    }
}

@Composable
private fun CommandPalette(state: AppState, switchWorkspace: (Path) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val files = remember(query, state.yamlFiles) {
        state.yamlFiles.filter { state.relPath(it).contains(query, true) }.take(10)
    }
    val ids = remember(query, state.indexVersion) {
        if (query.isBlank()) emptyList() else state.index.entries.keys.filter { it.contains(query, true) }.sorted().take(8)
    }
    // ワークスペース切替候補（R10-05）: 現在以外の履歴。文字入力があれば絞り込む
    val workspaces = remember(query, state.recentWorkspaces, state.workspace) {
        state.recentWorkspaces.filter { it != state.workspace }
            .filter { query.isBlank() || it.toString().contains(query, true) }
            .take(5)
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
                CommandItem("ID横ぐしビューを表示") { state.openCrossView(); onDismiss() }
                CommandItem("ビューグリッドを表示") { state.openViewGrid(); onDismiss() }
                CommandItem("すべて保存") { state.saveAll(); onDismiss() }
                CommandItem("ワークスペースを開く…") { onDismiss(); chooseDirectory()?.let(switchWorkspace) }
                workspaces.forEach { ws ->
                    CommandItem("ワークスペースを切替: $ws") { onDismiss(); switchWorkspace(ws) }
                }
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
    // root は通常の State ではないため、メタデータ編集時にもヘッダーを再描画させる。
    doc.version
    val documentName = doc.root?.let { IdIndex.documentNameOf(it) }
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
        // ヘッダ: 文書名・ファイル名・スキーマ・ダーティ表示
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (documentName != null && !isSchemaFile) {
                Column {
                    Text(documentName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(state.relPath(doc.file), fontSize = 10.sp, color = Palette.SubText)
                }
            } else {
                Text(state.relPath(doc.file), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
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
            if (state.readOnlyMode) {
                Text(
                    "閲覧専用",
                    fontSize = 11.sp, color = Palette.WarnOrange,
                    modifier = Modifier.background(Palette.ConflictBg, PanelShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (doc.schema != null && doc.root != null && !state.readOnlyMode) {
                OutlinedButton(
                    onClick = { state.requestAi(doc.schema!!.root, emptyList(), wholeDocument = true) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("AIで作成", fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
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
        // スキーマ文書ではタブ3にメタスキーマ（R11-02）のフォーム編集を追加する（設計UIが主要UXのまま）
        val tab = if (!isSchemaFile && state.editorTab == 3) 0 else state.editorTab
        TabRow(
            selectedTabIndex = tab,
            containerColor = Palette.Surface,
            contentColor = Palette.Primary,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { state.editorTab = 0 },
                text = { Text(if (isSchemaFile) "スキーマ設計" else "フォーム", fontSize = 13.sp) },
            )
            Tab(
                selected = tab == 1,
                onClick = { state.editorTab = 1 },
                text = { Text("ソース", fontSize = 13.sp) },
            )
            Tab(
                selected = tab == 2,
                onClick = { state.editorTab = 2 },
                text = { Text("差分", fontSize = 13.sp) },
            )
            if (isSchemaFile) {
                Tab(
                    selected = tab == 3,
                    onClick = { state.editorTab = 3 },
                    text = { Text("フォーム", fontSize = 13.sp) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> if (isSchemaFile) SchemaEditor(state, doc) else FormView(state, doc)
                1 -> SourcePane(state, doc)
                2 -> DiffPane(doc)
                else -> FormView(state, doc)
            }
        }
    }
    AiGenerationHost(state)
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
