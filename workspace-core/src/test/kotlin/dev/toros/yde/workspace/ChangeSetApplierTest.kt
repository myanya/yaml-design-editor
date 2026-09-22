package dev.toros.yde.workspace

import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.validate.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 変更セットの適用（P27 段D の D-b・意図的差分 D-35）: 競合の判定 → 適用後の姿を重ねた検証 → 1つのトランザクション＋保存。
 * アプリ側（root の Workspace.apply）の細かなケースは root の ChangeSetsTest が、今までどおり保証する（同じ部品を通る）。
 */
class ChangeSetApplierTest {

    private val schema = """
        id: item
        match: "items/*.yaml"
        entity: { type: item, idPattern: "IT-*", defines: true }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: title, type: string, required: true }
            - { key: next, type: ref }
    """.trimIndent()

    private fun store() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to schema,
            "items/a.yaml" to "id: IT-1\ntitle: A  # 先頭\nnext: IT-2\n",
            "items/b.yaml" to "id: IT-2\ntitle: B\n",
        )
    )

    @Test
    fun `複数の文書にまたがる変更は 適用後の姿で検証して 1つのトランザクションで保存する`() {
        val store = store()
        val session = WorkspaceSession(store)
        // ID の改名と、参照元の修正を、同じ変更セットで行う（片方だけなら、相手の見つからない参照のエラーになる）
        val changeSet = ChangeSets.parse(
            """
            changes:
              - file: items/b.yaml
                base: ${ChangeSets.contentHash("id: IT-2\ntitle: B\n")}
                ops:
                  - { op: set, path: id, value: IT-20 }
              - file: items/a.yaml
                ops:
                  - { op: set, path: next, value: IT-20 }
              - file: items/c.yaml
                create: true
                content: { id: IT-3, title: C, next: IT-20 }
            """.trimIndent()
        )
        val result = ChangeSetApplier.apply(session, changeSet, requireValid = true)
        assertTrue(result.ok, result.toString())
        assertEquals(listOf(true, true, true), result.results.map { it.applied })
        assertTrue(result.results.all { it.issues.isEmpty() }, result.toString())
        // 保存まで済んでいる。コメントは保たれる（コメントの前の空白は、書き出しのときに1つに整えられる）
        assertEquals("id: IT-1\ntitle: A # 先頭\nnext: IT-20\n", store.read(DocumentId("items/a.yaml"))?.text)
        assertEquals("id: IT-20\ntitle: B\n", store.read(DocumentId("items/b.yaml"))?.text)
        assertTrue(session.hasDocument(DocumentId("items/c.yaml")))
        assertTrue(session.dirtyDocuments().isEmpty())
        // 1つのトランザクション: Undo 1回で、全部の文書が戻る
        assertEquals(1, session.history.size)
        assertTrue(session.undo().ok)
        assertFalse(session.hasDocument(DocumentId("items/c.yaml")))
    }

    @Test
    fun `検証のエラーが残るなら requireValid では何も書き込まない`() {
        val store = store()
        val session = WorkspaceSession(store)
        val changeSet = ChangeSets.parse("changes:\n  - file: items/b.yaml\n    ops:\n      - { op: set, path: id, value: IT-20 }\n  - file: items/a.yaml\n    ops:\n      - { op: delete, path: title }\n")
        val rejected = ChangeSetApplier.apply(session, changeSet, requireValid = true)
        assertFalse(rejected.ok)
        assertTrue(rejected.results.none { it.applied })
        // items/a.yaml には、必須の項目の欠落と、相手の見つからなくなった参照が出る（適用後の姿で検証している）
        val issues = rejected.results.single { it.file == "items/a.yaml" }.issues
        assertEquals(listOf("required", "unresolved"), issues.filter { it.severity == Severity.ERROR }.map { it.source.kind })
        assertEquals(0, store.writeCount)
        // ドライランも書き込まない。requireValid でなければ、エラーが残っていても適用する
        assertTrue(ChangeSetApplier.apply(session, changeSet, dryRun = true).ok)
        assertEquals(0, store.writeCount)
        assertTrue(ChangeSetApplier.apply(session, changeSet).ok)
        assertEquals(2, store.writeCount)
    }

    @Test
    fun `競合と操作のエラーは 1件でもあれば全体を書き込まない`() {
        val store = store()
        val session = WorkspaceSession(store)
        fun apply(text: String) = ChangeSetApplier.apply(session, ChangeSets.parse(text.trimIndent()))
        val stale = apply(
            """
            changes:
              - file: items/a.yaml
                base: 0000
                ops: [{ op: set, path: title, value: X }]
              - file: items/b.yaml
                ops: [{ op: set, path: title, value: Y }]
            """
        )
        assertFalse(stale.ok)
        assertEquals("競合: ディスク内容が変更の前提（base）から変わっています", stale.results.first { it.file == "items/a.yaml" }.message)
        assertNull(stale.results.first { it.file == "items/b.yaml" }.message)
        assertTrue(stale.results.none { it.applied })
        assertEquals(0, store.writeCount)

        assertEquals("ファイルが存在しません: items/none.yaml", apply("changes:\n  - file: items/none.yaml\n    ops: [{ op: delete, path: x }]").results.single().message)
        assertEquals(
            "競合: 既に同名のファイルが存在します: items/a.yaml",
            apply("changes:\n  - file: items/a.yaml\n    create: true\n    content: { id: IT-9, title: Z }").results.single().message,
        )
        assertTrue(apply("changes:\n  - file: items/a.yaml\n    ops: [{ op: delete, path: nothing }]").results.single().message!!.contains("パスが存在しません"))
        // ワークスペースの外へ出るパスは拒否する
        assertEquals(
            "ワークスペース外のパスは扱えません: ../outside.yaml",
            apply("changes:\n  - file: ../outside.yaml\n    create: true\n    content: { id: IT-9 }").results.single().message,
        )
        assertEquals(0, store.writeCount)
    }
}
