package agent

import ai.AiProvider
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エージェント／アクティビティ定義（R2-01 / R2-02）。
 * `_agents` / `_activities` 配下に置かれたスキーマ付きYAML文書を実行時の型へ読み込む。
 * フォーム編集・検証は組込みスキーマ（schema.BuiltinSchemas）が担い、本ローダは実行時の正とする。
 */

/** エージェントに公開するツールの分類。manage 系（plan / note / submit / abort）は常に許可。 */
enum class ToolGroup(val id: String) {
    EXPLORE("explore"),   // list_documents / read_outline / read_node / search / related
    EDIT("edit"),         // apply_patch / create_document
    VALIDATE("validate"), // validate
    ASK("ask"),           // ask_human
    DELEGATE("delegate"); // delegate（別エージェントへの読み取り専用の相談）

    companion object {
        fun parse(raw: String): ToolGroup =
            entries.firstOrNull { it.id == raw } ?: error("未知のツール分類です: $raw")
    }
}

data class AgentDef(
    val id: String,
    val name: String,
    val persona: String,
    val rubric: List<String> = emptyList(),
    val provider: AiProvider? = null,   // 省略時は実行時の設定を使う
    val model: String? = null,
    val tools: Set<ToolGroup> = ToolGroup.entries.toSet(),
    val source: Path,
)

data class ActivityLimits(
    val maxTurns: Int = 24,
    val maxTokens: Long = 400_000,
    val maxSeconds: Long = 900,
)

enum class ValidationGate { ERROR_FREE, NONE }

/** 提出後の承認方式。AUTO はゲート通過・スコープ内・非編集中のときのみ自動適用（条件を欠くと承認待ちへ降格）。 */
enum class ApprovalMode { HUMAN, AUTO }

enum class TriggerType(val id: String) {
    MANUAL("manual"),        // 手動起動のみ
    ON_CHANGE("on-change"),  // 対象ファイルの更新（デバウンス付き）
    SCHEDULE("schedule"),    // 周期実行
    ON_INVALID("on-invalid");// 対象ファイルの更新後、検証エラーがあるときのみ

    companion object {
        fun parse(raw: String): TriggerType =
            entries.firstOrNull { it.id == raw } ?: error("未知のトリガ種別です: $raw")
    }
}

data class TriggerDef(
    val type: TriggerType = TriggerType.MANUAL,
    val match: String? = null,          // 監視対象の glob（省略時は target.match、それも無ければ全YAML）
    val debounceSeconds: Long = 30,     // on-change / on-invalid: 変更が落ち着くまでの待ち
    val everyMinutes: Long? = null,     // schedule: 周期（分）
)

/** 実行の終わり方に応じて次に起動するアクティビティ（宣言的な連鎖）。 */
data class ChainDef(
    val onSuccess: String? = null,
    val onFailure: String? = null,
)

/** 無人実行時の再試行方針。失敗理由（と検証エラー）をフィードバックして最大 maxAttempts 回まで再実行する。 */
data class RetryDef(
    val maxAttempts: Int = 1,
)

data class ActivityDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val agentId: String,
    val targetMatch: String? = null,            // 対象ファイルの glob（初期文脈の提示に使う）
    val instruction: String,
    val contextInclude: List<String> = listOf("catalog"),
    val contextViews: List<String> = emptyList(),  // 初期文脈として開示するビュー（_views）のID
    val allowedFiles: List<String> = emptyList(),  // 書込許可ファイル glob（空 = 制限なし）
    val allowedPaths: List<String> = emptyList(),  // 書込許可 YPath glob（空 = 制限なし）
    val tools: Set<ToolGroup> = ToolGroup.entries.toSet(),
    val limits: ActivityLimits = ActivityLimits(),
    val gate: ValidationGate = ValidationGate.ERROR_FREE,
    val approval: ApprovalMode = ApprovalMode.HUMAN,
    val trigger: TriggerDef = TriggerDef(),
    val chain: ChainDef = ChainDef(),
    val retry: RetryDef = RetryDef(),
    /**
     * メタアクティビティ（R4-03）: _agents / _activities / _evals / _skills / _views の定義文書への書込を許可する。
     * _schemas / _runs は常に不可。承認は human 固定（エージェントが自分の権限・挙動を変える
     * 変更を無人適用させないため、meta かつ approval: auto は定義エラーとする）。
     */
    val meta: Boolean = false,
    val source: Path,
)

object Definitions {

    data class LoadResult(
        val agents: List<AgentDef>,
        val activities: List<ActivityDef>,
        val errors: List<String>,
    ) {
        fun agent(id: String): AgentDef? = agents.firstOrNull { it.id == id }
        fun activity(id: String): ActivityDef? = activities.firstOrNull { it.id == id }
    }

