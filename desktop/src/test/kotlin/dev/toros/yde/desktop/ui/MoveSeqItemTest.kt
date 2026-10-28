package dev.toros.yde.desktop.ui

import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveSeqItemTest {
    private fun seq(vararg values: String) = YNode.Seq(values.map { YNode.Scalar(it) }.toMutableList())
    private fun YNode.Seq.values() = items.map { (it as YNode.Scalar).value }

    @Test
    fun `moves an element down and up`() {
        val node = seq("a", "b", "c", "d")
        moveSeqItem(node, 0, 2)
        assertEquals(listOf("b", "c", "a", "d"), node.values())
        moveSeqItem(node, 3, 0)
        assertEquals(listOf("d", "b", "c", "a"), node.values())
    }

    @Test
    fun `same position or out of range leaves the list unchanged`() {
        val node = seq("a", "b")
        moveSeqItem(node, 1, 1)
        moveSeqItem(node, 5, 0)
        moveSeqItem(node, 0, -1)
        assertEquals(listOf("a", "b"), node.values())
    }
}
