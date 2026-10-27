package dev.toros.yde.agent

import dev.toros.yde.semantic.schema.QuerySpec

/**
 * 一括実行（foreach。R18-01）の定義: アクティビティ定義の `foreach` に書く列挙元と条件。読むのは Definitions、
 * 列挙して作業項目に展開するのは auto の Foreach（docs/architecture/agents.md「一括実行」）。
 */

enum class ForeachSource(val id: String) {
    VIEW("view"),       // 横断グリッドビューの行（詳細未作成の行 / 全行）
    ISSUES("issues"),   // 検証の指摘（ファイルglob・重大度で絞る）
    SEARCH("search"),   // 検索ヒット（テキスト・パス・参照・未解決）
    FILES("files"),     // glob に一致する文書
    IDS("ids"),         // idPattern × schemaIds に一致する論理ID
    QUERY("query");     // パターン照合の束縛行（R22-02。architecture/graph-query.md「クエリコア」）

    companion object {
        fun parse(raw: String): ForeachSource = entries.firstOrNull { it.id == raw }
            ?: error("未知の列挙元です: $raw（view / issues / search / files / ids / query）")
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
    val query: QuerySpec? = null,  // query: パターン照合のスペック（R22-02）
    val asTemplate: String? = null,    // 1件の追加指示テンプレート（省略時は列挙元ごとの既定文）
    val limit: Int = 20,               // 1回の投入で展開する最大件数
)
