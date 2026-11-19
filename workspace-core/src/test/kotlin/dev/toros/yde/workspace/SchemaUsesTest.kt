package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaUsesTest {
    private fun YNode.at(path: List<Any>): YNode? = getAt(this, path)
    private val sources = """
        - id: common
          kind: library
          definitions:
            text: {type: string, maxLength: 200}
            part:
              type: object
              fields:
                - {key: note, type: text}
                - {key: next, type: array, item: {type: part}}
            choice:
              type: enum
              values: [a, b]
              cases: {a: [{key: note, type: text}]}
        - id: business
          kind: document
          match: records/*.yaml
          entity: {type: record, defines: true}
          root:
            type: object
            fields:
              - {key: id, type: id}
              - {key: left, type: common#part}
              - {key: right, type: common#part}
              - {key: limited, type: common#text, maxLength: 100, default: ''}
              - {key: choice, type: common#choice}
    """.trimIndent()

    @Test
    fun `継承値を明記に変えず 利用箇所と再帰の有限な場所を用意する`() {
        val input = YamlIO.fromAny(YamlIO.parseToRaw(sources))
        val before = YamlIO.dumpPlain(input)
        val prepared = SchemaUses.prepare(input)
        assertEquals(before, YamlIO.dumpPlain(input))
        assertEquals(mapOf("key" to "note"), YamlIO.toAny(prepared.at(listOf("schemas", 1, "root", "fields", 1, "fields", 0))!!))
        assertEquals(100, YamlIO.toAny(prepared.at(listOf("schemas", 1, "root", "fields", 3, "maxLength"))!!))
        assertEquals("", YamlIO.toAny(prepared.at(listOf("schemas", 1, "root", "fields", 3, "default"))!!))
        val uses = (YamlIO.toAny(prepared) as Map<*, *>)["uses"] as List<*>
        assertTrue(uses.size < 30, "再帰は型参照で閉じる")
        assertTrue(uses.map { (it as Map<*, *>)["path"] }.containsAll(listOf(listOf("fields", "left", "fields", "note"), listOf("fields", "right", "fields", "note"))))
        val cases = uses.map { it as Map<*, *> }.filter { it["writable"] == false }
        assertTrue(cases.any { "choice" in it["path"].toString() }, "継承した cases は適用できると偽らない")
        // 用意した木も既存の定義検査と型解決へ渡せる。
        val model = SchemaModels.resolve(prepared.at(listOf("schemas"))!!)
        val fields = model.at(listOf("entities", 0, "aspects", 0, "fields")) as YNode.Seq
        assertEquals(200, YamlIO.toAny(fields.items[1].at(listOf("fields", 0, "maxLength"))!!))
        assertEquals(100, YamlIO.toAny(fields.items[3].at(listOf("maxLength"))!!))
    }

    @Test
    fun `同じ型の片方だけに指定すると他方と元の共通型を変えない`() {
        val prepared = SchemaUses.prepare(YamlIO.fromAny(YamlIO.parseToRaw(sources)))
        val left = prepared.at(listOf("schemas", 1, "root", "fields", 1, "fields", 0)) as YNode.Map
        left.entries["maxLength"] = YNode.Scalar(60)
        val model = SchemaModels.resolve(prepared.at(listOf("schemas"))!!)
        val fields = model.at(listOf("entities", 0, "aspects", 0, "fields")) as YNode.Seq
        assertEquals(60, YamlIO.toAny(fields.items[1].at(listOf("fields", 0, "maxLength"))!!))
        assertEquals(200, YamlIO.toAny(fields.items[2].at(listOf("fields", 0, "maxLength"))!!))
        assertEquals(200, YamlIO.toAny(model.at(listOf("types", 0, "maxLength"))!!))
    }
}
