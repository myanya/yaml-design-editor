package dev.toros.yde.desktop.ui

import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R8-05: 汎用ビューのキー名変更（L-05）。 */
class RenameKeyTest {

    private fun mapOfKeys(vararg pairs: Pair<String, Any?>): YNode.Map =
        YNode.Map(LinkedHashMap(pairs.associate { (k, v) -> k to YNode.Scalar(v) }))

    @Test
    fun `rename keeps order and value`() {
        val map = mapOfKeys("a" to 1, "b" to 2, "c" to 3)
        assertTrue(renameMapKey(map, "b", "renamed"))
        assertEquals(listOf("a", "renamed", "c"), map.entries.keys.toList())
        assertEquals(2, (map.entries["renamed"] as YNode.Scalar).value)
    }

    @Test
    fun `rename rejects duplicates blanks and missing keys`() {
        val map = mapOfKeys("a" to 1, "b" to 2)
        assertFalse(renameMapKey(map, "a", "b"), "既存キーへの改名は拒否")
        assertFalse(renameMapKey(map, "a", "  "), "空白は拒否")
        assertFalse(renameMapKey(map, "zzz", "x"), "存在しないキーは拒否")
        assertFalse(renameMapKey(map, "a", "a"), "同名は何もしない")
        assertEquals(listOf("a", "b"), map.entries.keys.toList())
    }

    @Test
    fun `rename keeps comments attached to the value`() {
        val map = mapOfKeys("a" to 1)
        map.entries["a"]!!.ensureComments().before.add("説明")
        assertTrue(renameMapKey(map, "a", "b"))
        assertEquals(listOf<String?>("説明"), map.entries["b"]!!.comments?.before?.toList())
    }
}
