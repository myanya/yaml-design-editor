package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessModelRecursionTest {
    private fun model(schema: String, libraries: Map<String, String> = emptyMap()): Map<*, *> {
        val loaded = WorkspaceReader.load(InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "ledger: [data]\n", ".yde/schemas/model.yaml" to schema.trimIndent()) + libraries))
        assertEquals(emptyList(), loaded.schemaErrors)
        return YamlIO.toAny(BusinessModel.of(loaded)) as Map<*, *>
    }

    private fun rows(m: Map<*, *>, key: String) = (m[key] as List<*>).map { it as Map<*, *> }
    private fun field(m: Map<*, *>, key: String) = rows(m, "fields").single { it["key"] == key }
    private fun type(m: Map<*, *>, name: String) = rows(m, "types").single { it["name"] == "model#$name" }

    @Test
    fun `深さの上限を越える有限の配列とマップも 末端の宣言とエンティティを失わない`() {
        var child: Map<String, Any?> = mapOf("type" to "object", "entity" to mapOf("type" to "leaf", "defines" to true),
            "fields" to listOf(mapOf("key" to "id", "type" to "id"), mapOf("key" to "name", "type" to "string", "maxLength" to 17)))
        repeat(18) { i -> child = if (i % 2 == 0) mapOf("type" to "array", "item" to child) else mapOf("type" to "map", "value" to child) }
        val schema = mapOf("id" to "model", "match" to "data/*.yaml", "root" to mapOf("type" to "object",
            "entity" to mapOf("type" to "root", "defines" to true), "fields" to listOf(mapOf("key" to "id", "type" to "id"), child + ("key" to "deep"))))
        val result = model(YamlIO.dumpPlain(YamlIO.fromAny(schema)))
        assertEquals(setOf("root", "leaf"), rows(result, "entities").map { it["id"] }.toSet())
        var current = field(rows(rows(result, "entities").single { it["id"] == "root" }, "aspects").single(), "deep")
        repeat(18) { current = (current[if (current["base"] == "array") "item" else "value"] as Map<*, *>) }
        assertEquals("leaf", current["entity"])
        val leaf = rows(rows(result, "entities").single { it["id"] == "leaf" }, "aspects").single()
        assertEquals(17, field(leaf, "name")["maxLength"])
        val declaration = rows(field(leaf, "name"), "declarations").single()
        assertTrue((declaration["path"] as List<*>).size > 18)
    }

    @Test
    fun `分岐する自己参照は型の参照で閉じ 同じ型を使う別の項目は省略しない`() {
        val result = model("""
            id: model
            match: data/*.yaml
            definitions:
              node:
                type: object
                fields:
                  - {key: name, type: string, maxLength: 100}
                  - {key: left, type: node}
                  - {key: right, type: node}
                  - {key: up, type: node}
                  - {key: down, type: node}
            root:
              type: object
              entity: {type: root, defines: true}
              fields: [{key: id, type: id}, {key: first, type: node}, {key: second, type: node}]
        """)
        val node = type(result, "node")
        for (key in listOf("left", "right", "up", "down")) {
            assertEquals("model#node", field(node, key)["recursiveType"])
            assertFalse(field(node, key).containsKey("fields"))
        }
        val root = rows(rows(result, "entities").single(), "aspects").single()
        for (key in listOf("first", "second")) {
            assertEquals(100, field(field(root, key), "name")["maxLength"])
            assertFalse(field(root, key).containsKey("recursiveType"))
        }
        assertTrue(YamlIO.dumpPlain(YamlIO.fromAny(result)).length < 30_000, "型の繰り返しを木として増やさない")
    }

    @Test
    fun `再帰先での子の追加と型を省いた制限を残し 継承した子を明記として展開し直さない`() {
        val result = model("""
            id: model
            match: data/*.yaml
            definitions:
              node:
                type: object
                fields:
                  - {key: name, type: string, maxLength: 100}
                  - key: child
                    type: node
                    fields: [{key: child, label: 次の子}]
            root:
              type: object
              entity: {type: root, defines: true}
              fields:
                - {key: id, type: id}
                - key: tree
                  type: node
                  fields:
                    - key: child
                      fields:
                        - {key: name, maxLength: 40}
                        - {key: local, type: boolean, default: false}
        """)
        val root = rows(rows(result, "entities").single(), "aspects").single()
        val child = field(field(root, "tree"), "child")
        assertEquals("model#node", child["recursiveType"])
        assertEquals(40, field(child, "name")["maxLength"])
        assertEquals("string", field(child, "name")["base"])
        assertEquals(false, field(child, "local")["default"])
        val own = rows(field(child, "name"), "declarations").single { it["inherited"] == false }
        assertEquals(40, (own["declared"] as Map<*, *>)["maxLength"])
        assertEquals("次の子", field(child, "child")["label"])
        assertFalse(field(child, "child").containsKey("fields"))
    }

    @Test
    fun `共通の基底型を継承する別の型を 再帰と取り違えない`() {
        val result = model("""
            id: model
            kind: library
            definitions:
              named: {type: object, fields: [{key: name, type: string}]}
              address: {type: named, fields: [{key: zip, type: int}]}
              person: {type: named, fields: [{key: address, type: address}]}
        """)
        val address = field(type(result, "person"), "address")
        assertFalse(address.containsKey("recursiveType"))
        assertEquals(setOf("name", "zip"), rows(address, "fields").map { it["key"] }.toSet())
    }

    @Test
    fun `再帰する配列の要素とマップの値でも 利用箇所で明記した子を残す`() {
        for ((base, childKey) in listOf("array" to "item", "map" to "value")) {
            val result = model("""
                id: model
                match: data/*.yaml
                definitions:
                  nodes:
                    type: $base
                    $childKey:
                      type: object
                      fields: [{key: name, type: string, maxLength: 100}, {key: children, type: nodes}]
                root:
                  type: object
                  entity: {type: root, defines: true}
                  fields:
                    - {key: id, type: id}
                    - key: tree
                      type: nodes
                      $childKey:
                        fields:
                          - key: children
                            $childKey:
                              fields: [{key: name, maxLength: 40}]
            """)
            val root = rows(rows(result, "entities").single(), "aspects").single()
            val children = field(field(root, "tree")[childKey] as Map<*, *>, "children")
            assertEquals("model#nodes", children["recursiveType"])
            val local = children[childKey] as Map<*, *>
            assertEquals("string", field(local, "name")["base"])
            assertEquals(40, field(local, "name")["maxLength"])
            val inheritedChild = field(local, "children")
            assertEquals("model#nodes", inheritedChild["recursiveType"])
            assertFalse(inheritedChild.containsKey(childKey))
        }
    }

    @Test
    fun `異なるモジュールの同名の型は別々に展開する`() {
        val result = model("""
            id: model
            kind: library
            definitions:
              node:
                type: object
                fields: [{key: child, type: other#node}]
        """, mapOf(".yde/schemas/other.yaml" to """
            id: other
            kind: library
            definitions:
              node:
                type: object
                fields: [{key: name, type: string}, {key: parent, type: model#node}]
        """.trimIndent()))
        val child = field(type(result, "node"), "child")
        assertFalse(child.containsKey("recursiveType"))
        assertEquals("string", field(child, "name")["base"])
        assertEquals("model#node", field(child, "parent")["recursiveType"])
        assertFalse(field(child, "parent").containsKey("fields"))
    }

    @Test
    fun `再帰するエンティティの側面は最初の折り返しを記録して閉じる`() {
        val result = model("""
            id: model
            match: data/*.yaml
            definitions:
              node:
                type: object
                entity: {type: node, defines: true}
                fields: [{key: id, type: id}, {key: children, type: array, item: {type: node}}]
            root: {type: node}
        """)
        val aspects = rows(rows(result, "entities").single(), "aspects")
        assertEquals(listOf("", "children[*]"), aspects.map { it["at"] })
        assertEquals("model#node", aspects.last()["recursiveType"])
        assertEquals("node", (field(aspects.first(), "children")["item"] as Map<*, *>)["entity"])
    }

    @Test
    fun `配列とマップを通る相互参照と cases の再帰も有限の型参照にする`() {
        val result = model("""
            id: model
            kind: library
            definitions:
              a: {type: array, item: {type: b}}
              b: {type: map, value: {type: a}}
              choice:
                type: enum
                values: [again]
                cases:
                  again: [{key: next, type: choice}]
        """)
        val a = type(result, "a")
        val next = (a["item"] as Map<*, *>)["value"] as Map<*, *>
        assertEquals("model#a", next["recursiveType"])
        assertFalse(next.containsKey("item"))
        val choice = type(result, "choice")
        val repeated = (((choice["cases"] as Map<*, *>)["again"] as List<*>).single() as Map<*, *>)
        assertEquals("model#choice", repeated["recursiveType"])
        assertFalse(repeated.containsKey("cases"))
    }
}
