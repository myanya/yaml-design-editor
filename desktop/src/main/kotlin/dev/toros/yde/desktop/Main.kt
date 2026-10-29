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
import java.nio.file.Files
import java.nio.file.Path
import dev.toros.yde.desktop.ui.common.AppTheme
import dev.toros.yde.desktop.ui.common.HistoryShortcut
import dev.toros.yde.desktop.ui.common.LocalDialogParentWindow
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.historyShortcut
import dev.toros.yde.desktop.ui.shell.AppRoot
import dev.toros.yde.desktop.ui.shell.InitWizardHost
import dev.toros.yde.desktop.ui.shell.ReloadConfirmationDialog
import dev.toros.yde.desktop.ui.shell.StartScreen

fun main(args: Array<String>) = application {
    Extensions.install(DesktopExtensions.registry)  // 拡張（入出力の形式・ビューの種類）の登録は入口の一覧 1 ファイル（app-core に POI を入れない）
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
            // ネイティブのファイル・フォルダ選択（ui/common/FilePickers.kt）の親ウィンドウを提供する
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
