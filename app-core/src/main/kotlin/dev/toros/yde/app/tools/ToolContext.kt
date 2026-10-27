package dev.toros.yde.app.tools

import dev.toros.yde.app.core.Workspace

/** ツールが見る場所: ワークスペース・作業コピー（読み書きはここを通す）・書き込みの許可。 */
class ToolContext(val ws: Workspace, val copy: WorkingCopy, val policy: ToolPolicy = ToolPolicy())

/**
 * 書き込みの許可。入口がアクティビティの定義などから組み立てる。空の一覧は「制限なし」。
 * 権限はモデルへの指示でなくツール側（`Toolset`）で強制する（サンドボックスの要）。
 */
data class ToolPolicy(
    /** 定義ゾーン（`_agents` / `_activities` / `_evals` / `_skills` / `_views`）へ書ける（メタアクティビティ）。`_schemas` / `_runs` はこれでも不可。 */
    val meta: Boolean = false,
    /** 書込を許すファイルの glob（空 = 台帳の中なら制限なし）。 */
    val allowedFiles: List<String> = emptyList(),
    /** 書込を許す YPath の glob（空 = 制限なし）。 */
    val allowedPaths: List<String> = emptyList(),
    /** ツールにする操作（アクションの id か `型名.操作名`。architecture/actions.md）。無い操作を書いた許可は誤り。 */
    val actions: List<String> = emptyList(),
)
