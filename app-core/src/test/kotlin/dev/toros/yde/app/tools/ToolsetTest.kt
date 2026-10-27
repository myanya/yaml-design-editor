package dev.toros.yde.app.tools

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.patch.PatchOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 中核のツールの一式（ADR-0013「窓口の形」）: 入口に依らず、作業コピー越しに読み・作業コピーへ書き・許可した操作をツールにする。
 * - ツールは種類（読む・書く・検証・操作）を持ち、入口がそれで選ぶ
 * - 書込の権限（許可ファイル・予約ディレクトリ）はツール側で拒む。許可した操作は allowedFiles の外でも通る
 * - 結果は JSON にできる値（今は文字）。無い名前・無い操作の許可は誤り
 */
class ToolsetTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-toolset")
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

    private fun toolset(policy: ToolPolicy = ToolPolicy()): Toolset {
        val ws = Workspace.open(dir)
        return Toolset(ToolContext(ws, WorkingCopy(ws), policy))
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `中核のツールは種類を持ち 許可した操作が action_ の名前でツールになる`() {
        val tools = toolset(ToolPolicy(actions = listOf("add-item", "item.finish"))).tools
        assertEquals(
            listOf("list_documents", "read_outline", "read_node", "search", "related", "read_entity", "traverse", "read_view"),
            tools.filter { it.kind == ToolKind.READ }.map { it.name },
        )
        assertEquals(listOf("apply_patch", "create_document"), tools.filter { it.kind == ToolKind.EDIT }.map { it.name })
        assertEquals(listOf("validate"), tools.filter { it.kind == ToolKind.VALIDATE }.map { it.name })
        assertEquals(listOf("action_add-item", "action_item_finish"), tools.filter { it.kind == ToolKind.ACTION }.map { it.name })
        val finish = tools.first { it.name == "action_item_finish" }
        assertTrue("target" in finish.parameters["properties"]!!.jsonObject.keys, finish.parameters.toString())
        assertTrue("完了にする" in finish.description)
        // 許可が無ければ操作のツールは出ない。無い操作の許可は誤り
        assertTrue(toolset().tools.none { it.kind == ToolKind.ACTION })
        val error = assertFailsWith<IllegalStateException> { toolset(ToolPolicy(actions = listOf("no-such"))) }
        assertTrue("no-such" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `読むツールは作業コピーの編集を映し 結果は文字の JSON 値`() {
        val tools = toolset()
        tools.ctx.copy.applyOps("list.yaml", listOf(PatchOp.Set(parseYPath("items[0].name"), YNode.Scalar("変えた"))))
        val node = tools.run("read_node", args("""{"file":"list.yaml"}"""))
        assertTrue(node is JsonPrimitive && node.isString, node.toString())
        assertTrue("変えた" in node.toolText(), node.toolText())
        assertTrue("list.yaml" in tools.run("list_documents", args("{}")).toolText())
        assertTrue("IT-1" in tools.run("read_entity", args("""{"id":"IT-1"}""")).toolText())
        assertTrue("問題なし" in tools.run("validate", args("{}")).toolText())
        assertFailsWith<IllegalStateException> { tools.run("nope", args("{}")) }
        assertFalse("変えた" in Files.readString(dir.resolve("list.yaml")), "原本は変わらない")
    }

    @Test
    fun `書くツールは許可ファイルと予約ディレクトリで拒み 許可した操作は allowedFiles の外でも通る`() {
        val tools = toolset(ToolPolicy(allowedFiles = listOf("nothing/**"), actions = listOf("add-item")))
        val denied = assertFailsWith<IllegalStateException> {
            tools.run("apply_patch", args("""{"file":"list.yaml","ops":[{"op":"set","path":"items[0].name","value":"x"}]}"""))
        }
        assertTrue("allowedFiles" in denied.message.orEmpty(), denied.message)
        val reserved = assertFailsWith<IllegalStateException> {
            tools.run("create_document", args("""{"file":"_schemas/x.yaml","content_yaml":"id: x"}"""))
        }
        assertTrue("予約ディレクトリ" in reserved.message.orEmpty(), reserved.message)
        assertTrue(tools.ctx.copy.isEmpty())

        val out = tools.run("action_add-item", args("""{"id":"IT-2","name":"次の項目"}""")).toolText()
        assertTrue("ステージングへ積みました（2 文書" in out, out)
        assertEquals(setOf("list.yaml", "details/IT-2.yaml"), tools.ctx.copy.touchedFiles().map { it.rel }.toSet())
        assertFalse(Files.exists(dir.resolve("details/IT-2.yaml")), "原本は変更セットの適用まで変わらない")
        // 前提に合わなければ理由を返し、作業コピーは変わらない
        val refused = tools.run("action_add-item", args("""{"id":"IT-1","name":"重複"}""")).toolText()
        assertTrue("IT-1 は既にあります" in refused, refused)
        assertEquals(2, tools.ctx.copy.touchedFiles().size)
    }
}
