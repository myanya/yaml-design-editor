package agent

import ai.AiCallException
import ai.AiExchange
import ai.AiGenerated
import ai.AiGenerationInput
import ai.AiGenerator
import ai.AiProvider
import ai.ChatTurn
import ai.ChatUsage
import ai.ToolCall
import ai.ToolChatSession
import ai.ToolResult
import core.Workspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * AI生成ダイアログのカーネル統合（R9-01 / R2-09）。
 * 単発のフィールド／文書生成を「1ターンで提出する特殊アクティビティ」としてエージェントカーネル上で実行する。
 * - 生成そのものは従来どおり AiGenerator（Structured Outputs）で行い、型保証を崩さない
 * - 生成値は疑似セッション（StructuredGenerationSession）が apply_patch → submit_changeset の
 *   ツール呼び出しへ変換し、ステージング・書込権限の強制・実行記録（_runs）は
 *   通常のアクティビティ実行と完全に同じ経路を通る（実行系・実行記録の単一系統化）
 * - ダイアログの適用先は編集中ツリー（未保存・Undo可）のままとする。未保存編集や新規文書では
 *   ディスク基準のパッチが成立しないことがあるが、その場合も生成値はダイアログへ返り、
 *   実行記録には「変更なしの提出」として残る
 */
object GenerationRun {

    /** 生成に使うクライアント（テストでは疑似実装に差し替える）。 */
    fun interface Generator {
        suspend fun generate(provider: AiProvider, input: AiGenerationInput): AiGenerated
    }

    /** 生成の失敗。実行記録は失敗時も書かれるため、参照先としてパスを保持する。 */
    class GenerationFailedException(message: String, val runFile: Path?) : IllegalStateException(message)

    class Request(
        val provider: AiProvider,
        val input: AiGenerationInput,
        val fileRel: String,          // 対象文書（ワークスペース相対）
        val targetPath: YPath,        // 生成対象のパス（wholeDocument のときは無視）
        val wholeDocument: Boolean,
        val appendToArray: Boolean,   // 配列フィールドで「既存項目を残して追加」を選んだ場合
    )

    class Outcome(
        val generated: YNode,         // 生成値（ダイアログがプレビュー・ツリー適用に使う）
        val result: AgentRunResult,
        val runFile: Path?,
    )

    suspend fun run(
        ws: Workspace,
        request: Request,
        generator: Generator = Generator { provider, input -> AiGenerator.generate(provider, input) },
    ): Outcome {
        val agentDef = AgentDef(
            id = "ai-generate",
            name = "AI生成",
            persona = "スキーマに適合する構造化データを生成する。",
            tools = setOf(ToolGroup.EDIT),
            source = ws.root,
        )
        val targetPretty = request.targetPath.pretty()
        val activity = ActivityDef(
            id = "ai-generate",
            name = if (request.wholeDocument) "文書全体のAI生成" else "フィールドのAI生成",
            agentId = agentDef.id,
            targetMatch = request.fileRel,
            instruction = request.input.instruction,
            contextInclude = emptyList(),
            allowedFiles = listOf(request.fileRel),
            // 配列追記は「path[インデックス]」への insert になるため、末尾一致のパターンも許可する
            allowedPaths = if (request.wholeDocument || targetPretty.isBlank()) emptyList()
            else listOf(targetPretty, "$targetPretty*"),
            tools = setOf(ToolGroup.EDIT),
            limits = ActivityLimits(maxTurns = 4),
            gate = ValidationGate.NONE,
            source = ws.root,
        )
        val session = StructuredGenerationSession(ws, request, generator)
        val runRequest = AgentRunRequest(
            activity = activity,
            agent = agentDef,
            provider = request.provider,
            model = request.input.model,
            apiKey = request.input.apiKey,
        )
        val result = AgentKernel.run(
            ws, runRequest,
            host = object : AgentHost {
                override suspend fun askHuman(question: String): String? = null
            },
            sessionFactory = { _, _, _, _, _ -> session },
        )
        // 失敗時もデバッグのため実行記録を書く（入出力メッセージは exchange 節に全文を残す）
        val runFile = runCatching { RunLog.write(ws, runRequest, result, extras = exchangeExtras(session.exchange)) }.getOrNull()
        val generated = session.generated
            ?: throw GenerationFailedException(result.message ?: "生成に失敗しました", runFile)
        return Outcome(generated, result, runFile)
    }

    /** 実行記録に残す入出力メッセージ1文字列あたりの上限（プロンプト20万・出力10万・スキーマ5万字）。 */
    private const val EXCHANGE_INPUT_LIMIT = 200_000
    private const val EXCHANGE_OUTPUT_LIMIT = 100_000
    private const val EXCHANGE_SCHEMA_LIMIT = 50_000

