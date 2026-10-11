package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.structure.StructureParser
import dev.toros.yde.yaml.structure.StructureValidator
import dev.toros.yde.yaml.validate.Severity

/** 文書単位のフロー。業務の項目名はprojectionだけが知り、解決結果を保存しない。 */
data class VirtualFlowDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val match: String? = null,
    val schemaId: String? = null,
    val direction: String = "down",
    val projection: TransformDef,
) : VirtualViewDefinition {
    private val regex by lazy { match?.let(::globToRegex) }

    fun appliesTo(document: DocumentId, schemaId: String?): Boolean =
        (regex?.matches(document.value) != false) && (this.schemaId == null || this.schemaId == schemaId)
}

data class FlowLocation(val document: DocumentId, val path: YPath)

/** keyの欠落・重複でも、出力位置と原本または定義の位置から診断対象を特定する。 */
data class FlowDiagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    val output: YPath,
    val locations: List<FlowLocation>,
)

/** 原本対応と編集適格性。detailは読み取り用の写しであり、編集対象の木ではない。 */
data class FlowDetail(val value: YNode?, val origins: List<FlowLocation>, val editable: Boolean)

enum class FlowConnectionState { RESOLVED, MISSING, AMBIGUOUS, OUTSIDE, UNRESOLVED, UNKNOWN }

data class FlowConnection(val key: String?, val state: FlowConnectionState, val target: YPath? = null)

data class FlowNode(
    val output: YPath,
    val key: String?,
    val label: String?,
    val shape: String,
    val lane: FlowConnection?,
    val detail: FlowDetail,
)

data class FlowEdge(
    val output: YPath,
    val key: String?,
    val label: String?,
    val from: FlowConnection,
    val to: FlowConnection,
    val detail: FlowDetail,
)

data class FlowLane(val output: YPath, val key: String?, val label: String?)

data class FlowModel(val nodes: List<FlowNode>, val edges: List<FlowEdge>, val lanes: List<FlowLane>)

data class FlowResolution(
    val model: FlowModel?,
    val origins: List<TransformOrigin>,
    val diagnostics: List<FlowDiagnostic>,
)

