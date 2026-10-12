package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class FlowSelectionTest {
    private val detail = FlowDetail(null, emptyList(), false)
    private fun node(i: Int, lane: Int? = null) = FlowNode(listOf("nodes", i), "N$i", "活動$i", "box",
        lane?.let { FlowConnection("L$it", FlowConnectionState.RESOLVED, listOf("lanes", it)) }, detail)
    private fun edge(i: Int, from: Int, to: Int?, key: String? = "E$i") = FlowEdge(listOf("edges", i), key, "条件$i",
        FlowConnection("N$from", FlowConnectionState.RESOLVED, listOf("nodes", from)),
        FlowConnection(to?.let { "N$it" }, if (to == null) FlowConnectionState.UNRESOLVED else FlowConnectionState.RESOLVED,
            to?.let { listOf("nodes", it) }), detail)
    @Test
    fun `選択は文書ごとに分離し一意キーだけ並べ替え後も復元する`() {
        val document = DocumentId("a.yaml")
        val selection = FlowSelection("flow", document, FlowElementKind.NODE, "N0", listOf("nodes", 0), 1, 2)
        val model = FlowModel(listOf(node(1), node(0).copy(output = listOf("nodes", 1))), emptyList(), emptyList())
        assertEquals(listOf<Any>("nodes", 1), selection.resolve("flow", document, model, 3, 4))
        assertNull(selection.resolve("flow", DocumentId("b.yaml"), model, 1, 2))
        assertNull(selection.resolve("other", document, model, 1, 2))
        assertNull(selection.resolve("flow", document, model.copy(nodes = listOf(node(0), node(0))), 1, 2))
        assertNull(selection.resolve("flow", document, model.copy(nodes = emptyList()), 1, 2))
    }

    @Test
    fun `無キー辺は原本か定義の版が変われば解除する`() {
        val document = DocumentId("a.yaml")
        val selection = FlowSelection("flow", document, FlowElementKind.EDGE, null, listOf("edges", 0), 1, 2)
        val model = FlowModel(listOf(node(0)), listOf(edge(0, 0, 0, null)), emptyList())
        assertEquals(listOf<Any>("edges", 0), selection.resolve("flow", document, model, 1, 2))
        assertNull(selection.resolve("flow", document, model, 2, 2))
        assertNull(selection.resolve("flow", document, model, 1, 3))
        assertNull(selection.resolve("flow", document, model.copy(edges = emptyList()), 1, 2))
    }
}
