package dev.toros.yde.app.core

import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.*
import kotlin.test.*

class DiagramInputsTest {
    @Test fun `参照属性は原本の型と場所を保ち集約後は任意の一件を選ばない`() {
        val doc = DocumentId("classes.yaml")
        val tree = YamlIO.parse("links: [{to: B, count: 2}, {to: B, count: 3}]")
        fun link(i: Int) = DiagramLinkSource("A", "B", ScannedRef("B", doc, listOf("links", i, "to"), "links",
            attributes = mapOf("count" to (i + 2).toString())))
        val copy = TransformDef.ofTemplate("copy", mapOf("\$copy" to "data.count"), "copy")
        val single = DiagramInputs.edge(listOf(link(0)), "uses", null, "ref") { tree }
        val result = SemanticTransform.runAt(copy, single.place)
        assertEquals("2", scalarText(result.term))
        assertEquals(listOf("links", 0, "count"), result.origins.single().path)
        assertEquals(doc, result.origins.single().document)
        val merged = DiagramInputs.edge(listOf(link(0), link(1)), "uses", null, "ref") { tree }
        assertNull(SemanticTransform.runAt(copy, merged.place).term)
        val refs = SemanticTransform.runAt(TransformDef.ofTemplate("refs", mapOf("\$copy" to "references"), "refs"), merged.place)
        assertEquals(2, (refs.term as YNode.Seq).items.size)
        assertEquals(setOf(listOf("links", 0, "count"), listOf("links", 1, "count")), refs.origins.map { it.path }.toSet())
        assertEquals("2", scalarText(getAt(tree, listOf("links", 0, "count"))))
    }
}
