package dev.toros.yde.app.core

import dev.toros.yde.cli.runCli
import dev.toros.yde.yaml.YamlIO
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
 * CLI `action`（P41 段階 1）を labs/sysdev-template の定義で通す。labs は読み取り専用に扱い、一時ディレクトリへ写してから開く。
 * - 引数なしは使える操作の一覧（横断の操作と型の操作）
 * - 既定は計画だけ: 変更セットと、適用後の姿の検証（新しい画面が行き止まりになる、を到達性の検査が指摘する）
 * - --apply は検証エラーが残るなら書かない。--allow-errors で書く
 * - 前提に反する操作（参照の残るビジネスルールの廃止）は理由を出して止まる
 */
class ActionCliTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-actions")
        val source = Path.of("labs/sysdev-template")
        Files.walk(source).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun yaml(text: String): Map<String, Any?> = YamlIO.parseToRaw(text) as Map<String, Any?>

    private fun addScreen() = arrayOf(
        "action", dir.toString(), "add-screen",
        "--param", "id=SCR-ROOM-DETAIL", "--param", "name=会議室詳細画面", "--param", "derived_from=[UA-003]",
        "--param", "from=SCR-ROOM-LIST", "--param", "trigger=会議室名クリック",
    )

    @Test
    fun `一覧には横断の操作と型の操作が並ぶ`() {
        val out = yaml(runCli(arrayOf("action", dir.toString())))
        assertEquals(emptyList<Any?>(), out["schemaErrors"])
        val names = (out["actions"] as List<Map<String, Any?>>).map { it["name"] }
        assertTrue("add-screen" in names && "deprecate-rule" in names && "business-rule.deprecate" in names && "screen.create" in names, names.toString())
    }

    @Test
    fun `計画は 3 つの文書の変更セットと 適用後の検証を返し 原本は変えない`() {
        val out = yaml(runCli(addScreen()))
        assertEquals("画面を追加する", out["action"])
        val changes = out["changes"] as List<Map<String, Any?>>
        assertEquals(
            listOf("30_basic-design/screen-list.yaml", "30_basic-design/screen-transitions.yaml", "30_basic-design/screens/SCR-ROOM-DETAIL.yaml"),
            changes.map { it["file"] },
        )
        assertEquals(true, changes[2]["create"])
        // 新しい画面から出る遷移が無い → 到達性の検査が行き止まりをエラーにする（適用前に分かる）
        val issues = (out["results"] as List<Map<String, Any?>>).flatMap { it["issues"] as List<Map<String, Any?>> }
        assertTrue(issues.any { it["severity"] == "ERROR" && (it["message"] as String).contains("SCR-ROOM-DETAIL") }, issues.toString())
        assertEquals(false, out["applied"])
        assertFalse(Files.exists(dir.resolve("30_basic-design/screens/SCR-ROOM-DETAIL.yaml")))
    }

    @Test
    fun `apply は検証エラーが残るなら書かず allow-errors で書く`() {
        val refused = yaml(runCli(addScreen() + "--apply"))
        assertEquals(false, refused["applied"])
        assertFalse(Files.exists(dir.resolve("30_basic-design/screens/SCR-ROOM-DETAIL.yaml")))
        val written = yaml(runCli(addScreen() + "--apply" + "--allow-errors"))
        assertEquals(true, written["applied"])
        assertTrue(Files.exists(dir.resolve("30_basic-design/screens/SCR-ROOM-DETAIL.yaml")))
        assertTrue(Files.readString(dir.resolve("30_basic-design/screen-list.yaml")).contains("SCR-ROOM-DETAIL"))
        assertTrue(Files.readString(dir.resolve("30_basic-design/screen-transitions.yaml")).contains("会議室名クリック"))
        // 同じ ID をもう一度は前提で止まる
        val again = assertFailsWith<IllegalStateException> { runCli(addScreen()) }
        assertTrue("SCR-ROOM-DETAIL は既にあります" in again.message!!, again.message)
    }

    @Test
    fun `前提に反する操作は理由を出して止まる`() {
        val e = assertFailsWith<IllegalStateException> {
            runCli(arrayOf("action", dir.toString(), "deprecate-rule", "--on", "BR-004", "--param", "reason=統合"))
        }
        assertTrue("BR-004 はまだ UC-001 から根拠として参照されています" in e.message!!, e.message)
        // 型の操作を直接呼ぶ（参照の有無は前提ではなく、適用後の検査 br-unapproved-referenced が指摘する）
        val direct = yaml(runCli(arrayOf("action", dir.toString(), "business-rule.deprecate", "--on", "BR-004", "--param", "reason=統合")))
        assertEquals("廃止にする（BR-004）", direct["action"])
        val issues = (direct["results"] as List<Map<String, Any?>>).flatMap { it["issues"] as List<Map<String, Any?>> }
        assertTrue(issues.any { it["severity"] == "ERROR" }, issues.toString())
    }
}
