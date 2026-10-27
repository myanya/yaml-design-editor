package dev.toros.yde.agent

import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.agent.llm.ChatTurn
import dev.toros.yde.agent.llm.ChatUsage
import dev.toros.yde.agent.llm.ToolCall
import dev.toros.yde.agent.llm.ToolChatSession
import dev.toros.yde.agent.llm.ToolResult
import dev.toros.yde.app.core.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
 * 操作のツール（architecture/actions.md）: アクティビティの actions で許可した操作が 1 つずつツールになり、
 * 呼ぶとステージングへ変更が積まれる。LLM は台本（FakeSession）、ツール・ステージング・検証・変更セットは本物。
 * - ツール名は action_ + 名前（`.` と ASCII 以外は `_`）。引数は受け取る値の定義から作る（受け手は target）
 * - 許可した操作の書込は allowedFiles の外でも通る（apply_patch は今までどおり拒まれる）
 * - 前提に合わなければ理由を返し、ステージングは変わらない。後の手は前の操作の結果（ステージング）を見る
 * - 無い操作を許可したアクティビティは実行を始められない
 */
class ActionToolTest {

    private lateinit var dir: Path

    private class FakeSession(turns: List<ChatTurn>) : ToolChatSession {
        private val queue = turns.toMutableList()
        val toolResults = mutableListOf<List<ToolResult>>()
        override fun addUserText(text: String) = Unit
        override fun addToolResults(results: List<ToolResult>) { toolResults += results }
        override suspend fun send(): ChatTurn =
            if (queue.isEmpty()) ChatTurn(null, emptyList(), ChatUsage(1, 1), null) else queue.removeAt(0)
    }

    private class Host : AgentHost {
        override suspend fun askHuman(question: String): String? = null
        override suspend fun onEvent(event: AgentEvent) = Unit
    }

    private var callSeq = 0
    private fun call(name: String, argsJson: String): ToolCall = ToolCall("c-${callSeq++}", name, Json.parseToJsonElement(argsJson).jsonObject)
    private fun turn(vararg calls: ToolCall): ChatTurn = ChatTurn(null, calls.toList(), ChatUsage(10, 5), null)

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-action-tool")
        val d = "$"
        write("_schemas/item-list.yaml", """
            id: item-list
            match: list.yaml
            entity: { type: item, idPattern: "IT-*", defines: true }
            root:
              type: object
              fields:
                - key: items
                  type: array
                  required: true
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, required: true, role: title }
                      - { key: status, type: enum, values: [open, done], default: open }
                    methods:
                      finish:
                        label: 完了にする
                        do:
                          - set: { path: status, value: done }
            actions:
              - id: add-item
                label: 項目を追加する
                params:
                  - { key: id, type: id, required: true }
                  - { key: name, type: string, required: true }
                require:
                  - { match: [ { node: { id: ${d}id } } ], expect: absent, message: "${d}id は既にあります" }
                do:
                  - insert: { in: { schemaId: item-list }, path: items, params: { id: ${d}id, name: ${d}name } }
                  - create: { schema: item-detail, params: { id: ${d}id } }
        """)
        write("_schemas/item-detail.yaml", """
            id: item-detail
            match: "details/*.yaml"
            entity: { type: item, idPattern: "IT-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: note, type: string }
        """)
        write("list.yaml", "items:\n  - { id: IT-1, name: 最初, status: open }\n")
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

    private val agentDef = AgentDef(id = "editor", name = "編集担当", persona = "操作で変える。", source = Path.of("_agents/editor.yaml"))

    private fun activity(actions: List<String>, allowedFiles: List<String> = emptyList(), tools: Set<ToolGroup> = ToolGroup.entries.toSet()) = ActivityDef(
        id = "maintain", name = "整備", agentId = "editor", instruction = "操作で項目を整える。",
        allowedFiles = allowedFiles, tools = tools, actions = actions,
        limits = ActivityLimits(maxTurns = 10, maxTokens = 1_000_000, maxSeconds = 300),
        source = Path.of("_activities/maintain.yaml"),
    )

    private fun run(ws: Workspace, session: FakeSession, act: ActivityDef): AgentRunResult = runBlocking {
        AgentKernel.run(ws, AgentRunRequest(act, agentDef, AiProvider.OPENAI, "test-model", "test-key"), Host(), sessionFactory = { _, _, _, _, _ -> session })
    }

