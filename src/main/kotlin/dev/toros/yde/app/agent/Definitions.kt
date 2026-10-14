package dev.toros.yde.app.agent

import dev.toros.yde.app.ai.AiProvider
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
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
    DELEGATE("delegate"), // delegate（別エージェントへの読み取り専用の相談）
    ACTIONS("actions");   // スキーマに定義した操作（アクティビティの actions で許可したものだけがツールになる。architecture/actions.md）

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

/**
 * 提出ゲートの検証区分。ERROR_FREE = 検証エラー0（修正系）。NO_NEW_ERRORS = 適用前の原本との
 * 差分で新規エラーがないこと（点検系。既存エラーで提出を塞がない。R28-05b・UX-47）。
 */
enum class ValidationGate { ERROR_FREE, NO_NEW_ERRORS, NONE }

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

/**
 * 出力値による連鎖分岐の1ルール（R9-04）。条件はすべてANDで、上から順に評価し最初に一致した then へ連鎖する。
 * どのルールにも一致しないときは onSuccess / onFailure へフォールバックする。
 */
data class ChainRule(
    val then: String,                    // 分岐先アクティビティID
    val outcome: String = "any",         // success / failure / any: 実行の終わり方の条件
    val changedFiles: String? = null,    // 提出（採用）変更セットにこの glob に一致するファイルがあること
    val minChangedFiles: Int? = null,    // 提出（採用）変更ファイル数の下限
    val noteContains: String? = null,    // エージェントのメモ・提出メッセージにこの部分文字列があること
)

/** 実行の終わり方（と出力値。R9-04）に応じて次に起動するアクティビティ（宣言的な連鎖）。 */
data class ChainDef(
    val onSuccess: String? = null,
    val onFailure: String? = null,
    val rules: List<ChainRule> = emptyList(),
) {
    /**
     * 連鎖先の選択（R9-04）。changedFiles は採用（または提出）された変更ファイルの相対パス、
     * notesText はメモ・提出メッセージの連結。暴走防止（深さ上限・循環検出）は呼び出し側が行う。
     */
    fun selectNext(success: Boolean, changedFiles: List<String>, notesText: String): String? {
        for (rule in rules) {
            val outcomeOk = when (rule.outcome) {
                "success" -> success
                "failure" -> !success
                else -> true
            }
            val filesOk = rule.changedFiles?.let { glob ->
                runCatching { globToRegex(glob) }.getOrNull()
                    ?.let { regex -> changedFiles.any(regex::matches) } ?: false
            } ?: true
            val minOk = rule.minChangedFiles?.let { changedFiles.size >= it } ?: true
            val noteOk = rule.noteContains?.let { it in notesText } ?: true
            if (outcomeOk && filesOk && minOk && noteOk) return rule.then
        }
        return if (success) onSuccess else onFailure
    }

    val allTargets: List<String>
        get() = listOfNotNull(onSuccess, onFailure) + rules.map { it.then }
}

/** 無人実行時の再試行方針。失敗理由（と検証エラー）をフィードバックして最大 maxAttempts 回まで再実行する。 */
data class RetryDef(
    val maxAttempts: Int = 1,
)

/**
 * プレースホルダ（{name}）を対象値で置換する（R19-02）。未解決のプレースホルダが残る場合は null を返し、
 * 呼び出し側（コンテキスト項目の解決）は「その項目を開示しない」判断に使う。
 */
internal fun resolveContextTemplate(text: String, values: Map<String, String>): String? {
    var unresolved = false
    val out = Regex("""\{([a-zA-Z][a-zA-Z0-9_-]*)\}""").replace(text) { m ->
        values[m.groupValues[1]] ?: run { unresolved = true; m.value }
    }
    return if (unresolved) null else out
}

/** コンテキスト項目の開示条件（R19-02）。has-target = 対象の指定（foreach 対象値または追加指示）がある実行のみ。 */
enum class ContextWhen(val id: String) {
    ALWAYS("always"), HAS_TARGET("has-target"), NO_TARGET("no-target");

    companion object {
        fun parse(raw: String): ContextWhen = entries.firstOrNull { it.id == raw }
            ?: error("未知の開示条件です: $raw（always / has-target / no-target）")
    }
}

/**
 * コンテキストレシピの1項目（R19-02 / architecture/agents.md「コンテキスト」）。kind ごとに使えるキーをパースで制限する。
 * 文字列パラメータ（rows / file / path / id）にはプレースホルダ（{target} / {id} / {file} 等。foreach の
 * 対象値に連動）を書ける。実行時に解決できないプレースホルダが残る項目は開示から除外される（Kernel 側）。
 */
