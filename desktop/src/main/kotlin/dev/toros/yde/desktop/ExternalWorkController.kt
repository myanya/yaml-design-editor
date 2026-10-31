package dev.toros.yde.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.toros.yde.app.service.OperationPreview
import dev.toros.yde.app.service.WorkRecord
import dev.toros.yde.app.service.WorkState
import dev.toros.yde.app.service.WorkspaceWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 外部提出の画面用状態。Window の寿命ではなく DesktopRuntime のワークスペース寿命で保持する。 */
class ExternalWorkController(private val service: WorkspaceWork, private val scope: CoroutineScope) {
    var records by mutableStateOf<List<WorkRecord>>(emptyList())
        private set
    var preview by mutableStateOf<OperationPreview?>(null)
        private set
    var reviewedId by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    val pendingCount: Int get() = records.count { it.state in setOf(WorkState.AWAITING_APPROVAL, WorkState.APPLYING, WorkState.UNKNOWN, WorkState.PARTIAL) }

    init { scope.launch { service.inbox.collect { records = it } } }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "提出の処理に失敗しました: ${failure.message ?: "詳細不明"}" }
            finally { busy = false }
        }
    }

    fun refresh() = run { service.refresh() }
    fun review(id: String) = run {
        preview = null; reviewedId = id
        preview = service.review(id)
    }
    fun approve(id: String) = run {
        require(reviewedId == id) { "差分を再確認してください" }
        val plan = requireNotNull(preview) { "差分を再確認してください" }
        try { service.approve(id, plan.id) } finally { preview = null }
    }
    fun reject(id: String) = run { service.reject(id); preview = null; reviewedId = null }
}

internal fun WorkState.label(): String = when (this) {
    WorkState.WORKING -> "作業中"
    WorkState.VALIDATED -> "検証済み"
    WorkState.AWAITING_APPROVAL -> "承認待ち"
    WorkState.APPLYING -> "確定中"
    WorkState.APPLIED -> "保存済み"
    WorkState.REJECTED -> "却下済み"
    WorkState.DISCARDED -> "破棄済み"
    WorkState.FAILED -> "確定できませんでした"
    WorkState.PARTIAL -> "一部保存・確認が必要"
    WorkState.UNKNOWN -> "結果不明・確認が必要"
    WorkState.RECONCILED -> "照合済み"
}
