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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import dev.toros.yde.app.agent.AutomationEngine
import dev.toros.yde.app.ai.AiProvider
import androidx.compose.material.icons.filled.AutoAwesome
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
import dev.toros.yde.app.format.PoiTables
import dev.toros.yde.app.skill.Tables
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
import java.util.prefs.Preferences

private val uiPreferences: Preferences = Preferences.userRoot().node("yaml-design-editor/ui")

fun main(args: Array<String>) = application {
    Tables.binary = PoiTables  // xlsx / docx の読み書き（POI）は入口が登録する（app-core に POI を入れない）
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
        title = (state.workspace?.toAbsolutePath()?.normalize()?.let { "$it — " } ?: "") +
            "YAML Design Editor" + if (state.hasDirtyDocs()) " ●" else "",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
        // ショートカットはウィンドウで受ける（UX-120）: 焦点のある部品が消費しなかったキーが、画面のどこにも焦点が無い
        // とき（ジャンプの直後など）でも届く。画面の部品の Modifier.onKeyEvent は、焦点のある子がいるときにしか呼ばれない
        onKeyEvent = { event -> state.workspace != null && handleShortcut(event, state) { commandOpen = true } },
    ) {
        AppTheme(darkMode) {
            // ネイティブのファイル・フォルダ選択（ui/FilePickers.kt）の親ウィンドウを提供する
            CompositionLocalProvider(LocalDialogParentWindow provides window) {
                Surface(Modifier.fillMaxSize(), color = Palette.EditorBg) {
                    if (state.workspace == null) {
                        StartScreen(state)
                    } else AppRoot(state, darkMode, commandOpen, { commandOpen = it }) {
                        darkMode = !darkMode
                        uiPreferences.putBoolean("darkMode", darkMode)
                    }
                }
            }
            // 初期化ウィザード（新しいワークスペースを作る）はスタート画面からも、開いている画面からも浮く
            InitWizardHost(state)
            if (confirmExit) AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("未保存の変更があります") },
                text = { Text("変更を保存せずに終了しますか？") },
                confirmButton = { TextButton(onClick = { exitApplication() }) { Text("保存せず終了", color = Palette.ErrorRed) } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("キャンセル") } },
            )
            ReloadConfirmationDialog(state)
            // 解析できない書きかけを捨てて Undo/Redo するかの確認（P43 R43-02）
            state.pendingHistory?.let { pending ->
                AlertDialog(
                    onDismissRequest = { state.pendingHistory = null },
                    title = { Text("ソースに解析できない書きかけがあります") },
                    text = {
                        Text(
                            pending.docs.joinToString("・") { state.relPath(it.file) } +
                                " の書きかけを捨てて、" + (if (pending.undo) "元に戻し" else "やり直し") + "ますか？"
                        )
                    },
                    confirmButton = { TextButton(onClick = state::confirmPendingHistory) { Text("書きかけを捨てて進める", color = Palette.ErrorRed) } },
                    dismissButton = { TextButton(onClick = { state.pendingHistory = null }) { Text("キャンセル") } },
                )
            }
            // スキーマ文書を壊れたまま保存するかの確認（P43 R43-04。波及が大きい文書だけ止める）
            state.pendingTextSave?.let { doc ->
                AlertDialog(
                    onDismissRequest = { state.pendingTextSave = null },
                    title = { Text("スキーマ文書を壊れたまま保存しますか") },
                    text = {
                        Text(
                            "${state.relPath(doc.file)} は解析できない状態です。このまま保存すると、直して保存するまで、" +
                                "このスキーマを使う全文書の検証と索引が止まります。"
                        )
                    },
                    confirmButton = { TextButton(onClick = state::confirmTextSave) { Text("壊れたまま保存", color = Palette.ErrorRed) } },
                    dismissButton = { TextButton(onClick = { state.pendingTextSave = null }) { Text("キャンセル") } },
                )
            }
        }
    }
}

/**
 * アプリ全体のキーボードショートカット（戻る／進む・保存・Undo/Redo・コマンドパレット）。
 * ウィンドウの onKeyEvent から呼ばれる＝焦点のある部品（テキスト入力など）が先に受け、消費しなかったキーだけが来る。
 */