data class ContextItemDef(
    val kind: String,                        // catalog / phase / view / doc / related / entity / sample / graph
    val match: String? = null,               // catalog / phase: 対象ファイル glob（phase 省略時は target.match）
    val view: String? = null,                // view: ビューID
    val rows: String? = null,                // view: 行IDの絞り込みパターン（プレースホルダ可）
    val columns: List<String> = emptyList(), // view: 列ラベルの絞り込み（空 = 全列）
    val file: String? = null,                // doc: ワークスペース相対パス（プレースホルダ可）
    val path: String? = null,                // doc: YPath（省略時は文書全体）
    val id: String? = null,                  // related / entity: 論理ID（プレースホルダ可）
    val schema: String? = null,              // sample: スキーマID
    val count: Int = 1,                      // sample: 件数
    val start: String? = null,               // graph: トラバース起点ID（プレースホルダ可）
    val steps: List<TraverseStep> = emptyList(),  // graph: 経路ステップ（空 = 既定経路）
    val condition: ContextWhen = ContextWhen.ALWAYS,
    val budgetChars: Int? = null,            // 項目の文字数予算（超過は打切り）
)

data class ActivityDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val agentId: String,
    val targetMatch: String? = null,            // 対象ファイルの glob（初期文脈の提示に使う）
    val instruction: String,
    /**
     * コンテキストレシピ（R19-02）。宣言順に初期コンテキストへ開示される。
     * **無指定の既定は空**（指示＋権限＋ゲートのみ）——カタログも明示宣言制（P19 の空既定）。
     */
    val contextItems: List<ContextItemDef> = emptyList(),
    val allowedFiles: List<String> = emptyList(),  // 書込許可ファイル glob（空 = 制限なし）
    val allowedPaths: List<String> = emptyList(),  // 書込許可 YPath glob（空 = 制限なし）
    val tools: Set<ToolGroup> = ToolGroup.entries.toSet(),
    /**
     * エージェントに渡す操作（アクションの id か 型名.操作名。architecture/actions.md）。書いたものだけがツールになる（無指定は無し）。
     * 許可した操作の書込は allowedFiles / allowedPaths の外でも通す（操作の定義が範囲を決める。定義は人間だけが書ける）。
     */
    val actions: List<String> = emptyList(),
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
    /**
     * 一括実行の対象列挙（R18-01）。手動起動時に対象を列挙し、1件=1実行としてキューへ展開する。
     * 自動起動時の展開は未対応のため trigger: manual 専用（パースで強制）。
     */
    val foreach: ForeachDef? = null,
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
        val layout = WorkspaceLayout.of(workspace)
        val errors = mutableListOf<String>()
        val agents = loadDir(workspace.resolve(layout.agentsDir), errors, ::parseAgent)
        val activities = loadDir(workspace.resolve(layout.activitiesDir), errors, ::parseActivity)
        activities.forEach { activity ->
            if (agents.none { it.id == activity.agentId }) {
                errors += "${activity.source.fileName}: エージェント「${activity.agentId}」が ${layout.agentsDir} に見つかりません"
            }
            activity.chain.allTargets.distinct().forEach { next ->
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
            "no-new-errors" -> ValidationGate.NO_NEW_ERRORS
            "none" -> ValidationGate.NONE
            else -> error("gates.validation は error-free / no-new-errors / none のいずれかである必要があります: $v")
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
        val foreach = (raw["foreach"] as? Map<*, *>)?.let { parseForeach(it) }
        if (foreach != null && trigger.type != TriggerType.MANUAL) {
            error("foreach は trigger: manual のアクティビティでのみ使えます（自動起動時の展開は未対応）")
        }
        return ActivityDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            agentId = raw["agent"]?.toString()?.takeIf { it.isNotBlank() } ?: error("agent が必要です"),
            targetMatch = (raw["target"] as? Map<*, *>)?.get("match")?.toString(),
            instruction = raw["instruction"]?.toString()?.takeIf { it.isNotBlank() } ?: error("instruction が必要です"),
            contextItems = parseContextItems(raw["context"]),
            allowedFiles = stringList(raw["allowedFiles"]),
            allowedPaths = stringList(raw["allowedPaths"]),
            tools = toolGroups(raw["tools"]),
            actions = stringList(raw["actions"]).also { list ->
                list.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("actions に「$it」が重複しています") }
            },
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
                rules = parseChainRules(chainMap?.get("rules")),
            ),
            retry = RetryDef(maxAttempts = maxAttempts),
            meta = meta,
            foreach = foreach,
            source = source,
        )
    }

    /**
     * コンテキストレシピ（R19-02）のパース。旧語彙（include / views）は破壊的に廃止し、
     * 移行ヒント付きの定義エラーにする（P19: 全量ダンプ形式を残さない）。
     * 評価セットの変異（EvalVariant.contextItems。R19-06）からも同じパースを使う。
     */
    internal fun parseContextItems(raw: Any?): List<ContextItemDef> {
        val contextMap = raw as? Map<*, *> ?: return emptyList()
        if (contextMap.containsKey("include") || contextMap.containsKey("views")) {
            error(
                "context.include / context.views は廃止されました（P19 R19-02）。context.items へ移行してください。" +
                    "例: include: [catalog] → items: [{kind: catalog}] ／ views: [x] → items: [{kind: view, view: x}]（README §12）"
            )
        }
        contextMap.keys.map { it.toString() }.firstOrNull { it != "items" }?.let {
            error("context に未知のキーがあります: $it（使えるキー: items）")
        }
        val itemsRaw = contextMap["items"] ?: return emptyList()
        val list = itemsRaw as? List<*> ?: error("context.items は配列である必要があります")
        return list.mapIndexed { i, e -> parseContextItem(e, i) }
    }

    private fun parseContextItem(element: Any?, index: Int): ContextItemDef {
        val m = element as? Map<*, *> ?: error("context.items[$index] はobjectである必要があります")
        val kind = m["kind"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("context.items[$index] に kind が必要です（catalog / phase / view / doc / related / entity / sample / graph）")
        val common = setOf("kind", "when", "budgetChars")
        val allowed = when (kind) {
            "catalog", "phase" -> common + "match"
            "view" -> common + setOf("view", "rows", "columns")
            "doc" -> common + setOf("file", "path")
            "related", "entity" -> common + "id"
            "sample" -> common + setOf("schema", "count")
            "graph" -> common + setOf("start", "steps")
            else -> error("context.items[$index]: 未知の kind です: $kind（catalog / phase / view / doc / related / entity / sample / graph）")
        }
        m.keys.map { it.toString() }.firstOrNull { it !in allowed }?.let {
            error("context.items[$index]: kind: $kind では「$it」は使えません（使えるキー: ${allowed.joinToString(", ")}）")
        }
        val count = (m["count"] as? Number)?.toInt() ?: 1
        require(count >= 1) { "context.items[$index].count は1以上である必要があります" }
        val budget = (m["budgetChars"] as? Number)?.toInt()
        if (budget != null) require(budget >= 100) { "context.items[$index].budgetChars は100以上である必要があります" }
        val def = ContextItemDef(
            kind = kind,
            match = m["match"]?.toString()?.takeIf { it.isNotBlank() },
            view = m["view"]?.toString()?.takeIf { it.isNotBlank() },
            rows = m["rows"]?.toString()?.takeIf { it.isNotBlank() },
            columns = stringList(m["columns"]),
            file = m["file"]?.toString()?.takeIf { it.isNotBlank() },
            path = m["path"]?.toString()?.takeIf { it.isNotBlank() },
            id = m["id"]?.toString()?.takeIf { it.isNotBlank() },
            schema = m["schema"]?.toString()?.takeIf { it.isNotBlank() },
            count = count,
            start = m["start"]?.toString()?.takeIf { it.isNotBlank() },
            steps = (m["steps"] as? List<*>)?.mapIndexed { i, e ->
                TraverseStep.parse(e, "context.items[$index].steps[$i]")
            } ?: emptyList(),
            condition = m["when"]?.toString()?.let { ContextWhen.parse(it) } ?: ContextWhen.ALWAYS,
            budgetChars = budget,
        )
        when (kind) {
            "view" -> require(def.view != null) { "context.items[$index]: kind: view には view（ビューID）が必要です" }
            "doc" -> require(def.file != null) { "context.items[$index]: kind: doc には file が必要です" }
            "related" -> require(def.id != null) { "context.items[$index]: kind: related には id が必要です" }
            "entity" -> require(def.id != null) { "context.items[$index]: kind: entity には id（論理ID）が必要です" }
            "sample" -> require(def.schema != null) { "context.items[$index]: kind: sample には schema が必要です" }
            "graph" -> {
                require(def.start != null) { "context.items[$index]: kind: graph には start（起点ID）が必要です" }
                def.steps.forEachIndexed { i, step ->
                    if (i > 0 && step.follow == null) {
                        error("context.items[$index].steps[$i]: 2ステップ目以降には follow（out / in）が必要です")
                    }
                }
            }
        }
        return def
    }

    /**
     * 一括実行の対象列挙（foreach。R18-01）のパース。列挙元ごとに使えるキーを制限し、
     * 取り違え（例 source: files に idPattern）を定義エラーで検出する。
     */
    private fun parseForeach(raw: Map<*, *>): ForeachDef {
        val source = ForeachSource.parse(raw["source"]?.toString() ?: error("foreach.source が必要です"))
        val allowedKeys = when (source) {
            ForeachSource.VIEW -> setOf("source", "view", "where", "as", "limit")
            ForeachSource.ISSUES -> setOf("source", "severity", "match", "as", "limit")
            ForeachSource.SEARCH -> setOf("source", "text", "pathGlob", "refTo", "unresolved", "match", "schemaIds", "as", "limit")
            ForeachSource.FILES -> setOf("source", "match", "as", "limit")
            ForeachSource.IDS -> setOf("source", "idPattern", "schemaIds", "as", "limit")
            ForeachSource.QUERY -> setOf("source", "query", "as", "limit")
        }
        raw.keys.map { it.toString() }.firstOrNull { it !in allowedKeys }?.let {
            error("foreach: source: ${source.id} では「$it」は使えません（使えるキー: ${allowedKeys.joinToString(", ")}）")
        }
        val where = raw["where"]?.toString() ?: "detail-missing"
        require(where in setOf("detail-missing", "all")) { "foreach.where は detail-missing / all のいずれかです: $where" }
        val severity = raw["severity"]?.toString() ?: "error"
        require(severity in setOf("error", "warning")) { "foreach.severity は error / warning のいずれかです: $severity" }
        val limit = (raw["limit"] as? Number)?.toInt() ?: 20
        require(limit >= 1) { "foreach.limit は1以上である必要があります" }
        val def = ForeachDef(
            source = source,
            view = raw["view"]?.toString()?.takeIf { it.isNotBlank() },
            where = where,
            severity = severity,
            match = raw["match"]?.toString()?.takeIf { it.isNotBlank() },
            idPattern = raw["idPattern"]?.toString()?.takeIf { it.isNotBlank() },
            schemaIds = stringList(raw["schemaIds"]),
            text = raw["text"]?.toString()?.takeIf { it.isNotBlank() },
            pathGlob = raw["pathGlob"]?.toString()?.takeIf { it.isNotBlank() },
            refTo = raw["refTo"]?.toString()?.takeIf { it.isNotBlank() },
            unresolved = raw["unresolved"] as? Boolean ?: false,
            // パターン照合の列挙（R22-02）: スペックのパースはクエリコアと共用（schema/Query.kt）
            query = (raw["query"] as? Map<*, *>)?.let { QuerySpec.parse(it, "foreach.query") },
            asTemplate = raw["as"]?.toString()?.takeIf { it.isNotBlank() },
            limit = limit,
        )
        when (source) {
            ForeachSource.VIEW -> require(def.view != null) { "foreach: source: view には view（ビューID）が必要です" }
            ForeachSource.FILES -> require(def.match != null) { "foreach: source: files には match（glob）が必要です" }
            ForeachSource.IDS -> require(def.idPattern != null) { "foreach: source: ids には idPattern が必要です" }
            ForeachSource.SEARCH -> require(
                def.text != null || def.refTo != null || def.unresolved || def.pathGlob != null ||
                    def.match != null || def.schemaIds.isNotEmpty()
            ) { "foreach: source: search には検索条件（text / pathGlob / refTo / unresolved / match / schemaIds）が1つ以上必要です" }
            ForeachSource.QUERY -> {
                require(def.query != null) { "foreach: source: query には query（match の連鎖）が必要です" }
                require(def.query.binds.isNotEmpty()) { "foreach.query の match には bind（束縛名）が1つ以上必要です" }
            }
            ForeachSource.ISSUES -> Unit
        }
        return def
    }

    /** 出力値による連鎖分岐（R9-04）のパース。 */
    private fun parseChainRules(raw: Any?): List<ChainRule> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("chain.rules は配列である必要があります")
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("chain.rules[$i] はobjectである必要があります")
            val then = m["then"]?.toString()?.takeIf { it.isNotBlank() } ?: error("chain.rules[$i] に then が必要です")
            val whenMap = m["when"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val outcome = whenMap["outcome"]?.toString() ?: "any"
            require(outcome in setOf("success", "failure", "any")) {
                "chain.rules[$i].when.outcome は success / failure / any のいずれかです: $outcome"
            }
            val minChanged = (whenMap["minChangedFiles"] as? Number)?.toInt()
            if (minChanged != null) require(minChanged >= 1) { "chain.rules[$i].when.minChangedFiles は1以上である必要があります" }
            ChainRule(
                then = then,
                outcome = outcome,
                changedFiles = whenMap["changedFiles"]?.toString(),
                minChangedFiles = minChanged,
                noteContains = whenMap["noteContains"]?.toString(),
            )
        }
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
