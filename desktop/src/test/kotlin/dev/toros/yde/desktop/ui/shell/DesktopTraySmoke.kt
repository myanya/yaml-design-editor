package dev.toros.yde.desktop.ui.shell

import dev.toros.yde.desktop.lifecycle.DesktopTrayStatus
import dev.toros.yde.desktop.lifecycle.TrayCommand
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.SystemTray
import java.awt.event.ActionEvent

/** 対話セッションでだけ実行する実トレイの登録・更新・解除検査。キー入力や他の窓の操作はしない。 */
object DesktopTraySmoke {
    @JvmStatic fun main(args: Array<String>) {
        EventQueue.invokeAndWait {
            check(SystemTray.isSupported()) { "この環境ではトレイを検査できません" }
            val system = SystemTray.getSystemTray()
            val before = system.trayIcons.toSet()
            val commands = mutableListOf<TrayCommand>()
            val tray = AwtDesktopTray.open(commands::add)
            try {
                check(system.trayIcons.toSet() == before + tray.icon)
                tray.update(DesktopTrayStatus(false, true, 3, false))
                check(tray.icon.toolTip.contains("受信箱 3 件"))
                val popup = tray.icon.popupMenu
                val menu = (0 until popup.itemCount).map(popup::getItem).associateBy(MenuItem::getLabel)
                check(menu.getValue("画面を表示").isEnabled)
                check(!menu.getValue("画面を隠す").isEnabled)
                val inbox = menu.getValue("受信箱を開く（3）")
                inbox.actionListeners.forEach { it.actionPerformed(ActionEvent(inbox, ActionEvent.ACTION_PERFORMED, "")) }
                check(commands == listOf(TrayCommand.INBOX))
                tray.icon.actionListeners.forEach { it.actionPerformed(ActionEvent(tray.icon, ActionEvent.ACTION_PERFORMED, "")) }
                check(commands.last() == TrayCommand.SHOW)
                tray.update(DesktopTrayStatus(true, false, 0, true))
                check(!inbox.isEnabled)
                check(!menu.getValue("完全終了").isEnabled)
                check(menu.getValue("完全終了").label == "終了中…")
                check(tray.icon.image.getWidth(null) > 0)
            } finally { tray.close() }
            check(system.trayIcons.toSet() == before)
            tray.close()
            println("トレイの登録・件数表示・コマンド通知・解除を確認しました")
        }
    }
}
