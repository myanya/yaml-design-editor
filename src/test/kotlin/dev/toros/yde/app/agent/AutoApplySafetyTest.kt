package dev.toros.yde.app.agent

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AutoApplySafetyTest {
    private val dir = createTempDirectory("yde-backup-test")
    private val original = "title: before\n"
    private val changes = ChangeSets.parse("""
        changes:
          - file: doc.yaml
            ops:
              - { op: set, path: title, value: after }
    """.trimIndent())

    @BeforeTest fun setup() { Files.writeString(dir.resolve("doc.yaml"), original) }
    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    @Test fun `実行記録の名前で保存先の階層やドライブを変更できない`() {
        for (name in listOf("stamp-bad/../../outside.yaml", "stamp-bad\\..\\outside.yaml", "C:outside.yaml", "..")) {
            assertFailsWith<IllegalArgumentException>(name) { RunLog.destination(dir, name) }
        }
        assertEquals(dir.resolve("stamp-日本語.yaml"), RunLog.destination(dir, "stamp-日本語.yaml"))
    }

    @Test fun `バックアップがないか不正なら原本を書き換えない`() {
        val invalid = Files.writeString(dir.resolve("invalid.yaml"), "[]")
        for (log in listOf(null, dir.resolve("missing.yaml"), invalid, dir)) {
            val result = AutoApply.applyWithBackup(Workspace.open(dir), changes, log, false)
            assertFalse(result.ok)
            assertEquals(original, Files.readString(dir.resolve("doc.yaml")))
        }
    }

    @Test fun `成功時は原文と完了状態を保存して競合を検出しながら復元する`() {
        val log = Files.writeString(dir.resolve("run.yaml"), "activity: test\n")
        assertTrue(AutoApply.applyWithBackup(Workspace.open(dir), changes, log, false).ok)
        val raw = YamlIO.parseToRaw(Files.readString(log)) as Map<*, *>
        assertEquals("applied", (raw["autoApply"] as Map<*, *>)["state"])
        Files.writeString(dir.resolve("doc.yaml"), "title: edited\n")
        assertFalse(AutoApply.revert(Workspace.open(dir), log).ok)
        assertTrue(AutoApply.revert(Workspace.open(dir), log, force = true).ok)
        assertEquals(original, Files.readString(dir.resolve("doc.yaml")))
    }

    @Test fun `適用失敗時も準備済みバックアップを保ち通常の巻き戻しは拒否する`() {
        val log = Files.writeString(dir.resolve("run.yaml"), "activity: test\n")
        val conflict = changes.copy(changes = changes.changes.map { it.copy(baseHash = "wrong") })
        assertFalse(AutoApply.applyWithBackup(Workspace.open(dir), conflict, log, false).ok)
        val saved = Files.readString(log)
        assertTrue("prepared" in saved && "before" in saved)
        assertEquals(original, Files.readString(dir.resolve("doc.yaml")))
        assertFalse(AutoApply.revert(Workspace.open(dir), log).ok)
        assertFalse(AutoApply.applyWithBackup(Workspace.open(dir), changes, log, false).ok)
        assertEquals(saved, Files.readString(log))
    }
}
