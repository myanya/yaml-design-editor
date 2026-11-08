package dev.toros.yde.desktop.ui.view

import dev.toros.yde.semantic.DashboardFlow
import dev.toros.yde.semantic.DashboardFlowDirection
import dev.toros.yde.semantic.DashboardFlowStart
import dev.toros.yde.semantic.DashboardMove
import kotlin.test.*

/** 流れ図の配置（DashboardFlowLayout）: 列は入口からの最長の段数、戻る工程は弧、箱と札は重ならない。 */
class DashboardFlowLayoutTest {
    // 注文サンプルと同じ形: 未処理 → 受注確定 → 出荷済み、未処理・受注確定 → 取消済み、受注確定 → 未処理（戻る）、未処理に留まる工程
    private val orders = DashboardFlow(
        steps = listOf("pending", "confirmed", "shipped", "cancelled"),
        moves = listOf(
            DashboardMove("pending", "confirm-order", "confirmed"),
            DashboardMove("pending", "add-order-line"),
            DashboardMove("pending", "cancel-order", "cancelled"),
            DashboardMove("confirmed", "ship-order", "shipped"),
            DashboardMove("confirmed", "unconfirm-order", "pending"),
            DashboardMove("confirmed", "cancel-order", "cancelled"),
        ),
        start = DashboardFlowStart("register-order", "pending"),
    )

    @Test
    fun `列は入口からの最長の段数で戻る工程は弧になる`() {
        val (rank, back) = DashboardFlowLayout.ranks(orders)
        assertEquals(mapOf("pending" to 0, "confirmed" to 1, "shipped" to 2, "cancelled" to 2), rank)
        assertEquals(setOf(DashboardMove("confirmed", "unconfirm-order", "pending")), back)
        val layout = DashboardFlowLayout.arrange(orders)
        assertTrue(layout.edges.single { it.back }.move?.action == "unconfirm-order")
        // 終点の 2 つは同じ列に縦に並ぶ（定義の順）
        val shipped = layout.boxes.getValue("shipped")
        val cancelled = layout.boxes.getValue("cancelled")
        assertEquals(shipped.x, cancelled.x)
        assertTrue(shipped.y < cancelled.y)
        // 留まる工程は未処理の箱の中、入口の札は左端
        val stay = layout.stays.getValue(DashboardMove("pending", "add-order-line"))
        val pending = layout.boxes.getValue("pending")
        assertTrue(stay.y >= pending.y && stay.bottom <= pending.bottom)
        assertTrue(assertNotNull(layout.start).right < pending.x)
    }

    @Test
    fun `右向きでも下向きでも箱と札は重ならず全体の大きさに収まる`() {
        for (direction in DashboardFlowDirection.entries) {
            val layout = DashboardFlowLayout.arrange(orders.copy(direction = direction))
            val boxes = layout.boxes.values.toList()
            val labels = layout.edges.mapNotNull { it.label } + listOfNotNull(layout.start)
            boxes.forEachIndexed { i, a -> boxes.drop(i + 1).forEach { b -> assertFalse(a.overlaps(b), "$direction: $a / $b") } }
            labels.forEachIndexed { i, a ->
                boxes.forEach { b -> assertFalse(a.overlaps(b), "$direction: 札 $a と箱 $b") }
                labels.drop(i + 1).forEach { b -> assertFalse(a.overlaps(b), "$direction: 札 $a / $b") }
            }
            (boxes + labels + layout.stays.values).forEach { r ->
                assertTrue(r.x >= 0f && r.y >= 0f && r.right <= layout.width && r.bottom <= layout.height, "$direction: $r")
            }
        }
    }
}
