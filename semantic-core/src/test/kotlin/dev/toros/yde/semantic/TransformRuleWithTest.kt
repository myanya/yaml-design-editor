package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 規則へ値を渡す（`$apply` の `$with` と、規則の `with`）。規則は外側の繰り返しの名前を読めないので、
 * 入れ子をたどる規則が外側の事実（親の名前・親のキーの並び）を要るときに、当てる側が渡す。
 */
class TransformRuleWithTest {
    private val d = "$"

    private fun parse(text: String) = TransformDef.parse(YamlIO.parseToRaw(text))

    // 配列の項目ごとに表 1 つ。表のキーは、親のキーに自分の並び順を足したもの。行の中の配列は、さらに下の表
    private val tables = """
        rules:
          - id: tables
            with: [parent]
            when: [{ path: type, equals: array }]
            build:
              ${d}concat:
                - - name: { ${d}format: "{p}_{key}", ${d}with: { p: { ${d}copy: name, ${d}of: parent } } }
                    keys: { ${d}concat: [{ ${d}copy: keys, ${d}of: parent }, ["seq"]] }
                # 略記の渡す値は、繰り返しを書いた場所（この配列の項目）で計算する
                - ${d}each: item.fields
                  ${d}flatten: true
                  ${d}apply: tables
                  ${d}with:
                    parent:
                      name: { ${d}format: "{p}_{key}", ${d}with: { p: { ${d}copy: name, ${d}of: parent } } }
                      keys: { ${d}concat: [{ ${d}copy: keys, ${d}of: parent }, [{ ${d}format: "{key}_seq" }]] }
          - id: tables
            with: [parent]
            build: []
    """.trimIndent()

    private val input = YamlIO.parseFirst(
        """
        name: todo
        fields:
          - { key: title, type: string }
          - key: comments
            type: array
            item:
              fields:
                - { key: body, type: string }
                - { key: files, type: array, item: { fields: [{ key: path, type: string }] } }
          - { key: labels, type: array, item: { fields: [] } }
        """.trimIndent(),
    )!!

    @Test
    fun `入れ子をたどる規則に 親の名前とキーの並びを渡して 深さの決まっていない木から表を作れる`() {
        val def = parse(
            """
            id: t
            build:
              ${d}each: fields
              ${d}flatten: true
              ${d}apply: tables
              ${d}with:
                parent: { name: { ${d}copy: name }, keys: [{ ${d}format: "{name}_id" }] }
            """.trimIndent() + "\n" + tables,
        )
        val out = YamlIO.toAny(SemanticTransform.runAt(def, SemanticTransform.TemplatePlace(input, null, emptyList())).term!!)
        assertEquals(
            listOf(
                mapOf("name" to "todo_comments", "keys" to listOf("todo_id", "seq")),
                mapOf("name" to "todo_comments_files", "keys" to listOf("todo_id", "comments_seq", "seq")),
                mapOf("name" to "todo_labels", "keys" to listOf("todo_id", "seq")),
            ),
            out,
        )
    }

    @Test
    fun `ひな型の中に書いた $apply の渡す値は いまの場所で計算し 繰り返しの名前も読める`() {
        val def = parse(
            """
            id: t
            build:
              ${d}each: fields
              ${d}bind: f
              ${d}flatten: true
              ${d}build:
                ${d}apply: tables
                ${d}with:
                  parent: { name: { ${d}format: "{n}_x", ${d}with: { n: { ${d}copy: key, ${d}of: f } } }, keys: [k] }
            """.trimIndent() + "\n" + tables,
        )
        val out = YamlIO.toAny(SemanticTransform.runAt(def, SemanticTransform.TemplatePlace(input, null, emptyList())).term!!) as List<*>
        assertEquals(listOf("comments_x_comments", "comments_x_comments_files", "labels_x_labels"), out.map { (it as Map<*, *>)["name"] })
    }

