package dev.toros.yde.desktop.ui.shell

import androidx.compose.ui.graphics.toArgb
import dev.toros.yde.desktop.lifecycle.DesktopTray
import dev.toros.yde.desktop.lifecycle.DesktopTrayStatus
import dev.toros.yde.desktop.lifecycle.TrayCommand
import dev.toros.yde.desktop.ui.common.Palette
import java.awt.*
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

/** ネイティブの型はこの部品へ閉じる。登録の成否を返してから画面を隠せるようにする。 */
internal class AwtDesktopTray private constructor(
    private val system: SystemTray,
    internal val icon: TrayIcon,
    private val items: Map<TrayCommand, MenuItem>,
) : DesktopTray {
    private var closed = false

    override fun update(status: DesktopTrayStatus) {
        check(EventQueue.isDispatchThread()) { "トレイは画面の所有スレッドから操作してください" }
        check(!closed && system.trayIcons.any { it === icon }) { "トレイが解除されました" }
        val suffix = if (status.inboxCount > 0) " / 受信箱 ${status.inboxCount} 件" else ""
        icon.toolTip = "YAML Design Editor$suffix${if (status.exiting) " / 終了中" else ""}"
        icon.image = trayImage(status.inboxCount > 0)
        items.getValue(TrayCommand.INBOX).label = "受信箱を開く（${status.inboxCount}）"
        items.getValue(TrayCommand.EXIT).label = if (status.exiting) "終了中…" else "完全終了"
        items.forEach { (command, item) ->
            item.isEnabled = when (command) {
                TrayCommand.SHOW -> true
                TrayCommand.HIDE -> status.visible && !status.exiting
                TrayCommand.INBOX -> status.hasWorkspace && !status.exiting
                TrayCommand.SETTINGS, TrayCommand.EXIT -> !status.exiting
            }
        }
    }

    override fun close() {
        if (closed) return
        check(EventQueue.isDispatchThread()) { "トレイは画面の所有スレッドから操作してください" }
        system.remove(icon)
        closed = true
        icon.actionListeners.forEach(icon::removeActionListener)
        icon.popupMenu = null
    }

    companion object {
        fun open(action: (TrayCommand) -> Unit): AwtDesktopTray {
            check(EventQueue.isDispatchThread()) { "トレイは画面の所有スレッドから操作してください" }
            check(SystemTray.isSupported()) { "システムトレイを利用できません" }
            val system = SystemTray.getSystemTray()
            val popup = PopupMenu()
            val labels = linkedMapOf(TrayCommand.SHOW to "画面を表示", TrayCommand.HIDE to "画面を隠す",
                TrayCommand.INBOX to "受信箱を開く（0）", TrayCommand.SETTINGS to "接続・常駐の設定", TrayCommand.EXIT to "完全終了")
            val items = labels.mapValues { (command, label) ->
                if (command == TrayCommand.EXIT) popup.addSeparator()
                MenuItem(label).also { item -> item.addActionListener { action(command) }; popup.add(item) }
            }
            val icon = TrayIcon(trayImage(false), "YAML Design Editor", popup).apply {
                isImageAutoSize = true
                addActionListener { action(TrayCommand.SHOW) }
            }
            try { system.add(icon) }
            catch (failure: Exception) { system.remove(icon); icon.popupMenu = null; throw failure }
            return AwtDesktopTray(system, icon, items)
        }
    }
}

/** 文書の印を複数解像度で描く。色はアプリの Palette、受信箱の件数はメニューとツールチップへ出す。 */
private fun trayImage(pending: Boolean): Image = BaseMultiResolutionImage(*intArrayOf(16, 32, 64).map { size ->
    BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { image ->
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(size / 64.0, size / 64.0)
            g.color = Color(Palette.Primary.toArgb(), true)
            g.fillRoundRect(4, 4, 56, 56, 12, 12)
            g.color = Color(Palette.PanelBg.toArgb(), true)
            g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(16, 19, 46, 19); g.drawLine(16, 31, 40, 31); g.drawLine(16, 43, 34, 43)
            if (pending) {
                g.fillOval(40, 40, 24, 24)
                g.color = Color(Palette.WarnOrange.toArgb(), true)
                g.fillOval(44, 44, 16, 16)
            }
        } finally { g.dispose() }
    }
}.toTypedArray())
