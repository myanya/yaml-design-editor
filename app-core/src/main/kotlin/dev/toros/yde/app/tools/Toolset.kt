package dev.toros.yde.app.tools

import dev.toros.yde.app.core.Disclosure
import dev.toros.yde.app.core.Search
import dev.toros.yde.app.core.SearchQuery
import dev.toros.yde.app.core.TraverseSpec
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.ActionEntry
import dev.toros.yde.workspace.ActionFailure
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.ChangeIntent
import dev.toros.yde.workspace.WorkspaceOperation
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.WorkspaceTransaction
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.patch.PatchOp
import kotlinx.serialization.json.*

/**
 * 中核のツールの一式（ADR-0013「窓口の形」）。入口（agent の LLM 向け宣言・MCP・CLI）は `tools` を自分の形に包み、`run` で呼ぶ。
 * - 読む: list_documents・read_outline・read_node・search・related・read_entity・traverse・read_view（作業コピー越しに読むので、重ねた編集が見える）
 * - 作業コピーへ書く: apply_patch・create_document。原本は変更セットの適用（承認）まで変わらない
 * - 検証: validate（作業コピーの文書と、その変更の影響を受ける文書）
 * - 操作: `ToolPolicy.actions` で許可した操作が 1 つずつツールになる（`action_` ＋名前。architecture/actions.md）
 * 書き込みの権限はここで強制する: 許可ファイル glob・許可パス glob・予約ディレクトリへの書込禁止。
 * モデルへの指示ではなくツール側で拒否するのがサンドボックスの要。拡張のツールは `extra` で足す。
 */
class Toolset(val ctx: ToolContext, extra: List<Tool> = emptyList()) {
    private val ws = ctx.ws
    private val copy = ctx.copy
    private val policy = ctx.policy

    /** 読み出しの文（ツールの結果と、エージェントの初期文脈が共用する）。 */
    val readouts = Readouts(ctx)

    /**
     * 操作のツール（architecture/actions.md）: 許可した操作だけを、1 つずつツールにする（ツール名 → 操作）。
     * ツール名に使えない文字（日本語の型名・`.`）は `_` に置き換え、本当の名前は説明に出す。無い操作を許可した定義は誤り。
     */
    private val actionTools: Map<String, ActionEntry> = run {
        if (policy.actions.isEmpty()) return@run emptyMap()
        val catalog = ActionPlanner.catalog(ws.mirror.schemas).filter { it.kind != "create" }
        val out = LinkedHashMap<String, ActionEntry>()
        for (name in policy.actions) {
            val entry = catalog.firstOrNull { it.name == name }
                ?: error("許可した操作「$name」が見つかりません（使える操作: ${catalog.map { it.name }.sorted().joinToString(", ").ifEmpty { "なし" }}）")
            val base = "action_" + name.map { c -> if ((c.code < 128 && c.isLetterOrDigit()) || c == '_' || c == '-') c else '_' }.joinToString("")
            var toolName = base
            var n = 2
            while (toolName in out) toolName = "${base}_${n++}"
            out[toolName] = entry
        }
        out
    }

    /** 一式（中核の順: 読む → 書く → 検証 → 操作、そのあとに拡張）。 */
    val tools: List<Tool> = coreTools() + extra

