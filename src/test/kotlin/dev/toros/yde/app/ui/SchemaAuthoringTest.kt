package dev.toros.yde.app.ui

import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaAuthoringTest {
    private val tree get() = BuiltinSchemas.all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }.tree

    @Test
    fun `操作の入力項目と採番はメタスキーマの型付きフォームで定義できる`() {
        val action = tree.resolve(tree.root.fields.first { it.key == "actions" }.item!!)!!
        assertEquals(FieldType.OBJECT, action.type)
        val params = action.fields.first { it.key == "params" }
        assertEquals(FieldType.OBJECT, tree.resolve(params.item!!)!!.type)
        val array = YamlIO.parse("type: array") as YNode.Map
        val methods = schemaFieldAdditionalFields(tree, array, "array", true, false).first { it.key == "methods" }
        val insert = tree.resolve(methods.fields.single())!!
        val numbering = insert.fields.first { it.key == "id" }
        assertEquals(setOf("path", "prefix", "digits"), numbering.fields.map { it.key }.toSet())
    }

    @Test
    fun `各型の追加項目と将来の未知キーを表示して原本を変えない`() {
        for (type in FieldType.entries) {
            val node = YamlIO.parse("type: ${type.id}\nfuture: {flag: true}") as YNode.Map
            val before = YamlIO.dumpPlain(node)
            val extra = schemaFieldAdditionalFields(tree, node, type.id, true, false)
            val defined = tree.effectiveFields(tree.definitions.getValue("field").fields, node).fields
            val handled = schemaFieldHandledKeys(type.id, true, false)
            assertTrue(defined.all { it.key in handled || extra.any { e -> e.key == it.key } }, type.id)
            assertTrue(extra.any { it.key == "future" })
            assertEquals(before, YamlIO.dumpPlain(node))
        }
    }

    @Test
    fun `ファイル制約と型の操作と保有関係と式を設定できる`() {
        fun keys(type: String) = schemaFieldAdditionalFields(tree, YamlIO.parse("type: $type") as YNode.Map, type, true, false).map { it.key }
        assertTrue(keys("file").containsAll(listOf("extensions", "pattern")))
        assertTrue(keys("object").containsAll(listOf("constraints", "methods", "relation")))
        assertTrue(keys("string").contains("expression"))
        assertTrue(keys("int").contains("display"))
        assertTrue(keys("array").contains("default"))
    }

    @Test
    fun `属性付き参照の項目を構成一覧から辿れる`() {
        val rows = schemaOutline(YamlIO.parse("type: ref\nfields: [{key: weight, type: int}]"), listOf("root"), "参照", { it })
        assertEquals(listOf("root", "fields", 0), rows.last().path)
    }
}
