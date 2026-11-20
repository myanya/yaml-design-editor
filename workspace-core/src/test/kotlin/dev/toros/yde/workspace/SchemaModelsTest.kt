package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaModelsTest {
    private fun model(yaml: String) = YamlIO.toAny(SchemaModels.resolve(YamlIO.fromAny(YamlIO.parseToRaw(yaml.trimIndent())))) as Map<*, *>
    private fun types(model: Map<*, *>) = (model["types"] as List<*>).map { it as Map<*, *> }.associateBy { it["name"] }

    @Test
    fun `渡したモジュールだけで型を解き 明記と継承と初期値を残す`() {
        val result = types(model("""
            - id: common
              kind: library
              definitions:
                title: {type: line, maxLength: 200}
            - id: fields
              kind: library
              definitions:
                inherited: {type: common#title}
                explicit: {type: common#title, maxLength: 100}
                flag: {type: boolean, default: false}
                count: {type: int, default: 0}
                blank: {type: string, default: ''}
                values: {type: array, item: {type: string}, default: []}
        """))
        assertEquals(setOf("common#title", "fields#inherited", "fields#explicit", "fields#flag", "fields#count", "fields#blank", "fields#values"), result.keys)
        assertEquals(200, result.getValue("fields#inherited")["maxLength"])
        assertEquals(100, result.getValue("fields#explicit")["maxLength"])
        for ((name, value) in mapOf("flag" to false, "count" to 0, "blank" to "", "values" to emptyList<Any>())) {
            assertEquals(value, result.getValue("fields#$name")["default"])
        }
        val declarations = result.getValue("fields#explicit")["declarations"] as List<*>
        assertTrue(declarations.any { ((it as Map<*, *>)["declared"] as Map<*, *>)["maxLength"] == 100 })
    }

    @Test
    fun `配列とマップの再帰する型は参照で閉じる`() {
        val result = types(model("""
            - id: common
              kind: library
              definitions:
                node:
                  type: object
                  fields:
                    - {key: children, type: array, item: {type: common#node}}
                    - {key: named, type: map, value: {type: common#node}}
        """))
        val fields = (result.getValue("common#node")["fields"] as List<*>).map { it as Map<*, *> }
        assertEquals("common#node", (fields[0]["item"] as Map<*, *>)["recursiveType"])
        assertEquals("common#node", (fields[1]["value"] as Map<*, *>)["recursiveType"])
    }

    @Test
    fun `補正後の型とビュー原文から表示形式と到達性を一緒に解決する`() {
        val input = """
            schemas:
              - id: rows
                kind: document
                match: rows.yaml
                entity: {type: row}
                root:
                  type: object
                  fields:
                    - {key: id, type: id}
                    - {key: day, type: date, format: yyyy/MM/dd}
            views:
              - id: list
                rows: {match: [{node: {entity: row}}]}
                columns: [{label: 日付, path: day, schemaId: rows}]
              - id: dashboard
                kind: dashboard
                sections: [{id: main, view: list}]
        """.trimIndent()
        val result = model(input)
        val column = (((result["views"] as List<*>).single() as Map<*, *>)["columns"] as List<*>).single() as Map<*, *>
        assertEquals("yyyy/MM/dd", (column["field"] as Map<*, *>)["format"])
        assertEquals(listOf("row"), (result["reach"] as Map<*, *>)["entities"])
        assertEquals(2, (result["viewDefinitions"] as List<*>).size)
        val changedView = (model(input.replace("yyyy/MM/dd", "yyyy-MM-dd"))["views"] as List<*>).single() as Map<*, *>
        val changedColumn = (changedView["columns"] as List<*>).single() as Map<*, *>
        assertEquals("yyyy-MM-dd", (changedColumn["field"] as Map<*, *>)["format"])
        for (bad in listOf("{schemas: [], views: {}}", "{schemas: [], views: [null]}", "{schemas: [], views: [{id: a}, {id: a}]}", "{schemas: [], unexpected: []}")) {
            assertFailsWith<RuntimeException>(bad) { model(bad) }
        }
    }

    @Test
    fun `文書からビュー定義への参照は渡したビューだけで検査する`() {
        val input = """
            schemas:
              - id: record
                match: record.yaml
                root:
                  type: object
                  fields:
                    - {key: id, type: id}
                    - {key: source, type: ref}
            views:
              - id: source-view
                rows: {idPattern: 'ROW-*'}
                columns: [{label: ID, path: id}]
            documents:
              record.yaml: {id: DOC-1, source: source-view}
        """.trimIndent()
        val term = YamlIO.fromAny(YamlIO.parseToRaw(input))
        val result = YamlIO.toAny(SchemaModels.validate(term)) as Map<*, *>
        assertEquals(mapOf("id" to "DOC-1", "source" to "source-view"), result["record.yaml"])
        assertFailsWith<RuntimeException> {
            SchemaModels.validate(YamlIO.fromAny(YamlIO.parseToRaw(input.replace("source: source-view", "source: missing-view"))))
        }
    }

    @Test
    fun `入力の欠落や重複や型の誤りは一部だけ解決して通さない`() {
        for (bad in listOf("{}", "[null]", "[{kind: library}]", "[{id: 1, kind: library}]",
            "[{id: a, kind: library}, {id: a, kind: library}]",
            "[{id: a, kind: library, definitions: {x: {type: missing#text}}}]",
            "[{id: a, kind: library, definitions: {x: {type: string, maxLength: invalid}}}]",
            "[{id: a, kind: library, definitions: {x: {type: string, maxLength: 100}, y: {type: x, maxLength: 200}}}]")) {
            assertFailsWith<RuntimeException>(bad) { model(bad) }
        }
        assertEquals(emptySet(), types(model("[]")).keys)
    }
}
