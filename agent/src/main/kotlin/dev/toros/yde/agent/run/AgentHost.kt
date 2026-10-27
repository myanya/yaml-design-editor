package dev.toros.yde.agent.run

/*
 * 実行の土台の小さな型: 実行の終わり方（RunStatus）・進捗の知らせ（AgentEvent）・カーネルと外側（UI・CLI）の接点（AgentHost）・
 * 初期文脈の 1 区分（ContextSection）。カーネル（Kernel.kt）とツール（Tools.kt）の両方が使うので、どちらにも置かずここに置く（循環を作らない）。
 */

enum class RunStatus { SUBMITTED, ABORTED, LIMIT, CANCELLED, ERROR }

sealed class AgentEvent {
    data class TurnStarted(val turn: Int, val maxTurns: Int) : AgentEvent()
    data class AssistantText(val text: String) : AgentEvent()
    data class ToolInvoked(val name: String, val summary: String) : AgentEvent()
    data class ToolFailed(val name: String, val message: String) : AgentEvent()
    data class QuestionAsked(val question: String) : AgentEvent()
    data class PlanUpdated(val steps: List<String>) : AgentEvent()
    data class Finished(val status: RunStatus, val message: String?) : AgentEvent()
}

/** カーネルと外側（UI・CLI）の接点。質問への回答と進捗表示を提供する。 */
interface AgentHost {
    /** ブロッキングな質問。null を返すと「回答なし」としてエージェントに伝わる。 */
    suspend fun askHuman(question: String): String?
    suspend fun onEvent(event: AgentEvent) {}
}

/** 初期コンテキストの1区分。label はコンテキスト会計（内訳表示）の区分名になる（R19-01）。 */
class ContextSection(val label: String, val body: String)
