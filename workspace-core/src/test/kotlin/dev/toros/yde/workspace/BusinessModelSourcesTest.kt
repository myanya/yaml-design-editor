package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BusinessModelSourcesTest {
    @Test
    fun `スコープ内の原文を保持し 型の展開値や他のスコープを混ぜない`() {
        val declaration = """
            id: common
            kind: library
            definitions:
              title: {type: line, maxLength: 100}
              node:
                type: object
                fields:
                  - {key: name, type: title, maxLength: 100}
                  - {key: next, type: array, item: {type: node}, default: []}
                  - {key: flag, type: boolean, default: false}
                  - {key: count, type: int, default: 0}
                  - {key: blank, type: string, default: ''}
        """.trimIndent()
        val store = InMemoryDocumentStore(mapOf(
            ".yde/workspace.yaml" to "scopes: [{id: business, include: ['schemas/business/**']}, {id: design, include: ['schemas/design/**']}]\n",
            ".yde/schemas/business/shared.yaml" to declaration,
            ".yde/schemas/design/private.yaml" to "id: private\nkind: library\ndefinitions: {private: {type: string}}\n",
        ))
        val loaded = WorkspaceReader.load(store)
        assertEquals(emptyList(), loaded.schemaErrors)
        fun sources(node: YNode) = (YamlIO.toAny(node) as Map<*, *>)["schemas"] as List<*>
        val expected = listOf(mapOf("id" to "common", "document" to ".yde/schemas/business/shared.yaml", "declared" to YamlIO.parseToRaw(declaration)))
        val model = BusinessModel.of(loaded, "business")
        assertEquals(expected, sources(model))
        assertEquals(expected, sources(BusinessModel.of(WorkspaceSession(store), "business")))
        // 出力の写しを補正しても、元のスキーマや次の導出へ変更を持ち越さない。
        val schemas = (model as YNode.Map).entries.getValue("schemas") as YNode.Seq
        val source = schemas.items.single() as YNode.Map
        val raw = source.entries.getValue("declared") as YNode.Map
        raw.entries["id"] = YNode.Scalar("changed")
        assertEquals(expected, sources(BusinessModel.of(loaded, "business")))
        assertFalse("changed" in store.read(dev.toros.yde.yaml.model.DocumentId(".yde/schemas/business/shared.yaml"))!!.text)
    }
}