    fun find(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** 名前でツールを呼ぶ。無い名前は誤り。 */
    fun run(name: String, args: JsonObject): JsonElement =
        (find(name) ?: error("未知のツールです: $name")).run(ctx, args)

    // ---------------------------------------------------------------- 中核のツール

    private fun coreTools(): List<Tool> = buildList {
        add(Tool(
            "list_documents",
            "ワークスペースの文書カタログ（ファイル・文書ID・名前・概要・スキーマ・ID数）を返す。まず全体像を掴むのに使う。",
            toolArgs { optional("match", "ファイルパスのglobで絞り込み（省略時は全文書）") },
            ToolKind.READ,
        ) { _, a -> text(readouts.catalogText(a.str("match"))) })
        add(Tool(
            "read_outline",
            "文書の骨子（キー構造・配列件数・短い値。長文は文字数のみ）を返す。全文を読む前の構造把握に使う。",
            toolArgs { required("file", "ワークスペース相対パス") },
            ToolKind.READ,
        ) { _, a ->
            val rel = a.need("file")
            val root = copy.tree(rel) ?: error("ファイルが読めません: $rel")
            text(Disclosure.outline(root))
        })
        add(Tool(
            "read_node",
            "指定パス配下の全文をYAMLで返す。path 省略時は文書全体。末尾に配下の参照IDの解決（ID = 名前・定義元）が付くため、参照先を別途読む前にまず確認する。",
            toolArgs {
                required("file", "ワークスペース相対パス")
                optional("path", "YPath（例 items[2].title）。省略時はルート")
            },
            ToolKind.READ,
        ) { _, a ->
            val rel = a.need("file")
            val root = copy.tree(rel) ?: error("ファイルが読めません: $rel")
            val path = parseYPath(a.str("path") ?: "")
            text(Disclosure.nodeYaml(root, path, maxChars = 20_000) + readouts.refsFooterText(rel, path))
        })
        add(Tool(
            "search",
            "ワークスペースを検索する。text は本文・キーの部分一致。ref_to はそのIDへの参照を、unresolved=true は未解決参照を検索する。ステージング済みの編集も反映される。",
            toolArgs {
                optional("text", "検索文字列")
                optional("schema", "スキーマIDで絞り込み")
                optional("file_glob", "ファイルパスのglob")
                optional("ref_to", "このIDへの参照を検索")
                optionalBool("unresolved", "未解決参照のみ")
            },
            ToolKind.READ,
        ) { _, a ->
            val query = SearchQuery(
                text = a.str("text"),
                schemaIds = listOfNotNull(a.str("schema")),
                fileGlob = a.str("file_glob"),
                refsTo = a.str("ref_to"),
                unresolvedRefs = a["unresolved"]?.jsonPrimitive?.booleanOrNull ?: false,
                limit = 40,
            )
            val files = (ws.files + copy.touchedFiles().map { it.file }).distinct()
            val hits = Search.run(ws, query, files = files, index = copy.index(), loader = { f -> copy.tree(ws.relPath(f)) })
            text(
                if (hits.isEmpty()) "該当なし"
                else hits.joinToString("\n") { "- ${it.file} :: ${it.path} [${it.kind}] ${it.excerpt}" }
            )
        })
        add(Tool(
            "related",
            "IDの隣接情報（全出現・各出現の主要項目の要約とフェーズ状態・その要素からの参照・被参照）を軽量に返す。" +
                "隣接IDや参照元の素早い確認に使う。対象を深く理解して編集する場合は read_entity（全体像）を使い、両方を重ねて呼ばない。" +
                "被参照は既定で先頭20件に打切られる（全量が要るときだけ limit を増やす）。",
            toolArgs {
                required("id", "論理ID")
                optionalInt("limit", "被参照の表示上限（既定20）")
            },
            ToolKind.READ,
        ) { _, a ->
            val limit = a.int("limit") ?: 20
            text(readouts.relatedText(a.need("id"), backlinkLimit = limit.coerceAtLeast(1)))
        })
        add(Tool(
            "read_entity",
            "エンティティの全体像を1回で返す（related の上位互換）: アスペクト合成（出現ごとの所有フィールドと原本座標＝" +
                "そのまま apply_patch の対象にできる）・両方向の関係（関係ラベル・属性つき）・フェーズ／カバレッジ充足。" +
                "対象エンティティを深く理解して編集・レビューするときに使う（隣接の軽い確認だけなら related で足りる）。",
            toolArgs {
                required("id", "論理ID")
                optionalInt("limit", "関係（参照・被参照）の表示上限（既定20）")
            },
            ToolKind.READ,
        ) { _, a ->
            val limit = a.int("limit") ?: 20
            text(readouts.entityText(a.need("id"), relationLimit = limit.coerceAtLeast(1)))
        })
        add(Tool(
            "traverse",
            "ID関係グラフを経路パターンで多段に辿り、到達ノードをまとめて開示する（related の多段版）。" +
                "例:「対象の actor 参照を辿って定義の全文まで」「このIDを derives で参照するテーブル定義の一覧」。" +
                "related / read_node を数回連鎖させる代わりに1回で取得でき、影響分析（in 方向の多段）にも使う。",
            traverseSchema,
            ToolKind.READ,
        ) { _, a ->
            val steps = (a["steps"] as? JsonArray)
                ?.mapIndexed { i, e -> TraverseStep.parse(jsonToShallowMap(e), "steps[$i]") }
                ?.takeIf { it.isNotEmpty() }
                ?: TraverseSpec.DEFAULT_STEPS
            val budget = a.int("budgetChars") ?: 8_000
            text(readouts.traverseText(a.need("start"), steps, budget.coerceAtLeast(100)))
        })
        add(Tool(
            "read_view",
            "ビュー定義（_views）を解決して返す。横断グリッドは行×列に解決し、複数文書に散らばる同一IDの情報を" +
                "一覧で横断確認できる。各セルには原本上の座標（file :: path）が付くため、そのまま apply_patch の対象にできる。" +
                "全行は大きいため、目的の行が分かっているときは必ず rows / columns で絞る。" +
                "文書ビュー（kind: document）は file で対象文書を指定し、被参照表（参照元と座標）つきで返す。" +
                "ステージング済みの編集も反映される。view省略時は利用可能なビューの一覧を返す。",
            toolArgs {
                optional("view", "ビューID（省略時は一覧）")
                optional("file", "対象文書のワークスペース相対パス（文書ビューのみ。適用対象が1件ならば省略可）")
                optional("rows", "行IDの絞り込みパターン（例 USC-011-*。横断グリッドのみ）")
                optional("date", "ダッシュボードの基準日（YYYY-MM-DD。省略時は本日）")
                optionalStringArray("columns", "表示する列ラベルの列挙（省略時は全列。横断グリッドのみ）")
            },
            ToolKind.READ,
        ) { _, a ->
            val columns = (a["columns"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
            text(readouts.viewText(a.str("view"), a.str("file"), rowsPattern = a.str("rows"), columns = columns, date = a.str("date")))
        })
        add(Tool(
            "apply_patch",
            "ステージング（作業コピー）にパッチを適用する。原本は提出と人間の承認まで変更されない。opは set（置換・作成）/ insert（配列挿入・新キー）/ delete / move / rename（キー改名。順序・コメント保持）/ reorder（キー並べ替え）。",
            applyPatchSchema,
            ToolKind.EDIT,
        ) { _, a ->
            val rel = a.need("file")
            checkWritableFile(rel)
            val opsJson = a["ops"] as? JsonArray ?: error("引数 ops（配列）が必要です")
            val ops = opsJson.mapIndexed { i, e -> parsePatchOp(e, i) }
            ops.forEach { checkWritablePaths(it) }
            copy.applyOps(rel, ops)
            text("適用しました: $rel に ${ops.size} 操作（ステージング上。原本は未変更）")
        })
        add(Tool(
            "create_document",
            "新規YAML文書をステージングに作成する。",
            toolArgs {
                required("file", "ワークスペース相対パス（.yaml）")
                required("content_yaml", "文書全体のYAMLテキスト")
            },
            ToolKind.EDIT,
        ) { _, a ->
            val rel = a.need("file")
            checkWritableFile(rel)
            val root = YamlIO.parse(a.need("content_yaml"))
            copy.createDocument(rel, root)
            text("作成しました: $rel（ステージング上）")
        })
        add(Tool(
            "validate",
            "ステージング済みのファイルと、その変更の影響を受けるファイル（変更前後のIDを参照している文書）を、編集を重ねた状態の索引で検証する（スキーマ・参照・重複・カバレッジ）。提出前に必ず実行する。",
            toolArgs {},
            ToolKind.VALIDATE,
        ) { _, _ -> text(readouts.validationText()) })
        for ((toolName, entry) in actionTools) add(actionTool(toolName, entry))
    }

    /** 操作のツール: 引数のスキーマは受け取る値の定義から作る（AI 生成と同じ変換＝型・選択肢・必須が保証される）。 */
    private fun actionTool(toolName: String, entry: ActionEntry): Tool {
        val schemaDef = ws.schemas.firstOrNull { it.id == entry.definedIn }
        val target = entry.target
        val receiver = when {
            entry.kind == "method" -> "受け手の要素の ID（型 $target）"
            target != null && target.startsWith("entity:") -> "受け手の要素の ID（${target.removePrefix("entity: ")}）"
            target != null && target.startsWith("schema:") -> "受け手の文書の相対パス（スキーマ ${target.removePrefix("schema: ")}）"
            else -> null
        }
        val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                receiver?.let { putJsonObject("target") { put("type", "string"); put("description", it) } }
                entry.params.forEach { p ->
                    val key = p.key ?: return@forEach
                    val resolved = schemaDef?.resolve(p) ?: p
                    val schema = schemaDef?.let { fieldToJsonSchema(resolved, it) }
                        ?: buildJsonObject { put("type", "string") }
                    put(key, schema)
                }
            }
            putJsonArray("required") {
                if (receiver != null) add("target")
                entry.params.filter { it.required }.mapNotNull { it.key }.forEach { add(it) }
            }
        }
        val description = buildString {
            append("操作「${entry.label ?: entry.name}」（${entry.name}）")
            entry.description?.let { append(": $it") }
            append("。スキーマに定義された決まった手順の変更をステージングへ積む（前提に合わなければ理由を返す。原本は提出と承認まで変わらない）")
        }
        return Tool(toolName, description, parameters, ToolKind.ACTION) { _, a -> text(runActionTool(entry, a)) }
    }

    /**
     * 操作のツールの実行（architecture/actions.md）: 作業コピーを重ねたセッションの上で計画し、変更セットを作業コピーへ積む。
     * 許可した操作は人が許可した検証済みの手順なので、allowedFiles / allowedPaths は見ない（予約ディレクトリと台帳の外は今までどおり不可）。
     * 前提に合わない・受け取った値に誤りがあるときは、理由をそのまま返す（呼び手が直して呼び直せる）。
     */
    private fun runActionTool(entry: ActionEntry, args: JsonObject): String {
        val target = args["target"]?.jsonPrimitive?.contentOrNull
        val params = args.filterKeys { it != "target" }.mapValues { (_, v) -> YamlIO.toAny(jsonToYNode(v)) }
        val plan = try {
            ActionPlanner.plan(stagedSession(), ActionRequest(entry.name, target, params))
        } catch (e: ActionFailure) {
            return "操作「${entry.label ?: entry.name}」は実行できません: ${e.message}"
        }
        if (plan.isEmpty) return "操作「${plan.title}」: 変更はありません\n" + plan.summary.joinToString("\n") { "・$it" }
        plan.changeSet.changes.forEach { checkWritableZone(it.file) }
        plan.changeSet.changes.forEach { change ->
            if (change.create) copy.createDocument(change.file, change.content!!) else copy.applyOps(change.file, change.ops)
        }
        return "操作「${plan.title}」をステージングへ積みました（${plan.changeSet.changes.size} 文書。原本は未変更）:\n" +
            plan.summary.joinToString("\n") { "・$it" } + "\n提出の前に validate で確かめてください。"
    }

    /** 作業コピーの内容を重ねたセッション（ディスクを映すセッションの写しに、重ねた文書を置き換え・作成として当てる）。 */
    private fun stagedSession(): WorkspaceSession {
        val session = ws.mirror.fork()
        val operations = copy.touchedFiles().map { e ->
            val id = DocumentId(e.rel)
            val document = YDocument(id, listOf(e.root.deepCopy()))
            if (e.created) WorkspaceOperation.CreateDocument(id, document)
            else WorkspaceOperation.ReplaceDocument(id, document)
        }
        if (operations.isNotEmpty()) {
            val applied = session.apply(WorkspaceTransaction(session.version, ChangeIntent("ステージング"), operations))
            require(applied.ok) { applied.message ?: "ステージングの内容をセッションへ重ねられません" }
        }
        return session
    }

    // ---------------------------------------------------------------- 権限チェック

    /** 書き込めるゾーンか（台帳内、またはメタアクティビティの定義ゾーン）。allowedFiles は見ない。 */
    private fun checkWritableZone(rel: String) {
        WorkspacePaths.resolve(ws.root, rel, allowLinks = false)
        val normalized = WorkspacePaths.relative(rel).toString().replace('\\', '/')
        val layout = ws.layout
        // 書込可否の統一規則（architecture/workspace.md「ワークスペース構造と layout」）: 台帳内、またはメタアクティビティの定義ゾーンのみ
        if (layout.isReserved(normalized)) {
            if (!(policy.meta && layout.writableByMeta(normalized))) {
                error("予約ディレクトリへの書き込みはできません: $rel")
            }
        } else if (!layout.inLedger(normalized)) {
            error("台帳（ledger）外への書き込みはできません: $rel")
        }
    }

    private fun checkWritableFile(rel: String) {
        val normalized = WorkspacePaths.relative(rel).toString().replace('\\', '/')
        checkWritableZone(normalized)
        if (policy.allowedFiles.isNotEmpty() && policy.allowedFiles.none { globToRegex(it).matches(normalized) }) {
            error("このアクティビティの書込許可（allowedFiles）の対象外です: $rel")
        }
    }

    private fun checkWritablePaths(op: PatchOp) {
        if (policy.allowedPaths.isEmpty()) return
        val paths = when (op) {
            is PatchOp.Set -> listOf(op.path)
            is PatchOp.Insert -> listOf(op.path)
            is PatchOp.Delete -> listOf(op.path)
            is PatchOp.Move -> listOf(op.from, op.to)
            // 改名は元キーと改名後キーの両方が許可範囲にあること
            is PatchOp.RenameKey -> listOf(op.path, op.path.dropLast(1) + op.newName)
            is PatchOp.ReorderKeys -> listOf(op.path)
        }
        paths.forEach { p ->
            val pretty = p.pretty()
            if (policy.allowedPaths.none { idMatchesPattern(pretty, it) }) {
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
            "rename" -> PatchOp.RenameKey(
                path("path"),
                obj["to"]?.jsonPrimitive?.contentOrNull ?: error("ops[$index].to（改名先のキー）が必要です"),
            )
            "reorder" -> PatchOp.ReorderKeys(
                parseYPath(obj["path"]?.jsonPrimitive?.contentOrNull ?: ""),
                (obj["order"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: error("ops[$index].order（キーの並び）が必要です"),
            )
            else -> error("ops[$index].op は set / insert / delete / move / rename / reorder のいずれかです: $op")
        }
    }

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

    private companion object {
        fun text(s: String): JsonElement = JsonPrimitive(s)
        fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
        fun JsonObject.need(name: String): String = str(name) ?: error("引数 $name が必要です")
        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

        val traverseSchema: JsonObject = buildJsonObject {
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

        val applyPatchSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file") { put("type", "string"); put("description", "ワークスペース相対パス") }
                putJsonObject("ops") {
                    put("type", "array")
                    put("description", "パッチ操作のリスト")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("op") { put("type", "string"); putJsonArray("enum") { add("set"); add("insert"); add("delete"); add("move"); add("rename"); add("reorder") } }
                            putJsonObject("path") { put("type", "string"); put("description", "YPath（set/insert/delete/rename/reorder。例 items[2].title。reorder は空でルート）") }
                            putJsonObject("value") { put("description", "設定する値（set/insert。任意のJSON値）") }
                            putJsonObject("from") { put("type", "string"); put("description", "移動元YPath（move）") }
                            putJsonObject("to") { put("type", "string"); put("description", "移動先YPath（move）／改名先のキー名（rename）") }
                            putJsonObject("order") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "キーの並び（reorder。ここにあるキーをこの順で先頭へ・残りは相対順を保持）") }
                        }
                        putJsonArray("required") { add("op") }
                    }
                }
            }
            putJsonArray("required") { add("file"); add("ops") }
        }
    }
}
