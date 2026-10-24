package dev.toros.yde.app.ui

import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaDesignFieldsTest {
    private val tree get() = BuiltinSchemas.all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }.tree

    @Test
    fun `メタスキーマの全項目は専用欄か追加区画に現れ未知の既存内容も保持する`() {
        val root = YamlIO.parse("id: example\nroot: {type: object}\nactions: [{id: start}]\nfuture: {x: 1}") as YNode.Map
        val before = YamlIO.dumpPlain(root)
        val fields = schemaAdditionalFields(tree, root, false)
        assertTrue(tree.root.fields.mapNotNull { it.key }.all { it in schemaBasicKeys || fields.any { f -> f.key == it } })
        assertTrue(fields.any { it.key == "actions" })
        assertTrue(fields.any { it.key == "future" })
        assertEquals(before, YamlIO.dumpPlain(root))
        assertEquals(SchemaNavigationTarget(3, "actions", listOf("actions")), schemaNavigationTarget(listOf("actions", 1, "params")))
    }

    @Test
    fun `新しいメタ項目はUIの列挙を変更せず編集対象になる`() {
        val fields = schemaRemainingFields(listOf(FieldDef(key = "newProperty", type = FieldType.BOOLEAN)), YNode.Map(), emptySet())
        assertEquals(listOf("newProperty"), fields.map { it.key })
        assertEquals(FieldType.BOOLEAN, fields.single().type)
    }

    @Test
    fun `libraryには文書用の空欄を出さず既に書かれた設定は隠さない`() {
        val root = YamlIO.parse("kind: library\ndefinitions: {}") as YNode.Map
        assertTrue(schemaAdditionalFields(tree, root, true).none { it.key == "filePattern" })
        root.entries["filePattern"] = YNode.Scalar("details/{id}.yaml")
        assertTrue(schemaAdditionalFields(tree, root, true).any { it.key == "filePattern" })
    }
}