    /** 入出力メッセージを実行記録の exchange 節へ変換する。デバッグ用途のため上限内は全文を残す。 */
    private fun exchangeExtras(exchange: AiExchange?): Map<String, Any?> {
        if (exchange == null) return emptyMap()
        val section = LinkedHashMap<String, Any>()
        section["system"] = exchange.system.trim()
        section["input"] = exchange.input.take(EXCHANGE_INPUT_LIMIT)
        exchange.responseSchema?.let { section["responseSchema"] = it.take(EXCHANGE_SCHEMA_LIMIT) }
        exchange.output?.let { section["output"] = it.take(EXCHANGE_OUTPUT_LIMIT) }
        return mapOf("exchange" to section)
    }

    /**
     * Structured Outputs の単発生成を、カーネルのツール使用ループへ橋渡しする疑似セッション。
     * 1回目の send() で生成を実行して apply_patch を返し、以降は submit_changeset で終了する。
     * パッチはディスク内容を基準に組み立てるため、対象パスがディスク上に存在しない場合は
     * パッチなし（変更なしの提出）になる（生成値そのものは generated から取得できる）。
     */
    private class StructuredGenerationSession(
        private val ws: Workspace,
        private val request: Request,
        private val generator: Generator,
    ) : ToolChatSession {

        var generated: YNode? = null
        var exchange: AiExchange? = null   // 実行記録用の入出力メッセージ（失敗時も AiCallException から回収する）
        private var step = 0
        private var seq = 0

        private fun call(name: String, args: JsonObject) = ToolCall("gen-${seq++}", name, args)

        override fun addUserText(text: String) {}
        override fun addToolResults(results: List<ToolResult>) {}

        override suspend fun send(): ChatTurn {
            if (step++ == 0) {
                val outcome = try {
                    generator.generate(request.provider, request.input)
                } catch (e: AiCallException) {
                    exchange = e.exchange
                    throw e
                }
                generated = outcome.value
                exchange = outcome.exchange
                // 実トークン数を1ターン目の usage として計上する（実行記録の usage が実測になる）
                val usage = ChatUsage(outcome.exchange?.inputTokens ?: 0, outcome.exchange?.outputTokens ?: 0)
                val patch = buildPatchCall(outcome.value)
                return ChatTurn(null, listOf(patch ?: submitCall()), usage, null)
            }
            return ChatTurn(null, listOf(submitCall()), ChatUsage(), null)
        }

        private fun submitCall() = call("submit_changeset", buildJsonObject {
            put(
                "summary",
                if (request.wholeDocument) "AI生成（文書全体。適用はダイアログのプレビューで行う）"
                else "AI生成: ${request.targetPath.pretty()}",
            )
        })

        /** 生成値をディスク基準の apply_patch 呼び出しへ変換する（成立しない場合は null）。 */
        private fun buildPatchCall(value: YNode): ToolCall? {
            if (request.wholeDocument) return null  // ルート置換はパッチの対象外（文書全体はダイアログで適用）
            val file = ws.resolve(request.fileRel)
            if (!Files.isRegularFile(file)) return null
            val diskRoot = runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull() ?: return null
            val path = request.targetPath
            val current = getAt(diskRoot, path)
            val ops: List<JsonObject> = when {
                // 配列への追記: ディスク上の既存要素数の位置から insert（既存項目に触れない）
                request.appendToArray && value is YNode.Seq && current is YNode.Seq ->
                    value.items.mapIndexed { i, item ->
                        opJson("insert", (path + listOf(current.items.size + i)).pretty(), item)
                    }
                // 置換・新設: 親がディスク上に存在するときだけ set が成立する
                getAt(diskRoot, path.dropLast(1)) != null -> {
                    val last = path.lastOrNull()
                    if (last is Int && current == null) return null  // 配列の存在しない要素への set は不成立
                    listOf(opJson("set", path.pretty(), value))
                }
                else -> return null
            }
            if (ops.isEmpty()) return null
            return call("apply_patch", buildJsonObject {
                put("file", request.fileRel)
                putJsonArray("ops") { ops.forEach(::add) }
            })
        }

        private fun opJson(op: String, path: String, value: YNode): JsonObject = buildJsonObject {
            put("op", op)
            put("path", path)
            put("value", toJson(value))
        }
    }

    /** YNode → JSON 変換（apply_patch の value 引数用）。 */
    private fun toJson(node: YNode): JsonElement = when (node) {
        is YNode.Scalar -> when (val v = node.value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
        is YNode.Seq -> JsonArray(node.items.map(::toJson))
        is YNode.Map -> JsonObject(node.entries.mapValues { toJson(it.value) })
    }
}
