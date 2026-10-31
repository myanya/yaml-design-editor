package dev.toros.yde.desktop.ui.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import dev.toros.yde.app.service.WorkspaceInUseException
import dev.toros.yde.app.service.WorkspaceOwnership
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceOpenNoticeTest {
    private val temp = Files.createTempDirectory("workspace-open-notice")
    @AfterTest fun cleanup() { temp.toFile().deleteRecursively() }

    private fun recent(path: Path): AutoCloseable {
        val prefs = Preferences.userRoot().node("yaml-design-editor/app")
        val previous = prefs.get("recentWorkspaces", null)
        prefs.put("recentWorkspaces", path.toString())
        return AutoCloseable {
            if (previous == null) prefs.remove("recentWorkspaces") else prefs.put("recentWorkspaces", previous)
        }
    }

    @Test fun `履歴の使用中エラーが初期画面に出て閉じると再試行で復帰できる`() = runComposeUiTest {
        val root = Files.createDirectory(temp.resolve("使用中のワークスペース"))
        val savedRecent = recent(root)
        val ownership = WorkspaceOwnership(temp.resolve("locks"))
        val held = ownership.acquire(root)
        val runtime = DesktopRuntime(monitorFactory = { null }, ownership = ownership)
        try {
            setContent {
                if (runtime.state.workspace == null) StartScreen(runtime.state)
                else Text("ワークスペースを開きました")
            }
            onNodeWithText(root.fileName.toString()).performClick()
            waitUntil(timeoutMillis = 10_000) { runtime.state.workspaceOpenFailure != null }
            onNodeWithText("ワークスペースを開けませんでした").assertIsDisplayed()
            onNodeWithText("対象: $root").assertIsDisplayed()
            onNodeWithText("別の YAML Design Editor または CLI で使用中です。", substring = true).assertIsDisplayed()
            onNodeWithText("トレイの「画面を表示」", substring = true).assertExists()
            onNodeWithText("「完全終了」", substring = true).assertExists()
            onNodeWithText("閉じる").performClick()
            onNodeWithText("ワークスペースを開けませんでした").assertDoesNotExist()
            runOnIdle { assertNull(runtime.state.workspace) }
            onNodeWithText(root.fileName.toString()).performClick()
            waitUntil(timeoutMillis = 10_000) { runtime.state.workspaceOpenFailure != null }
            held.close()
            onNodeWithText("再試行").performClick()
            waitUntil(timeoutMillis = 10_000) { runtime.state.workspace == root }
            onNodeWithText("ワークスペースを開きました").assertIsDisplayed()
            runOnIdle { assertNull(runtime.state.workspaceOpenFailure) }
        } finally { held.close(); runBlocking { runtime.shutdown() }; savedRecent.close() }
    }

    @Test fun `存在しない履歴の読込失敗も表示し他のステータス更新では消さない`() = runComposeUiTest {
        val missing = Files.createDirectory(temp.resolve("なくなったフォルダ"))
        val savedRecent = recent(missing)
        val state = AppState()
        try {
            Files.delete(missing)
            setContent { StartScreen(state) }
            onNodeWithText(missing.fileName.toString()).performClick()
            onNodeWithText("ワークスペースを開けませんでした").assertIsDisplayed()
            onNodeWithText("対象: $missing").assertIsDisplayed()
            runOnIdle { state.status = "別の通知"; assertNull(state.workspace) }
            onNodeWithText("ワークスペースを開けませんでした").assertIsDisplayed()
            onNodeWithText("閉じる").performClick()
            runOnIdle { assertNull(state.workspaceOpenFailure) }
        } finally { state.close(); savedRecent.close() }
    }

    @Test fun `切替失敗の再試行も未保存確認を通りキャンセルで編集を保つ`() = runComposeUiTest {
        val root = Files.createDirectory(temp.resolve("編集中"))
        val file = Files.writeString(root.resolve("a.yaml"), "name: before\n")
        val next = temp.resolve("切替先")
        val savedRecent = recent(root)
        val state = AppState()
        var requested: Path? = null
        try {
            state.openWorkspace(root)
            state.openFile(file)
            val doc = assertNotNull(state.openDoc)
            doc.editSource { replace(0, length, "name: edited\n") }
            state.reportWorkspaceOpenFailure(next, WorkspaceInUseException(next))
            state.workspaceOpener = { requested = it }
            setContent { AppRoot(state, false, false, {}, toggleDarkMode = {}) }
            onNodeWithText("再試行").performClick()
            onNodeWithText("未保存の変更があります").assertIsDisplayed()
            runOnIdle { assertNull(requested) }
            onNodeWithText("キャンセル").performClick()
            runOnIdle {
                assertEquals(root, state.workspace)
                assertEquals("name: edited\n", doc.source.text.toString())
                assertNotNull(state.workspaceOpenFailure)
            }
            onNodeWithText("再試行").performClick()
            onNodeWithText("破棄して切り替え").performClick()
            runOnIdle { assertEquals(next, requested) }
        } finally { state.close(); savedRecent.close() }
    }
}
