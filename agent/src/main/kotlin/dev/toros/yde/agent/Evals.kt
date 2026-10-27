package dev.toros.yde.agent

import dev.toros.yde.agent.llm.AiProvider
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 評価セット（R4-02）と比較実験（R4-04）。
 * `_evals/` に置いた「ゴールデン入力＋期待結果」の定義でアクティビティを回帰評価する（アクティビティの「テスト」）。
 * - 実行はワークスペースの一時サンドボックスコピー上で行い、原本には一切触れない
 * - 期待結果は宣言的なチェック（終了状態・検証エラー・変更ファイル・パス内容）で機械判定する
 * - variants はモデル・エージェント・追加指示を差し替えて同じケース群を実行するA/B比較
 */

data class EvalSetupFile(val file: String, val content: String)

data class EvalContentCheck(
    val file: String,
    val path: String,                 // YPath（例 review_comments）。"" はルート
    val exists: Boolean = true,
    val contains: String? = null,
    val equals: String? = null,
)

data class EvalExpect(
    val status: String = "submitted", // submitted / aborted / limit / error / any
    val errorFree: Boolean? = null,   // true: 終了時点のステージング検証エラー0
    val changedFiles: List<String> = emptyList(),    // 各globに1件以上マッチする変更が必要
    val unchangedFiles: List<String> = emptyList(),  // マッチする変更があれば不合格
    val content: List<EvalContentCheck> = emptyList(),
)

data class EvalCase(
    val id: String,
    val name: String,
    val setup: List<EvalSetupFile> = emptyList(),    // サンドボックスへ上書き・作成するゴールデン入力
    val instruction: String? = null,                 // ケース固有の追加指示
    val expect: EvalExpect = EvalExpect(),
)

/** 比較実験の変異（R4-04）。指定した項目だけを差し替え、他はアクティビティ定義に従う。 */
data class EvalVariant(
    val name: String,
    val provider: AiProvider? = null,
    val model: String? = null,
    val agentId: String? = null,
    val instruction: String? = null,
    /**
     * コンテキストレシピの差し替え（R19-06）。null = アクティビティ定義のまま。
     * 非null はアクティビティの context.items を丸ごと置き換える（空 = 開示なしのA/Bも書ける）。
     * 「レシピA/B × 合格率 × トークン」の比較でレシピをデータでチューニングする器。
     */
    val contextItems: List<ContextItemDef>? = null,
)

data class EvalDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val activityId: String,
    val variants: List<EvalVariant> = emptyList(),   // 空 = 現行定義のまま1系統（baseline）
    val cases: List<EvalCase>,
    val source: Path,
)

object Evals {

    private val allowedStatus = setOf("submitted", "aborted", "limit", "error", "cancelled", "any")

    data class LoadResult(val evals: List<EvalDef>, val errors: List<String>) {
        fun eval(id: String): EvalDef? = evals.firstOrNull { it.id == id }
    }

    fun load(workspace: Path): LoadResult {
        val dir = runCatching { WorkspacePaths.resolve(workspace, WorkspaceLayout.of(workspace).evalsDir) }
            .getOrElse { return LoadResult(emptyList(), listOf(it.message ?: "評価定義の場所を解決できません")) }
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val errors = mutableListOf<String>()
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val evals = files.mapNotNull { file ->
            runCatching {
                WorkspacePaths.resolve(workspace, workspace.toAbsolutePath().relativize(file).toString())
                val raw = YamlIO.parseToRaw(Files.readString(file)) as? Map<*, *>
                    ?: error("ルートはobjectである必要があります")
                parseEval(raw, file)
            }.onFailure { errors += "${file.fileName}: ${it.message ?: it::class.simpleName}" }.getOrNull()
        }
        return LoadResult(evals, errors)
    }

