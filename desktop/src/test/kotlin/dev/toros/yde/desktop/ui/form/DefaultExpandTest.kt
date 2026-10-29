package dev.toros.yde.desktop.ui.form

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument

/**
 * ネスト構造の既定展開深さ（`FormCtx.defaultExpanded`）のテスト。
 * 畳み込みの描画そのものは手動確認（プロジェクト方針）だが、
 * 「どの深さまで既定で開くか」はモード・埋め込みに依存する純ロジックとして検証する。
 */
class DefaultExpandTest {

    private fun newDoc(): OpenDocument =
        OpenDocument(Path.of("dummy.yaml"), YNode.Map(linkedMapOf("a" to YNode.Scalar("1"))), null, "a: 1\n", null)

    @Test
    fun `ルートから2階層までは開き3階層目からは畳む`() {
        val ctx = FormCtx(AppState(), newDoc(), emptyList())
        assertTrue(ctx.defaultExpanded(listOf("sections")))
        assertTrue(ctx.defaultExpanded(listOf("sections", 0)))
        assertFalse(ctx.defaultExpanded(listOf("sections", 0, "steps")))
        assertFalse(ctx.defaultExpanded(listOf("sections", 0, "steps", 1)))
    }

    @Test
    fun `既定展開の深さは編集・閲覧専用モードで同じ`() {
        val doc = newDoc()
        val editing = FormCtx(AppState(), doc, emptyList(), readOnly = false)
        val reading = FormCtx(AppState(), doc, emptyList(), readOnly = true)
        for (path in listOf<List<Any>>(listOf("a"), listOf("a", 0), listOf("a", 0, "b"))) {
            assertEquals(editing.defaultExpanded(path), reading.defaultExpanded(path), "path=$path")
        }
    }

    @Test
    fun `埋め込みフォームは基準パスからの相対深さで判定する`() {
        // ID横ぐしの埋め込みフォーム: 文書ルートから深い要素でも、その要素を基準に数える
        val ctx = FormCtx(AppState(), newDoc(), emptyList(), baseDepth = 2)
        assertTrue(ctx.defaultExpanded(listOf("groups", 3, "members")))
        assertTrue(ctx.defaultExpanded(listOf("groups", 3, "members", 0)))
        assertFalse(ctx.defaultExpanded(listOf("groups", 3, "members", 0, "tags")))
    }
}
