package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.AnyIndex
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 変換の定義が読む入力の場所（入力の足あと）: 定義を当てずに、$copy・$format の穴・$each の源・条件・規則・まとめから、
 * 名前の付いた入れ物（model）からのパスのパターンを取り出す。生成の系譜（設計書がどの概念から作られるか）に使う。
 */
class TransformReadsTest {
    private fun reads(text: String): List<String> {
        val def = TransformDef.parse(YamlIO.parseToRaw(text.trimIndent()), places = setOf("model", "element"))
        return TransformReads.of(def, mapOf("model" to emptyList(), "element" to listOf("actions", AnyIndex)), listOf("actions", AnyIndex))
    }

    @Test
    fun `繰り返しの中は源の要素 bind と model も読める`() {
        val r = reads(
            """
            id: t
            build:
              name: { ${'$'}format: "{label}（{id}）" }
              steps:
                ${'$'}each: effects
                ${'$'}bind: e
                ${'$'}build:
                  ${'$'}when: [{ path: op, equals: set }]
                  item: { ${'$'}copy: field.key }
                  rule:
                    ${'$'}each: constraints
                    ${'$'}of: model
                    ${'$'}build: { ${'$'}when: [{ path: id, equals: { ${'$'}copy: path, ${'$'}of: e } }], ${'$'}then: { ${'$'}copy: label } }
            """,
        )
        assertEquals(
            listOf(
                "actions[*].label", "actions[*].id", "actions[*].effects", "actions[*].effects[*].op", "actions[*].effects[*].field.key",
                "constraints", "constraints[*].id", "actions[*].effects[*].path", "constraints[*].label",
            ),
            r,
        )
    }

    @Test
    fun `規則は当てる場所で読み まとめは items をまとめた要素として読む`() {
        val r = reads(
            """
            id: t
            build:
              kinds: { ${'$'}each: params, ${'$'}apply: kind }
              groups:
                ${'$'}group: { ${'$'}copy: effects }
                ${'$'}by: { ${'$'}copy: entity }
                ${'$'}build: { name: { ${'$'}copy: key }, first: { ${'$'}copy: "items[0].path" } }
            rules:
              - id: kind
                when: [{ path: base, equals: date }]
                build: 日付
              - id: kind
                build: { ${'$'}copy: type }
            """,
        )
        assertTrue("actions[*].params[*].base" in r && "actions[*].params[*].type" in r, "同じ名前の規則はすべて読む: $r")
        assertTrue("actions[*].effects[*].entity" in r && "actions[*].effects[*].path" in r, "まとまりの items[n] はまとめた要素: $r")
        assertTrue(r.none { "key" in it }, "まとまりのキーは計算した値なので数えない: $r")
    }
}