/** Constructで射影し、組込みの構造契約と図内の対応を検査する。原本・索引への変更は行わない。 */
object FlowViews {
    private val contract by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/builtin-structures/flow-model.yaml")) {
            "フロー表示モデルの構造スキーマが見つかりません"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        StructureParser.parse(YamlIO.parseToRaw(text), "flow-model")
    }

    /** ref型に裏付けられた原本位置だけ、既存の参照解決結果を呼び出し側から受け取る。 */
    fun resolve(
        view: VirtualFlowDef,
        document: DocumentId,
        schemaId: String?,
        input: YNode?,
        referenceState: (FlowLocation, String) -> FlowConnectionState = { _, _ -> FlowConnectionState.UNKNOWN },
    ): FlowResolution {
        fun failure(code: String, message: String, location: FlowLocation) = FlowResolution(
            null, emptyList(), listOf(FlowDiagnostic(code, Severity.ERROR, message, emptyList(), listOf(location))),
        )
        if (!view.appliesTo(document, schemaId)) return failure(
            "not-applicable", "文書がフロービューのmatch / schemaIdに適合しません", FlowLocation(view.source, emptyList()),
        )
        if (input == null) return failure("input-missing", "フローの原本文書を読み取れません", FlowLocation(document, emptyList()))
        val transformed = runCatching { SemanticTransform.run(view.projection, input, document) }.getOrElse {
            return failure("transform-failed", it.message ?: "フローへの変換に失敗しました", FlowLocation(view.source, listOf("projection")))
        }
        val term = transformed.term ?: return failure(
            "transform-empty", "フローへの変換結果がありません", FlowLocation(view.source, listOf("projection")),
        )
        val byOutput = transformed.origins.groupBy { it.output }
        fun locations(path: YPath): List<FlowLocation> = byOutput[path].orEmpty().mapNotNull {
            it.document?.let { document -> FlowLocation(document, it.path) }
        }.distinct()
        fun diagnosticLocations(path: YPath): List<FlowLocation> {
            locations(path).takeIf { it.isNotEmpty() }?.let { return it }
            if (path.size >= 2 && path[1] is Int) {
                locations(path.take(2) + "detail").takeIf { it.isNotEmpty() }?.let { return it }
            }
            for (size in path.size - 1 downTo 0) {
                locations(path.take(size)).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return listOf(FlowLocation(view.source, listOf("projection")))
        }
        val diagnostics = StructureValidator.validate(term, contract).mapTo(mutableListOf()) {
            val message = it.message + if (it.path.lastOrNull() == "shape") "（boxで表示します）" else ""
            FlowDiagnostic("structure-${it.kind.name.lowercase()}", it.severity, message, it.path, diagnosticLocations(it.path))
        }
        fun report(code: String, message: String, path: YPath) {
            diagnostics += FlowDiagnostic(code, Severity.ERROR, message, path, diagnosticLocations(path))
        }
        if (term !is YNode.Map) return FlowResolution(null, transformed.origins, diagnostics)
        fun elements(key: String): List<YNode> = (term.entries[key] as? YNode.Seq)?.items.orEmpty()
        fun text(node: YNode, key: String): String? =
            scalarText((node as? YNode.Map)?.entries?.get(key))
        fun key(node: YNode): String? = text(node, "key")?.takeIf { it.isNotBlank() }
        fun keyed(kind: String): Map<String, List<YPath>> = elements(kind).mapIndexedNotNull { i, node ->
            key(node)?.let { it to listOf<Any>(kind, i) }
        }.groupBy({ it.first }, { it.second }).also { groups ->
            groups.filterValues { it.size > 1 }.forEach { (key, paths) ->
                paths.forEach { report("duplicate-key", "識別子「$key」が${kind}内で重複しています", it + "key") }
            }
        }
        val nodesByKey = keyed("nodes")
        val lanesByKey = keyed("lanes")
        keyed("edges")
        fun connection(node: YNode, field: String, path: YPath, targets: Map<String, List<YPath>>, lane: Boolean = false): FlowConnection {
            val key = text(node, field)?.takeIf { it.isNotBlank() }
            val candidates = targets[key].orEmpty()
            val state = when {
                key == null -> FlowConnectionState.MISSING
                candidates.size == 1 -> FlowConnectionState.RESOLVED
                candidates.size > 1 -> FlowConnectionState.AMBIGUOUS
                lane -> FlowConnectionState.UNKNOWN
                else -> locations(path).singleOrNull()?.let { referenceState(it, key) } ?: FlowConnectionState.UNKNOWN
            }
            if (state != FlowConnectionState.RESOLVED) {
                val message = when (state) {
                    FlowConnectionState.MISSING -> "接続先の識別子がありません"
                    FlowConnectionState.AMBIGUOUS -> "接続先「$key」が重複しており、一意に選べません"
                    FlowConnectionState.OUTSIDE -> "参照先「$key」は存在しますが、図の表示対象に含まれません"
                    FlowConnectionState.UNRESOLVED -> "参照先「$key」を解決できません"
                    else -> if (lane) "レーン「$key」が図内にありません" else "接続先「$key」が図内にありません"
                }
                report(if (lane) "lane-${state.name.lowercase()}" else "endpoint-${state.name.lowercase()}", message, path)
            }
            return FlowConnection(key, state, candidates.singleOrNull())
        }
        val build = view.projection.build as? Template.MapOf
        fun detail(kind: String, i: Int, node: YNode, expected: List<YPath>?): FlowDetail {
            val origin = locations(listOf(kind, i, "detail"))
            return FlowDetail((node as? YNode.Map)?.entries?.get("detail"), origin,
                expected?.getOrNull(i)?.let { origin == listOf(FlowLocation(document, it)) } == true)
        }
        val editableNodes = editablePaths(build?.entries?.find { it.first == "nodes" }?.second, input)
        val editableEdges = editablePaths(build?.entries?.find { it.first == "edges" }?.second, input)
        val nodes = elements("nodes").mapIndexed { i, node ->
            val path = listOf<Any>("nodes", i)
            val shape = text(node, "shape")?.takeIf { it in setOf("box", "diamond", "rounded") } ?: "box"
            FlowNode(path, key(node), text(node, "label"), shape,
                if ((node as? YNode.Map)?.entries?.containsKey("lane") == true) connection(node, "lane", path + "lane", lanesByKey, true) else null,
                detail("nodes", i, node, editableNodes))
        }
        val edges = elements("edges").mapIndexed { i, node ->
            val path = listOf<Any>("edges", i)
            FlowEdge(path, key(node), text(node, "label"), connection(node, "from", path + "from", nodesByKey),
                connection(node, "to", path + "to", nodesByKey), detail("edges", i, node, editableEdges))
        }
        val lanes = elements("lanes").mapIndexed { i, node -> FlowLane(listOf("lanes", i), key(node), text(node, "label")) }
        return FlowResolution(FlowModel(nodes, edges, lanes), transformed.origins, diagnostics)
    }

    /** 評価器を作らず、直接eachまたはそのconcatだけの出力番号と原本位置を照合する。 */
    private fun editablePaths(template: Template?, input: YNode): List<YPath>? = when (template) {
        is Template.Each -> {
            val body = template.body as? Template.MapOf
            val copy = body?.entries?.find { it.first == "detail" }?.second as? Template.Copy
            if (template.entries || template.site.of != null || template.site.inDoc != null || copy == null ||
                copy.site.of != null || copy.site.inDoc != null || copy.site.path.isNotEmpty()) null
            else (getAt(input, template.site.path) as? YNode.Seq)?.items?.indices?.map { template.site.path + it }.orEmpty()
        }
        is Template.Concat -> {
            val parts = template.parts.map { editablePaths(it, input) }
            if (parts.any { it == null }) null else parts.filterNotNull().flatten()
        }
        else -> null
    }
}
