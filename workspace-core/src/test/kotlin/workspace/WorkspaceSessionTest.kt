package workspace

import edit.YamlOperation
import model.DocumentId
import model.parseYPath
import validate.Severity
import yaml.DefaultYamlCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WorkspaceSession（Phase 8・WS-004）の契約テスト。計画書 Phase 8 の必須テスト群と
 * 「保存とUndo/Redoの独立」シナリオ（§5）を InMemoryDocumentStore で固定する。
 */
class WorkspaceSessionTest {

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
            "items/b.yaml" to "id: IT-002\ntitle: B\nlink: IT-001\n",
        )
    )

    private fun session(store: DocumentStore) = WorkspaceSession(store) { Instant.EPOCH }

    private fun setTitle(session: WorkspaceSession, id: DocumentId, value: String, base: Long) =
        session.apply(
            WorkspaceTransaction(
                baseVersion = base,
                intent = ChangeIntent("タイトル変更"),
                operations = listOf(
                    WorkspaceOperation.EditDocument(id, listOf(YamlOperation.SetScalar(parseYPath("title"), value)))
                ),
            )
        )

    private fun titleOf(session: WorkspaceSession, id: DocumentId): String? {
        val d = session.snapshot.documents.getValue(id)
        return (d.document.documents.first() as model.YNode.Map).entries["title"]
            ?.let { (it as model.YNode.Scalar).value?.toString() }
    }

    @Test
    fun `applyはworking snapshotだけを更新しストアに書かない`() {
        val store = newStore()
        val s = session(store)
        assertEquals(0, store.writeCount)
        val result = setTitle(s, a, "A2", base = 0)
        assertTrue(result.ok, result.message ?: "")
        assertEquals(0, store.writeCount, "apply しても DocumentStore は変化しない")
        assertEquals("id: IT-001\ntitle: A\n", store.read(a)?.text)
        assertEquals("A2", titleOf(s, a))
        assertTrue(s.isDirty(a))
        assertEquals(1, s.version)
        assertEquals(1, s.derived.sourceVersion, "派生状態が新しい version に紐付く")
    }

    @Test
    fun `saveしたときだけストアが変わり履歴は消えない`() {
        val store = newStore()
        val s = session(store)
        setTitle(s, a, "A2", base = 0)
        val save = s.save()
        assertTrue(save.ok)
        assertEquals(listOf(a), save.written)
        assertTrue("A2" in (store.read(a)?.text ?: ""), "save でストアが更新される")
        assertFalse(s.isDirty(a))
        assertTrue(s.canUndo(), "save しても履歴が消えない")
        assertTrue(s.undo().ok)
        assertEquals("A", titleOf(s, a))
        assertTrue(s.isDirty(a), "undo で保存済み内容から離れたので dirty")
        assertEquals(Instant.EPOCH, s.history.firstOrNull()?.occurredAt ?: Instant.EPOCH, "時刻は注入で固定")
    }

    @Test
    fun `複数文書トランザクションは途中失敗で部分変更を公開しない`() {
        val store = newStore()
        val s = session(store)
        val before = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        val result = s.apply(
            WorkspaceTransaction(
                baseVersion = 0,
                intent = ChangeIntent("途中失敗"),
                operations = listOf(
                    WorkspaceOperation.EditDocument(a, listOf(YamlOperation.SetScalar(parseYPath("title"), "変更後"))),
                    // 既存キーへの Insert は失敗する（Patcher の契約）
                    WorkspaceOperation.EditDocument(b, listOf(YamlOperation.Insert(parseYPath("title"), model.YNode.Scalar("x")))),
                ),
            )
        )
        assertFalse(result.ok)
        assertEquals(0, s.version, "失敗したトランザクションは version を進めない")
        assertEquals(before, DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document), "a の変更も公開されない")
        assertEquals(0, s.derived.sourceVersion)
    }

    @Test
    fun `古いbaseVersionのトランザクションは拒否される`() {
        val s = session(newStore())
        assertTrue(setTitle(s, a, "A2", base = 0).ok)
        val stale = setTitle(s, a, "A3", base = 0)
        assertFalse(stale.ok)
        assertTrue("baseVersion" in (stale.message ?: ""), "${stale.message}")
        assertEquals("A2", titleOf(s, a))
    }

    @Test
    fun `undoとredoのたびに派生状態が対応versionへ更新される`() {
        val s = session(newStore())
        setTitle(s, a, "A2", base = 0)
        assertEquals(1, s.derived.semantic.sourceVersion)
        assertTrue(s.undo().ok)
        assertEquals(2, s.version)
        assertEquals(2, s.derived.semantic.sourceVersion, "undo 後の semantic snapshot は新 version")
        assertEquals("A", titleOf(s, a))
        assertTrue(s.redo().ok)
        assertEquals(3, s.version)
        assertEquals(3, s.derived.semantic.sourceVersion)
        assertEquals("A2", titleOf(s, a))
    }

    @Test
    fun `保存とUndoRedoの独立シナリオが成立する`() {
        val s = session(newStore())
        // 初期A → apply B → apply C
        assertTrue(setTitle(s, a, "B1", base = 0).ok)
        assertTrue(setTitle(s, a, "B2", base = 1).ok)
        assertTrue(s.undo().ok)
        assertEquals("B1", titleOf(s, a))
        assertTrue(s.redo().ok)
        assertEquals("B2", titleOf(s, a))
        // save（履歴はそのまま）
        assertTrue(s.save().ok)
        assertFalse(s.isDirty(a))
        // apply D → dirty
        assertTrue(setTitle(s, a, "B3", base = s.version).ok)
        assertTrue(s.isDirty(a))
        // undo → C（保存済みと一致 = dirty false）
        assertTrue(s.undo().ok)
        assertEquals("B2", titleOf(s, a))
        assertFalse(s.isDirty(a), "保存済み内容と一致するので dirty ではない")
        // さらに undo → B（dirty true）
        assertTrue(s.undo().ok)
        assertEquals("B1", titleOf(s, a))
        assertTrue(s.isDirty(a))
        // 新しい編集で redo が破棄される
        assertTrue(setTitle(s, a, "E", base = s.version).ok)
        assertFalse(s.canRedo(), "undo 後の新規編集で redo スタックは破棄される")
    }

    @Test
    fun `削除はsaveまでストアに残りundoで保存済みリンクごと戻る`() {
        val store = newStore()
        val s = session(store)
        val delete = s.apply(
            WorkspaceTransaction(0, ChangeIntent("削除"), listOf(WorkspaceOperation.DeleteDocument(b)))
        )
        assertTrue(delete.ok)
        assertNull(s.snapshot.documents[b], "working からは消える")
        assertTrue(store.read(b) != null, "save まではストアに残る")
        assertTrue(b in s.dirtyDocuments(), "削除予約は dirty 扱い")
        // undo で内容も保存済みリンクも戻る
        assertTrue(s.undo().ok)
        assertEquals("B", titleOf(s, b))
        assertFalse(s.isDirty(b), "persistedText が復元されるので dirty ではない")
        assertTrue(s.dirtyDocuments().isEmpty())
        // redo → save でストアから消える
        assertTrue(s.redo().ok)
        val save = s.save()
        assertEquals(listOf(b), save.deleted)
        assertNull(store.read(b))
    }

    @Test
    fun `新規作成はスキーマ割当つきで追加されsaveで書かれる`() {
        val store = newStore()
        val s = session(store)
        val doc = DefaultYamlCodec.parse(DocumentId("items/c.yaml"), "id: IT-003\ntitle: C\n")
        val create = s.apply(
            WorkspaceTransaction(
                0, ChangeIntent("新規作成"),
                listOf(WorkspaceOperation.CreateDocument(DocumentId("items/c.yaml"), doc)),
            )
        )
        assertTrue(create.ok, create.message ?: "")
        val created = s.snapshot.documents.getValue(DocumentId("items/c.yaml"))
        assertEquals("fx-item", created.schemaId, "match によるスキーマ割当")
        assertTrue(created.inLedger)
        assertTrue(s.isDirty(DocumentId("items/c.yaml")), "未保存の新規文書は dirty")
        assertEquals(0, store.writeCount)
        assertTrue(s.save().ok)
        assertTrue("IT-003" in (store.read(DocumentId("items/c.yaml"))?.text ?: ""))
    }

    @Test
    fun `MoveDocumentは割当再評価つきで移動しundoで完全に戻る`() {
        val store = newStore()
        val s = session(store)
        val to = DocumentId("notes/a.yaml")
        val move = s.apply(
            WorkspaceTransaction(0, ChangeIntent("移動"), listOf(WorkspaceOperation.MoveDocument(a, to)))
        )
        assertTrue(move.ok, move.message ?: "")
        assertNull(s.snapshot.documents[a])
        val moved = s.snapshot.documents.getValue(to)
        assertNull(moved.schemaId, "items/** の match から外れるので割当なし")
        assertEquals(0, store.writeCount, "save まではストアに触れない")
        assertTrue(a in s.dirtyDocuments() && to in s.dirtyDocuments())
        // undo で位置・スキーマ割当・保存済みリンクごと戻る
        assertTrue(s.undo().ok)
        assertEquals("fx-item", s.snapshot.documents.getValue(a).schemaId)
        assertTrue(s.dirtyDocuments().isEmpty(), "巻き戻しで削除予約も dirty も消える")
        // redo → save で「新パスへ書き・旧パスを削除」
        assertTrue(s.redo().ok)
        val save = s.save()
        assertTrue(save.ok)
        assertEquals(listOf(a), save.deleted)
        assertEquals(listOf(to), save.written)
        assertNull(store.read(a))
        assertTrue("IT-001" in (store.read(to)?.text ?: ""))
    }

    @Test
    fun `外部変更が検出できreloadで取り込める`() {
        val store = newStore()
        val s = session(store)
        assertTrue(s.externalChanges().isEmpty())
        // ローカル編集だけでは外部変更にならない
        setTitle(s, a, "ローカル編集", base = 0)
        assertTrue(s.externalChanges().isEmpty(), "dirty は外部変更ではない")
        // ストアを外部から書き換えると検出される
        store.write(a, "id: IT-001\ntitle: 外部変更\n")
        assertEquals(listOf(a), s.externalChanges())
        // reload で取り込まれる（履歴は破棄）
        assertTrue(s.reload().ok)
        assertEquals("外部変更", titleOf(s, a))
        assertTrue(s.externalChanges().isEmpty())
        assertFalse(s.canUndo())
    }

    @Test
    fun `requireValidは検証エラーになる適用を拒否する`() {
        val s = session(newStore())
        val removeTitle = WorkspaceTransaction(
            0, ChangeIntent("必須の削除"),
            listOf(WorkspaceOperation.EditDocument(a, listOf(YamlOperation.Remove(parseYPath("title"))))),
        )
        val rejected = s.apply(removeTitle, requireValid = true)
        assertFalse(rejected.ok)
        assertTrue("検証エラー" in (rejected.message ?: ""), "${rejected.message}")
        assertEquals(0, s.version)
        assertEquals("A", titleOf(s, a))
        // requireValid なしなら適用され、構造検証のERRORが派生状態に出る
        val applied = s.apply(removeTitle, requireValid = false)
        assertTrue(applied.ok)
        assertTrue(
            s.derived.structuralIssues.getValue(a).any { it.severity == Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
    }
}
