package dev.toros.yde.app.skill

import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.desktop.ext.DesktopExtensions
import dev.toros.yde.app.cli.runCli
import dev.toros.yde.app.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * コード実行skill（R9-06）のテスト。
 * 完了条件「許可なしに実行される経路がない」を、承認の失効（定義変更）・CLIの --yes ガードで検証する。
 * プロセス起動は launcher 注入で疑似化し、変更検出（宣言外・予約ディレクトリ）は本物のロジックを通す。
 */
class RunSkillTest {
    init { Extensions.install(DesktopExtensions.registry) }   // 形式（csv・xlsx …）は入口の一覧で登録する


    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-run")
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A")
        write(
            "_skills/normalize.yaml",
            """
            id: normalize
            name: 正規化スクリプト
            kind: run
            run:
              command: [python, scripts/normalize.py]
              workdir: .
              timeoutSeconds: 30
              writes: ["work/**"]
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `run skill definition is parsed with command and writes`() {
        val loaded = Skills.load(dir)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val def = loaded.skill("normalize")!!
        assertEquals(SkillKind.RUN, def.kind)
        assertEquals(listOf("python", "scripts/normalize.py"), def.run!!.command)
        assertEquals(listOf("work/**"), def.run!!.writes)
        assertEquals(30, def.run!!.timeoutSeconds)
    }

    @Test
    fun `reserved directory cannot be declared as write target`() {
        write(
            "_skills/bad.yaml",
            """
            id: bad
            kind: run
            run:
              command: [echo]
              writes: ["_schemas/**"]
            """,
        )
        val loaded = Skills.load(dir)
        assertTrue(loaded.errors.any { "予約ディレクトリ" in it }, loaded.errors.toString())
    }

    @Test
    fun `approval expires when the definition changes`() {
        val ws = Workspace.open(dir)
        val def = Skills.load(dir).skill("normalize")!!
        // 別の（古い）定義への承認は現在の定義に使えない = 承認後の定義すり替えは実行できない
        val staleApproval = Runner.Approval.approve(def.run!!.copy(command = listOf("python", "old.py")))
        var launched = false
        val e = assertFailsWith<IllegalArgumentException> {
            Runner.run(ws, def, staleApproval) { _, _, _ -> launched = true; Runner.ProcessResult(0, "", "") }
        }
        assertTrue("承認内容" in e.message.orEmpty())
        assertFalse(launched, "承認が一致しないのにプロセスが起動されました")
    }

    @Test
    fun `approved run detects out-of-scope and reserved changes`() {
        val ws = Workspace.open(dir)
        val def = Skills.load(dir).skill("normalize")!!
        val outcome = Runner.run(ws, def, Runner.Approval.approve(def.run!!)) { _, _, _ ->
            // 疑似プロセス: 宣言内（work/）・宣言外（docs/）・予約ディレクトリ（_runs/）へ書き込む
            write("work/output.csv", "a,b")
            write("docs/a.yaml", "id: DOC-A\ntitle: 書き換え")
            write("_runs/rogue.yaml", "x: 1")
            Runner.ProcessResult(0, "done", "")
        }
        assertEquals(0, outcome.exitCode)
        assertTrue("work/output.csv" in outcome.changedFiles)
        assertEquals(listOf("docs/a.yaml"), outcome.outOfScope)          // 宣言外の変更は警告
        assertEquals(listOf("_runs/rogue.yaml"), outcome.reservedTouched) // 予約ディレクトリは重大な逸脱
        assertFalse(outcome.ok)  // 予約ディレクトリに触れた実行は ok にならない
    }

    @Test
    fun `cli without yes flag only prints the command and never executes`() {
        // コマンドは存在しない実行ファイル: 実行経路に入れば例外になるが、--yes なしでは表示のみで正常終了する
        write(
            "_skills/danger.yaml",
            """
            id: danger
            kind: run
            run:
              command: [__no_such_binary__]
            """,
        )
        val output = runCli(arrayOf("run-skill", dir.toString(), "danger"))
        assertTrue("--yes" in output, output)
        assertTrue("__no_such_binary__" in output, output)
    }

    @Test
    fun `approved run executes a real process`() {
        // 実プロセスのスモーク: テスト実行中のJVMの java -version は常に利用できる
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val javaBin = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
        write(
            "_skills/smoke.yaml",
            """
            id: smoke
            kind: run
            run:
              command: ["${javaBin.toString().replace("\\", "\\\\")}", "-version"]
              timeoutSeconds: 60
            """,
        )
        val ws = Workspace.open(dir)
        val def = Skills.load(dir).skill("smoke")!!
        val outcome = Runner.run(ws, def, Runner.Approval.approve(def.run!!))
        assertEquals(0, outcome.exitCode, outcome.stderr)
        assertTrue(outcome.ok)
    }
}
