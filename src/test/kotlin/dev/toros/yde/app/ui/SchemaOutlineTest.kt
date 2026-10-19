package dev.toros.yde.app.ui

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaOutlineTest {
    private fun outline(text: String, base: (String) -> String = { it }) =
        schemaOutline(YamlIO.parse(text), listOf("root"), "ルート", base, emptyList())

    @Test
    fun `配列とマップの要素を辿っても一括変更用のデータパスは増えない`() {
        val rows = outline("""
            type: object
            fields:
              - key: tasks
                label: 作業
                type: array
                item:
                  type: map
                  value:
                    type: object
                    fields:
                      - key: title
                        type: string
        """.trimIndent())
        assertEquals(listOf("root", "fields", 0, "item", "value", "fields", 0), rows.last().path)
        assertEquals(listOf("tasks", "title"), rows.last().fieldPath)
        assertEquals("作業", rows[1].title)
        assertEquals(listOf("ルート", "作業", "配列の要素", "マップの値", "title"), rows.map { it.title })
    }

    @Test
    fun `条件付き項目と残った条件も一覧に残して一括変更の対象から除く`() {
        val rows = outline("""
            type: enum
            values: [a]
            cases:
              a: [{key: title, type: string}]
              stale: [{key: name, type: string}]
        """.trimIndent())
        assertEquals(5, rows.size)
        assertTrue(rows[1].caseGroup)
        assertEquals(listOf("root", "cases", "stale", 0), rows.last().path)
        assertNull(rows.last().fieldPath)
    }

    @Test
    fun `参照は展開せず名前付き型の基底だけを既存解決から受け取る`() {
        val rows = outline("type: MyObject\nfields: [{key: x, \$ref: Shared}]") {
            if (it == "MyObject") "object" else it
        }
        assertEquals(2, rows.size)
        assertEquals("参照: Shared", rows.last().type)
    }

    @Test
    fun `未定義要素や不正な項目を消さず表示だけで木を変更しない`() {
        val root = YamlIO.parse("type: object\nfields: [{key: x, type: array}, broken]")
        val before = YamlIO.dumpPlain(root)
        val rows = schemaOutline(root, listOf("root"), "ルート", { it })
        assertEquals("未定義", rows[2].type)
        assertNull(rows[2].node)
        assertEquals("形式を確認", rows.last().type)
        assertEquals(before, YamlIO.dumpPlain(root))
    }

    @Test
    fun `ルートの種類に依存せず消えた選択は近い親へ戻る`() {
        val rows = outline("type: array\nitem: {type: string}")
        assertEquals(rows[1], schemaOutlineSelection(rows, listOf("root", "item", "fields", 2)))
        assertEquals(rows[0], schemaOutlineSelection(rows, listOf("definitions", "gone")))
        assertEquals(1, outline("type: int").size)
        assertEquals(1, schemaOutline(null, listOf("root"), "ルート", { it }).size)
    }

    @Test
    fun `Undoで順序が戻っても一意なキーの項目を選び直す`() {
        val rows = outline("type: object\nfields: [{key: a, type: string}, {key: b, type: string}]")
        assertEquals(rows[2], schemaSelectionAfterEdit(rows, listOf("root", "fields", 0), "b"))
        assertEquals(rows[1], schemaSelectionAfterEdit(rows, listOf("root", "fields", 0), "deleted"))
    }

    @Test
    fun `並べ替えは選択項目の子孫と押し出された隣の選択を追随させる`() {
        val seq = listOf("root", "fields")
        assertEquals(seq + listOf(0, "item"), movedSchemaSelection(seq + listOf(2, "item"), seq, 2, 0))
        assertEquals(seq + 2, movedSchemaSelection(seq + 1, seq, 2, 0))
        assertEquals(seq + 1, movedSchemaSelection(seq + 2, seq, 0, 2))
        assertEquals(listOf("root"), movedSchemaSelection(listOf("root"), seq, 0, 1))
    }
}
