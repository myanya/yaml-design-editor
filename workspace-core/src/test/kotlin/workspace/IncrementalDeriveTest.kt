package workspace

import edit.YamlOperation
import model.DocumentId
import model.parseYPath
import semantic.SemanticSnapshot
import yaml.DefaultYamlCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * セッション増分導出の oracle テスト（Phase 11・PF-002）: どのトランザクション・undo / redo の後でも
 * `session.derived` が working 状態からの全再構築（`fullDerived()`＝正の基準）と完全一致し、
 * stale な snapshot を current として公開しないことを固定する。
 */
class IncrementalDeriveTest {

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
                      required: true
                    - key: link
                      type: ref
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ntitle: A\n",
            "items/b.yaml" to "id: IT-002\ntitle: B\nlink: IT-001\n",
            "items/c.yaml" to "id: IT-003\ntitle: C\nlink: IT-002\n",
        )
    )

    private fun session(incremental: Boolean = true) =
        WorkspaceSession(newStore(), clock = { Instant.EPOCH }, incrementalDerive = incremental)

    private fun assertSameSnapshot(oracle: SemanticSnapshot, actual: SemanticSnapshot, label: String) {
        assertEquals(oracle.sourceVersion, actual.sourceVersion, label)
        assertEquals(oracle.ids, actual.ids, label)
        assertEquals(oracle.refs, actual.refs, label)
        assertEquals(oracle.documentIds, actual.documentIds, label)
        assertEquals(oracle.issues, actual.issues, label)
        assertEquals(oracle.graph.entities, actual.graph.entities, label)
        assertEquals(oracle.graph.documents, actual.graph.documents, label)
        assertEquals(oracle.scans, actual.scans, label)
    }

    /** 増分導出の結果が全再構築（oracle）と一致し、版が現在と揃っていることの検査。 */
    private fun assertMatchesOracle(s: WorkspaceSession, label: String) {
        assertEquals(s.version, s.derived.sourceVersion, "$label: stale な派生状態を公開しない")
        assertEquals(s.version, s.derived.semantic.sourceVersion, label)
        val oracle = s.fullDerived()
        assertEquals(oracle.structuralIssues, s.derived.structuralIssues, label)
        assertEquals(
            oracle.structuralIssues.keys.toList(), s.derived.structuralIssues.keys.toList(),
            "$label: キー順も文書順で一致",
        )
        assertSameSnapshot(oracle.semantic, s.derived.semantic, label)
    }

    private fun edit(s: WorkspaceSession, id: String, path: String, value: Any?) =
        s.apply(
            WorkspaceTransaction(
                s.version, ChangeIntent("edit"),
                listOf(
                    WorkspaceOperation.EditDocument(
                        DocumentId(id), listOf(YamlOperation.SetScalar(parseYPath(path), value))
                    )
                ),
            )
        )

    @Test
    fun `編集追加削除移動とundoredoの派生状態は常に全再構築と一致する`() {
        val s = session()
        assertMatchesOracle(s, "初期ロード")

        // 編集: 参照切れ＋必須欠落を作る（意味・構造の両方が増分経路で更新される）
        assertTrue(edit(s, "items/b.yaml", "link", "IT-404").ok)
        assertMatchesOracle(s, "編集（参照切れ発生）")
        assertTrue(s.derived.semantic.issues.any { "IT-404" in it.message }, "${s.derived.semantic.issues}")
        assertTrue(edit(s, "items/a.yaml", "title", null).ok)
        assertMatchesOracle(s, "編集（必須欠落発生）")

        // 追加
        val d = DocumentId("items/d.yaml")
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    s.version, ChangeIntent("create"),
                    listOf(WorkspaceOperation.CreateDocument(d, DefaultYamlCodec.parse(d, "id: IT-004\ntitle: D\nlink: IT-003\n"))),
                )
            ).ok
        )
        assertMatchesOracle(s, "追加")

        // 削除（IT-003 が消え、d の link が未解決になる）
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    s.version, ChangeIntent("delete"),
                    listOf(WorkspaceOperation.DeleteDocument(DocumentId("items/c.yaml"))),
                )
            ).ok
        )
        assertMatchesOracle(s, "削除")
        assertTrue(s.derived.semantic.issues.any { "IT-003" in it.message }, "${s.derived.semantic.issues}")

        // 移動（パスが変わる＝文書IDの付け替え）
        assertTrue(
            s.apply(
                WorkspaceTransaction(
                    s.version, ChangeIntent("move"),
                    listOf(WorkspaceOperation.MoveDocument(DocumentId("items/b.yaml"), DocumentId("items/renamed.yaml"))),
                )
            ).ok
        )
        assertMatchesOracle(s, "移動")

        // undo で全部戻し、redo で2手進める——毎回 oracle と一致
        var undoCount = 0
        while (s.canUndo()) {
            assertTrue(s.undo().ok)
            undoCount++
            assertMatchesOracle(s, "undo $undoCount 回目")
        }
        assertTrue(s.derived.semantic.issues.isEmpty(), "初期状態へ復元: ${s.derived.semantic.issues}")
        repeat(2) { i ->
            assertTrue(s.redo().ok)
            assertMatchesOracle(s, "redo ${i + 1} 回目")
        }
    }

    @Test
    fun `スキーマ変更は全再構築へ振り分けられ結果も一致する`() {
        val s = session()
        // スキーマ文書の編集（required を外す）→ schemaTouched で全再構築経路
        assertTrue(edit(s, "_schemas/item.yaml", "root.fields[1].required", false).ok)
        assertMatchesOracle(s, "スキーマ編集")
        // 続く文書編集は増分経路（新しいスキーマ束の上で oracle と一致）
        assertTrue(edit(s, "items/a.yaml", "title", null).ok)
        assertMatchesOracle(s, "スキーマ変更後の文書編集")
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "required が外れているため欠落してもエラーなし: ${s.derived.structuralIssues}",
        )
    }

    @Test
    fun `noteStoreChangesはストア変更を増分で取り込み全再構築と一致する`() {
        val store = newStore()
        val s = WorkspaceSession(store) { Instant.EPOCH }

        // 外部変更: 参照切れ＋新規文書＋削除（セッションを介さずストアだけが変わる）
        store.write(DocumentId("items/b.yaml"), "id: IT-002\ntitle: B2\nlink: IT-404\n")
        store.write(DocumentId("items/d.yaml"), "id: IT-004\ntitle: D\n")
        store.delete(DocumentId("items/c.yaml"))
        val result = s.noteStoreChanges(
            changed = listOf(DocumentId("items/b.yaml"), DocumentId("items/d.yaml")),
            removed = listOf(DocumentId("items/c.yaml")),
        )
        assertTrue(result.ok)
        assertMatchesOracle(s, "外部変更の増分取り込み")
        assertTrue(s.derived.semantic.issues.any { "IT-404" in it.message }, "${s.derived.semantic.issues}")
        assertTrue("IT-004" in s.derived.semantic.ids, "新規文書のIDが載る")
        assertTrue("IT-003" !in s.derived.semantic.ids, "削除文書のIDが消える")
        assertTrue(s.dirtyDocuments().isEmpty(), "取り込みは dirty を作らない（保存済み状態の同期）")
    }

    @Test
    fun `noteStoreChangesは未保存working変更のある文書を上書きせず他は増分で取り込む`() {
        val store = newStore()
        val s = WorkspaceSession(store) { Instant.EPOCH }
        val a = DocumentId("items/a.yaml")
        val b = DocumentId("items/b.yaml")
        // b を編集直結（RA-05: 履歴を積まない replaceDocument）で dirty にする
        assertTrue(s.replaceDocument(b, DefaultYamlCodec.parse(b, "id: IT-002\ntitle: 編集中\nlink: IT-001\n")).ok)
        // 外部変更: b（dirty）と a（clean）の両方が書き換わる
        store.write(b, "id: IT-002\ntitle: 外部\nlink: IT-001\n")
        store.write(a, "id: IT-001\ntitle: A9\n")
        assertTrue(s.noteStoreChanges(listOf(a, b)).ok)
        assertEquals(
            "編集中", s.derived.semantic.ids.getValue("IT-002").single().title,
            "dirty 文書の working（見たまま）は外部変更で上書きされない",
        )
        assertEquals(
            "A9", s.derived.semantic.ids.getValue("IT-001").single().title,
            "clean 文書は増分で取り込まれる",
        )
        assertMatchesOracle(s, "dirty保持＋clean取り込み")
        assertTrue(s.isDirty(b) && !s.isDirty(a))
    }

    @Test
    fun `noteStoreChangesはスキーマ変更を全再読込へ降格する`() {
        val store = newStore()
        val s = WorkspaceSession(store) { Instant.EPOCH }
        // スキーマの required を外す外部変更 → 走査規則が変わるため reload 経路
        store.write(
            DocumentId("_schemas/item.yaml"),
            store.read(DocumentId("_schemas/item.yaml"))!!.text.replace("required: true", "required: false"),
        )
        store.write(DocumentId("items/a.yaml"), "id: IT-001\n")
        assertTrue(s.noteStoreChanges(listOf(DocumentId("_schemas/item.yaml"), DocumentId("items/a.yaml"))).ok)
        assertMatchesOracle(s, "スキーマ外部変更")
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "required が外れているため title 欠落もエラーなし: ${s.derived.structuralIssues}",
        )
    }

    @Test
    fun `derivedWithは未保存overlayを重ねた派生を返しセッション状態を変えない`() {
        val s = session()
        val versionBefore = s.version
        val overlay = s.derivedWith(
            mapOf(
                // 編集中: b の参照を未解決へ／新規の未保存文書 e を重ねる／c はパース不能（文書集合から外す）
                DocumentId("items/b.yaml") to DefaultYamlCodec.parse(DocumentId("items/b.yaml"), "id: IT-002\ntitle: B\nlink: IT-999\n"),
                DocumentId("items/e.yaml") to DefaultYamlCodec.parse(DocumentId("items/e.yaml"), "id: IT-005\ntitle: E\n"),
                DocumentId("items/c.yaml") to null,
            )
        )
        assertTrue(overlay.semantic.issues.any { "IT-999" in it.message }, "overlay の参照切れが見える: ${overlay.semantic.issues}")
        assertTrue("IT-005" in overlay.semantic.ids, "未保存の新規文書のIDが行になる")
        assertTrue("IT-003" !in overlay.semantic.ids, "パース不能 overlay は文書集合から外れる")
        // oracle: overlay を反映した全再構築と一致する（正しさ経路の共有）
        val t = s.apply(
            WorkspaceTransaction(
                s.version, ChangeIntent("oracle再現"),
                listOf(
                    WorkspaceOperation.EditDocument(DocumentId("items/b.yaml"), listOf(YamlOperation.SetScalar(parseYPath("link"), "IT-999"))),
                    WorkspaceOperation.CreateDocument(DocumentId("items/e.yaml"), DefaultYamlCodec.parse(DocumentId("items/e.yaml"), "id: IT-005\ntitle: E\n")),
                    WorkspaceOperation.DeleteDocument(DocumentId("items/c.yaml")),
                ),
            )
        )
        assertTrue(t.ok, t.message ?: "")
        assertEquals(s.derived.structuralIssues, overlay.structuralIssues, "構造検証が oracle と一致")
        assertEquals(s.derived.semantic.ids, overlay.semantic.ids, "ID集約が oracle と一致")
        assertEquals(s.derived.semantic.issues, overlay.semantic.issues, "意味指摘が oracle と一致")
        // derivedWith 自体はセッション状態を変えない（上の apply までは version 不変だった）
        assertEquals(versionBefore + 1, s.version, "derivedWith は version を進めない（進んだのは apply の1回だけ）")
    }

    @Test
    fun `増分導出を無効化しても同じ結果になる`() {
        val a = session(incremental = true)
        val b = session(incremental = false)
        for (s in listOf(a, b)) {
            assertTrue(edit(s, "items/b.yaml", "link", "IT-404").ok)
            assertTrue(edit(s, "items/c.yaml", "title", "C2").ok)
            assertTrue(s.undo().ok)
            assertMatchesOracle(s, "incremental=${s === a}")
        }
        assertEquals(a.derived.structuralIssues, b.derived.structuralIssues)
        assertSameSnapshot(b.derived.semantic, a.derived.semantic, "増分と全再構築の相互一致")
    }
}
