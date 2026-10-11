package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import kotlin.test.*

class FlowLayoutTest {
    private val detail = FlowDetail(null, emptyList(), false)
    private fun node(i: Int, lane: Int? = null) = FlowNode(listOf("nodes", i), "N$i", "活動$i", "box",
        lane?.let { FlowConnection("L$it", FlowConnectionState.RESOLVED, listOf("lanes", it)) }, detail)
    private fun edge(i: Int, from: Int, to: Int?, key: String? = "E$i") = FlowEdge(listOf("edges", i), key, "条件$i",
        FlowConnection("N$from", FlowConnectionState.RESOLVED, listOf("nodes", from)),
        FlowConnection(to?.let { "N$it" }, if (to == null) FlowConnectionState.UNRESOLVED else FlowConnectionState.RESOLVED,
            to?.let { listOf("nodes", it) }), detail)
    private fun overlaps(a: FlowRect, b: FlowRect) = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    @Test
    fun `分岐のノードとラベルが重ならず配置は決定的`() {
        val model = FlowModel((0..3).map { node(it) }, listOf(edge(0, 0, 1), edge(1, 0, 2), edge(2, 2, 3)), emptyList())
        val labels = (model.nodes.map { it.output } + model.edges.map { it.output }).associateWith { FlowSize(210f, 60f) }
        for (direction in listOf("down", "right")) {
            val result = FlowLayout.arrange(model, labels, direction)
            assertEquals(result, FlowLayout.arrange(model, labels, direction))
            val occupied = result.nodes.values + result.edges.map { it.label }
            occupied.forEachIndexed { i, a -> occupied.drop(i + 1).forEach { b -> assertFalse(overlaps(a, b), "$a / $b") } }
            occupied.forEach { assertTrue(it.right <= result.size.width && it.bottom <= result.size.height) }
        }
    }

    @Test
    fun `閉路と自己ループと並行辺を個別の経路にする`() {
        val model = FlowModel((0..2).map { node(it) },
            listOf(edge(0, 0, 1), edge(1, 1, 0), edge(2, 1, 1), edge(3, 0, 1), edge(4, 1, 2)), emptyList())
        val result = FlowLayout.arrange(model, emptyMap())
        assertEquals(5, result.edges.size)
        assertEquals(5, result.edges.map { it.points }.distinct().size)
        assertTrue(result.edges.all { it.points.size >= 4 })
        assertTrue(result.nodes.getValue(listOf("nodes", 2)).y > result.nodes.getValue(listOf("nodes", 0)).y)
    }

    @Test
    fun `レーン順を守り未割当ノードと未解決辺も保持する`() {
        val lanes = (0..1).map { FlowLane(listOf("lanes", it), "L$it", "主体$it") }
        val model = FlowModel(listOf(node(0, 1), node(1, 0), node(2)), listOf(edge(0, 0, null)), lanes)
        val result = FlowLayout.arrange(model, emptyMap())
        assertEquals(3, result.nodes.size)
        assertEquals(2, result.lanes.size)
        assertTrue(result.nodes.getValue(listOf("nodes", 1)).x < result.nodes.getValue(listOf("nodes", 0)).x)
        assertTrue(result.nodes.getValue(listOf("nodes", 2)).x > result.lanes.getValue(listOf("lanes", 1)).right)
        assertTrue(result.edges.single().points.isEmpty())
        assertTrue(result.edges.single().label.width > 0)
    }

    @Test
    fun `空の図と長い鎖を再帰なしに配置する`() {
        assertTrue(FlowLayout.arrange(FlowModel(emptyList(), emptyList(), emptyList()), emptyMap()).nodes.isEmpty())
        val model = FlowModel((0..2000).map { node(it) }, (0..1999).map { edge(it, it, it + 1) }, emptyList())
        assertEquals(2001, FlowLayout.arrange(model, emptyMap()).nodes.size)
    }

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
