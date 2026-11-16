package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransformCompositionTest {
    private fun definition(build: String) = TransformDef.parse(YamlIO.parseToRaw("id: combine\nbuild:\n" + build.trimIndent().prependIndent("  ")))
    private val transform = definition("{${'$'}compose: {${'$'}copy: .}}")
    private fun run(input: Map<String, Any?>) = SemanticTransform.runAt(transform,
        SemanticTransform.TemplatePlace(YamlIO.fromAny(input), DocumentId("input.yaml"), emptyList()))
    private fun record(id: String, value: Any?) = mapOf("id" to id, "value" to mapOf("default" to value), "source" to mapOf("id" to "DRV-$id"))
    private fun change(value: Any?, id: String, priority: String = "rule") = mapOf(
        "target" to "field", "path" to listOf("default"), "value" to value, "priority" to priority,
        "source" to mapOf("id" to id, "document" to "changes.yaml", "path" to listOf("changes", 0)))
    private fun input(changes: List<Map<String, Any?>>) = mapOf("derived" to listOf(record("field", 100)), "additions" to emptyList<Any>(), "changes" to changes)

    @Test
    fun `合成の指示は競合の根拠を伝え 個別指定で解決した結果と採用しなかった候補を返す`() {
        val rules = listOf(change(150, "rule-a"), change(200, "rule-b"))
        val failure = assertFailsWith<IllegalArgumentException> { run(input(rules)) }
        assertTrue(failure.message!!.contains("rule-a（changes.yaml:changes[0]）"), failure.message)
        assertTrue(failure.message!!.contains("rule-b"), failure.message)
        val result = run(input(rules + change(180, "COR-001", "individual")))
        val raw = YamlIO.toAny(result.term!!) as Map<*, *>
        val records = raw["records"] as List<*>
        assertEquals(180, ((records.single() as Map<*, *>)["value"] as Map<*, *>)["default"])
        val decision = (raw["decisions"] as List<*>).single() as Map<*, *>
        assertEquals(3, (decision["candidates"] as List<*>).size)
        assertEquals("COR-001", ((((decision["selected"] as List<*>).single() as Map<*, *>)["source"] as Map<*, *>)["id"]))
        assertTrue(result.origins.isEmpty(), "合成前の配列番号を出力の出どころにしない")
    }

    @Test
    fun `空の値と null は合成後も保ち 値のキーが無ければ誤りにする`() {
        for (value in listOf(null, false, 0, "", emptyList<Any>())) {
            val raw = YamlIO.toAny(run(input(listOf(change(value, "COR-001", "individual")))).term!!) as Map<*, *>
            val v = (((raw["records"] as List<*>).single() as Map<*, *>)["value"] as Map<*, *>)
            assertTrue(v.containsKey("default"))
            assertEquals(value, v["default"])
        }
        assertTrue(assertFailsWith<IllegalStateException> { run(input(listOf(change(0, "bad") - "value"))) }.message!!.contains("value がありません"))
    }

    @Test
    fun `候補の欠落や誤記を空として通さず 型を緩めて解釈しない`() {
        val original = input(emptyList())
        val bad = listOf(original - "changes", original + ("unknown" to true),
            original + ("changes" to listOf(change(1, "bad") + ("priority" to "Individual"))),
            original + ("changes" to listOf(change(1, "bad") + ("path" to listOf(-1)))),
            original + ("derived" to listOf(record("field", 1) + ("id" to 123))))
        for (value in bad) assertFailsWith<RuntimeException> { run(value) }
        val empty = YamlIO.toAny(run(mapOf("derived" to emptyList<Any>(), "additions" to emptyList<Any>(), "changes" to emptyList<Any>())).term!!)
        assertEquals(mapOf("records" to emptyList<Any>(), "decisions" to emptyList<Any>()), empty)
    }

    @Test
    fun `set は属性ごとに競合を調べ null や空の値をそのまま渡す`() {
        val set = mapOf("default" to false, "min" to 0, "label" to "", "values" to emptyList<Any>(), "nullable" to null)
        val c = (change(0, "COR-001", "individual") - "path" - "value") + ("set" to set)
        val raw = YamlIO.toAny(run(input(listOf(c))).term!!) as Map<*, *>
        assertEquals(set, (((raw["records"] as List<*>).single() as Map<*, *>)["value"]))
        assertEquals(set.size, (raw["decisions"] as List<*>).size)
        assertFailsWith<IllegalArgumentException> { run(input(listOf(c + ("path" to listOf("default"))))) }
        assertFailsWith<IllegalArgumentException> { run(input(listOf(c + ("set" to emptyMap<String, Any>())))) }
    }

    @Test
    fun `合成の中も束縛と規則を点検し 入力の足あとを読む`() {
        assertFailsWith<IllegalArgumentException> { definition("{${'$'}compose: {${'$'}copy: ., ${'$'}of: absent}}") }
        assertFailsWith<IllegalArgumentException> { definition("{${'$'}compose: {${'$'}apply: absent}}") }
        val read = definition("{${'$'}compose: {${'$'}copy: candidates}}")
        assertTrue(TransformReads.of(read, emptyMap(), emptyList()).any { it.contains("candidates") })
    }
}
