package dev.toros.yde.desktop.ui.form

import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefScope
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument

/**
 * 参照先の名前の解決（FormCtx.refTargetTitle）のテスト。参照欄とテーブルの参照セルが共用する。
 * 未保存の編集を含む自文書の ID（localIds）から引き、idPatterns と scope で絞ることを検証する（セルの見た目は手動確認）。
 */
class RefTargetTitleTest {

    private fun newDoc(): OpenDocument =
        OpenDocument(Path.of("dummy.yaml"), YNode.Map(linkedMapOf("to" to YNode.Scalar("SCR-001"))), null, "to: SCR-001\n", null)

    private fun entry(id: String, title: String?, schemaId: String? = null): IdEntry =
        IdEntry(id, DocumentId("docs/a.yaml"), emptyList(), title, schemaId, "docs/a.yaml")

    private val refField = FieldDef(key = "to", type = FieldType.REF)

    @Test
    fun `未保存の編集を含む自文書の ID から参照先の名前を引く`() {
        val ctx = FormCtx(AppState(), newDoc(), listOf(entry("SCR-001", "ログイン画面")))
        assertEquals("ログイン画面", ctx.refTargetTitle(refField, "SCR-001"))
    }

    @Test
    fun `idPatterns と scope に合わない出現からは引かない`() {
        val ctx = FormCtx(AppState(), newDoc(), listOf(entry("TBL-001", "顧客", schemaId = "table-design")))
        assertNull(ctx.refTargetTitle(refField.copy(idPatterns = listOf("SCR-*")), "TBL-001"))
        assertNull(ctx.refTargetTitle(refField.copy(scope = RefScope(schemaIds = listOf("screen-list"))), "TBL-001"))
        val allowed = refField.copy(idPatterns = listOf("TBL-*"), scope = RefScope(schemaIds = listOf("table-design")))
        assertEquals("顧客", ctx.refTargetTitle(allowed, "TBL-001"))
    }

    @Test
    fun `名前が無い・ID と同じ・解決できない・空の ID は null`() {
        val ctx = FormCtx(AppState(), newDoc(), listOf(entry("SCR-001", null), entry("SCR-002", "SCR-002")))
        assertNull(ctx.refTargetTitle(refField, "SCR-001"))
        assertNull(ctx.refTargetTitle(refField, "SCR-002"))
        assertNull(ctx.refTargetTitle(refField, "SCR-999"))
        assertNull(ctx.refTargetTitle(refField, ""))
    }
}
