package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDeclarationsTest {
    @Test
    fun `入れ子とエンティティ参照と cases にも原文の場所を保持する`() {
        val store = InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "ledger: [data]\n",
            ".yde/schemas/tasks.yaml" to """
                id: tasks
                match: data/*.yaml
                definitions:
                  word: {type: string, maxLength: 100}
                  child:
                    type: object
                    entity: {type: child, defines: true}
                    fields: [{key: id, type: id}]
                root:
                  type: object
                  entity: {type: task, defines: true}
                  fields:
                    - {key: id, type: id}
                    - key: rows
                      type: array
                      default: []
                      item:
                        type: object
                        fields: [{key: title, type: word, maxLength: 100}]
                    - {key: values, type: map, value: {type: word, default: ''}}
                    - {key: children, type: array, item: {type: child}}
                    - key: kind
                      type: enum
                      values: [detailed]
                      cases:
                        detailed: [{key: count, type: int, default: 0}]
            """.trimIndent(),
        ))
        val model = YamlIO.toAny(BusinessModel.of(WorkspaceSession(store))) as Map<*, *>
        val entity = (model["entities"] as List<*>).map { it as Map<*, *> }.single { it["id"] == "task" }
        val fields = ((entity["aspects"] as List<*>).single() as Map<*, *>)["fields"] as List<*>
        val byKey = fields.map { it as Map<*, *> }.associateBy { it["key"] }
        fun local(m: Map<*, *>) = (m["declarations"] as List<*>).map { it as Map<*, *> }.single { it["inherited"] == false }
        val rows = byKey.getValue("rows")
        assertEquals(emptyList<Any?>(), (local(rows)["declared"] as Map<*, *>)["default"])
        val title = (((rows["item"] as Map<*, *>)["fields"] as List<*>).single() as Map<*, *>)
        assertEquals(listOf("root", "fields", 1, "item", "fields", 0), local(title)["path"])
        assertEquals(100, (local(title)["declared"] as Map<*, *>)["maxLength"])
        val value = byKey.getValue("values")["value"] as Map<*, *>
        assertEquals(listOf("root", "fields", 2, "value"), local(value)["path"])
        assertEquals("", (local(value)["declared"] as Map<*, *>)["default"])
        val child = byKey.getValue("children")["item"] as Map<*, *>
        assertEquals("child", child["entity"])
        assertFalse(child.containsKey("fields"), "エンティティの項目は参照先の側面に保持する")
        assertEquals(listOf("root", "fields", 3, "item"), local(child)["path"])
        val count = ((byKey.getValue("kind")["cases"] as Map<*, *>)["detailed"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("root", "fields", 4, "cases", "detailed", 0), local(count)["path"])
        assertEquals(0, (local(count)["declared"] as Map<*, *>)["default"])
    }

    @Test
    fun `業務モデルは型と項目の宣言を文書と場所付きで渡す`() {
        val store = InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "ledger: [data]\n",
            ".yde/schemas/common.yaml" to """
                id: common
                kind: library
                definitions:
                  word: {type: string, maxLength: 100}
                  row:
                    type: object
                    entity: {type: task, defines: true}
                    fields:
                      - {key: id, type: id}
                      - {key: explicit, type: word, maxLength: 100}
                      - {key: inherited, type: word}
                      - {key: done, type: boolean, default: false}
            """.trimIndent(),
            ".yde/schemas/tasks.yaml" to "id: tasks\nmatch: data/*.yaml\nroot: {type: common#row}\n",
        ))
        val session = WorkspaceSession(store)
        val model = YamlIO.toAny(BusinessModel.of(session)) as Map<*, *>
        val entity = (model["entities"] as List<*>).single() as Map<*, *>
        val aspect = (entity["aspects"] as List<*>).single() as Map<*, *>
        val fields = (aspect["fields"] as List<*>).map { it as Map<*, *> }.associateBy { it["key"] }
        fun declarations(key: String) = (fields.getValue(key)["declarations"] as List<*>).map { it as Map<*, *> }
        val explicit = declarations("explicit")
        assertEquals(listOf(true, false), explicit.map { it["inherited"] })
        assertEquals(listOf(100, 100), explicit.map { (it["declared"] as Map<*, *>)["maxLength"] })
        assertTrue(explicit.all { it["document"] == ".yde/schemas/common.yaml" })
        assertEquals(listOf("definitions", "row", "fields", 1), explicit.last()["path"])
        val inherited = declarations("inherited").single { it["inherited"] == false }
        assertFalse((inherited["declared"] as Map<*, *>).containsKey("maxLength"))
        assertEquals(false, (declarations("done").single()["declared"] as Map<*, *>)["default"])
        assertEquals(100, fields.getValue("explicit")["maxLength"])
        assertEquals(100, fields.getValue("inherited")["maxLength"])
    }
}
