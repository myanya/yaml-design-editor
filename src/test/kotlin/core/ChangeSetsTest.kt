package core

import model.YNode
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 変更セットYAMLの解析テスト。旧 core/PatchTest の変更セットケースを分離（3層コア移行 YM-007a）。
 * 単一文書への操作（Patcher）のテストは yaml-core の core/PatchTest にある。
 */
class ChangeSetsTest {

    @Test
    fun `変更セットYAMLを解析する`() {
        val cs = ChangeSets.parse(
            """
            changes:
              - file: a.yaml
                base: abc123
                ops:
                  - { op: set, path: title, value: 新タイトル }
                  - { op: insert, path: "tags[0]", value: 重要 }
                  - { op: delete, path: obsolete }
                  - { op: move, from: "items[2]", to: "items[0]" }
                  - { op: rename, path: title, to: name }
            """.trimIndent()
        )
        assertEquals(1, cs.changes.size)
        val change = cs.changes.single()
        assertEquals("a.yaml", change.file)
        assertEquals("abc123", change.baseHash)
        assertEquals(5, change.ops.size)
        assertTrue(change.ops[0] is PatchOp.Set)
        assertTrue(change.ops[1] is PatchOp.Insert)
        assertTrue(change.ops[2] is PatchOp.Delete)
        assertTrue(change.ops[3] is PatchOp.Move)
        assertEquals(listOf<Any>("items", 2), (change.ops[3] as PatchOp.Move).from)
        assertEquals("name", (change.ops[4] as PatchOp.RenameKey).newName)
    }

    @Test
    fun `reorderが変更セットから解析される`() {
        val cs = ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - { op: reorder, path: details, order: [a, b] }")
        val op = cs.changes.single().ops.single() as PatchOp.ReorderKeys
        assertEquals(listOf("a", "b"), op.order)
    }

    @Test
    fun `未知のopやrenameのto欠落はエラーになる`() {
        assertFailsWith<IllegalStateException> {
            ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - { op: upsert, path: a }")
        }
        // rename は to（改名先）が必須
        assertFailsWith<IllegalStateException> {
            ChangeSets.parse("changes:\n  - file: a.yaml\n    ops:\n      - { op: rename, path: a }")
        }
    }

    @Test
    fun `構造化された値はノードに変換される`() {
        val cs = ChangeSets.parse(
            """
            changes:
              - file: a.yaml
                ops:
                  - op: set
                    path: item
                    value:
                      id: X-1
                      tags: [a, b]
            """.trimIndent()
        )
        val value: YNode = (cs.changes.single().ops.single() as PatchOp.Set).value
        assertEquals("id: X-1\ntags:\n  - a\n  - b\n", YamlIO.dump(value))
    }
}
