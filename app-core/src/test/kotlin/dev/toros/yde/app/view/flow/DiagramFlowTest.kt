package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.DiagramLocation
import dev.toros.yde.workspace.DiagramDefinitions
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class DiagramFlowTest {
    private val document = DocumentId("people/a.yaml")
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
        val bad = def.copy(appearance = DiagramDefinitions.appearance(mapOf("nodes" to listOf(mapOf("notation" to "missing")))))
        val invalid = FlowViews.resolve(bad, document, null, input)
        assertEquals("notation", invalid.diagnostics.single().code)
        assertEquals("P1", invalid.model!!.nodes.single().key, "記法の誤りでも要素と原本を隠さない")
    }
}
