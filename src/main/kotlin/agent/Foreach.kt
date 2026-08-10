package agent

import core.Search
import core.SearchQuery
import core.Views
import core.Workspace
import index.idMatchesPattern
import model.pretty
import schema.globToRegex
import validate.Severity

/**
 * 一括実行の対象列挙（foreach。R18-01）。アクティビティ定義の宣言から「残作業の集合」を列挙し、
 * 1件=1実行の作業項目（対象キー＋追加指示）へ展開する（docs/ARCHITECTURE.md §4.27）。
 * - 対象の列挙は機械（ビュー・検証・検索・ファイル・索引）が行い、エージェントには対象を明示注入する。
 *   「次の対象選び」をLLMに委ねない（決定性とコストのため）
 * - 一括実行が収束するのは「完了した項目が次回の列挙に現れない」列挙条件のとき
 *   （view の詳細未作成・issues の指摘はこの性質を自然に持つ。files / ids / search は毎回同じ集合を
 *   返しうるため、繰り返し投入する使い方には向かない）
 * - 展開は起動時に1回だけ行い、limit で件数を抑える（暴走防止はここで構造的に担保する）
 */

enum class ForeachSource(val id: String) {
    VIEW("view"),       // 横断グリッドビューの行（詳細未作成の行 / 全行）
    ISSUES("issues"),   // 検証の指摘（ファイルglob・重大度で絞る）
    SEARCH("search"),   // 検索ヒット（テキスト・パス・参照・未解決）
    FILES("files"),     // glob に一致する文書
    IDS("ids");         // idPattern × schemaIds に一致する論理ID

    companion object {
        fun parse(raw: String): ForeachSource = entries.firstOrNull { it.id == raw }
            ?: error("未知の列挙元です: $raw（view / issues / search / files / ids）")
    }
}

data class ForeachDef(
    val source: ForeachSource,
    val view: String? = null,          // view: 対象ビューID（横断グリッド）
    val where: String = "detail-missing",  // view: detail-missing（詳細未作成の行のみ）/ all（全行）
    val severity: String = "error",    // issues: error（エラーのみ）/ warning(警告以上)
    val match: String? = null,         // issues / files / search: ファイル glob
    val idPattern: String? = null,     // ids: 論理IDのパターン
    val schemaIds: List<String> = emptyList(),  // ids / search: スキーマ絞り込み
    val text: String? = null,          // search: テキスト
    val pathGlob: String? = null,      // search: パス glob
    val refTo: String? = null,         // search: このIDへの参照
    val unresolved: Boolean = false,   // search: 未解決参照のみ
    val asTemplate: String? = null,    // 1件の追加指示テンプレート（省略時は列挙元ごとの既定文）
    val limit: Int = 20,               // 1回の投入で展開する最大件数
)

/**
 * 作業項目1件。key は同一対象の重複投入を防ぐ識別子、instruction はその実行に注入する追加指示。
 * values はプレースホルダ値（{target} / {id} / {file} 等）で、コンテキストレシピの解決（R19-02）に渡す。
 */
data class ForeachItem(
    val key: String,
    val label: String,
    val instruction: String,
    val values: Map<String, String> = emptyMap(),
)

/** 列挙結果。items は limit で切った展開分、total は条件に一致した全件数。 */
data class ForeachEnumeration(val items: List<ForeachItem>, val total: Int)

object Foreach {

    /** 候補1件（プレースホルダの値つき）。 */
    private data class Candidate(val key: String, val label: String, val values: Map<String, String?>)

    fun enumerate(ws: Workspace, def: ForeachDef): ForeachEnumeration {
        val candidates = when (def.source) {
            ForeachSource.VIEW -> fromView(ws, def)
            ForeachSource.ISSUES -> fromIssues(ws, def)
            ForeachSource.SEARCH -> fromSearch(ws, def)
            ForeachSource.FILES -> fromFiles(ws, def)
            ForeachSource.IDS -> fromIds(ws, def)
        }
        val template = def.asTemplate ?: defaultTemplate(def.source)
        val items = candidates.take(def.limit).map { c ->
            // {target} はコンテキストレシピ向けの正規プレースホルダ（id > file > key の優先で決める）
            val values = c.values.filterValues { it != null }.mapValues { it.value!! } +
                ("target" to (c.values["id"] ?: c.values["file"] ?: c.key))
            ForeachItem(key = c.key, label = c.label, instruction = render(template, c.values), values = values)
        }
        return ForeachEnumeration(items, candidates.size)
    }

    /** 列挙元ごとの既定の追加指示。テンプレートの語彙（プレースホルダ）はここが規範。 */
    internal fun defaultTemplate(source: ForeachSource): String = when (source) {
        ForeachSource.VIEW, ForeachSource.IDS -> "対象: {id}"
        ForeachSource.FILES -> "対象ファイル: {file}"
        ForeachSource.ISSUES -> "対象の指摘を解消してください: {file} の {path} — {message}"
        ForeachSource.SEARCH -> "対象: {file} の {path}"
    }

