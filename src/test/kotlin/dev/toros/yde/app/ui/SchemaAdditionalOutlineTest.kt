package dev.toros.yde.app.ui

import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import java.nio.file.Path
import kotlin.test.*

class SchemaAdditionalOutlineTest {
    @Test
    fun `操作の初期設定と送信方式を子まで選んで編集できる`() {
        val root = root("actions: [{id: edit, input: {submit: changed, initial: {title: {${'$'}copy: title, ${'$'}of: self}}}}]")
        val items = outline(root)
        val submit = items.single { it.path == listOf("actions", 0, "input", "submit") }
        assertEquals(FieldType.ENUM, submit.field?.type)
        val copy = items.single { it.path == listOf("actions", 0, "input", "initial", "title", "${'$'}copy") }
        schemaSlot(root, copy.path)!!.set(YNode.Scalar("summary"))
        assertEquals("summary", (getAt(root, copy.path) as YNode.Scalar).value)
        assertEquals("ひな型", SchemaValueForms.groupFor(copy.path.dropLast(1)))
    }
    private val meta = BuiltinSchemas.all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }
    private fun outline(root: YNode.Map) = schemaAdditionalOutline(root, meta, false)
    private fun root(text: String) = YamlIO.parse(text) as YNode.Map

    @Test
    fun `操作の入力条件手順と未知の区画の全子項目へ原本パスで到達できる`() {
        val root = root("""
            actions:
              - id: start
                label: 着手
                target: {entity: todo}
                params:
                  - {key: note, type: enum, values: [A, B]}
                require: {not: {equals: {path: status, value: DONE}}}
                do: [{set: {path: status, value: ACTIVE}}]
            constraints: [{check: {exists: {path: title}}}]
            future: {nested: [{value: 5}]}
        """.trimIndent())
        val before = YamlIO.dumpPlain(root)
        val items = outline(root)
        val byPath = items.associateBy { it.path }
        assertEquals(items.size, byPath.size)
        fun check(node: YNode, path: YPath) {
            val item = assertNotNull(byPath[path], path.toString())
            assertSame(node, item.node)
            assertSame(node, schemaSlot(root, path)?.get())
            assertNotNull(item.field)
            assertEquals(path.take(1), schemaNavigationTarget(path).base)
            if (path.size > 1) {
                assertEquals(path.dropLast(1), item.parent)
                assertEquals(byPath.getValue(item.parent!!).depth + 1, item.depth)
            }
            when (node) {
                is YNode.Map -> node.entries.forEach { (key, child) -> check(child, path + key) }
                is YNode.Seq -> node.items.forEachIndexed { index, child -> check(child, path + index) }
                else -> Unit
            }
        }
        root.entries.forEach { (key, value) -> check(value, listOf(key)) }
        assertEquals("着手", byPath.getValue(listOf("actions", 0)).title)
        assertEquals(FieldType.ENUM, byPath.getValue(listOf("actions", 0, "params", 0, "type")).field?.type)
        assertEquals(before, YamlIO.dumpPlain(root))
    }

    @Test
    fun `メタ定義に新しい複合区画を足すだけで子項目の型と階層が反映される`() {
        val extended = SchemaLoader.parseSchemaText("""
            id: extended-meta
            root:
              type: object
              fields:
                - key: future
                  type: array
                  item: {${'$'}ref: entry}
            definitions:
              entry:
                type: object
                fields:
                  - {key: count, type: int, label: 件数}
                  - {key: settings, type: map, value: {type: boolean}}
                  - {key: absent, type: object, fields: [{key: child, type: string}]}
        """.trimIndent(), Path.of("extended.yaml"))
        val root = root("future: [{count: 2, settings: {enabled: true}}]")
        val before = YamlIO.dumpPlain(root)
        val items = schemaAdditionalOutline(root, extended, false)
        val count = items.single { it.path == listOf("future", 0, "count") }
        assertEquals("件数", count.title)
        assertEquals(FieldType.INT, count.field?.type)
        assertEquals(FieldType.BOOLEAN, items.single { it.path == listOf("future", 0, "settings", "enabled") }.field?.type)
        assertNull(items.single { it.path == listOf("future", 0, "absent") }.node)
        assertFalse(items.any { it.path.lastOrNull() == "child" })
        assertEquals(before, YamlIO.dumpPlain(root))
    }

    @Test
    fun `すべての追加区画は共通のツリーに入り未作成の子は生成しない`() {
        val root = root("kind: library\nfilePattern: {format: 'details/{id}'}")
        for (library in listOf(false, true)) {
            val fields = schemaAdditionalFields(meta.tree, root, library)
            val items = schemaAdditionalOutline(root, meta, library)
            assertEquals(fields.mapNotNull { it.key }, items.filter { it.parent == null }.map { it.path.single() as String })
            for (field in fields.filter { it.key !in root.entries }) {
                assertEquals(1, items.count { it.path.firstOrNull() == field.key })
            }
        }
    }

    @Test
    fun `操作と入力の並べ替え及びUndoで子の選択が同じIDとキーへ追随する`() {
        val root = root("actions: [{id: a}, {id: b, params: [{key: x}, {key: y, type: string}]}]")
        val original = YamlIO.dumpPlain(root)
        val previous = outline(root)
        val actions = root.entries["actions"] as YNode.Seq
        actions.items.add(0, actions.items.removeAt(1))
        val params = getAt(root, listOf("actions", 0, "params")) as YNode.Seq
        params.items.add(0, params.items.removeAt(1))
        val moved = outline(root)
        val path = listOf("actions", 1, "params", 1, "type")
        val newPath = schemaAdditionalSelection(moved, path, previous)
        assertEquals(listOf("actions", 0, "params", 0, "type"), newPath)
        assertEquals(path, schemaAdditionalSelection(outline(root(original)), newPath, moved))
    }

    @Test
    fun `選択中の操作を削除したときは同じ添字の別操作へ移らず親へ戻る`() {
        val root = root("actions: [{id: a}, {id: b}]")
        val previous = outline(root)
        (root.entries["actions"] as YNode.Seq).items.removeAt(0)
        val path = listOf("actions", 0, "id")
        assertEquals(listOf("actions"), schemaAdditionalSelection(outline(root), path, previous))
        assertEquals(listOf("actions"), schemaAdditionalSelection(outline(root("actions: [{id: b}]")), path, previous))
        assertEquals(listOf("actions"), schemaAdditionalSelection(outline(root("{}")), path, previous))
    }

    @Test
    fun `重複IDではUndoの選択を勝手に別の要素へ移さない`() {
        val previous = outline(root("actions: [{id: same, label: A}, {id: same, label: B}]"))
        val now = outline(root("actions: [{id: same, label: B}, {id: same, label: A}]"))
        val path = listOf("actions", 0, "label")
        assertEquals(path, schemaAdditionalSelection(now, path, previous))
    }

    @Test
    fun `自由配列の要素を置き換えても選択を保ち削除したら親へ戻る`() {
        val root = root("future: [one, two]")
        val before = outline(root)
        val path = listOf("future", 0)
        schemaSlot(root, path)!!.set(YNode.Map())
        val replaced = outline(root)
        assertEquals(path, schemaAdditionalSelection(replaced, path, before))
        schemaSlot(root, path)!!.remove()
        assertEquals(listOf("future"), schemaAdditionalSelection(outline(root), path, replaced))
    }
}
