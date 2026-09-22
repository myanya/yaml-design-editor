package dev.toros.yde.workspace

import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.DefaultYamlCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * セッションの fork（P27 段D の D-d・D-e）: 読み込み済みの状態から別のセッションを起こす。
 * 開き直したセッションと同じ中身になること・ストアを読み直さないこと・片方の編集がもう片方へ漏れないことを固定する。
 */
class SessionForkTest {

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
                      required: true
                    - key: link
                      type: ref
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ntitle: A\n",
            "items/b.yaml" to "id: IT-002\ntitle: B\nlink: IT-404\n",
            "items/c.yaml" to "id: IT-003\n",
        )
    )

    /** 読み取りの回数を数えるストア（fork がストアを読み直さないことの確認用）。 */
    private class CountingStore(private val inner: DocumentStore) : DocumentStore by inner {
        var reads = 0
        override fun list(): List<StoredDocument> = inner.list().also { reads++ }
        override fun read(id: DocumentId): StoredContent? = inner.read(id).also { reads++ }
    }

    private fun session(store: DocumentStore) = WorkspaceSession(store) { Instant.EPOCH }

    private fun setTitle(session: WorkspaceSession, id: DocumentId, value: String) = session.apply(
        WorkspaceTransaction(
            session.version, ChangeIntent("タイトル変更"),
            listOf(WorkspaceOperation.EditDocument(id, listOf(YamlOperation.SetScalar(parseYPath("title"), value)))),
        )
    )

    private fun titleOf(session: WorkspaceSession, id: DocumentId): String? =
        (session.snapshot.documents.getValue(id).document.documents.first() as YNode.Map).entries["title"]
            ?.let { (it as YNode.Scalar).value?.toString() }

    /** セッションの中身を、比べられる文字列にする（文書の内容・構造の指摘・ID・参照・意味の指摘）。 */
    private fun describe(s: WorkspaceSession): String = buildString {
        appendLine("version=${s.version} layout=${s.layout}")
        appendLine("schemas=${s.schemas.map { it.id }} errors=${s.schemaErrors}")
        for ((id, d) in s.snapshot.documents) appendLine("doc ${id.value}: ${DefaultYamlCodec.dump(d.document)}")
        for ((id, issues) in s.derived.structuralIssues) appendLine("structural ${id.value}: ${issues.map { it.message }}")
        appendLine("ids=${s.derived.semantic.ids.mapValues { (_, v) -> v.map { it.document.value } }}")
        appendLine("refs=${s.derived.semantic.refs.map { "${it.document.value}->${it.targetId}" }}")
        appendLine("issues=${s.derived.semantic.issues.map { it.message }}")
    }

    @Test
    fun `forkしたセッションは開き直したセッションと同じ中身でストアを読み直さない`() {
        val store = CountingStore(newStore())
        val origin = session(store)
        val readsAfterOpen = store.reads
        assertTrue(readsAfterOpen > 0)

        val forked = origin.fork()

        assertEquals(readsAfterOpen, store.reads, "fork はストアを読まない")
        assertEquals(describe(session(store)), describe(forked))
        assertTrue(describe(forked).contains("IT-404"), "指摘（未解決の参照）も引き継ぐ: ${describe(forked)}")
        assertTrue(forked.history.isEmpty(), "履歴は引き継がない")
        assertTrue(forked.dirtyDocuments().isEmpty())
        assertTrue(forked.externalChanges().isEmpty(), "外部変更の基準（ストアの文面）も引き継ぐ")
    }

    @Test
    fun `片方の編集はもう片方へ漏れない`() {
        val origin = session(newStore())
        val forked = origin.fork()
        val before = describe(origin)

        assertTrue(setTitle(forked, a, "編集用で変更").ok)
        assertEquals("編集用で変更", titleOf(forked, a))
        assertEquals("A", titleOf(origin, a), "fork の編集は、元のセッションへ漏れない")
        assertEquals(before, describe(origin))
        assertFalse(origin.isDirty(a))
        assertTrue(forked.isDirty(a))

        assertTrue(setTitle(origin, b, "元で変更").ok)
        assertEquals("B", titleOf(forked, b), "元のセッションの編集も、fork へ漏れない")
        assertTrue(forked.undo().ok)
        assertEquals("A", titleOf(forked, a))
        assertEquals("元で変更", titleOf(origin, b))
    }

    @Test
    fun `forkで保存した内容は元のセッションが外部変更として取り込める`() {
        val origin = session(newStore())
        val forked = origin.fork()
        assertTrue(setTitle(forked, a, "保存する").ok)
        assertTrue(forked.save().ok)

        assertEquals(listOf(a), origin.externalChanges(), "同じストアを見ているので、保存は外部変更として見える")
        assertTrue(origin.noteStoreChanges(listOf(a)).ok)
        assertEquals("保存する", titleOf(origin, a))
        assertEquals(describe(forked).lines().drop(1), describe(origin).lines().drop(1), "取り込んだ後は、版の番号を除いて同じ中身")
    }

    @Test
    fun `未保存の変更を持つセッションからはforkできない`() {
        val origin = session(newStore())
        assertTrue(setTitle(origin, a, "未保存").ok)
        assertFailsWith<IllegalArgumentException> { origin.fork() }
        assertTrue(origin.save().ok)
        assertEquals("未保存", titleOf(origin.fork(), a))
    }
}