    private fun render(template: String, values: Map<String, String?>): String =
        values.entries.fold(template) { acc, (k, v) -> acc.replace("{$k}", v ?: "") }

    // ---------------------------------------------------------------- 列挙元

    /** ビューの行。where: detail-missing は「詳細出現がない行」＝これから作るべき対象の一覧。 */
    private fun fromView(ws: Workspace, def: ForeachDef): List<Candidate> {
        val viewId = def.view ?: error("foreach.view が必要です")
        val loaded = Views.load(ws.root, ws.schemas)
        val view = loaded.view(viewId)
            ?: error("ビュー「$viewId」が見つかりません（foreach に使えるのは横断グリッドのビューです）")
        if (def.where == "detail-missing" && view.detail == null) {
            error("ビュー「$viewId」に detail 定義がないため where: detail-missing は使えません")
        }
        return Views.resolve(view, ws.root, ws.index)
            .filter { def.where == "all" || it.detailRel == null }
            .map { row ->
                Candidate(
                    key = row.id,
                    label = listOfNotNull(row.id, row.title).joinToString(" "),
                    values = mapOf("id" to row.id, "title" to row.title),
                )
            }
    }

    /** 検証の指摘。severity: error はエラーのみ、warning は警告以上（エラー含む）。予約ディレクトリは対象外。 */
    private fun fromIssues(ws: Workspace, def: ForeachDef): List<Candidate> {
        val regex = def.match?.let { globToRegex(it) }
        return ws.files.map { ws.relPath(it) }
            .filter { !ws.layout.isReserved(it) }
            .filter { regex == null || regex.matches(it) }
            .sorted()
            .flatMap { rel ->
                runCatching { ws.validate(rel) }.getOrDefault(emptyList())
                    .filter { it.severity == Severity.ERROR || (def.severity == "warning" && it.severity == Severity.WARNING) }
                    .map { issue ->
                        val path = issue.path.pretty()
                        Candidate(
                            key = "$rel::$path::${issue.message}",
                            label = "$rel $path",
                            values = mapOf(
                                "file" to rel,
                                "path" to path,
                                "message" to issue.message,
                                "severity" to issue.severity.name.lowercase(),
                            ),
                        )
                    }
            }
    }

    /** 検索ヒット。述語は search コマンド（core/Search.kt）と同じ語彙。limit は検索段階で効かせる。 */
    private fun fromSearch(ws: Workspace, def: ForeachDef): List<Candidate> =
        Search.run(
            ws,
            SearchQuery(
                text = def.text,
                schemaIds = def.schemaIds,
                fileGlob = def.match,
                pathGlob = def.pathGlob,
                refsTo = def.refTo,
                unresolvedRefs = def.unresolved,
                limit = def.limit,
            ),
        ).map { hit ->
            Candidate(
                key = "${hit.file}::${hit.path}",
                label = "${hit.file} ${hit.path}",
                values = mapOf("file" to hit.file, "path" to hit.path, "excerpt" to hit.excerpt),
            )
        }

    /** glob に一致する文書。予約ディレクトリ（_schemas / _runs 等）は対象外。 */
    private fun fromFiles(ws: Workspace, def: ForeachDef): List<Candidate> {
        val regex = globToRegex(def.match ?: error("foreach.match が必要です"))
        return ws.files.map { ws.relPath(it) }
            .filter { !ws.layout.isReserved(it) && regex.matches(it) }
            .sorted()
            .map { rel -> Candidate(key = rel, label = rel, values = mapOf("file" to rel)) }
    }

    /** 索引の論理ID。schemaIds が非空なら、いずれかのスキーマに出現するIDのみ（ビューの行と同じ規則）。 */
    private fun fromIds(ws: Workspace, def: ForeachDef): List<Candidate> {
        val pattern = def.idPattern ?: error("foreach.idPattern が必要です")
        return ws.index.entries.asSequence()
            .filter { (id, _) -> idMatchesPattern(id, pattern) }
            .filter { (_, occurrences) ->
                def.schemaIds.isEmpty() || occurrences.any { it.schemaId in def.schemaIds }
            }
            .sortedBy { it.key }
            .map { (id, occurrences) ->
                Candidate(
                    key = id,
                    label = listOfNotNull(id, occurrences.firstNotNullOfOrNull { it.title }).joinToString(" "),
                    values = mapOf(
                        "id" to id,
                        "title" to occurrences.firstNotNullOfOrNull { it.title },
                        "file" to occurrences.firstOrNull()?.let { ws.relPath(it.file) },
                    ),
                )
            }
            .toList()
    }
}
