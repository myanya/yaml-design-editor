package dev.toros.yde.agent

import dev.toros.yde.app.core.Views
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.app.tools.WorkingCopy
import dev.toros.yde.workspace.ReportDefinitions
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO

/**
 * エージェントの作業オーバーレイ（R2-05）＝作業コピー（app-core の `WorkingCopy`）に定義文書のゲートを足したもの。
 * 編集は原本（ディスク）に触れず、提出物はそのまま変更セットになる。実行が上限で打ち切られても、内容は未提出ドラフトとして取り出せる。
 * メタアクティビティ（R4-03）: ステージされた定義文書は実際のローダでパースできることもゲート条件にする
 * （スキーマ検証だけでは「schedule に everyMinutes がない」等の意味的エラーを見逃すため）。
 */
class Staging(ws: Workspace) : WorkingCopy(ws) {

    /** ステージされた定義文書（agents / activities / evals / skills / views ゾーン）のパースエラー。対象外は null。 */
    override fun definitionParseError(e: WorkingCopy.Entry): String? {
        val rel = e.rel.replace('\\', '/')
        val parse: (Map<*, *>) -> Unit = when (ws.layout.zoneOf(rel)) {
            WorkspaceLayout.Zone.AGENTS -> { raw -> Definitions.parseAgent(raw, e.file) }
            WorkspaceLayout.Zone.ACTIVITIES -> { raw -> Definitions.parseActivity(raw, e.file) }
            WorkspaceLayout.Zone.EVALS -> { raw -> Evals.parseEval(raw, e.file) }
            WorkspaceLayout.Zone.SKILLS -> { raw -> Skills.parseSkill(raw, e.file) }
            WorkspaceLayout.Zone.VIEWS -> { raw -> Views.parseView(raw, e.file, ws.schemas) }
            WorkspaceLayout.Zone.REPORTS -> { raw -> ReportDefinitions.parse(raw, dev.toros.yde.yaml.model.DocumentId(rel)) }
            else -> return null
        }
        val raw = YamlIO.toAny(e.root) as? Map<*, *> ?: return "定義のルートはobjectである必要があります"
        return runCatching { parse(raw) }.exceptionOrNull()
            ?.let { "定義として解釈できません: ${it.message ?: it::class.simpleName}" }
    }
}
