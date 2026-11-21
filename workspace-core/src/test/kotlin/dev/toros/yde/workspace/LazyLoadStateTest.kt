package dev.toros.yde.workspace

import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 開くときに全文書を書き出さない・検証しない（UX-301・UX-302）。
 * 保存基準のテキストと構造の検証の結果は、読まれたときに文書ごとに計算し、結果は初めから計算したときと同じ。
 */
class LazyLoadStateTest {

    private val a = DocumentId("items/a.yaml")
    private val b = DocumentId("items/b.yaml")
    private val broken = DocumentId("items/broken.yaml")

    private fun newStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                root:
                  type: object
                  fields:
                    - { key: id, type: id }
                    - { key: title, type: string, required: true }
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ntitle: A\n",
            "items/b.yaml" to "id: IT-002\ntitle:   B\n",
            "items/broken.yaml" to "id: IT-003\n",
            "notes/free.yaml" to "memo: スキーマの無い文書\n",
        )
    )

    private fun cells(state: WorkspaceDerivedState) = state.structuralIssues as StructuralIssueMap

    private fun setTitle(session: WorkspaceSession, id: DocumentId, value: String, requireValid: Boolean = false) =
        session.apply(
            WorkspaceTransaction(
                baseVersion = session.version,
                intent = ChangeIntent("タイトル変更"),
                operations = listOf(WorkspaceOperation.EditDocument(id, listOf(YamlOperation.SetScalar(parseYPath("title"), value)))),
            ),
            requireValid = requireValid,
        )

    @Test
    fun `読み込みは構造を検証せず 読まれた文書だけをそのときに検証する`() {
        val ws = WorkspaceReader.load(newStore())
        val issues = cells(ws.derived)
        // キーは検証の対象（スキーマの割当がある文書）で、文書の順。値はまだ計算していない
        val oracle = ws.snapshot.documents.mapNotNull { (id, d) -> WorkspaceReader.validateOne(d, ws.schemas.associateBy { it.id })?.let { id to it } }.toMap()
        assertEquals(oracle.keys.toList(), issues.keys.toList())
        assertFalse(DocumentId("notes/free.yaml") in issues, "スキーマの無い文書は検証の対象でない")
        assertTrue(issues.keys.none { assertNotNull(issues.cell(it)).isInitialized() }, "読み込みでは検証しない")
        // 1 件を読むと、その文書だけを検証する
        assertTrue(issues.getValue(broken).any { "title" in it.message }, "必須の欠落が出る: ${issues[broken]}")
        assertTrue(assertNotNull(issues.cell(broken)).isInitialized())
        assertFalse(assertNotNull(issues.cell(a)).isInitialized(), "読んでいない文書は検証しないまま")
        // 全部を読めば、初めから全件を検証したときと同じ
        assertEquals(oracle, issues)
    }

    @Test
    fun `読み込みは保存基準を書き出さず 編集していない文書は書き出さずに未保存でないと決まる`() {
        val store = newStore()
        val s = WorkspaceSession(store) { Instant.EPOCH }
        assertTrue(s.snapshot.documents.values.all { it.matchesPersisted }, "読み込んだ木が保存基準の元")
        assertTrue(s.dirtyDocuments().isEmpty())
        // 表記のゆれ（title:   B）があっても、読み込んだだけなら未保存でない。保存基準は正規化した文字
        assertEquals("id: IT-002\ntitle: B\n", s.snapshot.documents.getValue(b).persistedText)

        assertTrue(setTitle(s, a, "A2").ok)
        assertFalse(s.snapshot.documents.getValue(a).matchesPersisted, "編集した文書は比べて決める")
        assertEquals(setOf(a), s.dirtyDocuments())
        // 触れていない文書の検証の結果は、計算していない入れ物のまま引き継ぐ
        assertFalse(assertNotNull(cells(s.derived).cell(b)).isInitialized())

        // 保存は編集した文書だけを書く（開いただけの文書を書き出して比べない）。保存の後は、その木が保存基準の元になる
        assertEquals(listOf(a), s.save().written)
        assertEquals("id: IT-001\ntitle: A2\n", store.read(a)?.text)
        assertEquals("id: IT-002\ntitle:   B\n", store.read(b)?.text, "編集していない文書は元の表記のまま")
        assertTrue(s.snapshot.documents.getValue(a).matchesPersisted)
        assertTrue(s.dirtyDocuments().isEmpty())

        // 元の値へ戻す編集は、木は別物でも書き出しが同じなので未保存でない
        assertTrue(setTitle(s, b, "B").ok)
        assertFalse(s.snapshot.documents.getValue(b).matchesPersisted)
        assertFalse(s.isDirty(b), "保存基準（正規化した文字）と同じ内容")
        // 戻す（Undo）と未保存に戻り、保存基準はそのまま
        assertTrue(setTitle(s, a, "A3").ok)
        assertTrue(s.isDirty(a))
        assertTrue(s.undo().ok)
        assertFalse(s.isDirty(a))
    }

    @Test
    fun `検証つきの適用は 開いたときに検証していない文書の誤りでも止まる`() {
        val s = WorkspaceSession(newStore()) { Instant.EPOCH }
        assertTrue(cells(s.derived).keys.none { assertNotNull(cells(s.derived).cell(it)).isInitialized() })
        // broken は必須の title が無い。別の文書への変更でも、全文書の誤りで適用を止める意味は変わらない
        val rejected = setTitle(s, a, "A2", requireValid = true)
        assertFalse(rejected.ok)
        assertTrue("検証エラーのため適用しません" in rejected.message.orEmpty(), rejected.message)
        assertEquals("A", (s.snapshot.documents.getValue(a).document.documents.first() as dev.toros.yde.yaml.model.YNode.Map)
            .entries.getValue("title").let { (it as dev.toros.yde.yaml.model.YNode.Scalar).value })
        // 誤りを直してからなら通る
        assertTrue(setTitle(s, broken, "C").ok)
        assertTrue(setTitle(s, a, "A2", requireValid = true).ok)
        assertEquals(s.fullDerived().structuralIssues, s.derived.structuralIssues)
    }
}
