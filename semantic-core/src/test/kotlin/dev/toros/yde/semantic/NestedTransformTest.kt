package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NestedTransformTest {
    private val document = DocumentId("model.yaml")
    private fun def(build: String) = TransformDef.parse(YamlIO.parseToRaw("id: nested\nbuild:\n" + build.trimIndent().prependIndent("  ")))

    @Test fun `親の名前を読みながら可変長の子配列を平らにし両方の原本位置を保つ`() {
        val transform = def("""
            edges:
              ${'$'}each: tables
              ${'$'}bind: table
              ${'$'}flatten: true
              ${'$'}build:
                ${'$'}each: refs
                ${'$'}build:
                  ${'$'}when: {path: enabled, equals: true}
                  from: {${'$'}copy: id, ${'$'}of: table}
                  to: {${'$'}copy: to}
                  detail: {${'$'}copy: .}
        """)
        val input = YamlIO.parse("""
            tables:
              - {id: A, refs: []}
              - {id: B, refs: [{to: A, enabled: false}, {to: C, enabled: true}]}
              - {id: C}
              - {id: D, refs: [{to: A, enabled: true}, {to: B, enabled: true}, {to: C, enabled: true}]}
        """.trimIndent())
        val before = YamlIO.dumpPlain(input)
        val result = SemanticTransform.run(transform, input, document)
        val edges = (YamlIO.toAny(result.term!!) as Map<*, *>)["edges"] as List<*>
        assertEquals(listOf("B" to "C", "D" to "A", "D" to "B", "D" to "C"), edges.map {
            val edge = it as Map<*, *>
            edge["from"] to edge["to"]
        })
        assertTrue(TransformOrigin(listOf("edges", 0, "from"), document, listOf("tables", 1, "id")) in result.origins)
        assertTrue(TransformOrigin(listOf("edges", 3, "to"), document, listOf("tables", 3, "refs", 2, "to")) in result.origins)
        assertTrue(TransformOrigin(listOf("edges", 3, "detail"), document, listOf("tables", 3, "refs", 2)) in result.origins)
        assertEquals(YamlIO.dumpPlain(result.term), YamlIO.dumpPlain(SemanticTransform.run(transform, input, document).term!!))
        assertEquals(before, YamlIO.dumpPlain(input))
    }

    @Test fun `配列全体のコピーも平坦化後の番号へ来歴を移し既定では入れ子を保つ`() {
        val input = YamlIO.parse("groups: [{items: [a, b]}, {items: []}, {items: [c]}]")
        val flat = def("${'$'}each: groups\n${'$'}flatten: true\n${'$'}build: {${'$'}copy: items}")
        val result = SemanticTransform.run(flat, input, document)
        assertEquals(listOf("a", "b", "c"), YamlIO.toAny(result.term!!))
        assertEquals(listOf(
            TransformOrigin(listOf(0), document, listOf("groups", 0, "items", 0)),
            TransformOrigin(listOf(1), document, listOf("groups", 0, "items", 1)),
            TransformOrigin(listOf(2), document, listOf("groups", 2, "items", 0)),
        ), result.origins)
        val nested = def("${'$'}each: groups\n${'$'}build: {${'$'}copy: items}")
        assertEquals(listOf(listOf("a", "b"), emptyList<String>(), listOf("c")), YamlIO.toAny(SemanticTransform.run(nested, input).term!!))
        assertFailsWith<IllegalStateException> {
            SemanticTransform.run(flat, YamlIO.parse("groups: [{items: 1}]"))
        }
    }

    @Test fun `局所名は内側で隠せて外側へ戻り条件と値の式からも読める`() {
        val transform = def("""
            ${'$'}each: parents
            ${'$'}bind: item
            ${'$'}build:
              before: {${'$'}copy: name, ${'$'}of: item}
              children:
                ${'$'}each: children
                ${'$'}bind: item
                ${'$'}build: {${'$'}copy: name, ${'$'}of: item}
              after: {${'$'}copy: name, ${'$'}of: item}
              values:
                ${'$'}each: children
                ${'$'}build:
                  ${'$'}when: {of: item, path: active, equals: true}
                  ${'$'}then: {${'$'}value: {add: [{of: item, path: base}, {path: amount}]}}
        """)
        val input = YamlIO.parse("parents: [{name: 外, active: true, base: 10, children: [{name: 内, amount: 2}]}]")
        assertEquals(YamlIO.parseToRaw("[{before: 外, children: [内], after: 外, values: [12]}]"),
            YamlIO.toAny(SemanticTransform.run(transform, input).term!!))
    }

    @Test fun `マップの繰り返しにも局所名を付けられる`() {
        val transform = def("""
            ${'$'}eachEntry: entries
            ${'$'}bind: entry
            ${'$'}build:
              ${'$'}each: values
              ${'$'}build: {${'$'}copy: label, ${'$'}of: entry}
        """)
        val result = SemanticTransform.run(transform, YamlIO.parse("entries: {a: {label: A, values: [1, 2]}}"))
        assertEquals(mapOf("a" to listOf("A", "A")), YamlIO.toAny(result.term!!))
    }

    @Test fun `局所名の範囲外参照と誤った修飾子は定義時に拒む`() {
        listOf(
            "a: {${'$'}each: items, ${'$'}bind: item, ${'$'}build: {${'$'}copy: .}}\nb: {${'$'}copy: id, ${'$'}of: item}",
            "${'$'}each: items\n${'$'}bind: ''\n${'$'}build: 1",
            "${'$'}each: items\n${'$'}bind: index\n${'$'}build: 1",
            "${'$'}each: items\n${'$'}flatten: yes-please\n${'$'}build: []",
            "${'$'}eachEntry: items\n${'$'}flatten: true\n${'$'}build: []",
            "${'$'}each: items\n${'$'}bind: item\n${'$'}build: {${'$'}copy: id, ${'$'}of: item, ${'$'}in: {schemaId: another}}",
        ).forEach { build -> assertFailsWith<RuntimeException>(build) { def(build) } }
        assertFailsWith<IllegalArgumentException> {
            TransformDef.parse(YamlIO.parseToRaw("""
                id: scoped
                build: {${'$'}each: items, ${'$'}bind: item, ${'$'}apply: child}
                rules:
                  - id: child
                    build: {${'$'}copy: name, ${'$'}of: item}
            """.trimIndent()))
        }
    }
}
