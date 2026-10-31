package dev.toros.yde.workspace

import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.validate.Severity

/** 確認と保存で共用する確定候補。可変の木を外へ出さず、表記と自動値を固定する。 */
data class PreparedDocumentChange(val id: DocumentId, val before: String?, val after: String)

class PreparedChangeSet internal constructor(
    val documents: List<PreparedDocumentChange>,
    val issues: Map<DocumentId, List<CheckIssue>>,
    val failures: Map<String, String>,
) {
    val valid: Boolean get() = failures.isEmpty() && issues.values.flatten().none { it.severity == Severity.ERROR }
}

/** 保存できたことと、書込失敗時に元のままか確認できたことを区別する。 */
enum class ChangePersistence { WRITTEN, UNCHANGED, NOT_WRITTEN, UNKNOWN }

/** 変更前後の定義 ID と参照から、ステージングと適用で同じ検証範囲を作る。 */
object ChangeSetValidation {
    fun validate(base: WorkspaceSession, overlays: Map<DocumentId, YDocument>, includeAffected: Boolean = true): Map<DocumentId, List<CheckIssue>> {
        val derived = base.derivedWith(overlays)
        val documents = LinkedHashMap(base.snapshot.documents)
        overlays.forEach { (id, doc) -> documents[id] = WorkspaceDocument(
            doc, null, WorkspaceReader.assignSchemaId(id.value, doc, base.schemas, base.layout), base.layout.inLedger(id.value),
        ) }
        val targets = overlays.keys.toMutableSet()
        if (includeAffected) {
            val before = base.derived.semantic
            val after = derived.semantic
            val ids = (before.ids.values.flatten() + after.ids.values.flatten())
                .filter { it.document in targets }.map { it.id }.toSet()
            val documentIds = (before.scans + after.scans).filter { it.document in targets }.map { it.documentId }.toSet()
            targets += (before.refs + after.refs).filter {
                if (it.targetKind == RefTarget.DOCUMENT) it.targetId in documentIds else it.targetId in ids
            }.map { it.document }
            // 明示的な参照を介さない横断の検査も、結果が変わる文書を影響先へ含める。
            // 無関係な文書に元からある同じエラーでは確定を塞がない。
            val oldResults = WorkspaceCheckRunner.validateAll(base.schemas, base.declarations, base.layout,
                base.snapshot.documents, base.derived, indexedOf = { true }, fileExists = base::fileExists)
            val newResults = WorkspaceCheckRunner.validateAll(base.schemas, base.declarations, base.layout,
                documents, derived, indexedOf = { true }, fileExists = base::fileExists)
            targets += (oldResults.keys + newResults.keys).filter { oldResults[it] != newResults[it] }
            return newResults.filterKeys { it in targets }.mapValues { it.value.issues }
        }
        return WorkspaceCheckRunner.validateAll(
            base.schemas, base.declarations, base.layout, documents, derived,
            only = targets, indexedOf = { true }, fileExists = base::fileExists,
        ).mapValues { it.value.issues }
    }
}
