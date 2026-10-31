package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import dev.toros.yde.agent.auto.AutomationEngine
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
import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.workspace.layout.WorkspaceLayout
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Window
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import java.nio.file.Path
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.EditorTab
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.ui.action.ActionPanelHost
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.DiffPane
import dev.toros.yde.desktop.ui.common.FilePickers
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PaneHeader
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.rememberDirectoryChooser
import dev.toros.yde.desktop.ui.file.FileEditor
import dev.toros.yde.desktop.ui.file.FilePreviewPane
import dev.toros.yde.desktop.ui.file.FileTreePane
import dev.toros.yde.desktop.ui.file.ImagePane
import dev.toros.yde.desktop.ui.file.MarkdownPane
import dev.toros.yde.desktop.ui.form.FormSlots
import dev.toros.yde.desktop.ui.form.FormView
import dev.toros.yde.desktop.ui.form.LocalFormSlots
import dev.toros.yde.desktop.ui.graph.EntityPage
import dev.toros.yde.desktop.ui.graph.LensSidePanel
import dev.toros.yde.desktop.ui.run.AiGenerationHost
import dev.toros.yde.desktop.ui.run.RunsSpace
import dev.toros.yde.desktop.ui.schema.SchemaEditor
import dev.toros.yde.desktop.ui.source.SourceDraftBanner
import dev.toros.yde.desktop.ui.source.SourcePane
import dev.toros.yde.desktop.ui.view.DashboardPane
import dev.toros.yde.desktop.ui.view.EmbeddedExtensionPane
import dev.toros.yde.desktop.ui.view.ResolvedViewGrid
import dev.toros.yde.desktop.uiPreferences

/** フォームの差し込み口の中身。file 型の欄は ui.file、文書ビューに埋め込む拡張のビューと表は ui.view が描く。 */
private val appFormSlots = FormSlots(
    fileField = { ctx, field, slot, path, preview -> FileEditor(ctx, field, slot, path, preview) },
    embeddedView = { state, doc, embed -> EmbeddedExtensionPane(state, doc, embed) },
    embeddedGrid = { state, view, rows -> ResolvedViewGrid(state, view, rows) },
)

@Composable
fun AppRoot(
    state: AppState,
    darkMode: Boolean,
    commandOpen: Boolean,
    setCommandOpen: (Boolean) -> Unit,
    openSettings: () -> Unit = {},
    toggleDarkMode: () -> Unit,
) {
    CompositionLocalProvider(LocalFormSlots provides appFormSlots) {
        AppRootContent(state, darkMode, commandOpen, setCommandOpen, openSettings, toggleDarkMode)
    }
}

@Composable
private fun AppRootContent(
    state: AppState,
    darkMode: Boolean,
    commandOpen: Boolean,
    setCommandOpen: (Boolean) -> Unit,
    openSettings: () -> Unit,
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
    val automationVersion = state.automationVersion
    val automation = state.automation
    val runController = state.runController
    // ショートカット（Alt+←/→・Ctrl+S・Ctrl+Z・Ctrl+K）は main の Window.onKeyEvent（handleShortcut）で受ける（UX-120）
    Column(Modifier.fillMaxSize()) {
        automationVersion  // エンジン状態の変化でレール・受信箱バッジ・ステータスバーを再描画する
        // 受信箱の件数（R12-02）: 承認待ち変更セット＋エージェントの質問＋外部変更の競合
        val inboxCount = (automation?.pendingSnapshot()?.size ?: 0) +
            state.conflictedDocs().size +
            (if (runController?.pendingQuestion != null) 1 else 0) + (state.externalWork?.pendingCount ?: 0)
        AppToolbar(state, leftOpen, rightOpen, darkMode, toggleDarkMode, { setCommandOpen(true) },
            openSettings = openSettings,
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
                    DashboardPane(state, entityPage = { EntityPage(state, it, business = true) })
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
        // Markdown 文書・内蔵ヘルプ・画像は表示だけの画面（ui/file/MarkdownPane.kt・ui/file/ImagePane.kt）
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
        // ビューのタブ: 台帳の文書だけに出し（スキーマの文書・台帳の外は null）、当たる文書ビューを定義ファイル名順に並べる。
        // 選んだもの（覚え）が今は当たらなければ先頭（定義ファイル名順の先勝ち＝今までの既定）
        val document = state.documentOf(doc.file)
        val rel = state.relPath(doc.file)
        val ledgerDocument = !isSchemaZone && state.layout.inLedger(rel)
        val docViews = remember(state.indexVersion, state.editVersion, doc.file, doc.schema, ledgerDocument) {
            if (ledgerDocument) state.docViewsFor(doc.file) else emptyList()
        }
        val chosenView = docViews.firstOrNull { it.id == state.memory.docViewChoice[document] } ?: docViews.firstOrNull()
        // 今の文書で出せるタブに直して描く（ビューが無くなった・スキーマ文書でない文書のメタスキーマのフォーム → フォーム）。覚えは変えない
        val tab = EditorTab.fit(state.editorTab, isSchemaFile, docViews.isNotEmpty())
        // ソースの書きかけ・壊れたまま保存の案内（P43 R43-02・04）。ソースタブ自身は欄の上に出すので除く
        if (tab != EditorTab.SOURCE) SourceDraftBanner(state, doc)
        EditorModeBar(state, doc, isSchemaFile, tab, docViews = if (ledgerDocument) docViews else null, chosenView = chosenView)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                EditorTab.FORM -> if (isSchemaFile) SchemaEditor(state, doc) else FormView(state, doc)
                EditorTab.VIEW -> FormView(state, doc, docView = chosenView)
                EditorTab.SOURCE -> SourcePane(state, doc)
                EditorTab.DIFF -> DiffPane(doc)
                EditorTab.META_FORM -> FormView(state, doc)
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