    fun load(workspace: Path): LoadResult {
        val errors = mutableListOf<String>()
        val agents = loadDir(workspace.resolve(SchemaLoader.AGENTS_DIR), errors, ::parseAgent)
        val activities = loadDir(workspace.resolve(SchemaLoader.ACTIVITIES_DIR), errors, ::parseActivity)
        activities.forEach { activity ->
            if (agents.none { it.id == activity.agentId }) {
                errors += "${activity.source.fileName}: エージェント「${activity.agentId}」が _agents に見つかりません"
            }
            listOfNotNull(activity.chain.onSuccess, activity.chain.onFailure).forEach { next ->
                if (activities.none { it.id == next }) {
                    errors += "${activity.source.fileName}: 連鎖先のアクティビティ「$next」が見つかりません"
                }
            }
        }
        return LoadResult(agents, activities, errors)
    }

    private fun <T> loadDir(dir: Path, errors: MutableList<String>, parse: (Map<*, *>, Path) -> T): List<T> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        return files.mapNotNull { file ->
            runCatching {
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parse(raw, file)
            }.onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }.getOrNull()
        }
    }

    internal fun parseAgent(raw: Map<*, *>, source: Path): AgentDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val modelMap = raw["model"] as? Map<*, *>
        val provider = modelMap?.get("provider")?.toString()?.let { value ->
            AiProvider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("未知のプロバイダです: $value")
        }
        return AgentDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            persona = raw["persona"]?.toString()?.takeIf { it.isNotBlank() } ?: error("persona が必要です"),
            rubric = stringList(raw["rubric"]),
            provider = provider,
            model = modelMap?.get("id")?.toString(),
            tools = toolGroups(raw["tools"]),
            source = source,
        )
    }

    internal fun parseActivity(raw: Map<*, *>, source: Path): ActivityDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val limitsMap = raw["limits"] as? Map<*, *>
        fun limit(key: String): Long? = (limitsMap?.get(key) as? Number)?.toLong()
        val gate = when (val v = (raw["gates"] as? Map<*, *>)?.get("validation")?.toString()) {
            null, "error-free" -> ValidationGate.ERROR_FREE
            "none" -> ValidationGate.NONE
            else -> error("gates.validation は error-free / none のいずれかである必要があります: $v")
        }
        val approval = when (val v = raw["approval"]?.toString()) {
            null, "human" -> ApprovalMode.HUMAN
            "auto" -> ApprovalMode.AUTO
            else -> error("approval は human / auto のいずれかである必要があります: $v")
        }
        val trigger = parseTrigger(raw["trigger"])
        val chainMap = raw["chain"] as? Map<*, *>
        val retryMap = raw["retry"] as? Map<*, *>
        val maxAttempts = (retryMap?.get("maxAttempts") as? Number)?.toInt() ?: 1
        if (maxAttempts < 1) error("retry.maxAttempts は1以上である必要があります")
        val meta = raw["meta"] as? Boolean ?: false
        if (meta && approval == ApprovalMode.AUTO) {
            error("メタアクティビティ（meta: true）は approval: human のみ許可されます（定義変更の無人適用は不可）")
        }
        return ActivityDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            agentId = raw["agent"]?.toString()?.takeIf { it.isNotBlank() } ?: error("agent が必要です"),
            targetMatch = (raw["target"] as? Map<*, *>)?.get("match")?.toString(),
            instruction = raw["instruction"]?.toString()?.takeIf { it.isNotBlank() } ?: error("instruction が必要です"),
            contextInclude = (raw["context"] as? Map<*, *>)?.get("include")?.let(::stringList) ?: listOf("catalog"),
            contextViews = (raw["context"] as? Map<*, *>)?.get("views")?.let(::stringList) ?: emptyList(),
            allowedFiles = stringList(raw["allowedFiles"]),
            allowedPaths = stringList(raw["allowedPaths"]),
            tools = toolGroups(raw["tools"]),
            limits = ActivityLimits(
                maxTurns = limit("maxTurns")?.toInt() ?: 24,
                maxTokens = limit("maxTokens") ?: 400_000,
                maxSeconds = limit("maxSeconds") ?: 900,
            ),
            gate = gate,
            approval = approval,
            trigger = trigger,
            chain = ChainDef(
                onSuccess = chainMap?.get("onSuccess")?.toString(),
                onFailure = chainMap?.get("onFailure")?.toString(),
            ),
            retry = RetryDef(maxAttempts = maxAttempts),
            meta = meta,
            source = source,
        )
    }

    private fun parseTrigger(raw: Any?): TriggerDef = when (raw) {
        null -> TriggerDef()
        is String -> TriggerDef(type = TriggerType.parse(raw))
        is Map<*, *> -> {
            val type = TriggerType.parse(raw["type"]?.toString() ?: "manual")
            val every = (raw["everyMinutes"] as? Number)?.toLong()
            if (type == TriggerType.SCHEDULE && (every == null || every < 1)) {
                error("trigger.type: schedule には everyMinutes（1以上）が必要です")
            }
            TriggerDef(
                type = type,
                match = raw["match"]?.toString(),
                debounceSeconds = (raw["debounceSeconds"] as? Number)?.toLong() ?: 30,
                everyMinutes = every,
            )
        }
        else -> error("trigger は文字列またはobjectである必要があります")
    }

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    private fun toolGroups(raw: Any?): Set<ToolGroup> {
        val list = stringList(raw)
        if (list.isEmpty()) return ToolGroup.entries.toSet()
        return list.map { ToolGroup.parse(it) }.toSet()
    }
}
