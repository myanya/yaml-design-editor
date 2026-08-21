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
