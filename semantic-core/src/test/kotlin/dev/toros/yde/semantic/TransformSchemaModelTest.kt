package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransformSchemaModelTest {
    @Test fun `文書の検査も生成の供給元だけで実行し 読み取りと束縛を検査する`() {
        val def = definition("{\$validate: {\$copy: input}}")
        val input = YamlIO.fromAny(mapOf("input" to mapOf("documents" to emptyMap<String, Any>())))
        val before = YamlIO.dumpPlain(input)
        val result = SemanticTransform.run(def, input, validate = { (it as YNode.Map).entries.clear(); YamlIO.fromAny(emptyMap<String, Any>()) })
        assertEquals(emptyMap<String, Any>(), YamlIO.toAny(result.term!!))
        assertEquals(before, YamlIO.dumpPlain(input))
        assertTrue(result.origins.isEmpty())
        assertFailsWith<IllegalArgumentException> { SemanticTransform.run(def, input) }
        assertFailsWith<IllegalArgumentException> { definition("{\$validate: {\$copy: ., \$of: absent}}") }
        assertTrue(TransformReads.of(def, emptyMap(), emptyList()).any { "input" in it })
    }
    @Test
    fun `利用箇所の展開も供給された入口だけで実行し 入力の読み取りを保持する`() {
        val def = definition("{\$schemaUses: {\$copy: schemas}}")
        val input = YamlIO.fromAny(mapOf("schemas" to listOf(mapOf("id" to "source"))))
        var received: Any? = null
        val result = SemanticTransform.run(def, input, schemaUses = { received = YamlIO.toAny(it); it })
        assertEquals(listOf(mapOf("id" to "source")), received)
        assertEquals(received, YamlIO.toAny(result.term!!))
        assertTrue(assertFailsWith<IllegalArgumentException> { SemanticTransform.run(def, input) }.message!!.contains("schemaUses"))
        assertTrue(TransformReads.of(def, emptyMap(), emptyList()).any { "schemas" in it })
        assertFailsWith<IllegalArgumentException> { definition("{\$schemaUses: {\$copy: ., \$of: absent}}") }
    }
    private fun definition(build: String) = TransformDef.parse(YamlIO.parseToRaw("id: resolve\nbuild: $build"))

    @Test
    fun `組み立てた項を供給元へ渡し 入力の配列番号を結果の来歴にしない`() {
        val def = definition("{\$schemaModel: {\$copy: schemas}}")
        val input = YamlIO.fromAny(mapOf("schemas" to listOf(mapOf("id" to "common"))))
        val before = YamlIO.dumpPlain(input)
        val result = SemanticTransform.run(def, input, DocumentId("source.yaml"), schemaModel = { schemas ->
            assertEquals(listOf(mapOf("id" to "common")), YamlIO.toAny(schemas))
            (schemas as YNode.Seq).items.clear()
            YamlIO.fromAny(mapOf("types" to emptyList<Any>()))
        })
        assertEquals(mapOf("types" to emptyList<Any>()), YamlIO.toAny(result.term!!))
        assertTrue(result.origins.isEmpty())
        assertEquals(before, YamlIO.dumpPlain(input), "供給元へ渡す項は元の入力と分離する")
    }

    @Test
    fun `供給元と入力の欠落や検査の失敗は 空の成功結果にしない`() {
        val def = definition("{\$schemaModel: {\$copy: schemas}}")
        val input = YamlIO.fromAny(emptyMap<String, Any>())
        assertTrue(assertFailsWith<IllegalArgumentException> { SemanticTransform.run(def, input) }.message!!.contains("この入口では"))
        assertTrue(assertFailsWith<IllegalArgumentException> { SemanticTransform.run(def, input, schemaModel = { it }) }.message!!.contains("入力がありません"))
        val literal = definition("{\$schemaModel: []}")
        assertEquals("型の検査に失敗", assertFailsWith<IllegalArgumentException> {
            SemanticTransform.run(literal, input, schemaModel = { throw IllegalArgumentException("型の検査に失敗") })
        }.message)
    }

    @Test
    fun `スキーマを組み立てる指示の中も束縛と規則と読み取りを検査する`() {
        assertFailsWith<IllegalArgumentException> { definition("{\$schemaModel: {\$copy: ., \$of: absent}}") }
        assertFailsWith<IllegalArgumentException> { definition("{\$schemaModel: {\$apply: absent}}") }
        assertTrue(TransformReads.of(definition("{\$schemaModel: {\$copy: schemas}}"), emptyMap(), emptyList()).any { "schemas" in it })
    }
}
