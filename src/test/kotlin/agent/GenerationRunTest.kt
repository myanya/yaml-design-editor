package agent

import ai.AiCallException
import ai.AiExchange
import ai.AiGenerated
import ai.AiProvider
import core.PatchOp
import core.Workspace
import kotlinx.coroutines.runBlocking
import model.YNode
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AI生成のカーネル統合（R9-01）のテスト。
 * Structured Outputs の生成（Generator）を疑似化し、「1ターンで提出する特殊アクティビティ」として
 * ステージング・提出・実行記録が通常のアクティビティ実行と同じ経路を通ることを検証する。
 */
class GenerationRunTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-genrun")
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string, required: true }
                - key: tags
                  type: array
                  item: { type: string }
            """,
        )
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A\ntags:\n- 既存")
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

    private fun input(instruction: String = "タイトルを整える") = ai.AiGenerationInput(
        apiKey = "test-key", model = "test-model", instruction = instruction,
        field = FieldDef(key = "title", type = FieldType.STRING),
        schema = SchemaDef(
            id = "doc", name = "doc", match = "docs/**/*.yaml",
            root = FieldDef(type = FieldType.OBJECT), source = dir,
        ),
        targetPath = "title", currentValueYaml = "文書A", currentDocumentYaml = "", idCandidates = emptyList(),
    )

    @Test
    fun `field generation submits a set patch changeset and writes a run log`() = runBlocking {
        val ws = Workspace.open(dir)
        val outcome = GenerationRun.run(
            ws,
            GenerationRun.Request(
                provider = AiProvider.OPENAI, input = input(),
                fileRel = "docs/a.yaml", targetPath = listOf("title"),
                wholeDocument = false, appendToArray = false,
            ),
        ) { _, _ -> AiGenerated(YNode.Scalar("生成されたタイトル")) }

        assertEquals("生成されたタイトル", (outcome.generated as YNode.Scalar).value)
        assertEquals(RunStatus.SUBMITTED, outcome.result.status)
        // 変更セットは通常のアクティビティ実行と同じ形式（set 1 操作）
        val change = outcome.result.changeSet.changes.single()
        assertEquals("docs/a.yaml", change.file)
        val op = change.ops.single() as PatchOp.Set
        assertEquals(listOf<Any>("title"), op.path)
        // 実行記録が _runs に残る
        assertTrue(outcome.runFile != null && Files.isRegularFile(outcome.runFile!!))
        assertTrue("ai-generate" in outcome.runFile!!.fileName.toString())
        // 原本は変更されていない（提出＝ステージングまで）
        assertTrue("文書A" in Files.readString(dir.resolve("docs/a.yaml")))
    }

    @Test
    fun `array append generates insert ops that preserve existing items`() = runBlocking {
        val ws = Workspace.open(dir)
        val outcome = GenerationRun.run(
            ws,
            GenerationRun.Request(
                provider = AiProvider.OPENAI, input = input("タグを足す"),
                fileRel = "docs/a.yaml", targetPath = listOf("tags"),
                wholeDocument = false, appendToArray = true,
            ),
        ) { _, _ -> AiGenerated(YNode.Seq(mutableListOf(YNode.Scalar("追加1"), YNode.Scalar("追加2")))) }

        val change = outcome.result.changeSet.changes.single()
        // 既存要素（1件）の後ろへの insert になる（既存項目に触れない）
        val inserts = change.ops.map { it as PatchOp.Insert }
        assertEquals(listOf(listOf<Any>("tags", 1), listOf<Any>("tags", 2)), inserts.map { it.path })
    }

    @Test
    fun `missing target on disk still returns the generated value with an empty changeset`() = runBlocking {
        val ws = Workspace.open(dir)
        // ディスクに存在しないパス（未保存編集でだけ存在する想定）
        val outcome = GenerationRun.run(
            ws,
            GenerationRun.Request(
                provider = AiProvider.OPENAI, input = input(),
                fileRel = "docs/a.yaml", targetPath = listOf("sections", 0, "body"),
                wholeDocument = false, appendToArray = false,
            ),
        ) { _, _ -> AiGenerated(YNode.Scalar("本文")) }

        assertEquals(RunStatus.SUBMITTED, outcome.result.status)
        assertTrue(outcome.result.changeSet.changes.isEmpty())
        assertEquals("本文", (outcome.generated as YNode.Scalar).value)
    }

    @Test
    fun `generation failure surfaces the error message`() {
        val ws = Workspace.open(dir)
        val e = assertFailsWith<IllegalStateException> {
            runBlocking {
                GenerationRun.run(
                    ws,
                    GenerationRun.Request(
                        provider = AiProvider.OPENAI, input = input(),
                        fileRel = "docs/a.yaml", targetPath = listOf("title"),
                        wholeDocument = false, appendToArray = false,
                    ),
                ) { _, _ -> error("APIキーが不正です") }
            }
        }
        assertTrue("APIキーが不正です" in e.message.orEmpty())
    }

    // ---------------------------------------------------------------- exchange の実行記録

    private fun exchange(output: String?) = AiExchange(
        system = "システム指示",
        input = "プロンプト全文（参考文書を含む）",
        responseSchema = """{"type":"object"}""",
        output = output,
        inputTokens = 1200,
        outputTokens = 34,
    )

    @Test
    fun `run log records the exchange messages and real token usage`() = runBlocking {
        val ws = Workspace.open(dir)
        val outcome = GenerationRun.run(
            ws,
            GenerationRun.Request(
                provider = AiProvider.OPENAI, input = input(),
                fileRel = "docs/a.yaml", targetPath = listOf("title"),
                wholeDocument = false, appendToArray = false,
            ),
        ) { _, _ -> AiGenerated(YNode.Scalar("生成されたタイトル"), exchange("""{"value":"生成されたタイトル"}""")) }

        val text = Files.readString(outcome.runFile!!)
        assertTrue("exchange:" in text)
        assertTrue("プロンプト全文（参考文書を含む）" in text)
        assertTrue("生成されたタイトル" in text)          // 出力メッセージ
        assertTrue("inputTokens: 1200" in text)           // usage が実測値になる
        assertTrue("outputTokens: 34" in text)
        assertTrue("transcript:" in text)                 // カーネル側の会話記録も残る
    }

    @Test
    fun `failed generation still writes a run log with the exchange`() {
        val ws = Workspace.open(dir)
        val e = assertFailsWith<GenerationRun.GenerationFailedException> {
            runBlocking {
                GenerationRun.run(
                    ws,
                    GenerationRun.Request(
                        provider = AiProvider.OPENAI, input = input(),
                        fileRel = "docs/a.yaml", targetPath = listOf("title"),
                        wholeDocument = false, appendToArray = false,
                    ),
                ) { _, _ -> throw AiCallException("応答を解析できませんでした", exchange("""{"error":"bad json"""")) }
            }
        }
        assertTrue("応答を解析できませんでした" in e.message.orEmpty())
        // 失敗時も実行記録が残り、入出力メッセージ（exchange）を含む
        val runFile = e.runFile
        assertTrue(runFile != null && Files.isRegularFile(runFile))
        val text = Files.readString(runFile!!)
        assertTrue("ERROR" in text)
        assertTrue("プロンプト全文（参考文書を含む）" in text)
        assertTrue("bad json" in text)
    }
}