    internal fun parseEval(raw: Map<*, *>, source: Path): EvalDef {
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("id が必要です")
        val cases = (raw["cases"] as? List<*>)?.map { parseCase(it) } ?: error("cases が必要です")
        if (cases.isEmpty()) error("cases は1件以上必要です")
        cases.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { error("ケースID「$it」が重複しています") }
        val variants = (raw["variants"] as? List<*>)?.map { parseVariant(it) } ?: emptyList()
        variants.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { error("変異名「$it」が重複しています") }
        return EvalDef(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            activityId = raw["activity"]?.toString()?.takeIf { it.isNotBlank() } ?: error("activity が必要です"),
            variants = variants,
            cases = cases,
            source = source,
        )
    }

    private fun parseCase(raw: Any?): EvalCase {
        val map = raw as? Map<*, *> ?: error("cases の各要素はobjectである必要があります")
        val id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("case に id が必要です")
        val setup = (map["setup"] as? List<*>)?.map { entry ->
            val m = entry as? Map<*, *> ?: error("case「$id」: setup の各要素はobjectである必要があります")
            EvalSetupFile(
                file = m["file"]?.toString()?.takeIf { it.isNotBlank() } ?: error("case「$id」: setup に file が必要です"),
                content = m["content"]?.toString() ?: error("case「$id」: setup に content が必要です"),
            )
        } ?: emptyList()
        setup.forEach {
            setupPath(it.file)
        }
        return EvalCase(
            id = id,
            name = map["name"]?.toString() ?: id,
            setup = setup,
            instruction = map["instruction"]?.toString(),
            expect = parseExpect(map["expect"], id),
        )
    }

    internal fun setupPath(file: String): String {
        val normalized = WorkspacePaths.relative(file).toString().replace('\\', '/')
        require(file.replace('\\', '/').split('/').none { it == ".." }) { "setup では親ディレクトリを指定できません: $file" }
        require(normalized.split('/').none { it.startsWith(".") } && !normalized.startsWith("_")) {
            "setup で予約ディレクトリ・隠しディレクトリは上書きできません: $file"
        }
        return normalized
    }

    private fun parseExpect(raw: Any?, caseId: String): EvalExpect {
        if (raw == null) return EvalExpect()
        val map = raw as? Map<*, *> ?: error("case「$caseId」: expect はobjectである必要があります")
        val status = map["status"]?.toString() ?: "submitted"
        require(status in allowedStatus) { "case「$caseId」: expect.status は ${allowedStatus.joinToString(" / ")} のいずれかです: $status" }
        val content = (map["content"] as? List<*>)?.map { entry ->
            val m = entry as? Map<*, *> ?: error("case「$caseId」: expect.content の各要素はobjectである必要があります")
            EvalContentCheck(
                file = m["file"]?.toString() ?: error("case「$caseId」: expect.content に file が必要です"),
                path = m["path"]?.toString() ?: "",
                exists = m["exists"] as? Boolean ?: true,
                contains = m["contains"]?.toString(),
                equals = m["equals"]?.toString(),
            )
        } ?: emptyList()
        fun globs(key: String): List<String> = when (val v = map[key]) {
            is List<*> -> v.map { it.toString() }
            null -> emptyList()
            else -> listOf(v.toString())
        }
        return EvalExpect(
            status = status,
            errorFree = map["errorFree"] as? Boolean,
            changedFiles = globs("changedFiles"),
            unchangedFiles = globs("unchangedFiles"),
            content = content,
        )
    }

    private fun parseVariant(raw: Any?): EvalVariant {
        val map = raw as? Map<*, *> ?: error("variants の各要素はobjectである必要があります")
        val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: error("variant に name が必要です")
        val modelMap = map["model"] as? Map<*, *>
        val provider = modelMap?.get("provider")?.toString()?.let { value ->
            AiProvider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("variant「$name」: 未知のプロバイダです: $value")
        }
        return EvalVariant(
            name = name,
            provider = provider,
            model = modelMap?.get("id")?.toString(),
            agentId = map["agent"]?.toString(),
            instruction = map["instruction"]?.toString(),
            contextItems = if (map.containsKey("context")) Definitions.parseContextItems(map["context"]) else null,
        )
    }
}

