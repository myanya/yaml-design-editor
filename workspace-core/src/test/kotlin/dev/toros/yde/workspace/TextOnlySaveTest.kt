package dev.toros.yde.workspace

import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.model.DocumentId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「壊れたまま保存」（P43 R43-04）の契約: `saveAsText` はテキストをそのまま書いて文書集合から外し（索引・検証の対象外）、
 * 履歴は消さない。`attachDocument` で戻すと保存済みの内容との差で dirty になり、保存で木の内容が書かれる。
 */
class TextOnlySaveTest {
    private val a = DocumentId("items/a.yaml")
    private val b = DocumentId("items/b.yaml")

    private fun newStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: title
                      type: string
                    - key: link
                      type: ref
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ntitle: A\n",
            "items/b.yaml" to "id: IT-002\ntitle: B\nlink: IT-001\n",
        )
    )

    private fun session(store: DocumentStore) = WorkspaceSession(store) { Instant.EPOCH }

    @Test
    fun `saveAsTextはテキストを書いて文書集合から外し履歴を残す`() {
        val store = newStore()
        val s = session(store)
        s.recordEdit(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A2\n"))
        assertTrue(s.canUndo())
        assertTrue(s.derived.semantic.issues.isEmpty(), "b の参照 IT-001 は解決している")

        val broken = "id: IT-001\ntitle: [A2\n"
        assertTrue(s.saveAsText(a, broken).ok)
        assertEquals(broken, store.read(a)?.text, "壊れたテキストがそのまま書かれる")
        assertFalse(s.hasDocument(a), "文書集合から外れる")
        assertFalse(s.isDirty(a))
        assertTrue(s.dirtyDocuments().none { it == a })
        assertTrue(s.canUndo(), "履歴は消えない")
        assertTrue(s.derived.semantic.issues.any { "IT-001" in it.message }, "外れた文書の ID への参照は未解決になる: ${s.derived.semantic.issues}")
        assertTrue(s.externalChanges().isEmpty(), "自分で書いたテキストを外部変更と見ない")

        // 外れている間は、その文書に関わる記録は戻せない（セッションは壊れない）
        val undone = s.undo()
        assertFalse(undone.ok)
        assertFalse(s.hasDocument(a))
    }

    @Test
    fun `attachDocumentで戻すと保存済みとの差でdirtyになり保存で木の内容が書かれる`() {
        val store = newStore()
        val s = session(store)
        val broken = "id: IT-001\ntitle: [A2\n"
        assertTrue(s.saveAsText(a, broken).ok)

        val fixed = DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A2\n")
        assertTrue(s.attachDocument(a, fixed).ok)
        assertTrue(s.hasDocument(a))
        assertTrue(s.isDirty(a), "保存済み（壊れたテキスト）と違うので未保存")
        assertTrue(s.derived.semantic.issues.isEmpty(), "参照は再び解決する: ${s.derived.semantic.issues}")

        val saved = s.save(setOf(a))
        assertTrue(saved.ok)
        assertEquals("id: IT-001\ntitle: A2\n", store.read(a)?.text)
        assertFalse(s.isDirty(a))

        // 既に載っている文書への attach は置換と同じ
        assertTrue(s.attachDocument(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A3\n")).ok)
        assertTrue(s.isDirty(a))
    }

    @Test
    fun `acceptsDocumentは隠しパスを除く`() {
        val s = session(newStore())
        assertTrue(s.acceptsDocument(a))
        assertTrue(s.acceptsDocument(b))
        assertFalse(s.acceptsDocument(DocumentId(".hidden/x.yaml")))
        assertFalse(s.acceptsDocument(DocumentId("items/.draft.yaml")))
    }
}
