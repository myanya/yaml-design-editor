package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.validate.StructuralIssueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 配列の要素が重ならないこと（`unique`。UX-316）: 項目の決まりとして書き、導出した構造のスキーマで検査する。
 * 型に書けば使う側に引き継がれ、使う側は足せる（両方が効く）。書き方の誤りはスキーマの定義の点検が指摘する。
 */
class ArrayUniqueTest {
    private val schema = SchemaTreeParser.parse(
        """
        id: table
        root:
          type: object
          fields:
            - { key: tags, label: タグ, type: array, unique: true, item: { type: ref, idPattern: "TAG-*" } }
            - key: columns
              label: カラム
              type: カラムの並び
              unique: [code]          # 型の unique（name）に足す
        definitions:
          カラムの並び:
            type: array
            unique: [name]
            item:
              type: object
              fields:
                - { key: name, label: 名前, type: string }
                - { key: code, label: 略号, type: string }
        """.trimIndent(),
        fallbackId = "table",
    )

    private fun validate(yaml: String) = DerivedStructures(schema).validate(YamlIO.parse(yaml)).filter { it.kind == StructuralIssueKind.UNIQUE }

    @Test
    fun `参照の配列は同じ値を2回書けず 型と使う側の決まりは両方が効く`() {
        val tag = validate("tags: [TAG-001, TAG-001]").single()
        assertEquals(listOf<Any>("tags", 1), tag.path)
        assertEquals("「タグ」に同じ値「TAG-001」があります（1 件目と同じ）", tag.message)
        // 型の unique（名前）と、使う側で足した unique（略号）
        val columns = validate("columns: [{name: id, code: a}, {name: id, code: b}, {name: no, code: a}]")
        assertEquals(listOf(listOf<Any>("columns", 1), listOf<Any>("columns", 2)), columns.map { it.path })
        assertTrue(columns[0].message.contains("「名前」が「id」"), columns[0].message)
        assertTrue(columns[1].message.contains("「略号」が「a」"), columns[1].message)
        assertTrue(validate("tags: [TAG-001, TAG-002]\ncolumns: [{name: id, code: a}, {name: no, code: b}]").isEmpty())
    }

    @Test
    fun `書き方の誤りは読み込みかスキーマの定義の点検で分かる`() {
        // 配列でない項目には書けない（基底の種類が分かる項目は読み込みで）
        assertFailsWith<IllegalArgumentException> {
            SchemaTreeParser.parse("id: s\nroot:\n  type: object\n  fields:\n    - { key: a, type: string, unique: true }", fallbackId = "s")
        }
        fun errors(field: String): List<String> {
            val ws = WorkspaceReader.load(InMemoryDocumentStore(mapOf(
                "_schemas/s.yaml" to "id: s\nmatch: \"s/*.yaml\"\nroot:\n  type: object\n  fields:\n    - $field\n" +
                    "definitions:\n  名前:\n    type: string\n  行:\n    type: object\n    fields:\n      - { key: name, type: string }\n",
            )))
            return ws.schemaErrors
        }
        assertTrue(errors("{ key: a, type: array, unique: true, item: { type: string } }").isEmpty())
        assertTrue(errors("{ key: a, type: array, unique: true, item: { type: 行 } }").any { "比べる項目のキーの一覧" in it })
        assertTrue(errors("{ key: a, type: array, unique: [name], item: { type: string } }").any { "要素がスカラーなら unique: true" in it })
        assertTrue(errors("{ key: a, type: array, unique: [nope], item: { type: 行 } }").any { "キー「nope」は要素の項目にありません" in it })
        // 型の名前を書いた項目は、解決した基底の種類で点検する
        assertTrue(errors("{ key: a, type: 名前, unique: true }").any { "type: array の項目でのみ" in it })
    }
}
