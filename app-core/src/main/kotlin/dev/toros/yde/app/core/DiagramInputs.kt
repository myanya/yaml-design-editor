package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.*

/** レンズの意味を変えず、既存の合成と原本から記法へ渡す値を束ねる。 */
object DiagramInputs {
    data class Input(val place: DiagramInputPlace, val notices: List<DiagramNotice> = emptyList())

    fun entity(id: String, occurrence: ScannedId?, index: IdIndex, schemas: List<SchemaDef>,
        entityTypes: Map<String, EntityTypeDef>, loader: (DocumentId) -> YNode?): Input {
        val entity = index.graph.entities[id]
        val data = linkedMapOf<String, YNode>()
        val origins = mutableListOf<TransformOrigin>()
        val notices = mutableListOf<DiagramNotice>()
        if (occurrence != null) {
            val value = loader(occurrence.document)?.let { getAt(it, occurrence.path) }
            if (value is YNode.Map) data.putAll(value.entries)
            origins += TransformOrigin(listOf("data"), occurrence.document, occurrence.path)
        } else {
            val composite = Aspects.compose(id, index, schemas, entityTypes, loader)
            composite?.sections.orEmpty().flatMap { it.fields }.groupBy { it.key }.forEach { (key, fields) ->
                if (fields.size > 1) {
                    fields.forEach { notices += DiagramNotice("「$key」の所有が重複しているため記法のdataへ渡せません", DiagramLocation(it.document, it.path)) }
                } else fields.single().let { f -> f.value?.let { data[key] = it; origins += TransformOrigin(listOf("data", key), f.document, f.path) } }
            }
        }
        val node = YNode.Map(linkedMapOf(
            "id" to YNode.Scalar(id), "title" to YNode.Scalar(occurrence?.title ?: entity?.title ?: id),
            "entityType" to YNode.Scalar(entity?.entityType), "data" to YNode.Map(data),
        ))
        return Input(DiagramInputPlace(node, origins), notices)
    }

    fun edge(sources: List<DiagramLinkSource>, relation: String?, label: String?, kind: String, loader: (DocumentId) -> YNode?): Input {
        val origins = mutableListOf<TransformOrigin>()
        val references = sources.mapIndexed { i, link ->
            val data = linkedMapOf<String, YNode>()
            link.reference?.let { ref ->
                val parent = ref.path.dropLast(1)
                val root = loader(ref.document)
                ref.attributes.forEach { (key, value) ->
                    data[key] = root?.let { getAt(it, parent + key) } ?: YNode.Scalar(value)
                    origins += TransformOrigin(listOf("references", i, "data", key), ref.document, parent + key)
                    if (sources.size == 1) origins += TransformOrigin(listOf("data", key), ref.document, parent + key)
                }
            }
            YNode.Map(linkedMapOf("source" to YNode.Scalar(link.sourceId), "target" to YNode.Scalar(link.targetId), "data" to YNode.Map(data)))
        }
        fun endpoint(ids: List<String>): YNode = YamlIO.fromAny(mapOf("id" to ids.distinct().singleOrNull(), "ids" to ids.distinct()))
        val fields = linkedMapOf<String, YNode>("relation" to YNode.Scalar(relation), "label" to YNode.Scalar(label),
            "kind" to YNode.Scalar(kind), "source" to endpoint(sources.map { it.sourceId }), "target" to endpoint(sources.map { it.targetId }),
            "references" to YNode.Seq(references.toMutableList()))
        if (sources.size == 1) fields["data"] = references.single().entries.getValue("data")
        return Input(DiagramInputPlace(YNode.Map(fields), origins))
    }

    fun type(type: String, label: String): Input = Input(DiagramInputPlace(
        YamlIO.fromAny(mapOf("id" to type, "entityType" to type, "title" to label, "data" to emptyMap<String, Any>())), emptyList()))
}
