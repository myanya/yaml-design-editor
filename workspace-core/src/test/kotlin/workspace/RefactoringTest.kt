package workspace

import model.DocumentId
import semantic.RefactoringRequest
import yaml.DefaultYamlCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 意味的リファクタリング（Phase 9・SemanticRefactor ＋ WorkspaceSession.applyRefactoring）の契約テスト。
 * 計画書 Phase 9 の必須テスト（plan は不変・全影響文書と原本座標・stale 拒否・参照切れなし・1回Undo）を固定する。
 */
class RefactoringTest {

    private val a = DocumentId("items/a.yaml")
    private val b = DocumentId("items/b.yaml")
    private val c = DocumentId("items/c.yaml")

    private fun newStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                entity:
                  type: item
                  idPattern: "IT-*"
                  defines: true
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
            "items/c.yaml" to "id: IT-003\ntitle: C\nlink: IT-001\n",
        )
    )

    private fun session(store: DocumentStore) = WorkspaceSession(store) { Instant.EPOCH }

    private fun dumpAll(s: WorkspaceSession): Map<String, String> =
        s.snapshot.documents.entries.associate { (id, d) -> id.value to DefaultYamlCodec.dump(d.document) }

    @Test
    fun `plan作成ではworkspaceが変化しない`() {
        val store = newStore()
        val s = session(store)
        val before = dumpAll(s)
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("IT-001", "IT-100"))
        assertEquals(0, s.version, "plan は version を進めない")
        assertEquals(before, dumpAll(s), "plan は文書を変えない")
        assertEquals(0, store.writeCount)
        assertEquals(0, plan.sourceVersion)
    }

    @Test
    fun `planは全影響文書と原本座標を持つ`() {
        val s = session(newStore())
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("IT-001", "IT-100"))
        assertEquals(listOf("items/a.yaml", "items/b.yaml", "items/c.yaml"), plan.changes.map { it.document.value })
        // a はIDフィールド、b / c は参照値の座標
        assertEquals(listOf<Any>("id"), (plan.changes[0].operations.single() as edit.YamlOperation.SetScalar).path)
        assertEquals(listOf<Any>("link"), (plan.changes[1].operations.single() as edit.YamlOperation.SetScalar).path)
        assertTrue(plan.summary.any { "出現 1 件" in it }, "${plan.summary}")
        assertTrue(plan.summary.any { "参照 2 件" in it }, "${plan.summary}")
        // idPattern（IT-*）逸脱の警告
        val off = s.planRefactoring(RefactoringRequest.RenameEntity("IT-001", "XX-100"))
        assertTrue(off.warnings.any { "idPattern" in it }, "${off.warnings}")
    }

    @Test
    fun `plan作成後にversionが変わったら適用を拒否する`() {
        val s = session(newStore())
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("IT-001", "IT-100"))
        // 別の編集で version が進む
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    0, ChangeIntent("割り込み編集"),
                    listOf(
                        WorkspaceOperation.EditDocument(
                            a, listOf(edit.YamlOperation.SetScalar(model.parseYPath("title"), "A2"))
                        )
                    ),
                )
            ).ok
        )
        val stale = s.applyRefactoring(plan)
        assertFalse(stale.ok)
        assertTrue("baseVersion" in (stale.message ?: ""), "${stale.message}")
    }

    @Test
    fun `適用後に参照切れがなく1回のUndoで全変更が戻る`() {
        val store = newStore()
        val s = session(store)
        val before = dumpAll(s)
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("IT-001", "IT-100"))
        val result = s.applyRefactoring(plan)
        assertTrue(result.ok, result.message ?: "")
        assertEquals(0, store.writeCount, "適用は working のみ（ファイルへ直接書かない）")
        // 参照切れなし・旧IDは消え新IDに2参照
        assertTrue(s.derived.semantic.issues.isEmpty(), "${s.derived.semantic.issues}")
        assertTrue("IT-001" !in s.derived.semantic.ids)
        assertEquals(2, s.derived.semantic.backlinksTo(setOf("IT-100")).size)
        // 1回の Undo で3文書すべて戻る
        assertTrue(s.undo().ok)
        assertEquals(before, dumpAll(s))
        assertTrue(s.derived.semantic.issues.isEmpty())
    }

    @Test
    fun `被参照が残るIDのdeleteは参照元一覧つきで拒否される`() {
        val s = session(newStore())
        val e = assertFailsWith<IllegalStateException> {
            s.planRefactoring(RefactoringRequest.DeleteEntity("IT-001"))
        }
        assertTrue("参照が 2 件" in (e.message ?: ""), "${e.message}")
        assertTrue("items/b.yaml" in (e.message ?: ""), "${e.message}")
    }

    private fun docRefStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/doc.yaml" to """
                id: fx-doc
                match: "docs/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: spec
                      type: ref
                      target: document
            """.trimIndent(),
            "docs/spec.yaml" to "id: S-1\n",
            "docs/a.yaml" to "id: A-1\nspec: docs/spec.yaml\n",
        )
    )

    @Test
    fun `文書移動は参照書き換えと1トランザクションで適用され1回のUndoで戻る`() {
        val store = docRefStore()
        val s = session(store)
        assertTrue(s.derived.semantic.issues.isEmpty(), "${s.derived.semantic.issues}")
        val before = dumpAll(s)
        val from = DocumentId("docs/spec.yaml")
        val to = DocumentId("docs/spec2.yaml")
        val plan = s.planRefactoring(semantic.RefactoringRequest.MoveDocument(from, to))
        assertEquals(listOf(from to to), plan.moves)
        assertTrue(plan.summary.any { "文書参照 1 件" in it }, "${plan.summary}")
        assertEquals(0, s.version, "plan は不変")
        // 適用: 物理移動＋参照書き換えが1トランザクション
        assertTrue(s.applyRefactoring(plan).ok)
        assertEquals(0, store.writeCount)
        assertTrue(to in s.snapshot.documents.keys && from !in s.snapshot.documents.keys)
        assertTrue("docs/spec2.yaml" in DefaultYamlCodec.dump(s.snapshot.documents.getValue(a2).document))
        assertTrue(s.derived.semantic.issues.isEmpty(), "移動後も文書参照が解決される: ${s.derived.semantic.issues}")
        // 1回の Undo で移動も参照も戻る
        assertTrue(s.undo().ok)
        assertEquals(before, dumpAll(s))
        assertTrue(s.dirtyDocuments().isEmpty())
    }

    private val a2 = DocumentId("docs/a.yaml")

    @Test
    fun `_documentのid宣言済みの移動は参照を書き換えない`() {
        val store = InMemoryDocumentStore(
            mapOf(
                "_schemas/doc.yaml" to """
                    id: fx-doc
                    match: "docs/**/*.yaml"
                    root:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: spec
                          type: ref
                          target: document
                """.trimIndent(),
                "docs/spec.yaml" to "_document:\n  id: spec-doc\nid: S-1\n",
                "docs/a.yaml" to "id: A-1\nspec: spec-doc\n",
            )
        )
        val s = session(store)
        val plan = s.planRefactoring(
            semantic.RefactoringRequest.MoveDocument(DocumentId("docs/spec.yaml"), DocumentId("docs/spec2.yaml"))
        )
        assertTrue(plan.changes.isEmpty(), "文書参照は安定なので書き換えなし")
        assertTrue(plan.summary.any { "安定" in it }, "${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        assertTrue(s.derived.semantic.issues.isEmpty(), "${s.derived.semantic.issues}")
    }

    @Test
    fun `移動でスキーマ適用が変わるときは警告が付く`() {
        val s = session(docRefStore())
        val plan = s.planRefactoring(
            semantic.RefactoringRequest.MoveDocument(DocumentId("docs/a.yaml"), DocumentId("notes/a.yaml"))
        )
        assertTrue(plan.warnings.any { "スキーマの適用が変わります" in it }, "${plan.warnings}")
    }

    @Test
    fun `mergeは参照を張り替え出現を詳細化として残す`() {
        val s = session(newStore())
        val plan = s.planRefactoring(RefactoringRequest.MergeEntity("IT-001", "IT-002"))
        assertTrue(s.applyRefactoring(plan).ok)
        assertTrue(s.derived.semantic.issues.isEmpty(), "${s.derived.semantic.issues}")
        assertTrue("IT-001" !in s.derived.semantic.ids)
        assertEquals(2, s.derived.semantic.occurrences("IT-002").size, "a の出現が IT-002 の詳細化として残る")
        // b の link は自分自身（IT-002）へ、c の link も IT-002 へ
        assertEquals(2, s.derived.semantic.backlinksTo(setOf("IT-002")).size)
    }
}
