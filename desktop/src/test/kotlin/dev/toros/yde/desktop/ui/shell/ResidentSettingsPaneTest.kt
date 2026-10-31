package dev.toros.yde.desktop.ui.shell

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.toros.yde.desktop.lifecycle.PresenceStatus
import dev.toros.yde.desktop.lifecycle.ResidentSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ResidentSettingsPaneTest {
    @Test fun `常駐を切ると非表示起動も解除し実際に登録できるまで隠せない`() = runComposeUiTest {
        var settings by mutableStateOf(ResidentSettings())
        var actual by mutableStateOf(PresenceStatus())
        var hidden = false
        setContent { ResidentSettingsPane(settings, actual, true, { settings = it }, { hidden = true }) }
        onNodeWithContentDescription("画面を表示せずに起動する").assertIsNotEnabled()
        onNodeWithText("画面を隠す").assertIsNotEnabled()
        onNodeWithContentDescription("トレイ常駐を有効にする").performClick()
        onNodeWithContentDescription("画面を表示せずに起動する").performClick()
        runOnIdle { assertEquals(ResidentSettings(true, true), settings); actual = PresenceStatus(trayActive = true) }
        onNodeWithText("画面を隠す").performClick()
        runOnIdle { assertTrue(hidden) }
        onNodeWithContentDescription("トレイ常駐を有効にする").performClick()
        onNodeWithContentDescription("画面を表示せずに起動する").assertIsOff().assertIsNotEnabled()
        runOnIdle { assertEquals(ResidentSettings(), settings) }
    }

    @Test fun `今回だけの常駐も設定画面から解除できる`() = runComposeUiTest {
        var changed: ResidentSettings? = null
        setContent {
            ResidentSettingsPane(ResidentSettings(), PresenceStatus(trayActive = true, temporaryTray = true),
                true, { changed = it }, {})
        }
        onNodeWithContentDescription("トレイ常駐を有効にする").assertIsOn().performClick()
        runOnIdle { assertEquals(ResidentSettings(), changed) }
    }
}
