package agent

import ai.ToolCall
import core.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AgentToolbox の表示テキスト（related / list_documents / context.include の phase）。
 * 段階的詳細化のAI導線（P7）: 出現の中身プレビューとフェーズ状態が、追加のツール呼び出しなしで
 * 1回の related / list_documents から得られることを検証する。
 */
class ToolsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-tools")
        write(
            "_schemas/table.yaml",
            """
            id: table
            match: "tables/**/*.yaml"
            phases:
              order: [requirements, logical-design]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: phase
                  type: enum
                  values: [requirements, logical-design]
                  default: requirements
                - { key: name, type: string, required: true }
                - key: columns
                  type: array
                  requiredFrom: logical-design
                  item: { type: string }
            """,
        )
        write("tables/t.yaml", "id: TBL-001\nphase: requirements\nname: たすく\n")
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

    private fun toolbox(): AgentToolbox {
        val ws = Workspace.open(dir)
        val activity = ActivityDef(
            id = "a", name = "a", agentId = "editor", instruction = "x",
            source = Path.of("_activities/a.yaml"),
        )
        return AgentToolbox(ws, Staging(ws), activity, ToolGroup.entries.toSet())
    }

    private object NoAskHost : AgentHost {
        override suspend fun askHuman(question: String): String? = null
    }

    @Test
    fun `related includes occurrence content preview and phase state`() = runBlocking {
        val call = ToolCall("c1", "related", Json.parseToJsonElement("""{"id":"TBL-001"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        assertTrue("name: たすく" in text, text)
        assertTrue("phase=requirements" in text, text)
        assertTrue("logical-design 0/1" in text, text)
    }

    @Test
    fun `catalogText includes phase summary for phases-declared schema`() {
        val text = toolbox().catalogText()
        assertTrue("phase=requirements" in text, text)
    }

    @Test
    fun `phaseText lists phases-declared documents and respects targetMatch`() {
        val text = toolbox().phaseText(null)
        assertTrue("tables/t.yaml" in text && "phase=requirements" in text, text)
        val filtered = toolbox().phaseText("nomatch/**")
        assertTrue("phases を宣言したスキーマの対象文書がありません" in filtered, filtered)
    }
}
