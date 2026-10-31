package dev.toros.yde.app.service

import dev.toros.yde.app.core.EditBuffer
import dev.toros.yde.app.session.DocumentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** 入力の遅延解析と木から下書きへの同期。画面を隠しても継続する。 */
class SourceDraftWorker(
    private val host: WorkspaceHost,
    private val scope: CoroutineScope,
    private val delayMillis: Long = 300,
    private val parse: suspend (String) -> EditBuffer.Parsed = { text ->
        withContext(Dispatchers.Default) { EditBuffer.parse(text) }
    },
) : AutoCloseable {
    private val jobs = mutableMapOf<DocumentState, Job>()
    private var closed = false
    private var syncJob: Job? = null
    private val inputs = host.documents.observeSourceInput(::schedule)
    private val changes = host.documents.observeChanges { scheduleSync() }

    init {
        host.documents.documents.values.filter { it.hasPendingSourceInput }.forEach(::schedule)
        scheduleSync()
    }

    private fun owns(doc: DocumentState): Boolean = !closed && host.documents.document(doc.file) === doc

    private fun schedule(doc: DocumentState) {
        if (!owns(doc)) return
        jobs.remove(doc)?.cancel()
        jobs[doc] = scope.launch {
            // 入力変換内では Compose 状態へ触れない。入力が確定した後に通知・同期する。
            yield()
            host.publish()
            delay(delayMillis)
            host.status.first { it.busy == null || it.closed }
            if (!owns(doc)) return@launch
            val request = doc.sourceParseRequest() ?: return@launch
            val parsed = parse(request.text)
            // 別文書の解析が先に終わっても、先行入力の履歴を追い越さない。
            jobs.entries.filter { (other, job) ->
                other !== doc && owns(other) && other.hasPendingSourceInput &&
                    other.sourceInputOrder < request.inputOrder && job.isActive
            }.map { it.value }.forEach { it.join() }
            host.status.first { it.busy == null || it.closed }
            if (owns(doc) && !doc.applyParsed(request, parsed) && doc.hasPendingSourceInput) {
                // 同期で下書きが変わった場合も、新しい入力のキー待ちにせず取り直す。
                scope.launch { yield(); if (owns(doc) && doc.hasPendingSourceInput) schedule(doc) }
            }
        }
    }

    private fun scheduleSync() {
        if (closed || syncJob?.isActive == true) return
        syncJob = scope.launch {
            yield()
            host.status.first { it.busy == null || it.closed }
            if (closed) return@launch
            jobs.keys.filterNot(::owns).forEach { jobs.remove(it)?.cancel() }
            host.documents.documents.values.toList().forEach { doc ->
                if (doc.version != doc.sourceSyncedVersion) {
                    val conflicts = doc.syncSourceFromTree().conflicts
                    if (conflicts > 0) host.documents.onNotice?.invoke("下書きと食い違う変更が $conflicts か所あり、下書きを優先しました")
                }
                if (doc.hasPendingSourceInput && jobs[doc]?.isActive != true) schedule(doc)
            }
        }
    }

    override fun close() {
        closed = true
        inputs.close(); changes.close()
        syncJob?.cancel()
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }
}
