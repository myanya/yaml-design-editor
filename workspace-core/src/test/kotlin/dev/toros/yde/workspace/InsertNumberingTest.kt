package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.patch.PatchOp
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 配列の追加の採番で、接頭辞のひな型が配列を持つ要素（親）を `$of: self`、ID を持つ最も近い外側の要素を `$of: owner` で読めること（BACKLOG UX-199。
 * samples/orders の明細番号「注文番号-01」が最初の利用例）。アクションの insert と、追加ボタン・新規行（newElement）の両方。
 */
class InsertNumberingTest {

    private val now = LocalDateTime.of(2026, 10, 26, 9, 0)

    private fun files(): Map<String, String> = mapOf(
        "_schemas/order.yaml" to """
            id: order
            match: "orders/*.yaml"
            entity: { type: order, idPattern: "ORD-*", defines: true }
            root:
              type: object
              fields:
                - { key: orderId, type: id, required: true }
                - key: lines
                  type: array
                  methods:
                    insert:
                      id: { path: orderLineId, prefix: { ${'$'}format: "{orderId}-", ${'$'}of: self }, digits: 2 }
                  item:
                    type: object
                    entity: { type: order-line, defines: true }
                    fields:
                      - { key: orderLineId, type: id, required: true }
                      - { key: productId, type: string, required: true }
                      - { key: quantity, type: int, required: true }
                # 入れ子の表: 便（ID なし）の中の明細を、ID を持つ最も近い外側の要素（注文）の番号で採番する
                - key: shipments
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: note, type: string }
                      - key: items
                        type: array
                        methods:
                          insert:
                            id: { path: itemId, prefix: { ${'$'}format: "{orderId}-S", ${'$'}of: owner }, digits: 2 }
                        item:
                          type: object
                          entity: { type: shipment-item, defines: true }
                          fields:
                            - { key: itemId, type: id, required: true }
                            - { key: productId, type: string, required: true }
            actions:
              - id: add-line
                label: 明細を足す
                target: { entity: order }
                params:
                  - { key: productId, type: string, required: true }
                  - { key: quantity, type: int, required: true }
                do:
                  - insert: { of: self, path: lines, params: { productId: ${'$'}productId, quantity: ${'$'}quantity } }
              - id: add-shipped
                label: 最初の便に明細を足す
                target: { entity: order }
                params:
                  - { key: productId, type: string, required: true }
                do:
                  - insert: { of: self, path: "shipments[0].items", params: { productId: ${'$'}productId } }
        """.trimIndent() + "\n",
        "orders/ORD-001.yaml" to "orderId: ORD-001\nlines:\n  - { orderLineId: ORD-001-01, productId: PRD-001, quantity: 1 }\n" +
            "shipments:\n  - note: 1 便\n    items:\n      - { itemId: ORD-001-S01, productId: PRD-001 }\n",
        "orders/ORD-002.yaml" to "orderId: ORD-002\nlines: []\n",
    )

    private fun session() = WorkspaceSession(InMemoryDocumentStore(files())).also { assertEquals(emptyList(), it.schemaErrors) }

    private fun plan(session: WorkspaceSession, target: String, vararg params: Pair<String, Any?>): ActionPlan =
        ActionPlanner.plan(session, ActionRequest("add-line", target, params.toMap()), now)

    @Test
    fun `アクションの insert は 親の注文番号を接頭辞にして明細を採番する`() {
        val s = session()
        val second = plan(s, "ORD-001", "productId" to "PRD-002", "quantity" to 3)
        val op = second.changeSet.changes.single { it.file == "orders/ORD-001.yaml" }.ops.single() as PatchOp.Insert
        assertEquals("ORD-001-02", scalarText(getAt(op.value, parseYPath("orderLineId"))))
        val first = plan(s, "ORD-002", "productId" to "PRD-003", "quantity" to 1)
        val op2 = first.changeSet.changes.single { it.file == "orders/ORD-002.yaml" }.ops.single() as PatchOp.Insert
        assertEquals("ORD-002-01", scalarText(getAt(op2.value, parseYPath("orderLineId"))))
    }

    @Test
    fun `入れ子の表の行は ID を持つ最も近い外側の要素を owner で読んで採番する`() {
        val s = session()
        val plan = ActionPlanner.plan(s, ActionRequest("add-shipped", "ORD-001", mapOf("productId" to "PRD-009")), now)
        val op = plan.changeSet.changes.single { it.file == "orders/ORD-001.yaml" }.ops.single() as PatchOp.Insert
        assertEquals("ORD-001-S02", scalarText(getAt(op.value, parseYPath("itemId"))))
        // 追加ボタン・新規行: ownerOf が、便（ID なし）を飛ばして注文を見つける。owner を渡さなければ接頭辞が決まらない
        val schema = s.schemas.first { it.id == "order" }
        val tree = schema.tree
        val root = s.snapshot.documents.getValue(DocumentId("orders/ORD-001.yaml")).document.documents.first()
        val arrayPath = parseYPath("shipments[0].items")
        val owner = ActionPlanner.ownerOf(tree, root, arrayPath)
        assertEquals("ORD-001", scalarText(getAt(owner!!, parseYPath("orderId"))))
        val arrayDef = tree.fieldAt(root, arrayPath)!!
        val array = getAt(root, arrayPath) as YNode.Seq
        val ids = s.derived.semantic.graph.entities.keys
        val made = ActionPlanner.newElement(tree, arrayDef, array, mapOf("productId" to "PRD-010"), ids, now, holder = getAt(root, parseYPath("shipments[0]")), owner = owner)
        assertEquals("ORD-001-S02", scalarText(getAt(made.node!!, parseYPath("itemId"))))
        assertFailsWith<ActionFailure> { ActionPlanner.newElement(tree, arrayDef, array, mapOf("productId" to "PRD-010"), ids, now, holder = getAt(root, parseYPath("shipments[0]"))) }
    }

    @Test
    fun `追加ボタンと新規行の要素も 親を渡せば同じ採番になり 親が無ければ接頭辞が決まらない`() {
        val s = session()
        val schema = s.schemas.first { it.id == "order" }
        val tree = schema.tree
        val arrayDef = (tree.resolve(tree.root) ?: tree.root).fields.first { it.key == "lines" }.let { tree.resolve(it) ?: it }
        val root = s.snapshot.documents.getValue(DocumentId("orders/ORD-001.yaml")).document.documents.first()
        val array = getAt(root, parseYPath("lines")) as YNode.Seq
        val ids = s.derived.semantic.graph.entities.keys
        val made = ActionPlanner.newElement(tree, arrayDef, array, mapOf("productId" to "PRD-002", "quantity" to 2), ids, now, holder = root)
        assertEquals("ORD-001-02", scalarText(getAt(made.node!!, parseYPath("orderLineId"))))
        assertFailsWith<ActionFailure> { ActionPlanner.newElement(tree, arrayDef, array, mapOf("productId" to "PRD-002", "quantity" to 2), ids, now) }
    }
}
