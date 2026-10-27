package dev.toros.yde.agent

import dev.toros.yde.agent.llm.ToolCall
import dev.toros.yde.agent.llm.ToolSpec
import dev.toros.yde.app.core.Disclosure
import dev.toros.yde.app.core.TraverseSpec
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.tools.Readouts
import dev.toros.yde.app.tools.Tool
import dev.toros.yde.app.tools.ToolContext
import dev.toros.yde.app.tools.ToolKind
import dev.toros.yde.app.tools.ToolPolicy
import dev.toros.yde.app.tools.Toolset
import dev.toros.yde.app.tools.toolArgs
import dev.toros.yde.app.tools.toolText
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity
import kotlinx.serialization.json.*

/**
 * エージェントに公開するツール一式（R2-04）。本体は app-core の中核のツール（`Toolset`: 読む・作業コピーへ書く・検証・許可した操作。
 * 書込権限もそこで強制する）で、ここはアクティビティのツール分類で選んで LLM のツール宣言（`ToolSpec`）に包む薄い層。
 * 会話の相手が要るもの（ask_human・delegate）と実行の管理（plan・note・submit_changeset・abort）だけがここにある。
 */
class AgentToolbox(
    ws: Workspace,
    val staging: Staging,
    private val activity: ActivityDef,
    private val groups: Set<ToolGroup>,
    private val subAgents: List<AgentDef> = emptyList(),
    private val delegate: (suspend (agentId: String, instruction: String) -> String)? = null,
    private val consultMode: Boolean = false,   // 相談モード: 読み取り専用（explore / validate のみ）
) {
    /** 相談モードで許可するツール名。 */
    private val consultAllowed = setOf("list_documents", "read_outline", "read_node", "search", "related", "read_entity", "traverse", "read_view", "validate")

    /** 中核のツールの文脈: 書込の許可はアクティビティの定義から。操作は分類 actions を許したときだけツールにする。 */
    val context = ToolContext(
        ws, staging,
        ToolPolicy(
            meta = activity.meta, allowedFiles = activity.allowedFiles, allowedPaths = activity.allowedPaths,
            actions = if (ToolGroup.ACTIONS in groups && !consultMode) activity.actions else emptyList(),
        ),
    )
    val toolset: Toolset = try {
        Toolset(context)
    } catch (e: IllegalStateException) {
        error("アクティビティ「${activity.id}」の actions: ${e.message}")
    }
    private val readouts: Readouts get() = toolset.readouts

    /** ツール実行の結果。terminal が非null のときはループを終了する。 */
    data class Outcome(val content: String, val terminal: Terminal? = null)
    data class Terminal(val status: RunStatus, val message: String)

    val plan = mutableListOf<String>()
    val notes = mutableListOf<String>()

    /** 分類で選んだ中核のツール。 */
    private fun coreTools(): List<Tool> {
        val kinds = buildSet {
            if (ToolGroup.EXPLORE in groups) add(ToolKind.READ)
            if (ToolGroup.EDIT in groups && !consultMode) add(ToolKind.EDIT)
            if (ToolGroup.VALIDATE in groups) add(ToolKind.VALIDATE)
            if (ToolGroup.ACTIONS in groups && !consultMode) add(ToolKind.ACTION)
        }
        return toolset.tools.filter { it.kind in kinds }
    }

    // ---------------------------------------------------------------- ツール定義（モデルに公開するスキーマ）

    /** 出す順: 読む → 書く → 検証 → 対話（ask_human・delegate）→ 操作 → 管理（plan・note・submit_changeset・abort）。 */
    fun specs(): List<ToolSpec> {
        val core = coreTools()
        fun spec(tool: Tool) = ToolSpec(tool.name, tool.description, tool.parameters)
        val specs = mutableListOf<ToolSpec>()
        core.filter { it.kind != ToolKind.ACTION }.forEach { specs += spec(it) }
        if (ToolGroup.ASK in groups && !consultMode) {
            specs += ToolSpec("ask_human", "人間に質問して回答を待つ。判断が分かれる点や不足情報があるときだけ使う。", toolArgs {
                required("question", "質問文（選択肢があれば列挙する）")
            })
        }
        if (ToolGroup.DELEGATE in groups && !consultMode && delegate != null && subAgents.isNotEmpty()) {
            specs += ToolSpec(
                "delegate",
                "別のエージェントに読み取り専用の相談・レビューを依頼し、所見テキストを受け取る。" +
                    "相談相手はステージング済みの編集内容も読める（例: 執筆後にレビュー担当へ推敲を依頼）。" +
                    "利用可能: ${subAgents.joinToString(", ") { "${it.id}（${it.name}）" }}",
                toolArgs {
                    required("agent", "相談先エージェントのID")
                    required("instruction", "依頼内容（何をどの観点で見てほしいか）")
                },
            )
        }
        core.filter { it.kind == ToolKind.ACTION }.forEach { specs += spec(it) }
        if (consultMode) return specs
        specs += ToolSpec("plan", "作業計画（TODO）を宣言・更新する。人間に進捗として表示される。", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("steps") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
            putJsonArray("required") { add("steps") }
        })
        specs += ToolSpec("note", "前提・判断・根拠を作業メモとして記録する（実行記録に残る）。", toolArgs {
            required("text", "メモ")
        })
        specs += ToolSpec("submit_changeset", "ステージング済みの変更を変更セットとして提出し、作業を終了する。提出ゲート（検証）を通らない場合は拒否される。", toolArgs {
            required("summary", "変更内容の要約（人間のレビュー向け）")
        })
        specs += ToolSpec("abort", "作業を中断して終了する。", toolArgs {
            required("reason", "中断理由")
        })
        return specs
    }

    // ---------------------------------------------------------------- 実行

    suspend fun execute(call: ToolCall, host: AgentHost, questions: MutableList<Pair<String, String?>>): Outcome {
        fun arg(name: String): String? = call.arguments[name]?.jsonPrimitive?.contentOrNull
        fun requireArg(name: String): String = arg(name) ?: error("引数 $name が必要です")
        if (consultMode && call.name !in consultAllowed) {
            error("相談モードでは使えないツールです（読み取り専用）: ${call.name}")
        }
        require(specs().any { it.name == call.name }) { "この実行では許可されていないツールです: ${call.name}" }
        return when (call.name) {
            "delegate" -> {
                val fn = delegate ?: error("このアクティビティでは委譲できません")
                Outcome(fn(requireArg("agent"), requireArg("instruction")))
            }
            "ask_human" -> {
                val question = requireArg("question")
                val answer = host.askHuman(question)
                questions += question to answer
                Outcome(answer ?: "（回答が得られませんでした。必要な仮定を note で明示して進めるか、abort してください）")
            }
            "plan" -> {
                val steps = (call.arguments["steps"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: error("引数 steps（配列）が必要です")
                plan.clear(); plan.addAll(steps)
                Outcome("計画を更新しました（${steps.size} 項目）")
            }
            "note" -> {
                notes += requireArg("text")
                Outcome("記録しました")
            }
            "submit_changeset" -> {
                val summary = requireArg("summary")
                if (activity.gate == ValidationGate.ERROR_FREE) {
                    val errors = staging.validateAll().flatMap { (rel, issues) ->
                        issues.filter { it.severity == Severity.ERROR }.map { "$rel :: ${it.path.pretty()}: ${it.message}" }
                    }
                    if (errors.isNotEmpty()) {
                        return Outcome(
                            "提出は拒否されました。検証エラーを解消してください（${errors.size} 件）:\n" +
                                errors.take(20).joinToString("\n") { "- $it" }
                        )
                    }
                }
                if (activity.gate == ValidationGate.NO_NEW_ERRORS) {
                    // 点検系ゲート（R28-05b・UX-47）: 既存エラーで提出を塞がず、この変更で増えたエラーのみ拒否理由にする
                    val fresh = staging.newErrors().flatMap { (rel, issues) ->
                        issues.map { "$rel :: ${it.path.pretty()}: ${it.message}" }
                    }
                    if (fresh.isNotEmpty()) {
                        return Outcome(
                            "提出は拒否されました。この変更で新しく増えた検証エラーを解消してください" +
                                "（${fresh.size} 件。適用前からある既存エラーは提出を妨げません）:\n" +
                                fresh.take(20).joinToString("\n") { "- $it" }
                        )
                    }
                }
                Outcome("提出しました", Terminal(RunStatus.SUBMITTED, summary))
            }
            "abort" -> Outcome("中断しました", Terminal(RunStatus.ABORTED, requireArg("reason")))
            // 中核のツール（読む・書く・検証・操作）は app-core が実行し、結果の文字をそのまま返す
            else -> Outcome(toolset.run(call.name, call.arguments).toolText())
        }
    }

    // ---------------------------------------------------------------- 読み出しの文（初期文脈・画面・テストが使う。本体は app-core の Readouts）

    fun catalogText(match: String? = null): String = readouts.catalogText(match)
    fun phaseText(targetMatch: String?): String = readouts.phaseText(targetMatch)
    fun viewText(viewId: String?, fileRel: String? = null, rowsPattern: String? = null, columns: List<String>? = null, date: String? = null): String =
        readouts.viewText(viewId, fileRel, rowsPattern, columns, date)
    fun traverseText(start: String, steps: List<TraverseStep>, budgetChars: Int): String = readouts.traverseText(start, steps, budgetChars)
    fun entityText(id: String, relationLimit: Int = 20): String = readouts.entityText(id, relationLimit)
    fun validationText(): String = readouts.validationText()

    // ---------------------------------------------------------------- コンテキストレシピ（R19-02）

    /** レシピ項目の会計区分名（解決失敗時のフォールバック表示にも使う）。 */
    fun contextItemLabel(item: ContextItemDef): String = when (item.kind) {
        "catalog" -> "ワークスペースカタログ"
        "phase" -> "対象文書のフェーズ状態（段階的詳細化）"
        "view" -> "ビュー: ${item.view}"
        "doc" -> "文書: ${item.file}" + (item.path?.let { " :: $it" } ?: "")
        "related" -> "関連情報: ${item.id}"
        "entity" -> "エンティティ: ${item.id}"
        "sample" -> "記述例（schema: ${item.schema}）"
        "graph" -> "グラフ: ${item.start}"
        else -> item.kind
    }

    /**
     * コンテキストレシピ項目を初期コンテキストの1区分へ解決する（R19-02 / architecture/agents.md「コンテキスト」）。
     * プレースホルダ（{target} 等）を解決できない項目は null（開示しない）。予算（budgetChars）超過は打切り。
     * doc / related / sample は既定でも予算8,000字を掛ける（全文系は上限なしにしない）。
     */
    fun contextItemSection(item: ContextItemDef, values: Map<String, String>): ContextSection? {
        fun tpl(text: String): String? = resolveContextTemplate(text, values)
        return when (item.kind) {
            "catalog" -> ContextSection(contextItemLabel(item), Readouts.capped(readouts.catalogText(item.match), item.budgetChars))
            "phase" -> ContextSection(
                contextItemLabel(item),
                Readouts.capped(readouts.phaseText(item.match ?: activity.targetMatch), item.budgetChars),
            )
            "view" -> {
                val rowsPattern = item.rows?.let { tpl(it) ?: return null }
                ContextSection(
                    contextItemLabel(item),
                    Readouts.capped(
                        readouts.viewText(item.view, rowsPattern = rowsPattern, columns = item.columns.takeIf { it.isNotEmpty() }),
                        item.budgetChars,
                    ),
                )
            }
            "doc" -> {
                val rel = tpl(item.file!!) ?: return null
                val path = item.path?.let { tpl(it) ?: return null }
                val root = staging.tree(rel) ?: error("ファイルが読めません: $rel")
                val parsed = parseYPath(path ?: "")
                ContextSection(
                    "文書: $rel" + (path?.let { " :: $it" } ?: ""),
                    Disclosure.nodeYaml(root, parsed, maxChars = item.budgetChars ?: 8_000) + readouts.refsFooterText(rel, parsed),
                )
            }
            "related" -> {
                val id = tpl(item.id!!) ?: return null
                ContextSection("関連情報: $id", Readouts.capped(readouts.relatedText(id), item.budgetChars ?: 8_000))
            }
            "entity" -> {
                val id = tpl(item.id!!) ?: return null
                ContextSection("エンティティ: $id", Readouts.capped(readouts.entityText(id), item.budgetChars ?: 8_000))
            }
            "sample" -> ContextSection(
                contextItemLabel(item),
                Readouts.capped(readouts.sampleText(item.schema!!, item.count), item.budgetChars ?: 8_000),
            )
            "graph" -> {
                val start = tpl(item.start!!) ?: return null
                ContextSection(
                    "グラフ: $start",
                    readouts.traverseText(start, item.steps.ifEmpty { TraverseSpec.DEFAULT_STEPS }, item.budgetChars ?: 8_000),
                )
            }
            else -> error("未知のコンテキスト項目です: ${item.kind}")
        }
    }
}
