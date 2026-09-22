package dev.toros.yde.app.ui

import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.FieldType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 閲覧専用モード（R10-04）のテスト。
 * UIの表示分岐は手動確認（プロジェクト方針）だが、「編集経路（markEdited）に到達できない」
 * という完了条件は FormCtx の純ロジックとして検証する。
 */
class ReadOnlyTest {

    private fun newDoc(): OpenDocument =
        OpenDocument(Path.of("dummy.yaml"), YNode.Map(linkedMapOf("a" to YNode.Scalar("1"))), null, "a: 1\n", null)

    @Test
    fun `閲覧専用モードでは markEdited に到達できない`() {
        val doc = newDoc()
        val ctx = FormCtx(AppState(), doc, emptyList(), readOnly = true)
        assertFailsWith<IllegalStateException> { ctx.markEdited() }
        assertEquals(0, doc.version)
    }

    @Test
    fun `閲覧専用モードではスカラー書込がツリー変異前に拒否される`() {
        val doc = newDoc()
        val ctx = FormCtx(AppState(), doc, emptyList(), readOnly = true)
        val slot = MapSlot(doc.root as YNode.Map, "a")
        assertFailsWith<IllegalStateException> { ctx.setScalar(slot, "書き換え", FieldType.STRING) }
        assertFailsWith<IllegalStateException> { ctx.setGenericScalar(slot, "書き換え") }
        // ツリーは変異していない（markEdited より手前で止まる）
        assertEquals("1", ((doc.root as YNode.Map).entries["a"] as YNode.Scalar).value)
        assertTrue(!doc.dirty)
    }

    @Test
    fun `通常モードでは従来どおり編集できる`() {
        val doc = newDoc()
        val ctx = FormCtx(AppState(), doc, emptyList(), readOnly = false)
        ctx.setScalar(MapSlot(doc.root as YNode.Map, "a"), "書き換え", FieldType.STRING)
        assertEquals("書き換え", ((doc.root as YNode.Map).entries["a"] as YNode.Scalar).value)
        assertTrue(doc.version > 0)
    }

    @Test
    fun `readOnly の既定値は AppState の閲覧専用モードに追随する`() {
        val state = AppState()
        state.readOnlyMode = true
        try {
            val ctx = FormCtx(state, newDoc(), emptyList())
            assertTrue(ctx.readOnly)
            assertFailsWith<IllegalStateException> { ctx.markEdited() }
        } finally {
            state.readOnlyMode = false
        }
    }
}
