package dev.toros.yde.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 編集画面のタブ（フォーム・ビュー・ソース・差分）の決め方。文書を開いたときのタブ（opening）・その文書で出せるタブへの直し（fit）・
 * ジャンプの着地（landing）は純粋な関数で、AppState の文書を開く経路もそれを通ることを、文書ビューのあるワークスペースで確かめる。
 */
class EditorTabTest {

    @Test
    fun `開いたときのタブ 覚えが無ければ直前のタブから決め フォームの仲間ならビューが当たればビュー`() {
        fun open(previous: EditorTab, hasView: Boolean, remembered: EditorTab? = null, schema: Boolean = false, parsed: Boolean = true) =
            EditorTab.opening(remembered, previous, parsed, schema, hasView)
        assertEquals(EditorTab.VIEW, open(EditorTab.FORM, hasView = true), "直前がフォームでも、ビューが当たればビュー")
        assertEquals(EditorTab.VIEW, open(EditorTab.VIEW, hasView = true))
        assertEquals(EditorTab.FORM, open(EditorTab.VIEW, hasView = false), "ビューが当たらなければフォーム")
        assertEquals(EditorTab.SOURCE, open(EditorTab.SOURCE, hasView = true), "ソースはそのまま")
        assertEquals(EditorTab.DIFF, open(EditorTab.DIFF, hasView = true), "差分はそのまま")
        assertEquals(EditorTab.FORM, open(EditorTab.META_FORM, hasView = false), "メタスキーマのフォームはスキーマの文書だけ")
        assertEquals(EditorTab.META_FORM, open(EditorTab.META_FORM, hasView = false, schema = true))
        // 覚えがあればそれ（明示的に選んだフォームは、ビューが当たってもフォーム）。出せないタブは直す
        assertEquals(EditorTab.FORM, open(EditorTab.VIEW, hasView = true, remembered = EditorTab.FORM))
        assertEquals(EditorTab.FORM, open(EditorTab.SOURCE, hasView = false, remembered = EditorTab.VIEW), "ビューが無くなった文書はフォーム")
        assertEquals(EditorTab.SOURCE, open(EditorTab.FORM, hasView = true, parsed = false), "解析できない文書はソース")
        // スキーマの文書にビューのタブは無い
        assertEquals(EditorTab.FORM, EditorTab.fit(EditorTab.VIEW, schemaDocument = true, hasView = true))
    }

    @Test
    fun `ジャンプの着地 フォームの仲間はそのまま ソースは選べる それ以外はフォームの仲間の既定`() {
        assertEquals(EditorTab.VIEW, EditorTab.landing(EditorTab.VIEW, schemaDocument = false, hasView = true))
        assertEquals(EditorTab.FORM, EditorTab.landing(EditorTab.FORM, schemaDocument = false, hasView = true), "フォームを見ていればフォーム")
        assertEquals(EditorTab.SOURCE, EditorTab.landing(EditorTab.SOURCE, schemaDocument = false, hasView = true))
        assertEquals(EditorTab.VIEW, EditorTab.landing(EditorTab.SOURCE, schemaDocument = false, hasView = true, keepSource = false))
        assertEquals(EditorTab.VIEW, EditorTab.landing(EditorTab.DIFF, schemaDocument = false, hasView = true))
        assertEquals(EditorTab.FORM, EditorTab.landing(EditorTab.DIFF, schemaDocument = false, hasView = false))
        assertEquals(EditorTab.FORM, EditorTab.landing(EditorTab.VIEW, schemaDocument = false, hasView = false), "ビューが無くなればフォーム")
    }

    private val dir: Path = Files.createTempDirectory("editor-tab-test")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(rel: String, text: String): Path =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text.trimIndent() + "\n") }

    @Test
    fun `文書ビューが当たる文書はビューで開き 当たらない文書はフォームで開き 明示したタブは覚える`() {
        write("_schemas/item.yaml", """
            id: item
            match: "items/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string }
        """)
        write("_schemas/note.yaml", """
            id: note
            match: "notes/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
        """)
        write("_views/item-doc.yaml", """
            id: item-doc
            name: 項目の定義書
            kind: document
            schemaId: item
            sections:
              - { fields: { include: [name] } }
        """)
        write("_views/item-doc2.yaml", """
            id: item-doc2
            name: 項目の一覧書
            kind: document
            match: "items/*.yaml"
            sections:
              - { fields: {} }
        """)
        val item = write("items/A.yaml", "id: A\nname: 項目A")
        val note = write("notes/N.yaml", "id: N")
        val state = AppState()
        state.openWorkspace(dir)

        state.openFile(item)
        assertEquals(listOf("item-doc", "item-doc2"), state.docViewsFor(item).map { it.id }, "当たるビューを定義ファイル名順に")
        assertEquals(EditorTab.VIEW, state.editorTab, "初めて開く文書にビューが当たればビュー")
        state.openFile(note)
        assertTrue(state.docViewsFor(note).isEmpty())
        assertEquals(EditorTab.FORM, state.editorTab, "当たらなければフォーム")

        // 明示的にフォームを選んだ文書は、ビューが当たってもフォームで開き直す
        state.openFile(item)
        state.editorTab = EditorTab.FORM
        state.openFile(note)
        state.openFile(item)
        assertEquals(EditorTab.FORM, state.editorTab)

        // ジャンプの着地: ビューを見ていればビューのまま、差分からはビュー
        state.editorTab = EditorTab.VIEW
        state.navigateToPath(listOf("name"))
        assertEquals(EditorTab.VIEW, state.editorTab)
        state.editorTab = EditorTab.DIFF
        state.landOnForm()
        assertEquals(EditorTab.VIEW, state.editorTab)
    }
}
