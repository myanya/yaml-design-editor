package dev.toros.yde.semantic

import dev.toros.yde.semantic.TermComposition.Change
import dev.toros.yde.semantic.TermComposition.KeySpace
import dev.toros.yde.semantic.TermComposition.Priority
import dev.toros.yde.semantic.TermComposition.Problem
import dev.toros.yde.semantic.TermComposition.Record
import dev.toros.yde.semantic.TermComposition.Source
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.getAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermCompositionTest {
    private fun record(id: String = "field", text: String = "{key: title, label: 元の名称}", scope: String? = null) =
        Record(id, YamlIO.parseFirst(text) as YNode.Map, Source("derived-$id"), scope?.let { KeySpace(it, listOf("key")) })
    private fun change(value: Any?, by: String, priority: Priority = Priority.RULE, target: String = "field", path: List<Any> = listOf("label")) =
        Change(target, path, YamlIO.fromAny(value), priority, Source(by, DocumentId("input/$by.yaml"), listOf("set", path.lastOrNull() ?: "root")))
    private fun value(result: TermComposition.Result, id: String = "field", path: List<Any> = listOf("label")): Any? =
        YamlIO.toAny(assertNotNull(getAt(assertNotNull(result.records).single { it.id == id }.value, path)))

    @Test
    fun `異なる規則の既定値は競合し 個別指定があれば全候補を残して解決する`() {
        val input = record()
        val changes = listOf(change("版", "a"), change("更新回数", "b"))
        val conflict = TermComposition.compose(listOf(input), emptyList(), changes)
        assertNull(conflict.records)
        assertEquals(listOf(Problem.CONFLICT), conflict.issues.map { it.problem })
        assertTrue(conflict.decisions.single().selected.isEmpty())
        val resolved = TermComposition.compose(listOf(input), emptyList(), changes + change("改訂番号", "c", Priority.INDIVIDUAL))
        assertEquals(emptyList(), resolved.issues)
        assertEquals("改訂番号", value(resolved))
        assertEquals(listOf("a", "b", "c"), resolved.decisions.single().candidates.map { it.source.id })
        assertEquals(listOf("c"), resolved.decisions.single().selected.map { it.source.id })
        assertEquals("元の名称", YamlIO.toAny(input.value.entries.getValue("label")))
    }

    @Test
    fun `異なる個別指定は最後の指定で上書きせず 同じ値なら根拠をすべて残す`() {
        val a = change("版", "a", Priority.INDIVIDUAL)
        val b = change("更新回数", "b", Priority.INDIVIDUAL)
        assertNull(TermComposition.compose(listOf(record()), emptyList(), listOf(a, b)).records)
        val same = b.copy(value = a.value.deepCopy())
        val result = TermComposition.compose(listOf(record()), emptyList(), listOf(same, a))
        assertEquals("版", value(result))
        assertEquals(listOf("a", "b"), result.decisions.single().selected.map { it.source.id })
        assertEquals(DocumentId("input/a.yaml"), result.decisions.single().selected.first().source.document)
        assertEquals(listOf("set", "label"), result.decisions.single().selected.first().source.path)
    }

    @Test
    fun `false ゼロ 空文字 空配列 null は未指定と区別し 値の型を推測しない`() {
        for (specified in listOf(false, 0, "", emptyList<Any>(), null)) {
            val result = TermComposition.compose(listOf(record()), emptyList(), listOf(change(specified, "a", path = listOf("default"))))
            assertEquals(emptyList(), result.issues)
            assertEquals(specified, value(result, path = listOf("default")))
            assertTrue(assertNotNull(result.records).single().value.entries.containsKey("default"))
        }
        val conflict = TermComposition.compose(listOf(record()), emptyList(), listOf(change(0, "a"), change("0", "b")))
        assertNull(conflict.records)
        assertEquals(Problem.CONFLICT, conflict.issues.single().problem)
        val untouched = TermComposition.compose(listOf(record()), emptyList(), emptyList())
        assertTrue("default" !in assertNotNull(untouched.records).single().value.entries)
    }

    @Test
    fun `同じ規則値はマップのキー順に依存せずまとめ すべての出どころを残す`() {
        val changes = listOf(change(linkedMapOf("a" to 1, "b" to false), "a"), change(linkedMapOf("b" to false, "a" to 1), "b"))
        val result = TermComposition.compose(listOf(record()), emptyList(), changes.reversed())
        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("a", "b"), result.decisions.single().selected.map { it.source.id })
        assertEquals(YamlIO.dumpPlain(assertNotNull(result.records).single().value),
            YamlIO.dumpPlain(assertNotNull(TermComposition.compose(listOf(record()), emptyList(), changes).records).single().value))
    }

    @Test
    fun `新しい追加にも補正でき 対象が無ければ今回の全結果を渡さない`() {
        val added = record("new", "{key: version, default: 0}")
        val patch = change(1, "correction", Priority.INDIVIDUAL, target = "new", path = listOf("default"))
        val result = TermComposition.compose(listOf(record()), listOf(added), listOf(patch))
        assertEquals(1, value(result, "new", listOf("default")))
        val missing = TermComposition.compose(listOf(record()), emptyList(), listOf(patch, change("変更済み", "other")))
        assertNull(missing.records)
        assertEquals(Problem.MISSING_TARGET, missing.issues.single().problem)
    }

    @Test
    fun `追加の ID と同じ範囲のキーは 値が同じでも重複とする`() {
        val input = record(scope = "owner")
        val sameId = TermComposition.compose(listOf(input), listOf(input.copy(source = Source("addition"))), emptyList())
        assertNull(sameId.records)
        assertTrue(sameId.issues.any { it.problem == Problem.DUPLICATE_ID })
        val duplicate = record("new", scope = "owner")
        assertTrue(TermComposition.compose(listOf(input), listOf(duplicate), emptyList()).issues.any { it.problem == Problem.DUPLICATE_KEY })
        val separate = duplicate.copy(keySpace = KeySpace("different-case", listOf("key")))
        assertNotNull(TermComposition.compose(listOf(input), listOf(separate), emptyList()).records)
        val distinct = record("new", "{key: other}", "owner")
        val renamed = TermComposition.compose(listOf(input), listOf(distinct), listOf(change("title", "rename", target = "new", path = listOf("key"))))
        assertNull(renamed.records)
        assertEquals(Problem.DUPLICATE_KEY, renamed.issues.single().problem)
    }

    @Test
    fun `親子の重なる変更と存在しない親と不正な配列位置は誤りにする`() {
        val input = record(text = "{item: {maxLength: 100}, values: [a]}")
        val parent = change(mapOf("maxLength" to 200), "a", path = listOf("item"))
        val child = change(50, "b", Priority.INDIVIDUAL, path = listOf("item", "maxLength"))
        val overlap = TermComposition.compose(listOf(input), emptyList(), listOf(parent, child))
        assertNull(overlap.records)
        assertEquals(Problem.OVERLAPPING_PATH, overlap.issues.single().problem)
        for (path in listOf(listOf("missing", "maxLength"), listOf("values", -1), listOf("values", 1), emptyList())) {
            val result = TermComposition.compose(listOf(input), emptyList(), listOf(change(200, "a", path = path)))
            assertNull(result.records)
            assertTrue(result.issues.any { it.problem == Problem.INVALID_PATH })
        }
    }

    @Test
    fun `入れ子の補正は元の木を変えず 深さを省略しない`() {
        var node: YNode = YamlIO.fromAny(mapOf("maxLength" to 100))
        repeat(16) { node = YamlIO.fromAny(mapOf("item" to YamlIO.toAny(node))) }
        val input = Record("deep", node as YNode.Map, Source("source"))
        val path = List<Any>(16) { "item" } + "maxLength"
        val result = TermComposition.compose(listOf(input), emptyList(), listOf(change(200, "patch", target = "deep", path = path)))
        assertEquals(200, value(result, "deep", path))
        assertEquals(100, YamlIO.toAny(assertNotNull(getAt(input.value, path))))
        (result.decisions.single().selected.single().value as YNode.Scalar).value = 300
        assertEquals(200, value(result, "deep", path), "根拠のノードと結果のノードも共有しない")
    }
}
