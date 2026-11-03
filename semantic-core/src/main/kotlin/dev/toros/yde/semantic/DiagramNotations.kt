package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.structure.StructureParser
import dev.toros.yde.yaml.structure.StructureSchema
import dev.toros.yde.yaml.structure.StructureValidator

/** 記法は表示の契約。参照・保有・型の意味や原本の木を変更しない。 */
data class VirtualDiagramNotationDef(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    override val source: DocumentId,
    val target: String,
    val input: StructureSchema,
    val transform: TransformDef,
) : VirtualViewDefinition

/** 対象の種類で一意に選ぶ記法と、既存の変換で組む入力。未指定の条件は全対象に当たる。 */
data class DiagramBinding(
    val notation: String,
    val inputs: TransformDef,
    val level: String? = null,
    val entityType: String? = null,
    val relation: String? = null,
)

data class DiagramAppearance(val nodes: List<DiagramBinding> = emptyList(), val edges: List<DiagramBinding> = emptyList()) {
    fun node(level: String, type: String?): List<DiagramBinding> = nodes.filter {
        (it.level == null || it.level == level) && (it.entityType == null || it.entityType == type)
    }
    fun edge(relation: String?): List<DiagramBinding> = edges.filter { it.relation == null || it.relation == relation }
}

/** 描画の向きや畳みの前の論理的な端点。参照の属性と原本を残す。 */
data class DiagramLinkSource(val sourceId: String, val targetId: String, val reference: ScannedRef? = null)

data class DiagramLocation(val document: DocumentId, val path: YPath)
data class DiagramNotice(val message: String, val location: DiagramLocation)

/** 部品の座標は表示モデル内。origins が原本との対応を持つ。 */
data class DiagramPart(
    val path: YPath,
    val kind: String,
    val text: String = "",
    val source: String? = null,
    val children: List<DiagramPart> = emptyList(),
    val layout: String = "column",
    val align: String = "start",
    val shape: String = "box",
    val width: Float? = null,
    val height: Float? = null,
    val padding: Float = 8f,
    val gap: Float = 4f,
    val maxLines: Int = 12,
    val fit: String = "contain",
    val fallback: String = "person",
    val tone: String = "default",
)

data class DiagramEdgeStyle(
    val stroke: String = "solid",
    val width: Float = 1.5f,
    val sourceMarker: String = "none",
    val targetMarker: String = "arrow",
    val label: String? = null,
    val sourceLabel: String? = null,
    val targetLabel: String? = null,
    val tone: String = "default",
) {
    /** 反転の決定はLensGraphsだけ。描画側ではその印に従って端の記号と文字を交換する。 */
    fun reversedEndpoints(): DiagramEdgeStyle = copy(sourceMarker = targetMarker, targetMarker = sourceMarker,
        sourceLabel = targetLabel, targetLabel = sourceLabel)
}

data class DiagramRendering(
    val node: DiagramPart? = null,
    val edge: DiagramEdgeStyle? = null,
    val origins: List<TransformOrigin> = emptyList(),
    val notices: List<DiagramNotice> = emptyList(),
    val term: YNode? = null,
    val definition: DocumentId? = null,
) {
    fun locations(path: YPath): List<DiagramLocation> {
        val matches = origins.filter { path.take(it.output.size) == it.output }
        val length = matches.maxOfOrNull { it.output.size } ?: return emptyList()
        return matches.filter { it.output.size == length }.mapNotNull { origin ->
            origin.document?.let { DiagramLocation(it, origin.path + path.drop(origin.output.size)) }
        }.distinct()
    }

    /** 複数の出どころのうち一つを恣意的に編集先へ選ばない。 */
    fun location(path: YPath): DiagramLocation? = locations(path).singleOrNull()
}

/** 合成した入力でも最長一致の来歴を保ち、画像の相対パス・項目への移動を原本文書に戻す。 */
class DiagramInputPlace(
    node: YNode,
    private val origins: List<TransformOrigin>,
    private val output: YPath = emptyList(),
) : SemanticTransform.TemplatePlace(node, originAt(origins, output)?.document,
    originAt(origins, output)?.let { it.path + output.drop(it.output.size) }.orEmpty()) {
    override fun at(relative: YPath): SemanticTransform.TemplatePlace? {
        val value = getAt(node, relative) ?: return null
        return DiagramInputPlace(value, origins, output + relative)
    }

    override fun copyOrigins(output: YPath): List<TransformOrigin> = buildList {
        closest(origins, this@DiagramInputPlace.output).forEach {
            add(TransformOrigin(output, it.document, it.path + this@DiagramInputPlace.output.drop(it.output.size)))
        }
        origins.filter { it.output.size > this@DiagramInputPlace.output.size && it.output.take(this@DiagramInputPlace.output.size) == this@DiagramInputPlace.output }
            .forEach { add(it.copy(output = output + it.output.drop(this@DiagramInputPlace.output.size))) }
    }

    companion object {
        private fun closest(origins: List<TransformOrigin>, path: YPath): List<TransformOrigin> {
            val matches = origins.filter { path.take(it.output.size) == it.output }
            val length = matches.maxOfOrNull { it.output.size } ?: return emptyList()
            return matches.filter { it.output.size == length }.distinct()
        }
        private fun originAt(origins: List<TransformOrigin>, path: YPath) = closest(origins, path).singleOrNull()
    }
}

