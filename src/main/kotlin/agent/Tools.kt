package agent

import ai.ToolCall
import ai.ToolSpec
import ai.jsonToYNode
import core.Disclosure
import core.PatchOp
import core.Search
import core.SearchQuery
import core.Workspace
import index.IdIndex
import index.idMatchesPattern
import kotlinx.serialization.json.*
import model.parseYPath
import model.pretty
import schema.SchemaLoader
import schema.globToRegex
import validate.Severity
import yaml.YamlIO
import java.nio.file.Files

/**
 * エージェントに公開するツール一式（R2-04）。
 * - 読み取り系はP1のAPI（Disclosure / Search）をステージング（オーバーレイ）越しに呼ぶ薄い層
 * - 書き込み系はここで権限を強制する: 許可ファイルglob・許可パスglob・予約ディレクトリへの書込禁止。
 *   モデルへの指示ではなくツール側で拒否するのがサンドボックスの要（設計原則3）
 */
class AgentToolbox(
    private val ws: Workspace,
    val staging: Staging,
    private val activity: ActivityDef,
    private val groups: Set<ToolGroup>,
    private val subAgents: List<AgentDef> = emptyList(),
    private val delegate: (suspend (agentId: String, instruction: String) -> String)? = null,
    private val consultMode: Boolean = false,   // 相談モード: 読み取り専用（explore / validate のみ）
) {
    /** 相談モードで許可するツール名。 */
    private val consultAllowed = setOf("list_documents", "read_outline", "read_node", "search", "related", "validate")
    /**
     * 予約ディレクトリ（書込禁止・カタログから除外。read_outline 等での読み取りは可）。
     * _schemas / _runs は常に対象。定義類（_agents / _activities / _evals）は
     * メタアクティビティ（meta: true。R4-03）では作業対象なので除外しない。
     */
    private val reservedPrefixes = buildList {
        add(SchemaLoader.SCHEMA_DIR)
        add(SchemaLoader.RUNS_DIR)
        if (!activity.meta) {
            add(SchemaLoader.AGENTS_DIR)
            add(SchemaLoader.ACTIVITIES_DIR)
            add(SchemaLoader.EVALS_DIR)
        }
    }.map { "$it/" }

    /** ツール実行の結果。terminal が非null のときはループを終了する。 */
    data class Outcome(val content: String, val terminal: Terminal? = null)
    data class Terminal(val status: RunStatus, val message: String)

    val plan = mutableListOf<String>()
    val notes = mutableListOf<String>()

    // ---------------------------------------------------------------- ツール定義（モデルに公開するスキーマ）

    fun specs(): List<ToolSpec> {
        val specs = mutableListOf<ToolSpec>()
        if (ToolGroup.EXPLORE in groups) {
            specs += ToolSpec("list_documents", "ワークスペースの文書カタログ（ファイル・文書ID・名前・概要・スキーマ・ID数）を返す。まず全体像を掴むのに使う。", obj {})
            specs += ToolSpec("read_outline", "文書の骨子（キー構造・配列件数・短い値。長文は文字数のみ）を返す。全文を読む前の構造把握に使う。", obj {
                required("file", "ワークスペース相対パス")
            })
            specs += ToolSpec("read_node", "指定パス配下の全文をYAMLで返す。path 省略時は文書全体。", obj {
                required("file", "ワークスペース相対パス")
                optional("path", "YPath（例 items[2].title）。省略時はルート")
            })
            specs += ToolSpec("search", "ワークスペースを検索する。text は本文・キーの部分一致。ref_to はそのIDへの参照を、unresolved=true は未解決参照を検索する。ステージング済みの編集も反映される。", obj {
                optional("text", "検索文字列")
                optional("schema", "スキーマIDで絞り込み")
                optional("file_glob", "ファイルパスのglob")
                optional("ref_to", "このIDへの参照を検索")
                optionalBool("unresolved", "未解決参照のみ")
            })
            specs += ToolSpec("related", "IDの関連情報（全出現・その要素からの参照・被参照）を返す。影響分析に使う。", obj {
                required("id", "論理ID")
            })
        }
        if (ToolGroup.EDIT in groups && !consultMode) {
            val applyPatchSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("file") { put("type", "string"); put("description", "ワークスペース相対パス") }
                    putJsonObject("ops") {
                        put("type", "array")
                        put("description", "パッチ操作のリスト")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("op") { put("type", "string"); putJsonArray("enum") { add("set"); add("insert"); add("delete"); add("move") } }
                                putJsonObject("path") { put("type", "string"); put("description", "YPath（set/insert/delete。例 items[2].title）") }
                                putJsonObject("value") { put("description", "設定する値（set/insert。任意のJSON値）") }
                                putJsonObject("from") { put("type", "string"); put("description", "移動元YPath（move）") }
                                putJsonObject("to") { put("type", "string"); put("description", "移動先YPath（move）") }
                            }
                            putJsonArray("required") { add("op") }
                        }
                    }
                }
                putJsonArray("required") { add("file"); add("ops") }
            }
            specs += ToolSpec("apply_patch", "ステージング（作業コピー）にパッチを適用する。原本は提出と人間の承認まで変更されない。opは set（置換・作成）/ insert（配列挿入・新キー）/ delete / move。", applyPatchSchema)
            specs += ToolSpec("create_document", "新規YAML文書をステージングに作成する。", obj {
                required("file", "ワークスペース相対パス（.yaml）")
                required("content_yaml", "文書全体のYAMLテキスト")
            })
        }
        if (ToolGroup.VALIDATE in groups) {
            specs += ToolSpec("validate", "ステージング済みのファイルと、その変更の影響を受けるファイル（変更前後のIDを参照している文書）を、編集を重ねた状態の索引で検証する（スキーマ・参照・重複・カバレッジ）。提出前に必ず実行する。", obj {})
        }
        if (ToolGroup.ASK in groups && !consultMode) {
            specs += ToolSpec("ask_human", "人間に質問して回答を待つ。判断が分かれる点や不足情報があるときだけ使う。", obj {
                required("question", "質問文（選択肢があれば列挙する）")
            })
        }
        if (ToolGroup.DELEGATE in groups && !consultMode && delegate != null && subAgents.isNotEmpty()) {
            specs += ToolSpec(
                "delegate",
                "別のエージェントに読み取り専用の相談・レビューを依頼し、所見テキストを受け取る。" +
                    "相談相手はステージング済みの編集内容も読める（例: 執筆後にレビュー担当へ推敲を依頼）。" +
                    "利用可能: ${subAgents.joinToString(", ") { "${it.id}（${it.name}）" }}",
                obj {
                    required("agent", "相談先エージェントのID")
                    required("instruction", "依頼内容（何をどの観点で見てほしいか）")
                },
            )
        }
        if (consultMode) return specs
        specs += ToolSpec("plan", "作業計画（TODO）を宣言・更新する。人間に進捗として表示される。", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("steps") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
            putJsonArray("required") { add("steps") }
        })
        specs += ToolSpec("note", "前提・判断・根拠を作業メモとして記録する（実行記録に残る）。", obj {
            required("text", "メモ")
        })
        specs += ToolSpec("submit_changeset", "ステージング済みの変更を変更セットとして提出し、作業を終了する。提出ゲート（検証）を通らない場合は拒否される。", obj {
            required("summary", "変更内容の要約（人間のレビュー向け）")
        })
        specs += ToolSpec("abort", "作業を中断して終了する。", obj {
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
        return when (call.name) {
            "delegate" -> {
                val fn = delegate ?: error("このアクティビティでは委譲できません")
                Outcome(fn(requireArg("agent"), requireArg("instruction")))
            }
            "list_documents" -> Outcome(catalogText())
            "read_outline" -> {
                val rel = requireArg("file")
                val root = staging.tree(rel) ?: error("ファイルが読めません: $rel")
                Outcome(Disclosure.outline(root))
            }
            "read_node" -> {
                val rel = requireArg("file")
                val root = staging.tree(rel) ?: error("ファイルが読めません: $rel")
                Outcome(Disclosure.nodeYaml(root, parseYPath(arg("path") ?: ""), maxChars = 20_000))
            }
            "search" -> {
                val query = SearchQuery(
                    text = arg("text"),
                    schemaIds = listOfNotNull(arg("schema")),
                    fileGlob = arg("file_glob"),
                    refsTo = arg("ref_to"),
                    unresolvedRefs = call.arguments["unresolved"]?.jsonPrimitive?.booleanOrNull ?: false,
                    limit = 40,
                )
                val files = (ws.files + staging.touchedFiles().map { it.file }).distinct()
                val hits = Search.run(ws, query, files = files, index = staging.index(), loader = { f ->
                    staging.tree(ws.relPath(f))
                })
                Outcome(
                    if (hits.isEmpty()) "該当なし"
                    else hits.joinToString("\n") { "- ${it.file} :: ${it.path} [${it.kind}] ${it.excerpt}" }
                )
            }
            "related" -> Outcome(relatedText(requireArg("id")))
            "apply_patch" -> {
                val rel = requireArg("file")
                checkWritableFile(rel)
                val opsJson = call.arguments["ops"] as? JsonArray ?: error("引数 ops（配列）が必要です")
                val ops = opsJson.mapIndexed { i, e -> parsePatchOp(e, i) }
                ops.forEach { checkWritablePaths(it) }
                staging.applyOps(rel, ops)
                Outcome("適用しました: $rel に ${ops.size} 操作（ステージング上。原本は未変更）")
            }
            "create_document" -> {
                val rel = requireArg("file")
                checkWritableFile(rel)
                val root = YamlIO.parse(requireArg("content_yaml"))
                staging.createDocument(rel, root)
                Outcome("作成しました: $rel（ステージング上）")
            }
            "validate" -> Outcome(validationText())
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
                Outcome("提出しました", Terminal(RunStatus.SUBMITTED, summary))
            }
            "abort" -> Outcome("中断しました", Terminal(RunStatus.ABORTED, requireArg("reason")))
            else -> error("未知のツールです: ${call.name}")
        }
    }

    // ---------------------------------------------------------------- 権限チェック

    private fun checkWritableFile(rel: String) {
        val normalized = rel.replace('\\', '/')
        reservedPrefixes.forEach { prefix ->
            if (normalized.startsWith(prefix)) error("予約ディレクトリへの書き込みはできません: $rel")
        }
        if (activity.allowedFiles.isNotEmpty() && activity.allowedFiles.none { globToRegex(it).matches(normalized) }) {
            error("このアクティビティの書込許可（allowedFiles）の対象外です: $rel")
        }
    }

    private fun checkWritablePaths(op: PatchOp) {
        if (activity.allowedPaths.isEmpty()) return
        val paths = when (op) {
            is PatchOp.Set -> listOf(op.path)
            is PatchOp.Insert -> listOf(op.path)
            is PatchOp.Delete -> listOf(op.path)
            is PatchOp.Move -> listOf(op.from, op.to)
        }
        paths.forEach { p ->
            val pretty = p.pretty()
            if (activity.allowedPaths.none { idMatchesPattern(pretty, it) }) {
                error("このアクティビティの書込許可（allowedPaths）の対象外です: $pretty")
            }
        }
    }

    private fun parsePatchOp(element: JsonElement, index: Int): PatchOp {
        val obj = element as? JsonObject ?: error("ops[$index]はオブジェクトである必要があります")
        fun path(key: String) = parseYPath(obj[key]?.jsonPrimitive?.contentOrNull ?: error("ops[$index].$key が必要です"))
        fun value() = jsonToYNode(obj["value"] ?: error("ops[$index].value が必要です"))
        return when (val op = obj["op"]?.jsonPrimitive?.contentOrNull) {
            "set" -> PatchOp.Set(path("path"), value())
            "insert" -> PatchOp.Insert(path("path"), value())
            "delete" -> PatchOp.Delete(path("path"))
            "move" -> PatchOp.Move(path("from"), path("to"))
            else -> error("ops[$index].op は set / insert / delete / move のいずれかです: $op")
        }
    }

    // ---------------------------------------------------------------- 表示テキスト

    /** カタログ（オーバーレイ反映・予約ディレクトリ除外）。 */
    fun catalogText(): String {
        val reserved = reservedPrefixes
        val files = (ws.files + staging.touchedFiles().map { it.file }).distinct()
            .filter { f -> reserved.none { ws.relPath(f).startsWith(it) } }
        if (files.isEmpty()) return "（文書がありません）"
        return files.joinToString("\n") { f ->
            val rel = ws.relPath(f)
            val root = staging.tree(rel)
            val name = root?.let(IdIndex::documentNameOf)
            val description = root?.let(IdIndex::documentDescriptionOf)?.replace("\n", " ")?.take(120)
            val schemaId = ws.schemaFor(f)?.id
            buildString {
                append("- ").append(rel)
                schemaId?.let { append(" [schema: ").append(it).append(']') }
                name?.let { append(" — ").append(it) }
                description?.let { append(": ").append(it) }
            }
        }
    }

    private fun relatedText(id: String): String {
        val idx = staging.index()
        val occurrences = idx.occurrences(id)
        if (occurrences.isEmpty()) return "ID「$id」は見つかりません"
        val backlinks = idx.backlinksTo(setOf(id))
        return buildString {
            appendLine("出現（${occurrences.size}件）:")
            occurrences.forEach { e ->
                append("- ").append(ws.relPath(e.file)).append(" :: ").append(e.path.pretty())
                e.title?.let { append(" — ").append(it) }
                e.schemaId?.let { append(" [schema: ").append(it).append(']') }
                appendLine()
            }
            val refsOut = occurrences.flatMap { e ->
                idx.refs.filter {
                    it.file == e.file && it.path.size >= e.path.size && it.path.subList(0, e.path.size) == e.path
                }
            }
            if (refsOut.isNotEmpty()) {
                appendLine("この要素からの参照:")
                refsOut.forEach { r ->
                    append("- ").append(r.targetId).append("（").append(r.viaKey)
                    r.relation?.let { append("・関係: ").append(it) }
                    appendLine("）")
                }
            }
            appendLine("被参照（${backlinks.size}件）:")
            backlinks.forEach { b ->
                append("- ").append(ws.relPath(b.file)).append(" :: ").append(b.path.pretty())
                    .append("（").append(b.viaKey)
                b.relation?.let { append("・関係: ").append(it) }
                appendLine("）")
            }
        }.trimEnd()
    }

    fun validationText(): String {
        if (staging.isEmpty()) return "ステージングに変更がありません"
        val results = staging.validateAll()
        val lines = results.flatMap { (rel, issues) ->
            issues.map { "- [$rel] ${it.severity} ${it.path.pretty()}: ${it.message}" }
        }
        val errors = results.values.flatten().count { it.severity == Severity.ERROR }
        val warnings = results.values.flatten().count { it.severity == Severity.WARNING }
        return if (lines.isEmpty()) "問題なし（対象 ${results.size} ファイル）"
        else "エラー $errors 件・警告 $warnings 件:\n" + lines.take(40).joinToString("\n")
    }
}

// ---------------------------------------------------------------- JSON Schema 組み立ての補助

private class ObjBuilder {
    val properties = mutableListOf<Triple<String, String, String>>() // name, type, description
    val requiredNames = mutableListOf<String>()
    fun required(name: String, description: String) {
        properties += Triple(name, "string", description); requiredNames += name
    }
    fun optional(name: String, description: String) {
        properties += Triple(name, "string", description)
    }
    fun optionalBool(name: String, description: String) {
        properties += Triple(name, "boolean", description)
    }
}

private fun obj(build: ObjBuilder.() -> Unit): JsonObject {
    val builder = ObjBuilder().apply(build)
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            builder.properties.forEach { (name, type, description) ->
                putJsonObject(name) { put("type", type); put("description", description) }
            }
        }
        putJsonArray("required") { builder.requiredNames.forEach(::add) }
    }
}
