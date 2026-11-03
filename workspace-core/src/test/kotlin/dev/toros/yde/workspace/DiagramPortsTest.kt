package dev.toros.yde.workspace

import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class DiagramPortsTest {
    private val definition = DocumentId(".yde/views/ports.yaml")
    private val document = DocumentId("tables.yaml")
    private fun render(target: String, build: String, input: String): DiagramRendering {
        val def = DiagramDefinitions.parse(YamlIO.parseToRaw("id: ports\ntarget: $target\nbuild: $build") as Map<*, *>, definition)
        val binding = DiagramBinding("ports", TransformDef.ofTemplate("input", mapOf("\$copy" to "."), "input"))
        return DiagramNotations.resolve(listOf(def), listOf(binding), SemanticTransform.TemplatePlace(YamlIO.parse(input), document, emptyList()), definition, target)!!
    }

    @Test fun `行のポート名を原本から導出し重複の両方へ診断を戻す`() {
        val build = "{kind: box, children: {\$each: columns, \$build: {kind: text, text: {\$copy: name}, port: {\$copy: name}}}}"
        val valid = render("node", build, "columns: [{name: id}, {name: parent_id}]")
        assertTrue(valid.notices.isEmpty(), valid.notices.toString())
        assertNull(DiagramPorts.issue("id", valid.node))
        assertNotNull(DiagramPorts.issue("missing", valid.node))
        assertEquals("auto", valid.node!!.children.first().portSide)
        assertEquals(DiagramLocation(document, listOf("columns", 1, "name")), valid.location(listOf("children", 1, "port")))
        val duplicate = render("node", build, "columns: [{name: id}, {name: id}]")
        assertNotNull(duplicate.node, "内容の表示は残す")
        assertEquals(listOf(listOf("columns", 0, "name"), listOf("columns", 1, "name")), duplicate.notices.map { it.location.path })
        assertTrue(duplicate.notices.all { it.message.contains("重複") })
        assertNotNull(DiagramPorts.issue("id", duplicate.node))
    }

    @Test fun `辺の両端を入力から導き反転で記号とポートを一緒に交換する`() {
        val rendered = render("edge", "{sourcePort: {\$copy: column}, targetPort: {\$copy: targetColumn}, sourceLabel: '*', targetLabel: '1'}",
            "column: parent_id\ntargetColumn: id")
        assertTrue(rendered.notices.isEmpty(), rendered.notices.toString())
        val style = assertNotNull(rendered.edge)
        val reversed = style.reversedEndpoints()
        assertEquals("parent_id", style.sourcePort)
        assertEquals("id", reversed.sourcePort)
        assertEquals("parent_id", reversed.targetPort)
        assertEquals("1", reversed.sourceLabel)
        assertEquals(style, reversed.reversedEndpoints())
        assertEquals(DiagramLocation(document, listOf("column")), rendered.location(listOf("sourcePort")))
    }

    @Test fun `不正な名前と接続面は表示契約で拒否する`() {
        for (build in listOf("{kind: text, port: ''}", "{kind: text, port: '   '}", "{kind: text, port: id, portSide: diagonal}")) {
            val invalid = render("node", build, "{}")
            assertNull(invalid.node)
            assertTrue(invalid.notices.isNotEmpty(), build)
        }
        val invalid = render("edge", "{sourcePort: ''}", "{}")
        val edge = assertNotNull(invalid.edge)
        assertFalse(edge.portsValid)
        assertTrue(edge.hasPorts, "不正な端点を通常の箱への接続として扱わない")
        assertTrue(invalid.notices.isNotEmpty())
    }
}
