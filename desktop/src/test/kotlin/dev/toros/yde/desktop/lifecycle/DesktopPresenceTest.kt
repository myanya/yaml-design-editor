package dev.toros.yde.desktop.lifecycle

import java.nio.file.Path
import kotlin.test.*

class DesktopPresenceTest {
    private class Tray : DesktopTray {
        val updates = mutableListOf<DesktopTrayStatus>()
        var closed = false
        var failUpdate = false
        var beforeClose: () -> Unit = {}
        override fun update(status: DesktopTrayStatus) {
            check(!failUpdate) { "更新失敗" }
            updates += status
        }
        override fun close() { beforeClose(); closed = true }
    }

    @Test fun `起動引数は一つのワークスペースと一時的な表示指定だけを受ける`() {
        assertEquals(DesktopLaunch(), DesktopLaunch.parse(emptyArray()))
        assertEquals(DesktopLaunch(Path.of("my workspace"), StartupVisibility.HIDDEN),
            DesktopLaunch.parse(arrayOf("my workspace", "--hidden")))
        assertEquals(DesktopLaunch(Path.of("--show"), StartupVisibility.SHOW),
            DesktopLaunch.parse(arrayOf("--show", "--", "--show")))
        listOf(arrayOf("--show", "--hidden"), arrayOf("--hidden", "--hidden"), arrayOf("--unknown"), arrayOf("a", "b"))
            .forEach { assertFailsWith<IllegalArgumentException> { DesktopLaunch.parse(it) } }
        assertFailsWith<IllegalArgumentException> { ResidentSettings(startHidden = true) }
    }

    @Test fun `既定では窓だけを開き常駐なしでは隠せない`() {
        val p = DesktopPresence({ error("トレイを作ってはいけない") }, {})
        p.start(ResidentSettings(), StartupVisibility.DEFAULT)
        assertTrue(p.status.value.windowCreated)
        assertTrue(p.status.value.visible)
        assertFalse(p.hide())
        assertTrue(p.requestExit(false))
        assertFalse(p.requestExit(false), "終了は一度だけ要求する")
        p.close()
    }

    @Test fun `非表示起動は窓を作らず受信箱の更新と復帰を受け付ける`() {
        val tray = Tray()
        val commands = mutableListOf<TrayCommand>()
        lateinit var callback: (TrayCommand) -> Unit
        val p = DesktopPresence({ callback = it; tray }, commands::add)
        p.updateInbox(true, 2)
        p.start(ResidentSettings(true, true), StartupVisibility.DEFAULT)
        assertFalse(p.status.value.windowCreated)
        assertFalse(p.status.value.visible)
        p.updateInbox(true, 3)
        assertEquals(DesktopTrayStatus(false, true, 3, false), tray.updates.last())
        callback(TrayCommand.INBOX)
        assertEquals(listOf(TrayCommand.INBOX), commands)
        p.show()
        assertTrue(p.status.value.windowCreated)
        assertTrue(p.hide())
        assertTrue(p.status.value.windowCreated, "一度作った窓の状態は隠しても保持する")
        val reveal = p.status.value.reveal
        p.show()
        assertTrue(p.status.value.reveal > reveal)
        p.close()
        assertTrue(tray.closed)
    }

    @Test fun `起動時の表示指定は保存設定より優先し今回だけ常駐できる`() {
        val hidden = DesktopPresence({ Tray() }, {})
        hidden.start(ResidentSettings(), StartupVisibility.HIDDEN)
        assertTrue(hidden.status.value.temporaryTray)
        assertFalse(hidden.status.value.visible)
        hidden.configure(ResidentSettings())
        assertFalse(hidden.status.value.temporaryTray)
        assertTrue(hidden.status.value.visible)
        assertFalse(hidden.status.value.trayActive)
        hidden.close()
        val shown = DesktopPresence({ Tray() }, {})
        shown.start(ResidentSettings(true, true), StartupVisibility.SHOW)
        assertTrue(shown.status.value.visible)
        assertTrue(shown.status.value.trayActive)
        shown.close()
    }

    @Test fun `トレイを登録できない環境と起動エラーでは必ず画面を出す`() {
        val unavailable = DesktopPresence({ error("未対応または登録失敗") }, {})
        unavailable.start(ResidentSettings(true, true), StartupVisibility.DEFAULT)
        assertTrue(unavailable.status.value.visible)
        assertFalse(unavailable.status.value.trayActive)
        assertNotNull(unavailable.status.value.error)
        val failed = DesktopPresence({ Tray() }, {})
        failed.start(ResidentSettings(true, true), StartupVisibility.DEFAULT, "ワークスペースを開けません")
        assertTrue(failed.status.value.visible)
        assertEquals("ワークスペースを開けません", failed.status.value.error)
        failed.close()
    }

    @Test fun `常駐を止める前に画面へ戻し解除失敗時も復帰経路を残す`() {
        val tray = Tray()
        val p = DesktopPresence({ tray }, {})
        p.start(ResidentSettings(true, true), StartupVisibility.DEFAULT)
        tray.beforeClose = { assertTrue(p.status.value.visible); error("解除失敗") }
        p.configure(ResidentSettings())
        assertTrue(p.status.value.visible)
        assertTrue(p.status.value.trayActive)
        assertNotNull(p.status.value.error)
        tray.beforeClose = { assertTrue(p.status.value.visible) }
        p.configure(ResidentSettings())
        assertTrue(tray.closed)
        assertFalse(p.status.value.trayActive)
        assertNull(p.status.value.error)
        p.close()
    }

    @Test fun `非表示中にトレイ更新が失敗しても画面から操作を続けられる`() {
        val tray = Tray()
        val p = DesktopPresence({ tray }, {})
        p.start(ResidentSettings(true, true), StartupVisibility.DEFAULT)
        tray.failUpdate = true
        p.updateInbox(true, 1)
        assertTrue(p.status.value.visible)
        assertTrue(p.status.value.windowCreated)
        assertFalse(p.status.value.trayActive)
        assertTrue(tray.closed)
        assertNotNull(p.status.value.error)
        assertFalse(p.hide())
        p.close()
    }

    @Test fun `未保存なら画面へ戻して終了を確認し中断と終了失敗から復帰できる`() {
        val tray = Tray()
        val p = DesktopPresence({ tray }, {})
        p.start(ResidentSettings(true, true), StartupVisibility.DEFAULT)
        assertFalse(p.requestExit(true))
        assertTrue(p.status.value.visible)
        assertTrue(p.status.value.confirmExit)
        assertFalse(p.hide())
        p.cancelExit()
        assertFalse(p.status.value.exiting)
        assertTrue(p.hide())
        assertFalse(p.requestExit(true))
        assertTrue(p.requestExit(false))
        assertFalse(p.hide())
        assertTrue(tray.updates.last().exiting)
        p.exitFailed("保存処理を終了できません")
        assertFalse(p.status.value.exiting)
        assertTrue(p.status.value.visible)
        assertFalse(p.status.value.confirmExit)
        assertTrue(p.requestExit(false))
        p.close()
    }
}
