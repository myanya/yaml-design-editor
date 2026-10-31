package dev.toros.yde.desktop.lifecycle

import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ResidentSettings(val trayEnabled: Boolean = false, val startHidden: Boolean = false) {
    init { require(!startHidden || trayEnabled) { "画面非表示で起動するにはトレイ常駐を有効にしてください" } }
}

internal enum class StartupVisibility { DEFAULT, SHOW, HIDDEN }
internal data class DesktopLaunch(val workspace: Path? = null, val visibility: StartupVisibility = StartupVisibility.DEFAULT) {
    companion object {
        fun parse(args: Array<String>): DesktopLaunch {
            var path: Path? = null
            var visibility = StartupVisibility.DEFAULT
            var literal = false
            args.forEach { arg ->
                if (!literal && arg == "--") literal = true
                else if (!literal && arg in setOf("--hidden", "--show")) {
                    require(visibility == StartupVisibility.DEFAULT) { "--hidden と --show はどちらか一つだけ指定してください" }
                    visibility = if (arg == "--hidden") StartupVisibility.HIDDEN else StartupVisibility.SHOW
                } else {
                    require(literal || !arg.startsWith("--")) { "未対応の起動オプションです: $arg" }
                    require(path == null) { "ワークスペースは一つだけ指定してください" }
                    path = Path.of(arg)
                }
            }
            return DesktopLaunch(path, visibility)
        }
    }
}

internal enum class TrayCommand { SHOW, HIDE, INBOX, SETTINGS, EXIT }
internal data class DesktopTrayStatus(val visible: Boolean, val hasWorkspace: Boolean, val inboxCount: Int, val exiting: Boolean)
internal interface DesktopTray : AutoCloseable { fun update(status: DesktopTrayStatus) }
internal data class PresenceStatus(
    val ready: Boolean = false, val windowCreated: Boolean = false, val visible: Boolean = false,
    val trayActive: Boolean = false, val exiting: Boolean = false, val confirmExit: Boolean = false,
    val reveal: Long = 0, val error: String? = null, val temporaryTray: Boolean = false,
)

/** 表示と常駐の寿命。所有スレッドから呼ぶ。接続・セッションへの依存を持たない。 */
internal class DesktopPresence(
    private val openTray: ((TrayCommand) -> Unit) -> DesktopTray,
    private val command: (TrayCommand) -> Unit,
) : AutoCloseable {
    private val mutableStatus = MutableStateFlow(PresenceStatus())
    val status = mutableStatus.asStateFlow()
    private var tray: DesktopTray? = null
    private var hasWorkspace = false
    private var inboxCount = 0

    fun start(settings: ResidentSettings, visibility: StartupVisibility, error: String? = null) {
        check(!status.value.ready) { "表示の初期化は一度だけ行ってください" }
        val hidden = visibility == StartupVisibility.HIDDEN || visibility == StartupVisibility.DEFAULT && settings.startHidden
        configure(settings.copy(trayEnabled = settings.trayEnabled || visibility == StartupVisibility.HIDDEN))
        val visible = !hidden || tray == null || error != null
        mutableStatus.value = status.value.copy(ready = true, visible = visible, windowCreated = visible,
            error = error ?: status.value.error, temporaryTray = visibility == StartupVisibility.HIDDEN && !settings.trayEnabled)
        updateTray()
    }

    fun configure(settings: ResidentSettings) {
        if (status.value.exiting) return
        mutableStatus.value = status.value.copy(temporaryTray = false)
        if (settings.trayEnabled && tray == null) {
            try {
                tray = openTray { action -> command(action) }
                mutableStatus.value = status.value.copy(trayActive = true, error = null)
            } catch (_: Exception) {
                fail("この環境ではトレイを利用できません。画面を表示して続行します。")
            }
        } else if (!settings.trayEnabled && tray != null) {
            show() // 復帰経路を消す前に画面を戻す。
            try {
                tray?.close(); tray = null
                mutableStatus.value = status.value.copy(trayActive = false, error = null)
            } catch (_: Exception) { fail("トレイを停止できませんでした。画面を表示しています。もう一度切り替えてください。") }
        }
        updateTray()
    }

    fun updateInbox(hasWorkspace: Boolean, count: Int) {
        this.hasWorkspace = hasWorkspace
        inboxCount = count.coerceAtLeast(0)
        updateTray()
    }

    fun show() {
        mutableStatus.value = status.value.copy(windowCreated = true, visible = true, reveal = status.value.reveal + 1)
        updateTray()
    }

    fun hide(): Boolean {
        if (!status.value.ready || tray == null || status.value.exiting || status.value.confirmExit) return false
        mutableStatus.value = status.value.copy(visible = false)
        updateTray()
        return !status.value.visible
    }

    /** true のときだけ Runtime の終了を呼ぶ。未保存確認を表示するときは必ず画面へ戻る。 */
    fun requestExit(hasDirtyDocuments: Boolean): Boolean {
        if (status.value.exiting) return false
        if (hasDirtyDocuments) {
            show()
            mutableStatus.value = status.value.copy(confirmExit = true)
            return false
        }
        mutableStatus.value = status.value.copy(exiting = true, confirmExit = false)
        updateTray()
        return true
    }

    fun cancelExit() { mutableStatus.value = status.value.copy(confirmExit = false) }
    fun dismissError() { mutableStatus.value = status.value.copy(error = null) }
    fun exitFailed(message: String) {
        mutableStatus.value = status.value.copy(exiting = false, confirmExit = false)
        fail(message)
    }

    fun fail(message: String) {
        mutableStatus.value = status.value.copy(error = message, visible = true, windowCreated = true,
            reveal = status.value.reveal + 1)
    }

    private fun updateTray() {
        try { tray?.update(DesktopTrayStatus(status.value.visible, hasWorkspace, inboxCount, status.value.exiting)) }
        catch (_: Exception) {
            runCatching { tray?.close() }; tray = null
            mutableStatus.value = status.value.copy(trayActive = false)
            fail("トレイの表示を更新できませんでした。画面を表示して続行します。")
        }
    }

    override fun close() {
        runCatching { tray?.close() }; tray = null
        mutableStatus.value = status.value.copy(trayActive = false)
    }
}
