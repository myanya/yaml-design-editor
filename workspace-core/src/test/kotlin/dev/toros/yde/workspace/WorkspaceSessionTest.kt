package dev.toros.yde.workspace

import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.DefaultYamlCodec
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
        return (d.document.documents.first() as YNode.Map).entries["title"]
            ?.let { (it as YNode.Scalar).value?.toString() }
    }

    @Test
    fun `シナリオC 未保存snapshotの意味解析が成立する`() {
        val store = newStore()
        val s = session(store)
        // 1. 原本には存在しないID（IT-100）を working snapshot へ追加する
        val newDoc = DocumentId("items/new.yaml")
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    s.version, ChangeIntent("新規ID"),
                    listOf(
                        WorkspaceOperation.CreateDocument(
                            newDoc, DefaultYamlCodec.parse(newDoc, "id: IT-100\ntitle: 新規\n")
                        )
                    ),
                )
            ).ok
        )
        // 2. 別文書からそのIDへ ref を追加する
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    s.version, ChangeIntent("参照追加"),
                    listOf(
                        WorkspaceOperation.EditDocument(
                            a, listOf(YamlOperation.SetScalar(parseYPath("link"), "IT-100"))
                        )
                    ),
                )
            ).ok
        )
        // 3-4. 保存前に semantic validation → ref が解決される
        assertTrue(s.derived.semantic.issues.isEmpty(), "未保存のIDへ解決される: ${s.derived.semantic.issues}")
        assertEquals(1, s.derived.semantic.backlinksTo(setOf("IT-100")).size)
        // 5. DocumentStore は未変更
        assertEquals(0, store.writeCount, "保存前はストアに書かない")
    }

    @Test
    fun `recordEditは合体キーで1記録にまとまりundo1回で境界前へ戻る`() {
        val store = newStore()
        val s = session(store)
        // 連続入力（同一キー・時間窓内）→ 1記録に合体（UX-11 のセッション版）
        assertTrue(s.recordEdit(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A1\n"), coalesceKey = "title", nowMillis = 0).ok)
        assertTrue(s.recordEdit(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A12\n"), coalesceKey = "title", nowMillis = 500).ok)
        assertTrue(s.recordEdit(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A123\n"), coalesceKey = "title", nowMillis = 900).ok)
        assertEquals(1, s.history.size, "時間窓内の同一キーは1記録に合体")
        // 時間窓の超過は境界＝新しい記録
        assertTrue(s.recordEdit(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: B\n"), coalesceKey = "title", nowMillis = 5000).ok)
        assertEquals(2, s.history.size)
        assertTrue(s.isDirty(a))
        assertEquals(s.fullDerived().semantic.ids, s.derived.semantic.ids, "記録経路も派生は oracle と一致")
        // undo: 直近の記録 → 合体グループの先頭前 → 保存済み状態
        assertTrue(s.undo().ok)
        assertEquals("A123", titleOf(s, a))
        assertTrue(s.undo().ok)
        assertEquals("A", titleOf(s, a), "合体グループは1回の undo でグループ開始前へ戻る")
        assertFalse(s.isDirty(a), "保存済み状態へ戻れば dirty も消える")
        assertTrue(s.redo().ok)
        assertEquals("A123", titleOf(s, a), "redo は合体後の最終状態へ進む")
    }

    @Test
    fun `recordEditは記録にUI側ツリーを共有しない`() {
        val store = newStore()
        val s = session(store)
        val edited = DefaultYamlCodec.parse(a, "id: IT-001\ntitle: X\n")
        assertTrue(s.recordEdit(a, edited, coalesceKey = null, nowMillis = 0).ok)
        // 記録後に UI 側ツリーを書き換えても履歴（redo）が汚れない（ADR-0003）
        (edited.documents.first() as YNode.Map).entries["title"] = YNode.Scalar("汚染")
        assertTrue(s.undo().ok)
        assertTrue(s.redo().ok)
        assertEquals("X", titleOf(s, a), "履歴は記録時点の deep copy を持つ")
    }

    @Test
    fun `ReplaceDocument操作は文書全体を置換しundoとredoが対称に効く`() {
        val store = newStore()
        val s = session(store)
        val r = s.apply(
            WorkspaceTransaction(
                s.version, ChangeIntent("承認済み変更の適用"),
                listOf(
                    WorkspaceOperation.ReplaceDocument(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: 置換後\n")),
                    WorkspaceOperation.CreateDocument(
                        DocumentId("items/new.yaml"),
                        DefaultYamlCodec.parse(DocumentId("items/new.yaml"), "id: IT-100\ntitle: 新規\n"),
                    ),
                ),
            )
        )
        assertTrue(r.ok, r.message ?: "")
        assertEquals("置換後", titleOf(s, a))
        assertTrue("IT-100" in s.derived.semantic.ids, "1トランザクションで置換と作成が入る")
        assertTrue(s.undo().ok)
        assertEquals("A", titleOf(s, a), "Undo 1回で置換が旧内容へ戻る")
        assertNull(s.snapshot.documents[DocumentId("items/new.yaml")], "作成も同時に戻る")
        assertTrue(s.redo().ok)
        assertEquals("置換後", titleOf(s, a))
    }

    @Test
    fun `replaceDocumentはworkingだけを置き換え履歴を積まずsaveで書く`() {
        val store = newStore()
        val s = session(store)
        val r = s.replaceDocument(a, DefaultYamlCodec.parse(a, "id: IT-001\ntitle: A2\nlink: IT-404\n"))
        assertTrue(r.ok)
        assertEquals("A2", titleOf(s, a))
        assertFalse(s.canUndo(), "履歴は積まない（Undo は A-3 まで UI（EditBuffer）所有）")
        assertEquals(0, store.writeCount, "置換だけではストアに書かない")
        assertTrue(s.derived.semantic.issues.any { "IT-404" in it.message }, "派生が追随する: ${s.derived.semantic.issues}")
        // oracle: 全再構築と一致
        val full = s.fullDerived()
        assertEquals(full.structuralIssues, s.derived.structuralIssues)
        assertEquals(full.semantic.ids, s.derived.semantic.ids)
        assertTrue(s.isDirty(a))
        val saved = s.save(setOf(a))
        assertTrue(saved.ok)
        assertEquals("id: IT-001\ntitle: A2\nlink: IT-404\n", store.read(a)?.text)
        assertFalse(s.isDirty(a))
    }

    @Test
    fun `replaceDocumentはスキーマ文書の置換でスキーマ束を再構築する`() {
        val store = newStore()
        val s = session(store)
        assertTrue(s.replaceDocument(a, DefaultYamlCodec.parse(a, "id: IT-001\n")).ok)
        assertTrue(s.derived.structuralIssues.getValue(a).any { "title" in it.message }, "必須欠落が出る")
        val schemaDoc = DocumentId("_schemas/item.yaml")
        val relaxed = store.read(schemaDoc)!!.text.replace("required: true", "required: false")
        assertTrue(s.replaceDocument(schemaDoc, DefaultYamlCodec.parse(schemaDoc, relaxed)).ok)
        assertTrue(
            s.derived.structuralIssues.getValue(a).none { "title" in it.message },
            "スキーマ置換が全文書の検証に効く: ${s.derived.structuralIssues}",
        )
        assertEquals(s.fullDerived().structuralIssues, s.derived.structuralIssues)
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
                    WorkspaceOperation.EditDocument(b, listOf(YamlOperation.Insert(parseYPath("title"), YNode.Scalar("x")))),
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
    fun `スキーマ文書もEditDocumentで編集できる`() {
        // スキーマ・リファクタリング（RF-002）の前提: `_schemas` 文書は編集対象の文書（RF-002a）
        val store = newStore()
        val s = session(store)
        val schemaDoc = DocumentId("_schemas/item.yaml")
        assertTrue(schemaDoc in s.snapshot.documents.keys)
        val result = s.apply(
            WorkspaceTransaction(
                0, ChangeIntent("スキーマ名の変更"),
                listOf(
                    WorkspaceOperation.EditDocument(
                        schemaDoc, listOf(YamlOperation.SetScalar(parseYPath("name"), "アイテム"))
                    )
                ),
            )
        )
        assertTrue(result.ok, result.message ?: "")
        assertTrue(s.isDirty(schemaDoc))
        assertEquals(0, store.writeCount)
        assertTrue(s.save().ok)
        assertTrue("アイテム" in (store.read(schemaDoc)?.text ?: ""))
    }

    @Test
    fun `スキーマ文書の変更でスキーマ束と検証が再構築される`() {
        // WS-006: title の required を外すスキーマ編集 → 直後の検証が新スキーマで行われる
        val store = InMemoryDocumentStore(
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
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\n",
            )
        )
        val s = session(store)
        assertTrue(
            s.derived.structuralIssues.getValue(a).any { it.severity == Severity.ERROR },
            "必須 title 欠落の ERROR がある: ${s.derived.structuralIssues}",
        )
        val result = s.apply(
            WorkspaceTransaction(
                0, ChangeIntent("必須を外す"),
                listOf(
                    WorkspaceOperation.EditDocument(
                        DocumentId("_schemas/item.yaml"),
                        listOf(YamlOperation.SetScalar(parseYPath("root.fields[1].required"), false)),
                    )
                ),
            )
        )
        assertTrue(result.ok, result.message ?: "")
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == Severity.ERROR },
            "スキーマ再構築後は ERROR が消える: ${s.derived.structuralIssues}",
        )
        // undo で旧スキーマへ戻り、ERROR が復活する（対称性）
        assertTrue(s.undo().ok)
        assertTrue(
            s.derived.structuralIssues.getValue(a).any { it.severity == Severity.ERROR },
            "undo でスキーマも検証も戻る: ${s.derived.structuralIssues}",
        )
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