private fun handleShortcut(event: KeyEvent, state: AppState, openPalette: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (state.fileOperationBusy) return true
    if (event.isAltPressed) {
        return when (event.key) {
            Key.DirectionLeft -> { state.goBack(); true }
            Key.DirectionRight -> { state.goForward(); true }
            else -> false
        }
    }
    if (!event.isCtrlPressed) return false
    when (event.historyShortcut()) {
        HistoryShortcut.UNDO -> { state.undoFromUi(); return true }
        HistoryShortcut.REDO -> { state.redoFromUi(); return true }
        null -> Unit
    }
    return when {
        event.key == Key.S && event.isShiftPressed -> { state.saveAll(); true }
        event.key == Key.S -> { state.saveCurrent(); true }
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
        Text(BuildInfo.display, fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(4.dp))
        Text(
            "スキーマ駆動のYAML編集・検証・エージェント実行環境",
            fontSize = 12.sp, color = Palette.SubText,
        )
        Spacer(Modifier.height(20.dp))
        val chooseWorkspace = rememberDirectoryChooser(
            FilePickers.Purpose.WORKSPACE, "ワークスペース（ディレクトリ）を選択",
            fallback = state.recentWorkspaces.firstOrNull()?.parent,
            onError = { state.status = it },
        ) { state.openWorkspace(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 新しいワークスペースを作る（初期化ウィザード。ui/InitWizard.kt）: 1 つのフォルダに 1 度だけ
            Button(onClick = { state.startInitWizard() }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新しいワークスペースを作る…", fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = chooseWorkspace) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ワークスペースを開く…", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "初めてなら「新しいワークスペースを作る…」から、テンプレートか自分の項目で始められます",
            fontSize = 11.sp, color = Palette.SubText,
        )
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
    // 右ペインの開閉は AppState（レンズの画面からも開ける）。起動時に設定から読み、変わるたびに設定へ写す
    remember { state.rightPaneOpen = uiPreferences.getBoolean("rightOpen", true); true }
    val rightOpen = state.rightPaneOpen
    LaunchedEffect(rightOpen) { uiPreferences.putBoolean("rightOpen", rightOpen) }
    var entityRenameOpen by remember { mutableStateOf(false) }
    // ワークスペース切替（R10-05）: ツールバー・コマンドパレット共通。未保存確認（W-07）を一元化する
    var pendingWorkspace by remember { mutableStateOf<Path?>(null) }
    val switchWorkspace: (Path) -> Unit = { dir ->
        if (state.hasDirtyDocs()) pendingWorkspace = dir else state.openWorkspace(dir)
    }
    // ワークスペースを開くダイアログ（ツールバー・コマンドパレット共通）。最後に選んだ場所から開く
    val openWorkspaceDialog = rememberDirectoryChooser(
        FilePickers.Purpose.WORKSPACE, "ワークスペース（ディレクトリ）を選択",
        fallback = state.workspace?.parent ?: state.recentWorkspaces.firstOrNull()?.parent,
        onError = { state.status = it },
        onChosen = switchWorkspace,
    )
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
        val watcher = state.workspace?.let { root -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileWatcher.open(root) } }
        try {
            while (true) {
                if (watcher != null) {
                    delay(250)
                    state.noteFileEvents(watcher.consumePaths())
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
        AppToolbar(state, leftOpen, rightOpen, darkMode, toggleDarkMode, { setCommandOpen(true) },
            openWorkspaceDialog = openWorkspaceDialog,
            switchWorkspace = switchWorkspace,
            toggleReadOnly = {
                state.readOnlyMode = !state.readOnlyMode
                uiPreferences.putBoolean("readOnly", state.readOnlyMode)
            },
            toggleLeft = { leftOpen = !leftOpen; uiPreferences.putBoolean("leftOpen", leftOpen) },
            toggleRight = { state.rightPaneOpen = !state.rightPaneOpen })
        HorizontalDivider(color = Palette.Border)
        HistoryNoticeBanner(state)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 実行スペースは定義が空でも常時表示する（UX-20。定義ゼロのときは RunsSpace の空状態が導線を案内する）
            ActivityRail(state, inboxCount, automation?.enabled == true)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
            when (state.uiSpace) {
                UiSpace.DASHBOARD -> Box(Modifier.weight(1f).fillMaxHeight()) {
                    DashboardPane(state)
                }
                UiSpace.RUNS -> Box(Modifier.weight(1f).fillMaxHeight()) {
                    RunsSpace(state, automation, runController, automationVersion)
                }
                UiSpace.DESIGN -> Box(Modifier.weight(1f).fillMaxHeight()) {
                    DesignSpace(state)
                }
                else -> {
                    if (leftOpen && state.uiSpace == UiSpace.EDIT) {
                        FileTreePane(state, leftWidth, onClose = { leftOpen = false; uiPreferences.putBoolean("leftOpen", false) })
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
                        // 台帳のレンズでは、文書の検証パネルの代わりにレンズの型・関係・辺を出す（描く領域を広く取る）
                        Column(Modifier.width(rightWidth).fillMaxHeight()) {
                            val lens = state.uiSpace == UiSpace.LEDGER && state.workspaceView == 2
                            PaneHeader(if (lens) "レンズ" else "検証・関係") { state.rightPaneOpen = false }
                            Box(Modifier.weight(1f)) {
                                if (lens) LensSidePanel(state, rightWidth) else SidePanel(state, rightWidth)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Border)
        StatusBar(state, automation, inboxCount) { state.showSpace(UiSpace.RUNS) }
    }
    if (state.fileHistoryBusy) AlertDialog(onDismissRequest = {}, title = { Text("ファイルを復元しています") },
        text = { Text("外部変更の確認と配置の復元を行っています…") }, confirmButton = {})
    if (commandOpen) CommandPalette(
        state, switchWorkspace, openWorkspaceDialog,
        onShowRuns = { state.showSpace(UiSpace.RUNS) },
        onRenameEntity = { entityRenameOpen = true },
    ) { setCommandOpen(false) }
    if (entityRenameOpen) EntityRenameDialog(state) { entityRenameOpen = false }
    // AI生成ダイアログはスペース非依存（R24-01）: 対象文書は要求（AiRequest）が持つため、
    // 編集スペースだけでなくエンティティページ等の台帳スペースからの✨でもその場で開く
    AiGenerationHost(state)
    // 操作のパネルもスペース非依存: 入口（エンティティページ・行のメニュー・検査の指摘・パレット）がどのスペースにあっても、その場で浮く
    ActionPanelHost(state)
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

@Composable
private fun CommandPalette(
    state: AppState,
    switchWorkspace: (Path) -> Unit,
    openWorkspaceDialog: () -> Unit,
    onShowRuns: () -> Unit,
    onRenameEntity: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val files = remember(query, state.yamlFiles, state.markdownFiles) {
        (state.yamlFiles + state.markdownFiles).filter { state.relPath(it).contains(query, true) }.take(10)
    }
    // 内蔵ヘルプ: 入力が無ければ目次だけ、あれば題名で絞る
    val helpTopics = remember(query) {
        val all = Help.topics()
        if (query.isBlank()) all.take(1) else all.filter { it.title.contains(query, true) || it.file.contains(query, true) }
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
                CommandItem("ダッシュボードを表示") { state.openDashboard(); onDismiss() }
                CommandItem("台帳: ビューグリッドを表示") { state.openViewGrid(); onDismiss() }
                CommandItem("台帳: グラフレンズを表示") { state.openGraphLens(); onDismiss() }
                CommandItem("台帳: エンティティページを表示") { state.openCrossView(); onDismiss() }
                CommandItem("台帳: 準備完了レポートを表示") { state.showWorkspaceView(6); onDismiss() }
                // 操作（architecture/actions.md）: 受け手の無いアクションと、開いている文書を受け手にするアクション（閲覧専用でも出す）
                state.actionsForWorkspace().forEach { e ->
                    val available = remember(e, state.indexVersion, state.editVersion) { state.actionAvailability(e, null) }
                    CommandItem("操作: ${e.label ?: e.name}", available.enabled, available.reason) { state.startAction(e, null); onDismiss() }
                }
                state.openDoc?.let { d ->
                    state.actionsForDocument(d.file).forEach { e ->
                        val target = state.relPath(d.file)
                        val available = remember(e, target, state.indexVersion, state.editVersion) { state.actionAvailability(e, target) }
                        CommandItem("操作: ${e.label ?: e.name}（$target）", available.enabled, available.reason) { state.startAction(e, target); onDismiss() }
                    }
                }
                CommandItem("実行スペースを表示（受信箱）") { onShowRuns(); onDismiss() }
                CommandItem("設計スペースを表示") { state.showSpace(UiSpace.DESIGN); onDismiss() }
                helpTopics.forEach { topic -> CommandItem("ヘルプ: ${topic.title}") { state.openHelp(topic.file); onDismiss() } }
                CommandItem("エンティティを改名…") { onDismiss(); onRenameEntity() }
                CommandItem("すべて保存") { state.saveAll(); onDismiss() }
                CommandItem("ワークスペースを開く…") { onDismiss(); openWorkspaceDialog() }
                CommandItem("新しいワークスペースを作る…") { onDismiss(); state.startInitWizard() }
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
private fun CommandItem(text: String, enabled: Boolean = true, reason: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(text, fontSize = 12.sp, color = if (enabled) Palette.Text else Palette.SubText)
        reason?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
    }
}

/**
 * エンティティ改名の確認ダイアログ（R21-02。architecture/graph-query.md「エンティティ操作」）。
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

// ================================================================ エディタ領域

@Composable
fun EditorArea(state: AppState) {
    val doc = state.openDoc
    if (doc == null) {
        // Markdown 文書・内蔵ヘルプ・画像は表示だけの画面（ui/MarkdownPane.kt・ui/ImagePane.kt）
        if (state.openPreview != null) { FilePreviewPane(state); return }
        if (state.openImage != null) {
            ImagePane(state)
            return
        }
        if (state.openMarkdown != null || state.openHelp != null) {
            MarkdownPane(state)
            return
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 未初期化（定義が何もない）フォルダを開いたときの案内。モーダルにせず、ここから初期化ウィザードへ誘導する
            state.indexVersion
            if (state.isUninitializedWorkspace() && !state.memory.initBannerDismissed) {
                Column(
                    Modifier.width(560.dp).background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape).padding(16.dp),
                ) {
                    Text("このフォルダにはまだ定義がありません", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「新しいワークスペースを作る…」で、テンプレートか自分の項目から始められます。" +
                            "既に YAML があるなら、台帳の宣言だけを後入れしてスキーマを推測する道もあります。" +
                            "このまま手で作るなら、設計スペースの「スキーマ」からです。",
                        fontSize = 12.sp, color = Palette.SubText,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(onClick = { state.workspace?.let { state.startInitWizard(it) } }) { Text("新しいワークスペースを作る…", fontSize = 12.sp) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { state.memory.initBannerDismissed = true }) { Text("このまま使う", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Text("ファイルツリーから YAML か Markdown を選択してください", color = Palette.SubText, fontSize = 14.sp)
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
                TextButton(onClick = { state.keepLocalVersion() }) { Text("次回保存で上書きを許可", fontSize = 12.sp) }
            }
        }
        EditorDocumentHeader(state, doc,
            if (isSchemaFile) doc.file.fileName.toString() else documentName ?: doc.file.fileName.toString())
        // スキーマ文書ではタブ3にメタスキーマ（R11-02）のフォーム編集を追加する（設計UIが主要UXのまま）
        val tab = if (!isSchemaFile && state.editorTab == 3) 0 else state.editorTab
        // ソースの書きかけ・壊れたまま保存の案内（P43 R43-02・04）。ソースタブ自身は欄の上に出すので除く
        if (tab != 1) SourceDraftBanner(state, doc)
        EditorModeBar(state, doc, isSchemaFile, tab)
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
        state.historyNotice?.takeUnless { it.crossDocument }?.let { notice ->
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
                    files = state.filePolicy, fileExists = state::fileExists,
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
