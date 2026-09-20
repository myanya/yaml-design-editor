package core

import validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 規模の計測の合成ワークスペース（ROADMAP P27。BACKLOG UX-116）: 生成される内容は決定的で、検証エラーは0。
 * 「エラー0」が検査の空振りでないこと——検査の語彙（多段の存在・値の比較・網羅マトリクス・遷移検証・詳細文書の存在・
 * 非循環）が実際に効いていること——を、内容を1か所ずつ壊して確かめる。時間そのものはテストしない（環境で変わるため）。
 */
class BenchTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-bench-test")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun errorsOf(ws: Workspace): List<String> =
        ws.files.flatMap { f -> ws.validate(ws.relPath(f)).filter { it.severity == Severity.ERROR }.map { it.message } }

    private fun rewrite(rel: String, transform: (String) -> String) {
        val file = dir.resolve(rel)
        Files.writeString(file, transform(Files.readString(file)))
    }

    @Test
    fun `生成したワークスペースは決定的で 検証エラーが無い`() {
        val size = Bench.generate(dir, modules = 2)
        assertEquals(2, size.modules)
        assertEquals(13 + 2 * 66, size.entities)
        val result = Bench.measure(dir, size)
        assertEquals(0, result.errors)
        assertEquals(0, result.warnings)
        // 数えた要素の数は、索引の要素の数と一致する
        assertEquals(size.entities, Workspace.open(dir).index.graph.entities.size)
    }

    @Test
    fun `検査は空振りしていない`() {
        Bench.generate(dir, modules = 2)
        // テスト項目を1つ消す → 網羅マトリクスの不足
        rewrite("m001/tests.yaml") { it.lines().filterNot { l -> "TI-1-1-1," in l }.joinToString("\n") }
        // 画面項目の最大文字数をデータ型より大きくする → 値の比較の違反
        rewrite("m001/screens/SCR-1-1.yaml") { it.replace(Regex("""max_length: \d+ }"""), "max_length: 9999 }") }
        // 画面の詳細文書を1つ消す → 詳細文書の不足
        Files.delete(dir.resolve("m002/screens/SCR-2-6.yaml"))
        // 処理の呼び出しを循環させる → 非循環の違反
        rewrite("m002/processes/PROC-2-6.yaml") { it.replace("calls: []", "calls: [PROC-2-1]") }
        // 遷移を1本消す → 開始画面から到達できない画面
        rewrite("m002/transitions.yaml") { it.lines().filterNot { l -> "from: SCR-2-4, to: SCR-2-5" in l }.joinToString("\n") }
        val errors = errorsOf(Workspace.open(dir))
        assertTrue(errors.any { "網羅マトリクス" in it || "PROC-1-1" in it && "VP-1" in it }, errors.joinToString("\n"))
        assertTrue(errors.any { "9999" in it }, errors.joinToString("\n"))
        assertTrue(errors.any { "SCR-2-6" in it }, errors.joinToString("\n"))
        assertTrue(errors.any { "循環" in it }, errors.joinToString("\n"))
        assertTrue(errors.any { "SCR-2-5" in it && "到達" in it }, errors.joinToString("\n"))
    }
}
