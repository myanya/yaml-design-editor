package dev.toros.yde.workspace

import dev.toros.yde.semantic.DiagramAppearance
import dev.toros.yde.semantic.DiagramBinding
import dev.toros.yde.semantic.VirtualDiagramNotationDef
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.structure.StructureParser

/** 記法と適用指定の読み取り。表示の契約をビューのカタログで一度だけ解決する。 */
object DiagramDefinitions {
    fun parse(raw: Map<*, *>, source: DocumentId): VirtualDiagramNotationDef {
        val keys = setOf("id", "name", "description", "kind", "target", "input", "build", "rules")
        require(raw.keys.all { it in keys }) { "kind: diagram-notationに未知の項目があります" }
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("記法のidが必要です")
        val target = raw["target"]?.toString()
        require(target in listOf("node", "edge")) { "記法のtargetはnode / edgeで指定します" }
        require(raw["build"] != null) { "記法のbuildが必要です" }
        require(raw["input"] == null || raw["input"] is Map<*, *>) { "記法のinputは構造のスキーマで指定します" }
        return VirtualDiagramNotationDef(
            id, raw["name"]?.toString() ?: id, raw["description"]?.toString(), source, target!!,
            StructureParser.parse(mapOf("root" to (raw["input"] ?: mapOf("type" to "object", "unknownFields" to "allow"))), id),
            TransformDef.parse(mapOf("id" to id, "build" to raw["build"], "rules" to raw["rules"])),
        )
    }

    fun appearance(raw: Any?): DiagramAppearance? {
        if (raw == null) return null
        val map = raw as? Map<*, *> ?: error("appearanceはobjectで指定します")
        require(map.keys.all { it in listOf("nodes", "edges") }) { "appearanceに未知の項目があります" }
        fun bindings(name: String): List<DiagramBinding> {
            val value = map[name] ?: return emptyList()
            val list = value as? List<*> ?: error("appearance.${name}は配列で指定します")
            return list.mapIndexed { i, item ->
                val m = item as? Map<*, *> ?: error("appearance.$name[$i]はobjectで指定します")
                val allowed = if (name == "nodes") setOf("notation", "inputs", "level", "entityType") else setOf("notation", "inputs", "relation")
                require(m.keys.all { it in allowed }) { "appearance.$name[$i]に未知の項目があります" }
                val notation = m["notation"]?.toString()?.takeIf { it.isNotBlank() } ?: error("appearance.$name[$i].notationが必要です")
                val level = m["level"]?.toString()
                require(level == null || level in listOf("entity", "type")) { "levelはentity / typeで指定します" }
                DiagramBinding(notation, TransformDef.ofTemplate("inputs", m["inputs"] ?: emptyMap<String, Any>(), "appearance.$name[$i].inputs"),
                    level, m["entityType"]?.toString(), m["relation"]?.toString())
            }.also { entries ->
                require(entries.map { Triple(it.level, it.entityType, it.relation) }.distinct().size == entries.size) {
                    "appearance.${name}に同じ対象の指定が重複しています"
                }
            }
        }
        return DiagramAppearance(bindings("nodes"), bindings("edges"))
    }

    /** 同梱も利用者と同じYAML・同じ読み取りを通す。IDの衝突はカタログが診断する。 */
    val builtins: List<VirtualDiagramNotationDef> by lazy {
        listOf("plain-node", "plain-link", "directed-link", "portrait-card", "uml-class",
            "uml-association", "uml-generalization", "uml-realization", "uml-dependency", "uml-aggregation", "uml-composition").map { name ->
            val text = requireNotNull(javaClass.getResourceAsStream("/builtin-diagram-notations/$name.yaml")) {
                "同梱記法が見つかりません: $name"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(YamlIO.parseToRaw(text) as Map<*, *>, DocumentId("builtin-diagram-notations/$name.yaml"))
        }
    }

    fun lint(appearance: DiagramAppearance?, definitions: List<VirtualDiagramNotationDef>): List<String> = buildList {
        fun check(bindings: List<DiagramBinding>, target: String) = bindings.forEach { binding ->
            val def = definitions.singleOrNull { it.id == binding.notation }
            if (def == null) add("記法「${binding.notation}」が見つからないか、IDが重複しています")
            else if (def.target != target) add("記法「${def.id}」は $target 用ではありません")
        }
        check(appearance?.nodes.orEmpty(), "node")
        check(appearance?.edges.orEmpty(), "edge")
    }
}
