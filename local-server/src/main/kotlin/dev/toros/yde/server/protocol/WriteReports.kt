package dev.toros.yde.server.protocol

import dev.toros.yde.app.service.*
import dev.toros.yde.workspace.PreparedDocumentChange
import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.yaml.model.DocumentId

internal fun WorkRecord.report(): Map<String, Any?> = mapOf(
    "id" to id, "mode" to mode.name.lowercase(), "title" to title, "state" to state.name.lowercase(),
    "createdAt" to createdAt, "submittedAt" to submittedAt, "planId" to planId,
    "documents" to documents.map(::documentReport), "persistence" to persistence,
)
internal fun OperationPreview.report(): Map<String, Any?> = mapOf(
    "planId" to id, "expiresAt" to expiresAt.toString(), "canCommit" to canCommit,
    "documents" to documents.map(::documentReport), "issues" to issueReports(issues), "failedDocuments" to failures.keys.toList(),
)
internal fun WorkValidation.report(): Map<String, Any?> = mapOf(
    "valid" to valid, "documents" to documents.map(::documentReport), "issues" to issueReports(issues),
    "failedDocuments" to failures.keys.toList(),
)
internal fun OperationRecord.report(): Map<String, Any?> = mapOf(
    "requestId" to requestId.removePrefix("action/"), "planId" to planId, "state" to state.name.lowercase(),
    "before" to before, "after" to after, "persistence" to persistence,
)
private fun documentReport(d: PreparedDocumentChange) = mapOf("document" to d.id.value, "before" to d.before, "after" to d.after)
private fun issueReports(issues: Map<DocumentId, List<CheckIssue>>) = issues.flatMap { (id, values) -> values.map {
    mapOf("document" to id.value, "path" to it.path, "severity" to it.severity.name.lowercase(), "message" to it.message)
} }
