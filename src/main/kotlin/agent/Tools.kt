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
import model.YNode
import model.getAt
import model.parseYPath
import model.pretty
import schema.SchemaLoader
import schema.globToRegex
import validate.Validator
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
    private val consultAllowed = setOf("list_documents", "read_outline", "read_node", "search", "related", "traverse", "read_view", "validate")
    /**
     * カタログから除外するか（read_outline 等での読み取りは可）。
     * schemas / runs ゾーン・構成ファイルは常に除外。定義ゾーン（agents / activities / evals / skills / views）は
     * メタアクティビティ（meta: true。R4-03）では作業対象なので除外しない。
     */
    private fun catalogExcluded(rel: String): Boolean {
        val layout = ws.layout
        return layout.indexExcluded(rel) || (!activity.meta && layout.isReserved(rel))
    }

    /** ツール実行の結果。terminal が非null のときはループを終了する。 */
    data class Outcome(val content: String, val terminal: Terminal? = null)
    data class Terminal(val status: RunStatus, val message: String)

    val plan = mutableListOf<String>()
    val notes = mutableListOf<String>()

    // ---------------------------------------------------------------- ツール定義（モデルに公開するスキーマ）

    fun specs(): List<ToolSpec> {
        val specs = mutableListOf<ToolSpec>()
        if (ToolGroup.EXPLORE in groups) {
            specs += ToolSpec("list_documents", "ワークスペースの文書カタログ（ファイル・文書ID・名前・概要・スキーマ・ID数）を返す。まず全体像を掴むのに使う。", obj {
                optional("match", "ファイルパスのglobで絞り込み（省略時は全文書）")
            })
            specs += ToolSpec("read_outline", "文書の骨子（キー構造・配列件数・短い値。長文は文字数のみ）を返す。全文を読む前の構造把握に使う。", obj {
                required("file", "ワークスペース相対パス")
            })
            specs += ToolSpec("read_node", "指定パス配下の全文をYAMLで返す。path 省略時は文書全体。末尾に配下の参照IDの解決（ID = 名前・定義元）が付くため、参照先を別途読む前にまず確認する。", obj {
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
            specs += ToolSpec(
                "related",
                "IDの関連情報（全出現・各出現の主要項目の要約とフェーズ状態・その要素からの参照・被参照）を返す。" +
                    "同一IDが複数文書に段階的詳細化されている場合、出現ごとに read_node を呼ばずに横断確認できる。影響分析やID串刺しレビューに使う。" +
                    "被参照は既定で先頭20件に打切られる（全量が要るときだけ limit を増やす）。",
                obj {
                    required("id", "論理ID")
                    optionalInt("limit", "被参照の表示上限（既定20）")
                },
            )
            val traverseSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("start") { put("type", "string"); put("description", "起点の論理ID") }
                    putJsonObject("steps") {
                        put("type", "array")
                        put(
                            "description",
                            "経路ステップの配列（連鎖: 各ステップは前ステップの到達ノードから辿る）。follow（out=参照を辿る / in=被参照を辿る。" +
                                "先頭のみ省略可=起点自身の開示）で移動し、到達ノードを emit（title / summary / outline / full）の粒度で開示する。" +
                                "relation / via（参照キー名）/ schema で辿るエッジ・到達先を絞る。省略時は「起点の要約→参照先の要約」",
                        )
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("follow") { put("type", "string"); putJsonArray("enum") { add("out"); add("in") } }
                                putJsonObject("relation") { put("type", "string") }
                                putJsonObject("via") { put("type", "string") }
                                putJsonObject("schema") { put("type", "string") }
                                putJsonObject("emit") { put("type", "string"); putJsonArray("enum") { add("title"); add("summary"); add("outline"); add("full") } }
                                putJsonObject("limitPerHop") { put("type", "integer") }
                            }
                        }
                    }
                    putJsonObject("budgetChars") { put("type", "integer"); put("description", "出力の文字数予算（既定8000。超過は打切り）") }
                }
                putJsonArray("required") { add("start") }
            }
            specs += ToolSpec(
                "traverse",
                "ID関係グラフを経路パターンで多段に辿り、到達ノードをまとめて開示する（related の多段版）。" +
                    "例:「対象の actor 参照を辿って定義の全文まで」「このIDを derives で参照するテーブル定義の一覧」。" +
                    "related / read_node を数回連鎖させる代わりに1回で取得でき、影響分析（in 方向の多段）にも使う。",
                traverseSchema,
            )
            specs += ToolSpec(
                "read_view",
                "ビュー定義（_views）を解決して返す。横断グリッドは行×列に解決し、複数文書に散らばる同一IDの情報を" +
                    "一覧で横断確認できる。各セルには原本上の座標（file :: path）が付くため、そのまま apply_patch の対象にできる。" +
                    "全行は大きいため、目的の行が分かっているときは必ず rows / columns で絞る。" +
                    "文書ビュー（kind: document）は file で対象文書を指定し、被参照表（参照元と座標）つきで返す。" +
                    "ステージング済みの編集も反映される。view省略時は利用可能なビューの一覧を返す。",
                obj {
                    optional("view", "ビューID（省略時は一覧）")
                    optional("file", "対象文書のワークスペース相対パス（文書ビューのみ。適用対象が1件ならば省略可）")
                    optional("rows", "行IDの絞り込みパターン（例 USC-011-*。横断グリッドのみ）")
                    optionalStringArray("columns", "表示する列ラベルの列挙（省略時は全列。横断グリッドのみ）")
                },
            )
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
            "list_documents" -> Outcome(catalogText(arg("match")))
            "read_outline" -> {
                val rel = requireArg("file")
                val root = staging.tree(rel) ?: error("ファイルが読めません: $rel")
                Outcome(Disclosure.outline(root))
            }
            "read_node" -> {
                val rel = requireArg("file")
                val root = staging.tree(rel) ?: error("ファイルが読めません: $rel")
                val path = parseYPath(arg("path") ?: "")
                Outcome(Disclosure.nodeYaml(root, path, maxChars = 20_000) + refsFooterText(rel, path))
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
            "related" -> {
                val limit = (call.arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 20
                Outcome(relatedText(requireArg("id"), backlinkLimit = limit.coerceAtLeast(1)))
            }
            "traverse" -> {
                val steps = (call.arguments["steps"] as? JsonArray)
                    ?.mapIndexed { i, e -> core.TraverseStep.parse(jsonToShallowMap(e), "steps[$i]") }
                    ?.takeIf { it.isNotEmpty() }
                    ?: core.TraverseSpec.DEFAULT_STEPS
                val budget = (call.arguments["budgetChars"] as? JsonPrimitive)?.intOrNull ?: 8_000
                Outcome(traverseText(requireArg("start"), steps, budget.coerceAtLeast(100)))
            }
            "read_view" -> {
                val columns = (call.arguments["columns"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                Outcome(viewText(arg("view"), arg("file"), rowsPattern = arg("rows"), columns = columns))
            }
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
        val layout = ws.layout
        // 書込可否の統一規則（ARCHITECTURE §4.25）: 台帳内、またはメタアクティビティの定義ゾーンのみ
        if (layout.isReserved(normalized)) {
            if (!(activity.meta && layout.writableByMeta(normalized))) {
                error("予約ディレクトリへの書き込みはできません: $rel")
            }
        } else if (!layout.inLedger(normalized)) {
            error("台帳（ledger）外への書き込みはできません: $rel")
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

    /** カタログ（オーバーレイ反映・予約領域除外）。match（glob）で対象を絞れる（R19-02）。 */
    fun catalogText(match: String? = null): String {
        val regex = match?.let { globToRegex(it) }
        val files = (ws.files + staging.touchedFiles().map { it.file }).distinct()
            .filter { f -> !catalogExcluded(ws.relPath(f)) }
            .filter { f -> regex == null || regex.matches(ws.relPath(f)) }
        if (files.isEmpty()) return "（文書がありません）"
        return files.joinToString("\n") { f ->
            val rel = ws.relPath(f)
            val root = staging.tree(rel)
            val name = root?.let(IdIndex::documentNameOf)
            val description = root?.let(IdIndex::documentDescriptionOf)?.replace("\n", " ")?.take(120)
            val schemaDef = ws.schemaFor(f)
            buildString {
                append("- ").append(rel)
                schemaDef?.let { append(" [schema: ").append(it.id).append(']') }
                name?.let { append(" — ").append(it) }
                description?.let { append(": ").append(it) }
                // phases を宣言したスキーマの文書は、段階的詳細化の現在地をカタログの時点から見せる
                schemaDef?.let { sc -> Validator.phaseSummaryText(root, sc) }?.let { append(" [").append(it).append(']') }
            }
        }
    }

    /** 文書ごとのフェーズ状態（phases 宣言のあるスキーマの文書のみ）。context.include の "phase" から使う。 */
    fun phaseText(targetMatch: String?): String {
        val files = (ws.files + staging.touchedFiles().map { it.file }).distinct()
            .filter { f -> !catalogExcluded(ws.relPath(f)) }
            .filter { f -> targetMatch == null || globToRegex(targetMatch).matches(ws.relPath(f)) }
        val lines = files.mapNotNull { f ->
            val schemaDef = ws.schemaFor(f) ?: return@mapNotNull null
            val root = staging.tree(ws.relPath(f)) ?: return@mapNotNull null
            val summary = Validator.phaseSummaryText(root, schemaDef) ?: return@mapNotNull null
            "- ${ws.relPath(f)}: $summary"
        }
        return if (lines.isEmpty()) "（phases を宣言したスキーマの対象文書がありません）" else lines.joinToString("\n")
    }

    /**
     * ビューの解決テキスト（read_view と初期文脈の開示で共用）。
     * 行・出現はステージングのオーバーレイ索引、セル値はステージング済みツリーから読む。
     * rowsPattern（行IDの絞り込み）・columns（列ラベルの選別）は横断グリッドのみ（R19-02）。
     */
    fun viewText(viewId: String?, fileRel: String? = null, rowsPattern: String? = null, columns: List<String>? = null): String {
        val loaded = core.Views.load(ws.root, ws.schemas)
        if (viewId.isNullOrBlank()) {
            if (loaded.views.isEmpty() && loaded.docViews.isEmpty()) return "（ビュー定義がありません）"
            val lines = loaded.views.map { v ->
                "- ${v.id}（${v.name}）: idPattern=${v.rows.idPattern}, 列=${v.columns.joinToString("/") { it.label }}"
            } + loaded.docViews.map { v ->
                "- ${v.id}（${v.name}）: 文書ビュー 適用先=" +
                    listOfNotNull(v.match, v.schemaId?.let { "schema:$it" }).joinToString(" & ")
            }
            return lines.joinToString("\n") +
                (if (loaded.errors.isEmpty()) "" else "\n定義エラー:\n" + loaded.errors.joinToString("\n") { "- $it" })
        }
        loaded.docView(viewId)?.let {
            require(rowsPattern == null && columns == null) { "rows / columns の絞り込みは横断グリッドのビューでのみ使えます" }
            return docViewText(it, fileRel)
        }
        val view = loaded.view(viewId)
            ?: error(
                "ビュー「$viewId」が見つかりません" +
                    "（利用可能: ${(loaded.views + loaded.docViews).joinToString(", ") { it.id }.ifEmpty { "なし" }}）"
            )
        val index = staging.index()
        val rows = core.Views.resolve(view, ws.root, index) { f -> staging.tree(ws.relPath(f)) }
            .filter { rowsPattern == null || idMatchesPattern(it.id, rowsPattern) }
        return core.Views.renderText(view, rows, columns, index)
    }

    /** 文書ビュー（R14-01）の解決テキスト。対象文書は file 指定＞適用対象が1件なら自動選択。 */
    private fun docViewText(view: core.DocViewDef, fileRel: String?): String {
        val candidates = ws.files.map { ws.relPath(it) }
            .filter { rel -> view.appliesTo(rel, ws.schemaFor(ws.resolve(rel))?.id) }
            .sorted()
        val rel = when {
            fileRel != null -> {
                val n = fileRel.replace('\\', '/')
                require(view.appliesTo(n, ws.schemaFor(ws.resolve(n))?.id)) {
                    "文書「$n」はビュー「${view.id}」の適用対象ではありません" +
                        "（適用対象: ${candidates.joinToString(", ").ifEmpty { "なし" }}）"
                }
                n
            }
            candidates.size == 1 -> candidates.first()
            candidates.isEmpty() -> return "ビュー「${view.id}」の適用対象の文書がありません"
            else -> return "ビュー「${view.id}」は文書ビューです。file で対象文書を指定してください:\n" +
                candidates.joinToString("\n") { "- $it" }
        }
        val file = ws.resolve(rel)
        val index = staging.index()
        val loader = { f: java.nio.file.Path -> staging.tree(ws.relPath(f)) }
        val sections = core.Views.resolveDocument(view, ws.root, file, index, loader = loader)
        val attachments = core.Views.resolveAttachments(view, ws.root, file, index, loader = loader)
        return core.Views.renderDocText(view, rel, sections, attachments)
    }

    // ---------------------------------------------------------------- コンテキストレシピ（R19-02）

    /** レシピ項目の会計区分名（解決失敗時のフォールバック表示にも使う）。 */
    fun contextItemLabel(item: ContextItemDef): String = when (item.kind) {
        "catalog" -> "ワークスペースカタログ"
        "phase" -> "対象文書のフェーズ状態（段階的詳細化）"
        "view" -> "ビュー: ${item.view}"
        "doc" -> "文書: ${item.file}" + (item.path?.let { " :: $it" } ?: "")
        "related" -> "関連情報: ${item.id}"
        "sample" -> "記述例（schema: ${item.schema}）"
        "graph" -> "グラフ: ${item.start}"
        else -> item.kind
    }

    /** グラフトラバース（R19-04）。ツール・レシピ項目の双方から呼ぶ（評価器は core.Traverse の1実装）。 */
    fun traverseText(start: String, steps: List<core.TraverseStep>, budgetChars: Int): String =
        core.Traverse.run(
            staging.index(),
            relPath = { f -> ws.relPath(f) },
            loader = { f -> staging.tree(ws.relPath(f)) },
            spec = core.TraverseSpec(start = start, steps = steps, budgetChars = budgetChars),
        )

    /**
     * コンテキストレシピ項目を初期コンテキストの1区分へ解決する（R19-02 / ARCHITECTURE §4.28）。
     * プレースホルダ（{target} 等）を解決できない項目は null（開示しない）。予算（budgetChars）超過は打切り。
     * doc / related / sample は既定でも予算8,000字を掛ける（全文系は上限なしにしない）。
     */
    fun contextItemSection(item: ContextItemDef, values: Map<String, String>): ContextSection? {
        fun tpl(text: String): String? = resolveContextTemplate(text, values)
        return when (item.kind) {
            "catalog" -> ContextSection(contextItemLabel(item), capped(catalogText(item.match), item.budgetChars))
            "phase" -> ContextSection(
                contextItemLabel(item),
                capped(phaseText(item.match ?: activity.targetMatch), item.budgetChars),
            )
            "view" -> {
                val rowsPattern = item.rows?.let { tpl(it) ?: return null }
                ContextSection(
                    contextItemLabel(item),
                    capped(
                        viewText(item.view, rowsPattern = rowsPattern, columns = item.columns.takeIf { it.isNotEmpty() }),
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
                    Disclosure.nodeYaml(root, parsed, maxChars = item.budgetChars ?: 8_000) + refsFooterText(rel, parsed),
                )
            }
            "related" -> {
                val id = tpl(item.id!!) ?: return null
                ContextSection("関連情報: $id", capped(relatedText(id), item.budgetChars ?: 8_000))
            }
            "sample" -> ContextSection(
                contextItemLabel(item),
                capped(sampleText(item.schema!!, item.count), item.budgetChars ?: 8_000),
            )
            "graph" -> {
                val start = tpl(item.start!!) ?: return null
                ContextSection(
                    "グラフ: $start",
                    traverseText(start, item.steps.ifEmpty { core.TraverseSpec.DEFAULT_STEPS }, item.budgetChars ?: 8_000),
                )
            }
            else -> error("未知のコンテキスト項目です: ${item.kind}")
        }
    }

    /** 同スキーマの既存文書N件を記述例として返す（記述粒度・文体の手本。R19-02）。 */
    private fun sampleText(schemaId: String, count: Int): String {
        val rels = ws.files.asSequence()
            .map { ws.relPath(it) }
            .filter { !catalogExcluded(it) }
            .filter { rel -> ws.schemaFor(ws.resolve(rel))?.id == schemaId }
            .sorted()
            .take(count)
            .toList()
        if (rels.isEmpty()) return "（schema: $schemaId の文書がまだありません）"
        return rels.joinToString("\n") { rel ->
            val root = staging.tree(rel)
            "### $rel\n" + (root?.let { Disclosure.nodeYaml(it, parseYPath(""), maxChars = 4_000) } ?: "（読めません）")
        }
    }

    /** 予算（文字数）超過の決定的な打切り。 */
    private fun capped(text: String, budget: Int?): String =
        if (budget == null || text.length <= budget) text
        else text.take(budget) + "\n…（予算 ${budget} 文字で打切り）"

    /** ツール引数のフラットな object を raw Map へ変換する（TraverseStep.parse と共用するため）。 */
    private fun jsonToShallowMap(element: JsonElement): Map<String, Any?> {
        val obj = element as? JsonObject ?: error("ステップはobjectである必要があります")
        return obj.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> v.intOrNull ?: v.booleanOrNull ?: v.contentOrNull
                else -> v.toString()
            }
        }
    }

    private fun relatedText(id: String, backlinkLimit: Int = 20): String {
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
                // 出現の中身を要約表示する（R7-04の横ぐしビューと同等の情報をツール1回で得られるようにする）
                val docRoot = staging.tree(ws.relPath(e.file))
                ws.schemaFor(e.file)?.let { sc -> Validator.phaseSummaryText(docRoot, sc) }
                    ?.let { appendLine("    $it") }
                val element = docRoot?.let { getAt(it, e.path) } as? YNode.Map
                element?.entries?.asSequence()
                    ?.filter { (_, v) -> v is YNode.Scalar }
                    ?.take(6)
                    ?.forEach { (k, v) ->
                        val text = (v as YNode.Scalar).value?.toString().orEmpty().replace("\n", " ")
                        appendLine("    $k: ${if (text.length > 60) text.take(60) + "…" else text}")
                    }
            }
            val refsOut = occurrences.flatMap { e ->
                idx.refs.filter {
                    it.file == e.file && it.path.size >= e.path.size && it.path.subList(0, e.path.size) == e.path
                }
            }
            if (refsOut.isNotEmpty()) {
                appendLine("この要素からの参照:")
                refsOut.forEach { r ->
                    append("- ").append(r.targetId)
                    // 参照先のタイトル（表示解決）を添え、IDの意味を別のツール呼び出しなしで閉じる（R19-05）
                    idx.occurrences(r.targetId).firstNotNullOfOrNull { it.title }?.let { append(" = ").append(it) }
                    append("（").append(r.viaKey)
                    r.relation?.let { append("・関係: ").append(it) }
                    appendLine("）")
                }
            }
            appendLine("被参照（${backlinks.size}件）:")
            backlinks.take(backlinkLimit).forEach { b ->
                append("- ").append(ws.relPath(b.file)).append(" :: ").append(b.path.pretty())
                    .append("（").append(b.viaKey)
                b.relation?.let { append("・関係: ").append(it) }
                // 参照を書いた要素の ID・タイトル（索引の表示解決結果）を添え、read_node なしで参照元を特定できるようにする
                idx.sourceEntryOf(b)?.let { src ->
                    append("・元要素: ").append(listOfNotNull(src.id, src.title).joinToString(" "))
                }
                appendLine("）")
            }
            // 被参照の全列挙は膨張の主因（例: 全ユースケースが参照するアクター）のため既定20件で打切る（R19-05）
            if (backlinks.size > backlinkLimit) {
                appendLine("…他 ${backlinks.size - backlinkLimit} 件（related の limit 引数で拡張できます）")
            }
        }.trimEnd()
    }

    /**
     * ノード配下の参照IDを「ID = タイトル [定義元]」へ解決するフッタ（R19-05）。
     * read_node / doc 項目の出力末尾に付け、「このIDは何か」を追加のツール呼び出しなしで閉じる。
     */
    private fun refsFooterText(rel: String, path: model.YPath): String {
        val idx = staging.index()
        val targetIds = idx.refs
            .filter { ws.relPath(it.file) == rel && it.path.size >= path.size && it.path.subList(0, path.size) == path }
            .map { it.targetId }
            .distinct()
        if (targetIds.isEmpty()) return ""
        val lines = targetIds.take(30).map { id ->
            val occurrences = idx.occurrences(id)
            if (occurrences.isEmpty()) "- $id（未解決）"
            else {
                val title = occurrences.firstNotNullOfOrNull { it.title }
                "- $id" + (title?.let { " = $it" } ?: "") + " [${ws.relPath(occurrences.first().file)}]"
            }
        }
        return buildString {
            appendLine()
            appendLine()
            appendLine("参照の解決:")
            lines.forEach { appendLine(it) }
            if (targetIds.size > 30) appendLine("…他 ${targetIds.size - 30} 件")
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
    fun optionalInt(name: String, description: String) {
        properties += Triple(name, "integer", description)
    }
    fun optionalStringArray(name: String, description: String) {
        properties += Triple(name, "array", description)
    }
}

private fun obj(build: ObjBuilder.() -> Unit): JsonObject {
    val builder = ObjBuilder().apply(build)
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            builder.properties.forEach { (name, type, description) ->
                putJsonObject(name) {
                    put("type", type)
                    put("description", description)
                    if (type == "array") putJsonObject("items") { put("type", "string") }
                }
            }
        }
        putJsonArray("required") { builder.requiredNames.forEach(::add) }
    }
}
