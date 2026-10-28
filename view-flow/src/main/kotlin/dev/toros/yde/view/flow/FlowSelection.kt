package dev.toros.yde.view.flow

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath

/** 図で選んだ要素の種類（画面の選択。描画が読むので公開する）。 */
enum class FlowElementKind { NODE, EDGE }

/** 無key辺は原本と定義の同じ版の間だけ有効。版は呼び出し側がセッション内で発行する。 */
data class FlowSelection(
    val view: String,
    val document: DocumentId,
    val kind: FlowElementKind,
    val key: String?,
    val output: YPath,
    val documentRevision: Long,
    val definitionRevision: Long,
) {
    fun resolve(
        view: String, document: DocumentId, model: FlowModel,
        documentRevision: Long, definitionRevision: Long,
    ): YPath? {
        if (this.view != view || this.document != document) return null
        val elements = when (kind) {
            FlowElementKind.NODE -> model.nodes.map { it.key to it.output }
            FlowElementKind.EDGE -> model.edges.map { it.key to it.output }
        }
        if (!key.isNullOrBlank()) return elements.singleOrNull { it.first == key }?.second
        if (kind != FlowElementKind.EDGE || this.documentRevision != documentRevision ||
            this.definitionRevision != definitionRevision) return null
        return elements.singleOrNull { it.first.isNullOrBlank() && it.second == output }?.second
    }
}
