package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.semantic.schema.SchemaTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldDeclarationTest {
    @Test
    fun `共通型と等しい明記と 未指定を型の連鎖を通して区別する`() {
        val source = """
            id: words
            definitions:
              word: {type: string, maxLength: 100}
              alias: {type: word}
            root:
              type: object
              fields:
                - {key: explicit, type: alias, maxLength: 100}
                - {key: inherited, type: alias}
        """.trimIndent()
        val tree = SchemaTreeParser.parse(source, "words")
        val explicit = assertNotNull(tree.resolve(tree.root.fields[0]))
        val inherited = assertNotNull(tree.resolve(tree.root.fields[1]))
        assertEquals(100, explicit.maxLength)
        assertEquals(100, inherited.maxLength)
        assertEquals(listOf(100, null, 100), explicit.declarations.map { it.attributes["maxLength"] })
        assertEquals(listOf(100, null, null), inherited.declarations.map { it.attributes["maxLength"] })
        assertEquals(listOf("root", "fields", 0), explicit.declarations.last().path)
        assertEquals(listOf("definitions", "word"), explicit.declarations.first().path)
        assertEquals(explicit.declarations, tree.resolve(explicit)!!.declarations)
        // 同じ原文の項目を使い、型だけ 200 に変えたときの意味も既存の型解決で確かめる。
        val changed = SchemaTreeParser.parse(source.replace("word: {type: string, maxLength: 100}", "word: {type: string, maxLength: 200}"), "words")
        assertEquals(100, changed.resolve(changed.root.fields[0])!!.maxLength)
        assertEquals(200, changed.resolve(changed.root.fields[1])!!.maxLength)
        assertEquals(200, changed.resolve(changed.root.fields[1])!!.declarations.first().attributes["maxLength"])
    }

    @Test
    fun `名前付き object の子の指定と 配列 マップ cases の場所を失わない`() {
        val library = SchemaTreeParser.parse("""
            id: common
            kind: library
            definitions:
              row:
                type: object
                fields: [{key: title, type: string, maxLength: 100}]
        """.trimIndent(), "common")
        val tree = SchemaTreeParser.parse("""
            id: use
            root:
              type: object
              fields:
                - key: rows
                  type: array
                  item:
                    type: common#row
                    fields: [{key: title, maxLength: 100}]
                - key: map
                  type: map
                  value: {type: string, default: ''}
                - key: kind
                  type: enum
                  values: [detail]
                  cases:
                    detail: [{key: flag, type: boolean, required: false, default: false}]
        """.trimIndent(), "use").let { it.copy(definitionCatalog = SchemaCatalog.linked(listOf("common" to library.definitions))) }
        val row = assertNotNull(tree.resolve(tree.root.fields[0].item))
        val title = row.fields.single()
        assertEquals(100, title.maxLength)
        assertEquals(listOf("common", "use"), title.declarations.map { it.schema })
        assertEquals(listOf("root", "fields", 0, "item", "fields", 0), title.declarations.last().path)
        assertEquals(listOf("root", "fields", 1, "value"), tree.root.fields[1].value!!.declarations.single().path)
        val flag = tree.root.fields[2].cases.getValue("detail").single().declarations.single()
        assertEquals(listOf("root", "fields", 2, "cases", "detail", 0), flag.path)
        assertEquals(false, flag.attributes["default"])
        assertEquals(false, flag.attributes["required"])
        assertFalse("fields" in row.declarations.last().attributes)
    }

    @Test
    fun `空とゼロと未指定を保持し 原文の可変な値を共有しない`() {
        val defaults = mutableListOf<Any?>()
        val source = mutableMapOf<String, Any?>("type" to "array", "default" to defaults, "item" to mapOf("type" to "string"))
        val field = SchemaTreeParser.parseField(source, "items", "sample", sourcePath = listOf("root"))
        defaults += "changed"
        assertEquals(emptyList<Any?>(), field.declarations.single().attributes["default"])
        assertFalse("item" in field.declarations.single().attributes)
        val tree = SchemaTreeParser.parse("""
            id: sample
            root:
              type: object
              fields:
                - {key: count, type: int, default: 0, min: 0}
                - {key: text, type: string, default: ''}
                - {key: missing, type: string}
        """.trimIndent(), "sample")
        val entries = tree.root.fields.map { it.declarations.single().attributes }
        assertEquals(0, entries[0]["default"])
        assertEquals(0, entries[0]["min"])
        assertEquals("", entries[1]["default"])
        assertTrue(entries[1].containsKey("default"))
        assertFalse(entries[2].containsKey("default"))
    }
}
