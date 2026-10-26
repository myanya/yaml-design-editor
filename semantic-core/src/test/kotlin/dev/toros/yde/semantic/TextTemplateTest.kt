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