/** Construct の結果を構造契約で検査して表示用の型へ読む。値・条件をここで再評価しない。 */
object DiagramNotations {
    private fun contract(name: String): StructureSchema = StructureParser.parse(
        YamlIO.parseToRaw(requireNotNull(javaClass.getResourceAsStream("/diagram/$name.yaml")) {
            "図の表示契約が見つかりません: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }), "diagram-$name",
    )
    private val nodeContract by lazy { contract("node") }
    private val edgeContract by lazy { contract("edge") }

    fun resolve(
        definitions: List<VirtualDiagramNotationDef>, bindings: List<DiagramBinding>,
        input: SemanticTransform.TemplatePlace, source: DocumentId, target: String,
    ): DiagramRendering? {
        if (bindings.isEmpty()) return null
        fun failure(message: String) = DiagramRendering(notices = listOf(DiagramNotice(message, DiagramLocation(source, listOf("appearance")))))
        if (bindings.size != 1) return failure("複数の記法指定が同じ対象に当たります")
        val binding = bindings.single()
        val def = definitions.singleOrNull { it.id == binding.notation }
            ?: return failure("記法「${binding.notation}」が見つからないか、IDが重複しています")
        if (def.target != target) return failure("記法「${def.id}」は $target 用ではありません")
        return runCatching {
            val values = SemanticTransform.runAt(binding.inputs, input)
            val value = values.term ?: YNode.Map(linkedMapOf())
            val inputIssues = StructureValidator.validate(value, def.input)
            if (inputIssues.isNotEmpty()) return@runCatching DiagramRendering(notices = inputIssues.map {
                DiagramNotice("記法「${def.id}」の入力: ${it.message}", DiagramLocation(source, listOf("appearance")))
            })
            val result = SemanticTransform.runAt(def.transform, DiagramInputPlace(value, values.origins))
            val term = result.term ?: return@runCatching failure("記法「${def.id}」の結果がありません")
            val issues = StructureValidator.validate(term, if (target == "node") nodeContract else edgeContract)
            val notices = issues.map { DiagramNotice(it.message, DiagramLocation(def.source, listOf("build") + it.path)) }
            if (notices.isNotEmpty()) DiagramRendering(notices = notices, definition = def.source)
            else DiagramRendering(
                node = if (target == "node") part(term, emptyList()) else null,
                edge = if (target == "edge") edge(term) else null,
                origins = result.origins, term = term, definition = def.source,
            )
        }.getOrElse { failure("記法「${def.id}」を解決できません: ${it.message}") }
    }

    private fun text(n: YNode, key: String): String? = scalarText((n as? YNode.Map)?.entries?.get(key))
    private fun number(n: YNode, key: String): Float? = ((n as? YNode.Map)?.entries?.get(key) as? YNode.Scalar)?.value.let { (it as? Number)?.toFloat() }
    private fun part(n: YNode, path: YPath): DiagramPart = DiagramPart(
        path, text(n, "kind")!!, text(n, "text").orEmpty(), text(n, "source"),
        (((n as? YNode.Map)?.entries?.get("children") as? YNode.Seq)?.items.orEmpty()).mapIndexed { i, child -> part(child, path + "children" + i) },
        text(n, "layout") ?: "column", text(n, "align") ?: "start", text(n, "shape") ?: "box",
        number(n, "width"), number(n, "height"), number(n, "padding") ?: 8f, number(n, "gap") ?: 4f,
        number(n, "maxLines")?.toInt() ?: 12, text(n, "fit") ?: "contain", text(n, "fallback") ?: "person", text(n, "tone") ?: "default",
    )
    private fun edge(n: YNode): DiagramEdgeStyle = DiagramEdgeStyle(
        text(n, "stroke") ?: "solid", number(n, "width") ?: 1.5f,
        text(n, "sourceMarker") ?: "none", text(n, "targetMarker") ?: "arrow",
        text(n, "label"), text(n, "sourceLabel"), text(n, "targetLabel"), text(n, "tone") ?: "default",
    )
}
