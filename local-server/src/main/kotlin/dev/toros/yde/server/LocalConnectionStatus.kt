package dev.toros.yde.server

enum class LocalFeature { API, MCP, WEB }
enum class ConnectionPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class FeatureConnectionStatus(val phase: ConnectionPhase, val generation: String, val error: String? = null)

/** 希望値と実際の状態を区別する。ポートは停止に成功するまで保持し、停止失敗を OFF 完了にしない。 */
data class LocalConnectionStatus(
    val desired: LocalConnectionSettings,
    val listener: ConnectionPhase,
    val boundPort: Int?,
    val features: Map<LocalFeature, FeatureConnectionStatus>,
    val revision: Long,
    val settledRevision: Long,
    val error: String? = null,
) {
    val stopped: Boolean get() = listener == ConnectionPhase.STOPPED && boundPort == null &&
        features.values.all { it.phase == ConnectionPhase.STOPPED }
}
