package dev.toros.yde.app.validate

import dev.toros.yde.desktop.AppState
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.workspace.validate.CheckResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * カバレッジ指摘メッセージの作成場所案内（R28-05・UX-97）のテスト。
 * detailIn の不足警告に、詳細スキーマの match パターン（どこに作ればよいか）が併記されること。
 */
class CoverageMessageTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-covmsg")
        write(
            "_schemas/list.yaml",
            """
            id: list
            match: "list.yaml"
            coverage:
              - description: 全要素は詳細文書を持つこと
                detailIn:
                  schemaIds: [detail]
                level: warning
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
            """,
        )
        write(
            "_schemas/detail.yaml",
            """
            id: detail
            match: "details/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: body, type: text }
            """,
        )
        write("list.yaml", "items:\n- { id: X-1 }")
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
    fun `detailIn不足の警告に詳細スキーマの作成場所が併記される`() {
        val ws = Workspace.open(dir)
        val issue = ws.validate("list.yaml").single { "詳細文書が不足" in it.message }
        assertTrue("detail（details/*.yaml に作成）" in issue.message, issue.message)
    }

    @Test
    fun `detailIn不足の指摘には作成導線が付き詳細文書を作成できる`() {
        // R28-05a（UX-97）: 指摘の scaffold から「詳細文書を作成」→ 作成後は不足が解消される
        val ws = Workspace.open(dir)
        val issue = ws.validate("list.yaml").single { "詳細文書が不足" in it.message }
        // 対応は組込み定義体の宣言（responses の create: detailDocument）から作られる（P29 Phase 6 第3段）
        val create = issue.responses.filterIsInstance<CheckResponse.CreateDetail>().single()
        kotlin.test.assertEquals("X-1", create.id)
        kotlin.test.assertEquals("detail", create.schemaId)
        kotlin.test.assertEquals("詳細文書を作成: X-1（detail）", create.label)
        kotlin.test.assertEquals("スキーマの配置先に新規ファイルを作成して開きます", create.note)

        val state = AppState().also { it.openWorkspace(dir) }
        val created = state.createDetailForCoverage("detail", "X-1").getOrThrow()
        assertTrue(created.toString().replace('\\', '/').endsWith("details/X-1.yaml"), created.toString())
        assertTrue("id: X-1" in Files.readString(created))
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.validate("list.yaml").none { "詳細文書が不足" in it.message })
    }

    @Test
    fun `matchからの作成先導出は単純なglobのみ対応する`() {
        kotlin.test.assertEquals("details/{id}.yaml", Views.detailPatternFromMatch("details/*.yaml"))
        kotlin.test.assertEquals("a/b/{id}.yaml", Views.detailPatternFromMatch("a/b/*.{yaml,yml}"))
        kotlin.test.assertEquals(null, Views.detailPatternFromMatch("a/**/*.yaml"))
        kotlin.test.assertEquals(null, Views.detailPatternFromMatch("a/x-*.yaml"))
    }

    @Test
    fun `詳細スキーマに filePattern があれば 作成場所は元の要素で評価した実際のパスになる`() {
        write(
            "_schemas/detail.yaml",
            """
            id: detail
            match: "details/**/*.yaml"
            filePattern: "details/{id}_{title}.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: body, type: text }
            """,
        )
        write("list.yaml", "items:\n- { id: X-1, title: 最初 }")
        val ws = Workspace.open(dir)
        val issue = ws.validate("list.yaml").single { "詳細文書が不足" in it.message }
        assertTrue("detail（details/X-1_最初.yaml に作成）" in issue.message, issue.message)
    }
}
