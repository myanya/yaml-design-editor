package dev.toros.yde.view.flow

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.pretty

/** CLIとエージェントが同じ原本座標・診断を開示するための出力形式（FlowViewResult.report）。 */
internal object FlowReports {
    fun toMap(view: VirtualFlowDef, document: DocumentId, result: FlowResolution, errors: List<String> = emptyList()): Map<String, Any?> {
        fun location(value: FlowLocation) =
            linkedMapOf("file" to value.document.value, "path" to value.path.pretty())
        fun detail(value: FlowDetail) = linkedMapOf(
            "value" to value.value?.let { YamlIO.toAny(it) },
            "origins" to value.origins.map(::location), "editable" to value.editable,
        )
        fun connection(value: FlowConnection) = linkedMapOf(
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
