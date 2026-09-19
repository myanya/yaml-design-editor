package schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 定義体の仕組み（SP-12。P29 Phase 6 第1段）のテスト: 関数のような定義・語彙の定義・基本の検査への展開。
 * ここは YAML を読んだままの値（Map / List）の上の操作だけを確かめる（検査の意味は知らない）。
 */
class CheckDefinitionsTest {

    private val definitions = mapOf(
        "functions" to listOf(
            mapOf(
                "id" to "referenced-at-least",
                "params" to listOf("target", "relation", "min", "note"),
                "checks" to listOf(
                    mapOf(
                        "description" to "\$note",
                        "match" to listOf(mapOf("node" to mapOf("entity" to "\$target", "bind" to "t"))),
                        "count" to mapOf("min" to "\$min"),
                        "message" to "「\$relation」の被参照が \$min 件に足りません（費用は \$\$100）",
                    ),
                ),
            ),
        ),
        "vocabulary" to listOf(
            mapOf(
                "word" to "mustBeUsed",
                "in" to "mustBeUsed[]",
                "uses" to listOf(
                    mapOf(
                        "when" to "by",
                        "use" to "referenced-at-least",
                        "with" to mapOf(
                            "target" to mapOf("from" to "type"),
                            "relation" to mapOf("from" to "by"),
                            "min" to mapOf("from" to "atLeast", "default" to 1),
                            "note" to mapOf("schema" to "id"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val library = CheckLibrary.parse(definitions, "defs.yaml")

    @Test
    fun `定義を名前で使う検査は渡した値が差し込まれて基本の検査へ展開される`() {
        val schema = mapOf(
            "constraints" to listOf(
                mapOf("match" to listOf(mapOf("node" to mapOf("entity" to "a"))), "expect" to "exists"),
                mapOf("use" to "referenced-at-least", "with" to mapOf("target" to "usecase", "relation" to "uses", "min" to 2)),
            ),
        )
        val expanded = library.expand(schema, "s1")
        assertEquals(2, expanded.size)
        // プリミティブを直接書いた検査は、そのまま通る
        assertEquals("exists", expanded[0].raw["expect"])
        assertEquals("constraints[0]", expanded[0].origin)
        val check = expanded[1].raw
        // 文字列がちょうど $名前 なら値そのもの（数値は数値のまま）・文中の $名前 は文字列として埋まる・$$ は $
        assertEquals(mapOf("min" to 2), check["count"])
        assertEquals(listOf(mapOf("node" to mapOf("entity" to "usecase", "bind" to "t"))), check["match"])
        assertEquals("「uses」の被参照が 2 件に足りません（費用は \$100）", check["message"])
        // 渡さなかった値は「無い」（null）として差し込まれる
        assertTrue(check.containsKey("description"))
        assertNull(check["description"])
        assertEquals("constraints[1]（定義 referenced-at-least）", expanded[1].origin)
    }

    @Test
    fun `語彙は書かれた内容から定義の使用へ対応づけられて展開される`() {
        val schema = mapOf(
            "mustBeUsed" to listOf(
                mapOf("type" to "usecase", "by" to "uses", "atLeast" to 3),
                mapOf("type" to "screen", "by" to "shows"),
                mapOf("type" to "ignored"),  // when のキー（by）が無い → この対応は使われない
            ),
        )
        val expanded = library.expand(schema, "my-schema")
        assertEquals(2, expanded.size)
        assertEquals(mapOf("min" to 3), expanded[0].raw["count"])
        assertEquals(mapOf("min" to 1), expanded[1].raw["count"], "書かれていなければ default")
        assertEquals("my-schema", expanded[0].raw["description"], "{schema: id} は語彙が書かれたスキーマのID")
        assertEquals("mustBeUsed[1]（定義 referenced-at-least）", expanded[1].origin)
        assertEquals(setOf("mustBeUsed"), library.topLevelKeys)
    }

    @Test
    fun `語彙の値の指定は入れ子と読み替えを扱い全てが無いマップは無いものとする`() {
        val lib = CheckLibrary.parse(
            mapOf(
                "functions" to listOf(
                    mapOf("id" to "f", "params" to listOf("op", "where"), "checks" to listOf(mapOf("op" to "\$op", "in" to "\$where"))),
                ),
                "vocabulary" to listOf(
                    mapOf(
                        "word" to "w", "in" to "w[]",
                        "uses" to listOf(
                            mapOf(
                                "use" to "f",
                                "with" to mapOf(
                                    "op" to mapOf("from" to "assert", "default" to "equals", "map" to mapOf("equals" to "eq", "lte" to "lte")),
                                    "where" to mapOf("schemaId" to mapOf("from" to "sel.schemaId"), "fileGlob" to mapOf("from" to "sel.fileGlob")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "x.yaml",
        )
        val expanded = lib.expand(
            mapOf("w" to listOf(mapOf("assert" to "lte", "sel" to mapOf("schemaId" to "s")), mapOf("other" to 1))),
            "s",
        )
        assertEquals("lte", expanded[0].raw["op"])
        assertEquals(mapOf("schemaId" to "s", "fileGlob" to null), expanded[0].raw["in"])
        // 省略時は default を読み替える（equals → eq）。schemaId も fileGlob も無ければ、指定そのものが無い
        assertEquals("eq", expanded[1].raw["op"])
        assertNull(expanded[1].raw["in"])
    }

    @Test
    fun `定義の誤りは読み込み時に見つかる`() {
        // ひな型が参照する名前は params に宣言されていること
        val undeclared = assertFailsWith<IllegalStateException> {
            CheckLibrary.parse(
                mapOf("functions" to listOf(mapOf("id" to "f", "params" to listOf("a"), "checks" to listOf(mapOf("x" to "\$b"))))),
                "x.yaml",
            )
        }
        assertTrue("\$b" in (undeclared.message ?: ""), undeclared.message)
        // 見つからない定義・宣言にない受け取る値
        val missing = assertFailsWith<IllegalStateException> {
            library.expand(mapOf("constraints" to listOf(mapOf("use" to "nothing"))), "s")
        }
        assertTrue("nothing" in (missing.message ?: ""), missing.message)
        val extra = assertFailsWith<IllegalStateException> {
            library.expand(mapOf("constraints" to listOf(mapOf("use" to "referenced-at-least", "with" to mapOf("typo" to 1)))), "s")
        }
        assertTrue("typo" in (extra.message ?: ""), extra.message)
        // 定義の名前の重複は暗黙に上書きしない
        assertFailsWith<IllegalStateException> { library + library }
    }
}