    @Test
    fun `渡す名前と受け取る名前が合わない定義は誤り`() {
        val missing = assertFailsWith<IllegalArgumentException> {
            parse(
                """
                id: t
                build: { ${d}apply: r }
                rules:
                  - { id: r, with: [parent], build: { ${d}copy: name, ${d}of: parent } }
                """.trimIndent(),
            )
        }
        assertTrue("受け取る: parent" in missing.message!!, missing.message)
        val extra = assertFailsWith<IllegalArgumentException> {
            parse(
                """
                id: t
                build: { ${d}apply: r, ${d}with: { parent: x } }
                rules:
                  - { id: r, build: { ${d}copy: name } }
                """.trimIndent(),
            )
        }
        assertTrue("渡す: parent" in extra.message!!, extra.message)
        val differs = assertFailsWith<IllegalStateException> {
            parse(
                """
                id: t
                build: { ${d}apply: r, ${d}with: { parent: x } }
                rules:
                  - { id: r, with: [parent], when: [{ path: a, exists: true }], build: { ${d}copy: name, ${d}of: parent } }
                  - { id: r, build: x }
                """.trimIndent(),
            )
        }
        assertTrue("同じ名前の規則" in differs.message!!, differs.message)
        // 受け取っていない名前は、規則の中で読めない（外側の繰り返しの名前と同じ扱い）
        assertFailsWith<IllegalArgumentException> {
            parse(
                """
                id: t
                build: { ${d}each: xs, ${d}bind: x, ${d}apply: r }
                rules:
                  - { id: r, build: { ${d}copy: name, ${d}of: x } }
                """.trimIndent(),
            )
        }
        // 略記の渡す値は繰り返しの外側で計算するので、その繰り返しの $bind は読めない
        assertFailsWith<IllegalArgumentException> {
            parse(
                """
                id: t
                build: { ${d}each: xs, ${d}bind: x, ${d}apply: r, ${d}with: { p: { ${d}copy: name, ${d}of: x } } }
                rules:
                  - { id: r, with: [p], build: { ${d}copy: name, ${d}of: p } }
                """.trimIndent(),
            )
        }
        // $with は $apply か $format と一緒にだけ
        assertFailsWith<IllegalStateException> { parse("id: t\nbuild: { ${d}each: xs, ${d}build: { a: 1 }, ${d}with: { p: 1 } }") }
    }

    @Test
    fun `渡した値の写しは 入力の足あとに 元の場所として数える`() {
        val def = parse(
            """
            id: t
            build:
              ${d}each: entities
              ${d}bind: e
              ${d}build:
                ${d}each: fields
                ${d}apply: column
                ${d}with: { owner: { ${d}copy: ., ${d}of: e } }
            rules:
              - id: column
                with: [owner]
                build: { ${d}format: "{o}.{key}", ${d}with: { o: { ${d}copy: id, ${d}of: owner } } }
            """.trimIndent(),
        )
        val reads = TransformReads.of(def, emptyMap(), emptyList())
        assertTrue("entities[*].id" in reads, reads.toString())
        assertTrue("entities[*].fields[*].key" in reads, reads.toString())
    }

    // 並びの番号が、入力の別の場所にある番号の一覧に入っているか（MZ の System.json の skillTypes と magicSkills。UX-239）
    @Test
    fun `渡した一覧に 自分の番号があるかを 規則の中の条件で調べられる`() {
        val def = parse(
            """
            id: t
            build:
              ${d}each: skillTypes
              ${d}apply: skillType
              ${d}with: { magic: { ${d}copy: magicSkills } }
            rules:
              - id: skillType
                when: [{ path: ., empty: false }]
                with: [magic]
                build:
                  name: { ${d}copy: . }
                  magic: { ${d}when: { of: magic, includes: ${d}index }, ${d}then: true }
            """.trimIndent(),
        )
        val out = SemanticTransform.runAt(
            def,
            SemanticTransform.TemplatePlace(YamlIO.parseFirst("skillTypes: ['', 術, 技, 祈り]\nmagicSkills: [1, 3]")!!, null, emptyList()),
        ).term!!
        assertEquals(
            listOf(mapOf("name" to "術", "magic" to true), mapOf("name" to "技"), mapOf("name" to "祈り", "magic" to true)),
            YamlIO.toAny(out),
            "一覧に無い番号の要素には、条件つきの項目ごと出ない",
        )
    }
}
