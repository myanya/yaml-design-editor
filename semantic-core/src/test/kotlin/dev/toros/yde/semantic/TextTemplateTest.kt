package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文字のひな形（レポート。R30-04）: 穴・形・繰り返し・条件・文字の中括弧。タグだけの行は消え、埋まらない穴は名前が残る。
 */
class TextTemplateTest {

    private val values = YamlIO.parse(
        """
        month: 2026-10
        total: 69990
        when: 2026-10-26
        sales:
          cells:
            - { column: 文具, value: 20990 }
            - { column: 消耗品, value: 18400 }
        orders: []
        tags: { a: 1, b: 2 }
        flag: false
        """.trimIndent(),
    )

    @Test
    fun `穴は値の一式のパスで埋まり 形で数と日付をそろえる`() {
        val r = TextTemplate.render("# {month} の売上は {total:#,##0} 円（{when:yyyy/MM/dd}）{{注}}", values)
        assertEquals("# 2026-10 の売上は 69,990 円（2026/10/26）{注}", r.text)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `繰り返しはタグだけの行を消して表の行を作り 条件は空の配列や false で中を出さない`() {
        val template = """
            |表|
            |---|
            {#each sales.cells}
            | {position}. {column} | {value:#,##0} |
            {/each}
            {#if orders}
            （注文あり）
            {/if}
            {#if flag}
            （フラグ）
            {/if}
            {#each tags}{key}={value};{/each}
            終わり
        """.trimIndent()
        val r = TextTemplate.render(template, values)
        assertEquals("|表|\n|---|\n| 1. 文具 | 20,990 |\n| 2. 消耗品 | 18,400 |\na=1;b=2;\n終わり", r.text)
    }

    @Test
    fun `繰り返しの more は後ろにまだ要素があるときだけ真で 並びを区切れる`() {
        val r = TextTemplate.render("{#each sales.cells}{column}{#if more}・{/if}{/each} / {#each tags}{key}{#if more}, {/if}{/each}", values)
        assertEquals("文具・消耗品 / a, b", r.text)
        val one = TextTemplate.render("[{#each xs}{.}{#if more}・{/if}{/each}]", YamlIO.parse("xs: [only]"))
        assertEquals("[only]", one.text)
    }

    @Test
    fun `表のセルの形は 縦棒と改行を逃がして Markdown の表の行を崩さない`() {
        val cell = YamlIO.parse("memo: \"a | b\\nc\\r\\nd\"\nplain: \"x|y\"")
        val r = TextTemplate.render("| {memo:cell} | {plain} |", cell)
        assertEquals("| a \\| b<br>c<br>d | x|y |", r.text)
        // 形を書かない穴はそのまま（ひな形の書き手が決める）
        assertEquals("a | b\nc\r\nd", TextTemplate.render("{memo}", cell).text)
    }

    @Test
    fun `空の文字の穴は空のまま書き YAML の引用符を出さない`() {
        // 以前は空の文字が YAML の書き出しに回り、Markdown の表に '' が出た（UX-220）
        val input = YamlIO.fromAny(mapOf("empty" to "", "spaces" to " \n "))
        val r = TextTemplate.render("[{empty}]|{empty:cell}|[{spaces}]|{spaces:cell}|{empty:#,##0}", input)
        assertEquals("[]||[ \n ]| <br> |", r.text)
    }

    @Test
    fun `SQL の文字列の形は引用符を二重にして囲み 値の見た目から型を変えない`() {
        val input = YamlIO.fromAny(mapOf("quote" to "O'Brien", "empty" to "", "number" to "001", "flag" to false, "body" to "a\nb\\c{d}", "spaces" to " \n "))
        val r = TextTemplate.render("{quote:sql-string}|{empty:sql-string}|{number:sql-string}|{flag:sql-string}|{body:sql-string}|{spaces:sql-string}", input)
        assertEquals("'O''Brien'|''|'001'|'false'|'a\nb\\c{d}'|' \n '", r.text)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `埋まらない穴は空になり名前が残る 閉じ忘れは点検で分かる`() {
        val r = TextTemplate.render("{month}/{nothing}/{sales.values.合計}", values)
        assertEquals("2026-10//", r.text)
        assertEquals(listOf("nothing", "sales.values.合計"), r.missing)
        assertNull(TextTemplate.check("{#each a}{x}{/each}"))
        assertNotNull(TextTemplate.check("{#each a}{x}"))
        assertNotNull(TextTemplate.check("{#if a}{x}{/each}"))
        assertNotNull(TextTemplate.check("{/if}"))
    }
}
