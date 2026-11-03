package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.DiagramLocation
import dev.toros.yde.workspace.DiagramDefinitions
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class DiagramFlowTest {
    private val document = DocumentId("people/a.yaml")
    @Test fun `ポートの解決診断を参照属性の原本へ戻しCLIにも表示モデルを開示する`() {
        val source = DocumentId(".yde/views/ports.yaml")
        fun notation(text: String) = DiagramDefinitions.parse(YamlIO.parseToRaw(text) as Map<*, *>, source)
        val notations = listOf(
            notation("id: rows\ntarget: node\nbuild: {kind: box, children: {\$each: data.columns, \$build: {kind: text, text: {\$copy: name}, port: {\$copy: name}}}}"),
            notation("id: fk\ntarget: edge\nbuild: {sourcePort: {\$copy: data.column}, targetPort: {\$copy: data.targetColumn}}"))
        val def = FlowViewKind.parse(YamlIO.parseToRaw("""
            id: ports
            kind: flow
            match: people/*.yaml
            appearance:
              nodes: [{notation: rows, inputs: {${'$'}copy: .}}]
              edges: [{notation: fk, inputs: {${'$'}copy: .}}]
            projection: {id: copy, build: {${'$'}copy: .}}
        """.trimIndent()) as Map<*, *>, source)
        fun input(target: String) = YamlIO.parse("""
            nodes:
              - {key: A, label: A, detail: {columns: [{name: id}, {name: parent_id}]}}
              - {key: B, label: B, detail: {columns: [{name: id}]}}
            edges: [{from: A, to: B, detail: {column: parent_id, targetColumn: $target}}]
        """.trimIndent())
        val valid = FlowViews.resolve(def, document, null, input("id"), notations)
        assertTrue(valid.diagnostics.isEmpty(), valid.diagnostics.toString())
        assertEquals("parent_id", valid.model!!.edges.single().rendering!!.edge!!.sourcePort)
        val report = FlowReports.toMap(def, document, valid)
        val edge = ((report["model"] as Map<*, *>)["edges"] as List<*>).single() as Map<*, *>
        assertEquals("id", ((edge["rendering"] as Map<*, *>)["model"] as Map<*, *>)["targetPort"])
        val invalid = FlowViews.resolve(def, document, null, input("unknown"), notations)
        val issue = invalid.diagnostics.single { it.code == "port-unresolved" }
        assertEquals(listOf("edges", 0, "detail", "targetColumn"), issue.locations.single().path)
        assertEquals(document, issue.locations.single().document)
        assertEquals(2, invalid.model!!.nodes.size)
    }
    @Test fun `人物の射影から記法を適用して画像の基準文書と詳細の編集経路を保つ`() {
        val def = FlowViewKind.parse(YamlIO.parseToRaw("""
            id: people
            kind: flow
            match: people/*.yaml
            appearance:
              nodes:
                - notation: portrait-card
                  inputs: {title: {${'$'}copy: title}, portrait: {${'$'}copy: data.portrait}}
            projection:
              id: people-projection
              build:
                nodes:
                  ${'$'}each: people
                  ${'$'}build: {key: {${'$'}copy: id}, label: {${'$'}copy: name}, detail: {${'$'}copy: .}}
                edges: []
        """.trimIndent()) as Map<*, *>, DocumentId(".yde/views/people.yaml"))
        val input = YamlIO.parse("people: [{id: P1, name: 花子, portrait: ./face.png}]")
        val before = YamlIO.dumpPlain(input)
        val result = FlowViews.resolve(def, document, null, input, DiagramDefinitions.builtins)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val node = result.model!!.nodes.single()
        assertTrue(node.detail.editable)
        val rendering = assertNotNull(node.rendering)
        assertEquals("花子", rendering.node!!.children[1].text)
        assertEquals(DiagramLocation(document, listOf("people", 0, "portrait")), rendering.location(listOf("children", 0, "source")))
        assertEquals(before, YamlIO.dumpPlain(input))
        val report = FlowReports.toMap(def, document, result)
        val reported = ((report["model"] as Map<*, *>)["nodes"] as List<*>).single() as Map<*, *>
        val visible = reported["rendering"] as Map<*, *>
        assertNotNull(visible["model"])
        assertTrue((visible["origins"] as List<*>).isNotEmpty(), "CLIとread_viewにも記法の来歴を開示する")
        val bad = def.copy(appearance = DiagramDefinitions.appearance(mapOf("nodes" to listOf(mapOf("notation" to "missing")))))
        val invalid = FlowViews.resolve(bad, document, null, input)
        assertEquals("notation", invalid.diagnostics.single().code)
        assertEquals("P1", invalid.model!!.nodes.single().key, "記法の誤りでも要素と原本を隠さない")
    }
}
