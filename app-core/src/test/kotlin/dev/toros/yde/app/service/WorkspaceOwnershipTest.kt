package dev.toros.yde.app.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.*

class WorkspaceOwnershipTest {
    private val temp = Files.createTempDirectory("workspace-ownership")
    private val root = temp.resolve("workspace")
    private val locks = temp.resolve("locks")
    private val ownership = WorkspaceOwnership(locks)
    @AfterTest fun cleanup() { temp.toFile().deleteRecursively() }

    @Test fun `初期化前の場所を変更せず所有し同じJVMの別名を拒否する`() {
        ownership.acquire(root).use { first ->
            assertEquals(root, first.root)
            assertFalse(Files.exists(root))
            assertFailsWith<WorkspaceInUseException> { WorkspaceOwnership(locks).acquire(root.resolve("../workspace")) }
            Files.createDirectory(root)
            assertFailsWith<WorkspaceInUseException> { ownership.acquire(root) }
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertFailsWith<WorkspaceInUseException> { ownership.acquire(Path.of(root.toString().uppercase())) }
            }
            assertFalse(Files.exists(root.resolve(".yde")))
            ownership.acquire(temp.resolve("other")).close()
        }
        ownership.acquire(root).use { it.close(); it.close() }
        assertEquals(2, Files.list(locks).use { it.count().toInt() }, "空のロックファイルは残す")
    }

    @Test fun `ロック保存先の異常は所有成功として扱わない`() {
        Files.writeString(locks, "file")
        assertFails { ownership.acquire(root) }
        assertFalse(Files.exists(root))
    }

    @Test fun `別プロセスを拒否し通常終了と強制終了後は引き継げる`() {
        ownership.acquire(root).use {
            // JVM 内での競合失敗が OS ロックを外さないことも別プロセスで確かめる。
            assertFailsWith<WorkspaceInUseException> { ownership.acquire(root) }
            val refused = child("once")
            try { assertEquals("BUSY", line(refused)); assertTrue(refused.waitFor(10, TimeUnit.SECONDS)); assertEquals(23, refused.exitValue()) }
            finally { stop(refused) }
        }
        for (forced in listOf(false, true)) {
            val holder = child("hold")
            try {
                assertEquals("READY", line(holder))
                assertFailsWith<WorkspaceInUseException> { ownership.acquire(root) }
                if (forced) holder.destroyForcibly() else holder.outputStream.use { it.write('\n'.code); it.flush() }
                assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
                awaitReleased()
            } finally { stop(holder) }
        }
    }

    private fun awaitReleased() {
        // Windows の OS による解放はプロセスの終了通知より遅れることがある。
        // 待つのは終了確認後だけ。生存中の競合拒否は上で即時に検査する。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            try { ownership.acquire(root).close(); return }
            catch (busy: WorkspaceInUseException) {
                if (System.nanoTime() >= deadline) throw AssertionError("終了後も所有権が解放されませんでした", busy)
                Thread.sleep(10)
            }
        }
    }

    private fun child(mode: String): Process {
        val executable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = listOf(WorkspaceOwnership::class.java, OwnershipProcessProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(executable, "-cp", classpath, OwnershipProcessProbe::class.java.name, locks.toString(), root.toString(), mode)
            .redirectErrorStream(true).start()
    }
    private fun line(process: Process): String? = CompletableFuture.supplyAsync { process.inputReader().readLine() }.get(10, TimeUnit.SECONDS)
    private fun stop(process: Process) { if (process.isAlive) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) } }
}

/** テスト専用の別 JVM。実際の OS ロックを保持し、標準入力の EOF または改行で終了する。 */
object OwnershipProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            WorkspaceOwnership(Path.of(args[0])).acquire(Path.of(args[1])).use {
                println("READY")
                if (args[2] == "hold") System.`in`.read()
            }
        } catch (_: WorkspaceInUseException) { println("BUSY"); exitProcess(23) }
    }
}
