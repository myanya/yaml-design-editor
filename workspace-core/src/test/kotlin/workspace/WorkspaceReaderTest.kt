package workspace

import model.DocumentId
import validate.Severity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 読取専用ワークスペース（WorkspaceReader）の契約テスト（Phase 7・WS-002）。
 * fixture の期待結果（test-fixtures/README.md＝現行実装のCLIで確認済み）と一致することを固定する。
 */
class WorkspaceReaderTest {

    private fun store(name: String): DocumentStore {
        val root = System.getProperty("fixtures.root")
            ?: error("システムプロパティ fixtures.root が未設定（workspace-core/build.gradle.kts が渡す）")
        return FileSystemDocumentStore(Path.of(root, "workspaces", name))
    }

    @Test
    fun `legacy構成のfixtureを読み取り意味解析まで一気通貫で通る`() {
        val ws = WorkspaceReader.load(store("legacy-basic"), version = 7)
        assertNull(ws.layout.configDir, "レガシー構成")
        assertEquals(listOf("fx-item", "fx-task"), ws.schemas.map { it.id }.sorted())
        assertTrue(ws.schemaErrors.isEmpty(), "${ws.schemaErrors}")
        assertTrue(ws.parseErrors.isEmpty(), "${ws.parseErrors}")
        // 文書4件（_schemas ゾーンは除外）・すべて台帳内・スキーマ割当（match）が効く
        assertEquals(
            listOf("items/a.yaml", "items/b.yaml", "items/c.yaml", "tasks/t1.yaml"),
            ws.snapshot.documents.keys.map { it.value },
        )
        assertTrue(ws.snapshot.documents.values.all { it.inLedger })
        assertEquals("fx-item", ws.snapshot.documents.getValue(DocumentId("items/a.yaml")).schemaId)
        assertEquals("fx-task", ws.snapshot.documents.getValue(DocumentId("tasks/t1.yaml")).schemaId)
        // 構造検証: ERROR なし（cases＋$ref スキーマの適用込み。fixture README の期待どおり）
        assertTrue(
            ws.derived.structuralIssues.values.flatten().none { it.severity == Severity.ERROR },
            "${ws.derived.structuralIssues}",
        )
        // 意味検証: 文書内重複 PT-001 ×2 ＋ 未解決 IT-404（fixture README の期待: errors 3）
        val messages = ws.derived.semantic.issues.map { it.message }
        assertEquals(3, messages.size, "$messages")
        assertEquals(2, messages.count { "PT-001" in it && "重複" in it }, "$messages")
        assertEquals(1, messages.count { "IT-404" in it && "解決できません" in it }, "$messages")
        // sourceVersion は入力の転記（ADR-0007）
        assertEquals(7, ws.derived.semantic.sourceVersion)
        assertEquals(7, ws.derived.sourceVersion)
    }

    @Test
    fun `yde構成では台帳外が意味解析の対象外になる`() {
        val ws = WorkspaceReader.load(store("yde-basic"))
        assertEquals(".yde", ws.layout.configDir)
        assertEquals(listOf("doc"), ws.layout.ledger)
        // 文書: doc/a・b と outside/note（台帳外だが文書としては載る）。.yde 配下は文書に載せない
        assertEquals(
            setOf("doc/a.yaml", "doc/b.yaml", "outside/note.yaml"),
            ws.snapshot.documents.keys.map { it.value }.toSet(),
        )
        assertFalse(ws.snapshot.documents.getValue(DocumentId("outside/note.yaml")).inLedger)
        // 台帳内の2文書だけが索引される（OUT-001 は載らない。fixture README の期待どおり）
        assertEquals(setOf("IT-001", "IT-002"), ws.derived.semantic.ids.keys)
        assertTrue(ws.derived.semantic.issues.isEmpty(), "${ws.derived.semantic.issues}")
    }
}