    @Test
    fun `許可した操作がツールになり 呼ぶとステージングへ積まれ 後の操作は前の結果を見る`() {
        val ws = Workspace.open(dir)
        val toolbox = AgentToolbox(ws, Staging(ws), activity(listOf("add-item", "item.finish")), ToolGroup.entries.toSet())
        val names = toolbox.specs().map { it.name }
        assertTrue("action_add-item" in names && "action_item_finish" in names, names.toString())
        val finish = toolbox.specs().first { it.name == "action_item_finish" }
        assertTrue("target" in finish.parameters["properties"]!!.jsonObject.keys, finish.parameters.toString())
        assertTrue("完了にする" in finish.description)

        val session = FakeSession(
            listOf(
                // 追加（一覧へ 1 行と、詳細文書の作成）→ 続けて、いま足した要素を型の操作で完了にする（ステージングの内容が見える）
                turn(call("action_add-item", """{"id":"IT-2","name":"次の項目"}""")),
                turn(call("action_item_finish", """{"target":"IT-2"}""")),
                turn(call("validate", "{}")),
                turn(call("submit_changeset", """{"summary":"項目を足して完了にした"}""")),
            )
        )
        val result = run(ws, session, activity(listOf("add-item", "item.finish")))
        assertEquals(RunStatus.SUBMITTED, result.status, result.message ?: "")
        assertEquals(setOf("list.yaml", "details/IT-2.yaml"), result.changeSet.changes.map { it.file }.toSet())
        val list = result.changeSet.changes.first { it.file == "list.yaml" }
        assertEquals(2, list.ops.size, "追加の insert と、完了にする set")
        val firstResult = session.toolResults.first().single().content
        assertTrue("ステージングへ積みました（2 文書" in firstResult, firstResult)
        assertFalse(Files.exists(dir.resolve("details/IT-2.yaml")), "原本は提出と承認まで変わらない")
    }

    @Test
    fun `許可した操作の書込は allowedFiles の外でも通り 前提に合わなければ理由を返す`() {
        val ws = Workspace.open(dir)
        val session = FakeSession(
            listOf(
                // apply_patch は allowedFiles で拒まれるが、操作は通る
                turn(call("apply_patch", """{"file":"list.yaml","ops":[{"op":"set","path":"items[0].name","value":"x"}]}""")),
                turn(call("action_add-item", """{"id":"IT-1","name":"重複"}""")),
                turn(call("action_add-item", """{"id":"IT-3","name":"三つ目"}""")),
                turn(call("submit_changeset", """{"summary":"追加"}""")),
            )
        )
        val result = run(ws, session, activity(listOf("add-item"), allowedFiles = listOf("nothing/**")))
        val results = session.toolResults.map { it.single().content }
        assertTrue("allowedFiles" in results[0], results[0])
        assertTrue("IT-1 は既にあります" in results[1], results[1])
        assertTrue("ステージングへ積みました" in results[2], results[2])
        assertEquals(RunStatus.SUBMITTED, result.status, result.message ?: "")
        assertEquals(setOf("list.yaml", "details/IT-3.yaml"), result.changeSet.changes.map { it.file }.toSet())
    }

    @Test
    fun `無い操作を許可したアクティビティは始められず actions を書かなければツールは出ない`() {
        val ws = Workspace.open(dir)
        assertFailsWith<IllegalStateException> { AgentToolbox(ws, Staging(ws), activity(listOf("no-such")), ToolGroup.entries.toSet()).specs() }
        val plain = AgentToolbox(ws, Staging(ws), activity(emptyList()), ToolGroup.entries.toSet()).specs().map { it.name }
        assertTrue(plain.none { it.startsWith("action_") }, plain.toString())
        // tools から actions を外せば、許可一覧があってもツールにならない
        val noGroup = AgentToolbox(ws, Staging(ws), activity(listOf("add-item"), tools = setOf(ToolGroup.EXPLORE, ToolGroup.EDIT)), setOf(ToolGroup.EXPLORE, ToolGroup.EDIT)).specs().map { it.name }
        assertTrue(noGroup.none { it.startsWith("action_") }, noGroup.toString())
    }
}
