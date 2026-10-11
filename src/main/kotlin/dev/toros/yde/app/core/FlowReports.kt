package dev.toros.yde.app.core

import dev.toros.yde.semantic.FlowResolution
import dev.toros.yde.semantic.VirtualFlowDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.pretty

/** CLIとエージェントが同じ原本座標・診断を開示するための出力形式。 */
object FlowReports {
    /** 埋め込み先は現在の文書に固定し、適用条件は通常のフロー解決で判定する。 */
    fun embedded(
        id: String, document: DocumentId, catalog: Views.LoadResult,
        index: dev.toros.yde.app.index.IdIndex,
        schemaFor: (DocumentId) -> dev.toros.yde.app.schema.SchemaDef?,
        loader: (DocumentId) -> dev.toros.yde.yaml.model.YNode?,
    ): Map<String, Any?> {
        val targets = (catalog.views + catalog.docViews + catalog.flowViews).filter { it.id == id }
        val view = targets.singleOrNull() as? VirtualFlowDef
            ?: return linkedMapOf("view" to id, "kind" to "flow", "file" to document.value,
                "errors" to listOf("埋め込み先「$id」は一意なflowビューを参照してください"))
        return toMap(view, document, Views.resolveFlow(view, document, index, schemaFor, loader))
    }

    fun toMap(view: VirtualFlowDef, document: DocumentId, result: FlowResolution, errors: List<String> = emptyList()): Map<String, Any?> {
        fun location(value: dev.toros.yde.semantic.FlowLocation) =
            linkedMapOf("file" to value.document.value, "path" to value.path.pretty())
        fun detail(value: dev.toros.yde.semantic.FlowDetail) = linkedMapOf(
            "value" to value.value?.let { YamlIO.toAny(it) },
            "origins" to value.origins.map(::location), "editable" to value.editable,
        )
        fun connection(value: dev.toros.yde.semantic.FlowConnection) = linkedMapOf(
            "key" to value.key, "state" to value.state.name.lowercase(), "target" to value.target?.pretty(),
        )
        return linkedMapOf(
            "view" to view.id, "kind" to "flow", "file" to document.value, "direction" to view.direction,
            "model" to result.model?.let { model -> linkedMapOf(
                "nodes" to model.nodes.map { node -> linkedMapOf(
                    "output" to node.output.pretty(), "key" to node.key, "label" to node.label,
                    "shape" to node.shape, "lane" to node.lane?.let(::connection), "detail" to detail(node.detail),
                ) },
                "edges" to model.edges.map { edge -> linkedMapOf(
                    "output" to edge.output.pretty(), "key" to edge.key, "label" to edge.label,
                    "from" to connection(edge.from), "to" to connection(edge.to), "detail" to detail(edge.detail),
                ) },
                "lanes" to model.lanes.map { lane -> linkedMapOf(
                    "output" to lane.output.pretty(), "key" to lane.key, "label" to lane.label,
                ) },
            ) },
            "origins" to result.origins.map { origin -> linkedMapOf(
                "output" to origin.output.pretty(), "file" to origin.document?.value, "path" to origin.path.pretty(),
            ) },
            "diagnostics" to result.diagnostics.map { diagnostic -> linkedMapOf(
                "code" to diagnostic.code, "severity" to diagnostic.severity.name,
                "message" to diagnostic.message, "output" to diagnostic.output.pretty(),
                "locations" to diagnostic.locations.map(::location),
            ) },
            "errors" to errors,
        )
    }
}
