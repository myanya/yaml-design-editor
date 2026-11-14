package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.NameCase
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 生成で使う変換の書き方: 名前の書き方をそろえる `$case`・呼び出し側が渡す名前の付いた場所（`$of: model`）・
 * 条件の比べる値に別の場所の値（`{$copy: パス, $of: 名前}`）。
 */
class TransformPlacesTest {
    @Test
    fun `名前の書き方は機械的な変換だけ`() {
        assertEquals("completeTodo", NameCase.convert("complete-todo", "camel"))
        assertEquals("TodoTaskFinish", NameCase.convert("todo-task.finish", "pascal"))
        assertEquals("todo_task", NameCase.convert("todo-task", "snake"))
        assertEquals("in-progress", NameCase.convert("IN_PROGRESS", "kebab"))
        assertEquals("IN_PROGRESS", NameCase.convert("inProgress", "constant"))
        assertEquals("http_server", NameCase.convert("HTTPServer", "snake"))
        assertEquals("todo_001", NameCase.convert("TODO-001", "snake"))
        assertEquals(listOf("作業項目", "finish"), NameCase.words("作業項目.finish"), "英数字の外の文字は語の中に残す")
    }

    @Test
    fun `渡された場所を読み 条件で外側の要素と比べる`() {
        val def = TransformDef.parse(
            YamlIO.parseToRaw(
                """
                id: t
                build:
                  controller: { ${'$'}format: "{name}Controller", ${'$'}with: { name: { ${'$'}case: { ${'$'}copy: id }, ${'$'}as: pascal } } }
                  handlers:
                    ${'$'}each: actions
                    ${'$'}of: model
                    ${'$'}build:
                      ${'$'}when: [{ path: subject, equals: { ${'$'}copy: id, ${'$'}of: element } }]
                      method: { ${'$'}case: { ${'$'}copy: id }, ${'$'}as: camel }
                """.trimIndent(),
            ),
            places = setOf("model", "element"),
        )
        val model = YamlIO.parseFirst("actions: [{ id: complete-todo, subject: todo }, { id: add-tag, subject: tag }, { id: start-todo, subject: todo }]")!!
        val element = YamlIO.parseFirst("id: todo-task")!!
        val todo = YamlIO.parseFirst("id: todo")!!
        fun run(at: dev.toros.yde.yaml.model.YNode) = YamlIO.toAny(
            SemanticTransform.runAt(
                def, SemanticTransform.TemplatePlace(at, null, emptyList()),
                places = mapOf("model" to SemanticTransform.TemplatePlace(model, null, emptyList()), "element" to SemanticTransform.TemplatePlace(at, null, emptyList())),
            ).term!!,
        ) as Map<*, *>
        assertEquals(mapOf("controller" to "TodoController", "handlers" to listOf(mapOf("method" to "completeTodo"), mapOf("method" to "startTodo"))), run(todo))
        assertEquals(mapOf("controller" to "TodoTaskController", "handlers" to emptyList<Any>()), run(element))
    }

    @Test
    fun `渡さない場所の名前と 書けない書き方は定義の誤り`() {
        val raw = YamlIO.parseToRaw("id: t\nbuild: { v: { ${'$'}copy: id, ${'$'}of: model } }")
        assertTrue(assertFailsWith<IllegalArgumentException> { TransformDef.parse(raw) }.message!!.contains("from のクエリで付けた名前ではありません"))
        TransformDef.parse(raw, places = setOf("model"))
        val badCase = YamlIO.parseToRaw("id: t\nbuild: { v: { ${'$'}case: { ${'$'}copy: id }, ${'$'}as: title } }")
        assertTrue(assertFailsWith<IllegalStateException> { TransformDef.parse(badCase) }.message!!.contains("名前の書き方"))
        val badOperand = YamlIO.parseToRaw("id: t\nbuild: { v: { ${'$'}when: [{ path: a, equals: { ${'$'}first: [x] } }], ${'$'}then: 1 } }")
        assertTrue(assertFailsWith<IllegalArgumentException> { TransformDef.parse(badOperand) }.message!!.contains("比べる値に書ける場所の指示"))
    }
}
