package dev.toros.yde.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.desktop.ext.DesktopExtensions
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.toros.yde.desktop.lifecycle.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import dev.toros.yde.desktop.ui.common.AppTheme
import dev.toros.yde.desktop.ui.common.HistoryShortcut
import dev.toros.yde.desktop.ui.common.LocalDialogParentWindow
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.historyShortcut
import dev.toros.yde.desktop.ui.shell.AppRoot
import dev.toros.yde.desktop.ui.shell.InitWizardHost
import dev.toros.yde.desktop.ui.shell.ReloadConfirmationDialog
import dev.toros.yde.desktop.ui.shell.StartScreen
import dev.toros.yde.desktop.ui.shell.LocalConnectionSettingsPage
import dev.toros.yde.desktop.ui.shell.AwtDesktopTray
import dev.toros.yde.desktop.ui.shell.ResidentNotice

fun main(args: Array<String>) = application {
    Extensions.install(DesktopExtensions.registry)  // 拡張（Excel・Word の形式）の登録は入口の一覧 1 ファイル（app-core に POI を入れない。組込みは最初から入っている）
    val runtime = remember { DesktopRuntime() }
    val state = runtime.state
    val appScope = rememberCoroutineScope()
    val launch = remember { runCatching { DesktopLaunch.parse(args) } }
    val trayCommands = remember { Channel<TrayCommand>(Channel.UNLIMITED) }
    val presence = remember { DesktopPresence(AwtDesktopTray::open) { trayCommands.trySend(it) } }
    val display by presence.status.collectAsState()
    val settings by runtime.connectionSettings.state.collectAsState()
    var appliedResident by remember { mutableStateOf<ResidentSettings?>(null) }
    val stop: () -> Unit = {
        appScope.launch {
            try { runtime.shutdown(); presence.close(); exitApplication() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = "終了できませんでした: ${failure.message}"
                state.status = message
                presence.exitFailed(message)
            }
        }
    }
    val quit: () -> Unit = { if (presence.requestExit(state.hasDirtyDocs())) stop() }
    DisposableEffect(runtime) { onDispose { presence.close(); trayCommands.close(); runtime.cancel() } }
    var darkMode by remember { mutableStateOf(uiPreferences.getBoolean("darkMode", false)) }
    var commandOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(runtime) {
        runtime.connectionSettings.initialize()
        state.readOnlyMode = uiPreferences.getBoolean("readOnly", false)
        var failure = launch.exceptionOrNull()?.message
        val options = launch.getOrDefault(DesktopLaunch())
        options.workspace?.let { if (!runtime.openWorkspace(it)) failure = state.status }
        val resident = runtime.connectionSettings.state.value.resident
        appliedResident = resident
        val blocked = runtime.connectionSettings.state.value.blocked
        if (blocked) settingsOpen = true
        presence.start(resident, if (blocked) StartupVisibility.SHOW else options.visibility, failure)
    }
    LaunchedEffect(settings.resident, display.ready) {
        if (display.ready && appliedResident != settings.resident) {
            appliedResident = settings.resident
            presence.configure(settings.resident)
        }
    }
    LaunchedEffect(runtime) {
        snapshotFlow { (state.workspace != null) to state.pendingInboxCount() }
            .collect { (opened, count) -> presence.updateInbox(opened, count) }
    }
    LaunchedEffect(trayCommands) {
        for (command in trayCommands) {
            if (presence.status.value.exiting && command != TrayCommand.SHOW) continue
            when (command) {
                TrayCommand.SHOW -> presence.show()
                TrayCommand.HIDE -> presence.hide()
                TrayCommand.INBOX -> {
                    settingsOpen = false
                    if (state.workspace != null) state.openInbox()
                    presence.show()
                }
                TrayCommand.SETTINGS -> { settingsOpen = true; presence.show() }
                TrayCommand.EXIT -> quit()
            }
        }
    }

    val windowState = rememberWindowState(width = 1440.dp, height = 920.dp)
    if (display.windowCreated) Window(
        onCloseRequest = {
            if (!display.exiting && !display.confirmExit) {
                if (display.trayActive) presence.hide() else quit()
            }
        },
        title = (state.workspace?.toAbsolutePath()?.normalize()?.let { "$it — " } ?: "") +
            "YAML Design Editor" + if (state.hasDirtyDocs()) " ●" else "",
        state = windowState,
        visible = display.visible,
        // ショートカットはウィンドウで受ける（UX-120）: 焦点のある部品が消費しなかったキーが、画面のどこにも焦点が無い
        // とき（ジャンプの直後など）でも届く。画面の部品の Modifier.onKeyEvent は、焦点のある子がいるときにしか呼ばれない
        onKeyEvent = { event -> !settingsOpen && state.workspace != null && handleShortcut(event, state) { commandOpen = true } },
    ) {
        LaunchedEffect(display.reveal, display.visible) {
            if (display.visible) { windowState.isMinimized = false; window.toFront(); window.requestFocus() }
        }
        AppTheme(darkMode) {
            // ネイティブのファイル・フォルダ選択（ui/common/FilePickers.kt）の親ウィンドウを提供する
            CompositionLocalProvider(LocalDialogParentWindow provides window) {
                Surface(Modifier.fillMaxSize(), color = Palette.EditorBg) {
                    Column(Modifier.fillMaxSize()) {
                        display.error?.let { ResidentNotice(it, presence::dismissError) }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (settingsOpen) {
                                val workspaceStatus by runtime.host.status.collectAsState()
                                LocalConnectionSettingsPage(runtime.connectionSettings, workspaceStatus.workspace?.toString(),
                                    presence = display, onHide = { presence.hide() },
                                    onResidentChanged = { appliedResident = it; presence.configure(it) }) { settingsOpen = false }
                            } else if (state.workspace == null) {
                                StartScreen(state, openSettings = { settingsOpen = true })
                            } else AppRoot(state, darkMode, commandOpen, { commandOpen = it }, openSettings = { settingsOpen = true }) {
                                darkMode = !darkMode
                                uiPreferences.putBoolean("darkMode", darkMode)
                            }
                        }
                    }
                }
            }
            // 非表示中に処理中・確認の別ウィンドウだけを出さない。確認の状態は保持する。
            if (display.visible) {
                // 初期化ウィザード（新しいワークスペースを作る）はスタート画面からも、開いている画面からも浮く
                InitWizardHost(state)
                if (display.confirmExit) AlertDialog(
                    onDismissRequest = presence::cancelExit,
                    title = { Text("未保存の変更があります") },
                    text = { Text("変更を保存せずに終了しますか？") },
                    confirmButton = { TextButton(onClick = { if (presence.requestExit(false)) stop() }) { Text("保存せず終了", color = Palette.ErrorRed) } },
                    dismissButton = { TextButton(onClick = presence::cancelExit) { Text("キャンセル") } },
                )
                ReloadConfirmationDialog(state)
                if (state.fileOperationBusy || display.exiting) AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (display.exiting) "終了しています" else "処理しています") },
                    text = { Text(runtime.host.status.value.busy ?: "処理の完了を待っています") },
                    confirmButton = {},
                )
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
