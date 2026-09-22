package dev.toros.yde.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.History
import dev.toros.yde.app.agent.AutomationEngine
import dev.toros.yde.app.ai.AiProvider
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.*
import dev.toros.yde.app.core.FileWatcher
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.workspace.layout.WorkspaceLayout
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.toros.yde.app.AppState
import dev.toros.yde.app.UiSpace
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.app.ui.*
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import java.util.prefs.Preferences

private val uiPreferences: Preferences = Preferences.userRoot().node("yaml-design-editor/ui")

fun main(args: Array<String>) = application {
    val state = remember { AppState() }
    var confirmExit by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(uiPreferences.getBoolean("darkMode", false)) }
    var commandOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.readOnlyMode = uiPreferences.getBoolean("readOnly", false)
        // 起動引数でワークスペース指定可。未指定ならスタート画面（履歴からの選択。R10-05）を表示する。
        args.firstOrNull()?.let { Path.of(it) }?.takeIf(Files::isDirectory)?.let { state.openWorkspace(it) }
    }

    Window(
        onCloseRequest = { if (state.hasDirtyDocs()) confirmExit = true else exitApplication() },
        title = "YAML Design Editor" + if (state.hasDirtyDocs()) " ●" else "",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
        // ショートカットはウィンドウで受ける（UX-120）: 焦点のある部品が消費しなかったキーが、画面のどこにも焦点が無い
        // とき（ジャンプの直後など）でも届く。画面の部品の Modifier.onKeyEvent は、焦点のある子がいるときにしか呼ばれない
        onKeyEvent = { event -> state.workspace != null && handleShortcut(event, state) { commandOpen = true } },
    ) {
        AppTheme(darkMode) {
            Surface(Modifier.fillMaxSize(), color = Palette.EditorBg) {
                if (state.workspace == null) {
                    StartScreen(state)
                } else AppRoot(state, darkMode, commandOpen, { commandOpen = it }) {
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

/**
 * アプリ全体のキーボードショートカット（戻る／進む・保存・Undo/Redo・コマンドパレット）。
 * ウィンドウの onKeyEvent から呼ばれる＝焦点のある部品（テキスト入力など）が先に受け、消費しなかったキーだけが来る。
 */
private fun handleShortcut(event: KeyEvent, state: AppState, openPalette: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isAltPressed) {
        return when (event.key) {
            Key.DirectionLeft -> { state.goBack(); true }
            Key.DirectionRight -> { state.goForward(); true }
            else -> false
        }
    }
    if (!event.isCtrlPressed) return false
    return when {
        event.key == Key.S && event.isShiftPressed -> { state.saveAll(); true }
        event.key == Key.S -> { state.saveCurrent(); true }
        event.key == Key.Z && event.isShiftPressed -> { state.redo(); true }
        event.key == Key.Z -> { state.undo(); true }
        event.key == Key.Y -> { state.redo(); true }
        event.key == Key.K -> { openPalette(); true }
        else -> false
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
fun AppRoot(
    state: AppState,
    darkMode: Boolean,
    commandOpen: Boolean,
    setCommandOpen: (Boolean) -> Unit,
    toggleDarkMode: () -> Unit,
) {
    var leftWidth by remember { mutableStateOf(uiPreferences.getFloat("leftWidth", 240f).dp) }
    var rightWidth by remember { mutableStateOf(uiPreferences.getFloat("rightWidth", 280f).dp) }
    var leftOpen by remember { mutableStateOf(uiPreferences.getBoolean("leftOpen", true)) }
    var rightOpen by remember { mutableStateOf(uiPreferences.getBoolean("rightOpen", true)) }
    var entityRenameOpen by remember { mutableStateOf(false) }
    // ワークスペース切替（R10-05）: ツールバー・コマンドパレット共通。未保存確認（W-07）を一元化する
    var pendingWorkspace by remember { mutableStateOf<Path?>(null) }
    val switchWorkspace: (Path) -> Unit = { dir ->
        if (state.hasDirtyDocs()) pendingWorkspace = dir else state.openWorkspace(dir)
    }
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
    // エージェント実行の状態機械（R12-01）: スペース切替で実行が中断しないよう AppRoot 生存期間で保持する
    val runScope = rememberCoroutineScope()
    val runController = remember(state.workspace) { state.workspace?.let { RunController(state, it, runScope) } }
    DisposableEffect(runController) {
        onDispose { runController?.cancel() }
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
        val watcher = state.workspace?.let { FileWatcher.open(it) }
        try {
            while (true) {
                if (watcher != null) {
                    delay(250)
                    if (watcher.consumeChanged()) state.pollExternalChanges(eventHint = true)
                } else {
                    delay(1000)
                    state.pollExternalChanges()
                }
            }
        } finally {
            watcher?.close()
        }
    }
    // ショートカット（Alt+←/→・Ctrl+S・Ctrl+Z・Ctrl+K）は main の Window.onKeyEvent（handleShortcut）で受ける（UX-120）
    Column(Modifier.fillMaxSize()) {
        automationVersion  // エンジン状態の変化でレール・受信箱バッジ・ステータスバーを再描画する
        // 受信箱の件数（R12-02）: 承認待ち変更セット＋エージェントの質問＋外部変更の競合
        val inboxCount = (automation?.pendingSnapshot()?.size ?: 0) +
            state.conflictedDocs().size +
            (if (runController?.pendingQuestion != null) 1 else 0)
        Toolbar(state, leftOpen, rightOpen, darkMode, toggleDarkMode, { setCommandOpen(true) },
            switchWorkspace = switchWorkspace,
            toggleReadOnly = {
                state.readOnlyMode = !state.readOnlyMode
                uiPreferences.putBoolean("readOnly", state.readOnlyMode)
            },
            toggleLeft = { leftOpen = !leftOpen; uiPreferences.putBoolean("leftOpen", leftOpen) },
            toggleRight = { rightOpen = !rightOpen; uiPreferences.putBoolean("rightOpen", rightOpen) })
        Divider(color = Palette.Border)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 実行スペースは定義が空でも常時表示する（UX-20。定義ゼロのときは RunsSpace の空状態が導線を案内する）
            ActivityRail(state, inboxCount, automation?.enabled == true)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
            when (state.uiSpace) {
                UiSpace.RUNS -> Box(Modifier.weight(1f).fillMaxHeight()) {
                    RunsSpace(state, automation, runController, automationVersion)
                }
                UiSpace.DESIGN -> Box(Modifier.weight(1f).fillMaxHeight()) {
                    DesignSpace(state)
                }
                else -> {
                    if (leftOpen && state.uiSpace == UiSpace.EDIT) {
                        FileTreePane(state, leftWidth)
                        ResizeHandle { delta ->
                            leftWidth = (leftWidth + delta).coerceIn(170.dp, 480.dp)
                            uiPreferences.putFloat("leftWidth", leftWidth.value)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.uiSpace == UiSpace.EDIT) EditorArea(state) else LedgerSpace(state)
                    }
                    if (rightOpen) {
                        ResizeHandle { delta ->
                            rightWidth = (rightWidth - delta).coerceIn(210.dp, 520.dp)
                            uiPreferences.putFloat("rightWidth", rightWidth.value)
                        }
                        SidePanel(state, rightWidth)
                    }
                }
            }
        }
        Divider(color = Palette.Border)
        StatusBar(state, automation, inboxCount) { state.showSpace(UiSpace.RUNS) }
    }
    if (commandOpen) CommandPalette(
        state, switchWorkspace,
        onShowRuns = { state.showSpace(UiSpace.RUNS) },
        onRenameEntity = { entityRenameOpen = true },
    ) { setCommandOpen(false) }
    if (entityRenameOpen) EntityRenameDialog(state) { entityRenameOpen = false }
    // AI生成ダイアログはスペース非依存（R24-01）: 対象文書は要求（AiRequest）が持つため、
    // 編集スペースだけでなくエンティティページ等の台帳スペースからの✨でもその場で開く
    AiGenerationHost(state)
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
                                    "${entry.document.value} · ${entry.documentId}",
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
            openCommands: () -> Unit,
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
        Box {
            IconButton(onClick = { recentOpen = true }) { Icon(Icons.Default.History, "最近開いたファイル", Modifier.size(18.dp)) }
            DropdownMenu(recentOpen, { recentOpen = false }) {
                if (state.recentFiles.isEmpty()) DropdownMenuItem(text = { Text("履歴なし") }, onClick = {}, enabled = false)
                state.recentFiles.forEach { file -> DropdownMenuItem(text = { Text(state.relPath(file), fontSize = 11.sp) }, onClick = {
                    state.openFile(file); recentOpen = false
                }) }
            }
        }
        Spacer(Modifier.weight(1f))
        // 統合検索（R12-04）: ID・ファイル・コマンド・ワークスペース切替を1つのパレットに集約
        OutlinedButton(
            enabled = state.workspace != null,
            onClick = openCommands,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("検索・コマンド (Ctrl+K)", fontSize = 12.sp)
        }
        Spacer(Modifier.width(4.dp))
        // 閲覧専用モード（R10-04）: フォーム・グリッドの編集UIを表示専用に切り替える
        IconButton(onClick = toggleReadOnly) {
            Icon(
                if (state.readOnlyMode) Icons.Default.EditOff else Icons.Default.Visibility,
                if (state.readOnlyMode) "閲覧専用モードを解除" else "閲覧専用モード",
                Modifier.size(18.dp),
                tint = if (state.readOnlyMode) Palette.WarnOrange else Palette.SubText,
            )
        }
        IconButton(onClick = toggleDarkMode) {
            Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                if (darkMode) "ライトモード" else "ダークモード", Modifier.size(18.dp))
        }
        IconButton(onClick = toggleLeft) { Text(if (leftOpen) "◀" else "▶", fontSize = 11.sp) }
        IconButton(onClick = toggleRight) { Text(if (rightOpen) "▶" else "◀", fontSize = 11.sp) }
    }
}

@Composable
private fun CommandPalette(
    state: AppState,
    switchWorkspace: (Path) -> Unit,
    onShowRuns: () -> Unit,
    onRenameEntity: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val files = remember(query, state.yamlFiles) {
        state.yamlFiles.filter { state.relPath(it).contains(query, true) }.take(10)
    }
    // ID検索（R12-04）: 旧ツールバーのID検索ボックスを統合。ID・タイトル（表示解決 S-13）の部分一致。
    // 未保存で追加したIDも候補に出すためオーバーレイ索引で引く（UX-18。「見たまま判定」）
    val idMatches: List<IdEntry> = remember(query, state.indexVersion, state.editVersion) {
        if (query.isBlank()) emptyList()
        else state.overlayIndex().entries.values.asSequence()
            .mapNotNull { it.firstOrNull() }
            .filter { it.id.contains(query, true) || (it.title?.contains(query, true) == true) }
            .sortedBy { it.id }
            .take(10)
            .toList()
    }
    // 本文検索（R17-04）: 構造検索API（C-05）を未保存編集込みの「見たまま」で。2文字以上で発動
    val contentHits = remember(query, state.indexVersion, state.editVersion) {
        if (query.trim().length < 2) emptyList() else state.searchContent(query.trim(), limit = 10)
    }
    // ワークスペース切替候補（R10-05）: 現在以外の履歴。文字入力があれば絞り込む
    val workspaces = remember(query, state.recentWorkspaces, state.workspace) {
        state.recentWorkspaces.filter { it != state.workspace }
            .filter { query.isBlank() || it.toString().contains(query, true) }
            .take(5)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("検索・コマンド") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 460.dp)) {
                CompactTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("ID・タイトル・ファイル・コマンドを検索…") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                idMatches.forEach { e ->
                    CommandItem("IDへ移動: ${e.id}" + (e.title?.let { " — $it" } ?: "")) { state.navigateToId(e.id); onDismiss() }
                }
                files.forEach { file -> CommandItem("開く: ${state.relPath(file)}") { state.openFile(file); onDismiss() } }
                contentHits.forEach { hit ->
                    CommandItem("本文: ${hit.file} · ${hit.path} — ${hit.excerpt}") {
                        state.workspace?.resolve(hit.file)?.let { abs ->
                            // キー名に . [ ] を含むパスは pretty 形式から復元できないため、ファイル先頭へのジャンプに落とす
                            val focus = runCatching { parseYPath(hit.path) }.getOrNull()?.takeIf { it.isNotEmpty() }
                            state.openFile(abs, focus)
                        }
                        onDismiss()
                    }
                }
                CommandItem("台帳: 一覧を表示") { state.showWorkspaceView(1); onDismiss() }
                CommandItem("台帳: ビューグリッドを表示") { state.openViewGrid(); onDismiss() }
                CommandItem("台帳: グラフレンズを表示") { state.openGraphLens(); onDismiss() }
                CommandItem("台帳: エンティティページを表示") { state.openCrossView(); onDismiss() }
                CommandItem("実行スペースを表示（受信箱）") { onShowRuns(); onDismiss() }
                CommandItem("設計スペースを表示") { state.showSpace(UiSpace.DESIGN); onDismiss() }
                CommandItem("エンティティを改名…") { onDismiss(); onRenameEntity() }
                CommandItem("すべて保存") { state.saveAll(); onDismiss() }
                CommandItem("ワークスペースを開く…") { onDismiss(); chooseDirectory()?.let(switchWorkspace) }
                workspaces.forEach { ws ->
                    CommandItem("ワークスペースを切替: $ws") { onDismiss(); switchWorkspace(ws) }
                }
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

/**
 * エンティティ改名の確認ダイアログ（R21-02。ARCHITECTURE §4.31）。
 * 計画（全出現・全参照の書き換え）をプレビューし、適用はセッションの1トランザクション
 * （AppState.applyEntityPlan。Undo 1回で戻る。B-1）。本格的な操作UIは P23 のエンティティページで扱う。
 */
@Composable
private fun EntityRenameDialog(state: AppState, onDismiss: () -> Unit) {
    var oldId by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var applyError by remember { mutableStateOf<String?>(null) }
    state.indexVersion
    val plan = remember(oldId, newId, state.indexVersion) {
        if (oldId.isBlank() || newId.isBlank()) null
        else state.planEntityRename(oldId.trim(), newId.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("エンティティを改名") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 480.dp)) {
                CompactTextField(
                    oldId, { oldId = it }, singleLine = true,
                    placeholder = { Text("現在のID（例 SCR-001）") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                CompactTextField(
                    newId, { newId = it }, singleLine = true,
                    placeholder = { Text("改名先のID") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    plan == null -> Text("現在のIDと改名先を入力するとプレビューを表示します", fontSize = 12.sp, color = Palette.SubText)
                    plan.isFailure -> Text(plan.exceptionOrNull()?.message ?: "プレビューを生成できません", fontSize = 12.sp, color = Palette.ErrorRed)
                    else -> {
                        val p = plan.getOrThrow()
                        p.summary.forEach { Text(it, fontSize = 12.sp) }
                        p.warnings.forEach { Text("注意: $it", fontSize = 12.sp, color = Palette.WarnOrange) }
                        Spacer(Modifier.height(6.dp))
                        Text("変更されるファイル:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        p.changes.forEach { c ->
                            Text("・${c.document.value}（${c.operations.size} 操作）", fontSize = 12.sp, color = Palette.SubText)
                        }
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan?.isSuccess == true,
                onClick = {
                    plan?.getOrNull()?.let { p ->
                        state.applyEntityPlan(p)
                            .onSuccess { onDismiss() }
                            .onFailure { applyError = it.message ?: "適用に失敗しました" }
                    }
                },
            ) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
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

    val isSchemaZone = state.layout.zoneOf(state.relPath(doc.file)) == WorkspaceLayout.Zone.SCHEMAS
    // 関係レジストリ（kind: relations。R20-02）はスキーマ設計UIでなく builtin-relations のフォームで編集する
    val isSchemaFile = isSchemaZone && doc.schema?.id != BuiltinSchemas.RELATIONS_SCHEMA_ID
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
                    onClick = { state.requestAi(doc, doc.schema!!.root, emptyList(), wholeDocument = true) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("AIで作成", fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            // Undo/Redo はワークスペース横断の単一履歴（A-3・D-07）——活性判定もセッション基準。
            // 開いていない文書も戻るため、ホバーで「次に何が戻るか」を操作前に予告する（UX-86）
            HistoryTooltip("元に戻す (Ctrl+Z)", state.undoPreview()) {
                IconButton(enabled = state.canUndo(), onClick = state::undo, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Undo, "元に戻す (Ctrl+Z)", Modifier.size(17.dp))
                }
            }
            HistoryTooltip("やり直す (Ctrl+Y)", state.redoPreview()) {
                IconButton(enabled = state.canRedo(), onClick = state::redo, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Redo, "やり直す (Ctrl+Y)", Modifier.size(17.dp))
                }
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
}

/**
 * Undo/Redo ボタンの予告ツールチップ（UX-86）: 横断履歴は開いていない文書も戻すため、
 * ホバーで「次に何が戻るか」（変更の意図＋対象ファイル）を操作前に見せる。予告がなければ操作名だけ出す。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryTooltip(label: String, preview: String?, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(color = Palette.PanelBg, shadowElevation = 4.dp, shape = PanelShape) {
                Text(
                    if (preview != null) "$label — $preview" else label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                )
            }
        },
        content = content,
    )
}

// ================================================================ ステータスバー

@Composable
fun StatusBar(state: AppState, engine: AutomationEngine?, inboxCount: Int, onOpenInbox: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(Palette.PanelBg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Undo/Redo の通知（UX-86）: 何が戻ったかの明示と戻った文書へのジャンプ導線。編集・保存・✕で消える
        state.historyNotice?.let { notice ->
            Row(
                Modifier.weight(2f, fill = false).background(Palette.Selected, PanelShape).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice.message, fontSize = 11.sp, color = Palette.Primary, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                notice.files.take(3).forEach { f ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        f.fileName.toString(),
                        fontSize = 11.sp, color = Palette.Primary, fontWeight = FontWeight.Bold, maxLines = 1,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { state.openFile(f) },
                    )
                }
                if (notice.files.size > 3) {
                    Text(" ほか${notice.files.size - 3}件", fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "✕", fontSize = 11.sp, color = Palette.SubText,
                    modifier = Modifier.clickable(onClick = state::dismissHistoryNotice).padding(horizontal = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(state.status, fontSize = 11.sp, color = Palette.SubText, maxLines = 1, modifier = Modifier.weight(1f))
        // 検証サマリ（開いている文書。詳細は右パネルの検証タブ）
        val doc = state.openDoc
        if (doc != null) {
            doc.version
            state.indexVersion
            val issues = remember(doc, doc.version, state.indexVersion, state.editVersion) {
                Validator.validate(
                    doc.root, doc.parseError, state.documentOf(doc.file), doc.schema, state.overlayIndex(),
                    indexed = state.isIndexed(doc.file), allSchemas = state.schemas,
                    loader = state.unsavedFirstTree(),
                    relations = state.relations, enforcement = state.enforcement,
                )
            }
            val errors = issues.count { it.severity == Severity.ERROR } + state.schemaErrors.size
            val warnings = issues.count { it.severity == Severity.WARNING }
            Text(
                "検証: エラー $errors・警告 $warnings",
                fontSize = 11.sp, maxLines = 1,
                color = if (errors > 0) Palette.ErrorRed else if (warnings > 0) Palette.WarnOrange else Palette.OkGreen,
            )
            Spacer(Modifier.width(12.dp))
        }
        // 実行の状態（R12-02）: 自動化の稼働・実行中アクティビティ・判断待ちを常設表示する
        engine?.runningActivity?.let {
            Text("実行中: $it", fontSize = 11.sp, color = Palette.Primary, maxLines = 1)
            Spacer(Modifier.width(12.dp))
        }
        if (engine?.enabled == true) {
            Text("自動化 有効", fontSize = 11.sp, color = Palette.OkGreen, maxLines = 1)
            Spacer(Modifier.width(12.dp))
        }
        if (inboxCount > 0) {
            Text(
                "判断待ち $inboxCount 件",
                fontSize = 11.sp, color = Palette.WarnOrange, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clickable(onClick = onOpenInbox),
            )
        }
    }
}
