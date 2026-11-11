package dev.toros.yde.cli

import dev.toros.yde.app.service.WorkspaceInUseException
import dev.toros.yde.app.service.WorkspaceOwnership
import java.nio.file.Files
import kotlin.test.*

class CliOwnershipTest {
    private val root = Files.createTempDirectory("cli-ownership")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `所有中は書込の準備より前に拒否し参照とプレビューは通す`() {
        Files.writeString(root.resolve("a.yaml"), "name: before\n")
        val patch = root.resolve("change.txt")
        Files.writeString(patch, "changes:\n  - file: a.yaml\n    ops:\n      - {op: set, path: name, value: after}\n")
        val writers = listOf(
            listOf("apply", "missing.yaml"), listOf("action", "missing", "--apply"),
            listOf("import", "missing", "--apply"), listOf("infer-schema", "--apply"),
            listOf("infer-relations", "--apply"), listOf("agent", "missing"), listOf("watch", "--once"),
            listOf("revert", "missing"), listOf("export", "missing"), listOf("migrate"),
            listOf("eval", "missing"), listOf("report", "missing"), listOf("run-skill", "missing", "--yes"),
            listOf("init", "--adopt"), listOf("event", "missing", "--record"),
        )
        WorkspaceOwnership().acquire(root).use {
            for (command in writers) {
                assertFailsWith<WorkspaceInUseException>(command.toString()) {
                    runCli((command.take(1) + root.toString() + command.drop(1)).toTypedArray())
                }
            }
            assertContains(runCli(arrayOf("catalog", root.toString())), "a.yaml")
            runCli(arrayOf("validate", root.toString()))
            assertFalse(appCommandWrites("behavior-audit", emptyList()))
            runCli(arrayOf("behavior-audit", root.toString()))
            assertFalse(appCommandWrites("event", listOf("missing")))
            runCli(arrayOf("init", root.toString(), "--adopt", "--dry-run"))
            runCli(arrayOf("apply", root.toString(), patch.toString(), "--dry-run"))
            assertEquals(0, workspaceApply(root.toString(), listOf(patch.toString(), "--dry-run"), {}))
            val out = mutableListOf<String>()
            assertEquals(1, run(arrayOf("workspace", "apply", root.toString(), "missing.yaml"), out::add))
            assertTrue(out.any { "使用中" in it })
            assertFailsWith<WorkspaceInUseException> { workspaceRefactor(root.toString(), listOf("--apply"), {}) }
            assertFailsWith<WorkspaceInUseException> { workspaceShell(root.toString(), { error("入力まで進まない") }, {}) }
            // 無効な入力は使用中エラーではなく、既存の入力エラーになる（プレビューは所有しない）。
            val preview = assertFails { runCli(arrayOf("apply", root.toString(), "missing.yaml", "--dry-run")) }
            assertFalse(preview is WorkspaceInUseException)
            assertEquals("name: before\n", Files.readString(root.resolve("a.yaml")))
            assertFalse(Files.exists(root.resolve(".yde")))
        }
        // 例外でも、対話セッションの終了でも所有権を残さない。
        assertFails { runCli(arrayOf("apply", root.toString(), "missing.yaml")) }
        assertEquals(0, workspaceShell(root.toString(), { null }, {}))
        runCli(arrayOf("apply", root.toString(), patch.toString()))
        assertContains(Files.readString(root.resolve("a.yaml")), "after")
        WorkspaceOwnership().acquire(root).close()
    }

    @Test fun `保存するベンチマークも生成先を所有してから書く`() {
        val generated = root.resolve("m1")
        WorkspaceOwnership().acquire(generated).use {
            assertFailsWith<WorkspaceInUseException> {
                runCli(arrayOf("bench", "--modules", "1", "--keep", root.toString()))
            }
            assertFalse(Files.exists(generated))
        }
    }
}
