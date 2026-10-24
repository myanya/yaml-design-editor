package dev.toros.yde.workspace

import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保存時に自動で入る値（印 `auto`）のカーネル側: セッションの保存前の 1 トランザクション・変更セットの適用への組み込み・
 * 定義の点検（型との適合・式との併用）。「今」は固定する。
 */
class AutoValuesKernelTest {

    private val now = LocalDateTime.of(2026, 10, 5, 9, 0, 0)
    private val a = DocumentId("rules/a.yaml")

    private val schema = """
        id: rule
        match: "rules/*.yaml"
        entity: { type: rule, idPattern: "RULE-*", defines: true }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: title, type: string, required: true }
            - { key: updated_on, type: date, auto: updated, required: true }
            - { key: revision, type: int, auto: revision, format: "'rev-'000" }
    """.trimIndent()

    private fun store() = InMemoryDocumentStore(
        mapOf(
            "_schemas/rule.yaml" to schema,
            "rules/a.yaml" to "id: RULE-1\ntitle: A\nupdated_on: 2026-01-01\nrevision: 3\n",
        )
    )

    private fun root(session: WorkspaceSession, id: DocumentId) =
        session.snapshot.documents.getValue(id).document.documents.first() as YNode.Map

    private fun scalar(map: YNode.Map, key: String) = (map.entries[key] as? YNode.Scalar)?.value

    private fun titleChange(session: WorkspaceSession, value: String) = WorkspaceTransaction(
        session.version, ChangeIntent("一括変更: title"),
        listOf(WorkspaceOperation.EditDocument(a, listOf(YamlOperation.SetScalar(listOf("title"), value)))),
    )

    @Test
    fun `内容と自動値を一つの履歴へ同梱しUndoとRedoでまとめて復元する`() {
        val store = store()
        val session = WorkspaceSession(store)
        val before = session.copyDocument(a)!!
        val result = session.applyWithAutoValues(titleChange(session, "変更後"), listOf(a), now)
        assertTrue(result.result.ok)
        assertEquals(1, session.history.size)
        assertEquals(1L, session.version)
        assertEquals("一括変更: title", session.peekUndo()!!.intent.description)
        assertEquals("2026-10-05", scalar(root(session, a), "updated_on"))
        assertEquals(4L, scalar(root(session, a), "revision"))
        assertEquals("A", scalar(before.documents.first() as YNode.Map, "title"), "計画は元の木を変更しない")
        assertEquals(0, store.writeCount, "適用だけでは書き込まない")
        assertTrue(session.save().ok)
        assertTrue(session.undo().ok)
        assertEquals("A", scalar(root(session, a), "title"))
        assertEquals("2026-01-01", scalar(root(session, a), "updated_on"))
        assertEquals(3, scalar(root(session, a), "revision"))
        assertTrue(session.isDirty(a))
        assertTrue(session.redo().ok)
        assertEquals("変更後", scalar(root(session, a), "title"))
        assertEquals("2026-10-05", scalar(root(session, a), "updated_on"))
        assertEquals(4L, scalar(root(session, a), "revision"), "Redo は版を再加算しない")
        assertFalse(session.isDirty(a))
    }

    @Test
    fun `同梱の計画は未保存の編集と以前の履歴を保つ`() {
        val session = WorkspaceSession(store())
        assertTrue(session.apply(titleChange(session, "未保存")).ok)
        assertTrue(session.applyWithAutoValues(titleChange(session, "一括"), listOf(a), now).result.ok)
        assertEquals(2, session.history.size)
        assertTrue(session.undo().ok)
        assertEquals("未保存", scalar(root(session, a), "title"))
        assertEquals(3, scalar(root(session, a), "revision"))
        assertTrue(session.undo().ok)
        assertEquals("A", scalar(root(session, a), "title"))
    }

    @Test
    fun `同梱の準備に失敗しても内容や履歴やRedoを変更しない`() {
        val store = store()
        val session = WorkspaceSession(store)
        assertTrue(session.apply(titleChange(session, "やり直せる変更")).ok)
        assertTrue(session.undo().ok)
        val version = session.version
        val redo = session.peekRedo()
        val tx = titleChange(session, "反映してはいけない")
        val invalid = tx.copy(operations = tx.operations + WorkspaceOperation.EditDocument(
            DocumentId("missing.yaml"), listOf(YamlOperation.SetScalar(listOf("title"), "失敗")),
        ))
        assertFalse(session.applyWithAutoValues(invalid, listOf(a), now).result.ok)
        assertEquals(version, session.version)
        assertEquals(redo, session.peekRedo())
        assertTrue(session.history.isEmpty())
        assertEquals("A", scalar(root(session, a), "title"))
        assertEquals(3, scalar(root(session, a), "revision"))
        assertEquals(0, store.writeCount)
        val stale = session.applyWithAutoValues(tx.copy(baseVersion = version - 1), listOf(a), now)
        assertTrue(stale.result.staleBase)
        assertEquals(version, session.version)
    }

    @Test
    fun `同梱した必須の自動値を含めて検証し新規文書も一回で戻せる`() {
        val session = WorkspaceSession(store())
        val b = DocumentId("rules/b.yaml")
        val tx = WorkspaceTransaction(session.version, ChangeIntent("作成"), listOf(
            WorkspaceOperation.CreateDocument(b, dev.toros.yde.yaml.DefaultYamlCodec.parse(b, "id: RULE-2\ntitle: B\n")),
        ))
        assertTrue(session.applyWithAutoValues(tx, listOf(b), now, requireValid = true).result.ok)
        assertEquals(1L, scalar(root(session, b), "revision"))
        assertEquals("2026-10-05", scalar(root(session, b), "updated_on"))
        assertEquals(1, session.history.size)
        assertTrue(session.undo().ok)
        assertFalse(session.hasDocument(b))
    }

    @Test
    fun `自動値以外の検証に失敗すれば本文も自動値も履歴も反映しない`() {
        val session = WorkspaceSession(store())
        val b = DocumentId("rules/b.yaml")
        val tx = titleChange(session, "変更後").let { original -> original.copy(operations = original.operations +
            WorkspaceOperation.CreateDocument(b, dev.toros.yde.yaml.DefaultYamlCodec.parse(b, "id: RULE-2\n"))) }
        val result = session.applyWithAutoValues(tx, listOf(a, b), now, requireValid = true)
        assertFalse(result.result.ok)
        assertEquals(0L, session.version)
        assertTrue(session.history.isEmpty())
        assertFalse(session.hasDocument(b))
        assertEquals("A", scalar(root(session, a), "title"))
        assertEquals(3, scalar(root(session, a), "revision"))
    }

    @Test
    fun `保存の直前の 1 トランザクションで更新日と版が入り Undo で戻る 何も変わっていなければ履歴は増えない`() {
        val store = store()
        val session = WorkspaceSession(store)
        // 何も変わっていない → 計画は空で履歴も増えない
        assertTrue(session.applyAutoValues(setOf(a), now).isEmpty)
        assertEquals(0, session.history.size)

        session.apply(
            WorkspaceTransaction(
                session.version, ChangeIntent("編集"),
                listOf(WorkspaceOperation.EditDocument(a, listOf(YamlOperation.SetScalar(parseYPath("title"), "A2")))),
            )
        )
        val applied = session.applyAutoValues(setOf(a), now)
        assertTrue(applied.result.ok)
        assertEquals("文書: updated_on・revision", applied.plans.getValue(a).describe())
        assertEquals(2, session.history.size)
        assertTrue(session.history.last().intent.description.startsWith("自動の値を設定: rules/a.yaml"), session.history.last().intent.description)
        assertEquals("2026-10-05", scalar(root(session, a), "updated_on"))
        assertEquals(4L, scalar(root(session, a), "revision"))

        assertTrue(session.save().ok)
        assertEquals("id: RULE-1\ntitle: A2\nupdated_on: 2026-10-05\nrevision: 4\n", store.read(a)?.text)

        // 保存した後にもう一度当てても、保存済みと同じなので何も入らない
        assertTrue(session.applyAutoValues(setOf(a), now).isEmpty)

        // Undo 1 回で日付と版が戻る（内容はまだ A2）。もう 1 回で内容が戻る
        assertTrue(session.undo().ok)
        assertEquals("2026-01-01", scalar(root(session, a), "updated_on"))
        assertEquals(3, scalar(root(session, a), "revision"))
        assertEquals("A2", scalar(root(session, a), "title"))
        assertTrue(session.isDirty(a))
    }

    @Test
    fun `変更セットの適用は 検証の前に自動の値を入れ 同じトランザクションで保存する`() {
        val store = store()
        val session = WorkspaceSession(store)
        val changeSet = ChangeSets.parse(
            """
            changes:
              - file: rules/a.yaml
                ops:
                  - { op: set, path: title, value: A3 }
              - file: rules/b.yaml
                create: true
                content: { id: RULE-2, title: B }
            """.trimIndent()
        )
        // 新しい文書 b には必須の updated_on が無いが、自動の値が検証の前に入るので requireValid を通る
        val result = ChangeSetApplier.apply(session, changeSet, requireValid = true, now = now)
        assertTrue(result.ok, result.toString())
        assertEquals("id: RULE-1\ntitle: A3\nupdated_on: 2026-10-05\nrevision: 4\n", store.read(a)?.text)
        val b = store.read(DocumentId("rules/b.yaml"))?.text.orEmpty()
        assertTrue("updated_on: 2026-10-05" in b && "revision: 1" in b, b)
        // 1 つのトランザクション: Undo 1 回で内容も自動の値も戻る
        assertEquals(1, session.history.size)
        assertTrue(session.undo().ok)
        assertEquals("A", scalar(root(session, a), "title"))
        assertEquals(3, scalar(root(session, a), "revision"))
        assertFalse(session.hasDocument(DocumentId("rules/b.yaml")))
    }

    @Test
    fun `定義の点検 日付系以外の created updated と int 以外の revision と 式との併用 は誤り`() {
        val ws = WorkspaceReader.load(
            InMemoryDocumentStore(
                mapOf(
                    "_schemas/bad.yaml" to """
                        id: bad
                        match: "bad/*.yaml"
                        root:
                          type: object
                          fields:
                            - { key: a, type: string, auto: updated }
                            - { key: b, type: date, auto: revision }
                            - { key: c, type: string, auto: created, expression: true }
                            - { key: d, type: datetime, auto: created }
                            - { key: e, type: int, auto: revision }
                    """.trimIndent(),
                )
            ),
            builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY),
        )
        val errors = ws.schemaErrors.joinToString("\n")
        assertTrue("auto: updated（保存時に入る日付・時刻）は date / time / datetime 型でのみ使用できます" in errors, errors)
        assertTrue("auto: revision（保存のたびに +1）は int 型でのみ使用できます" in errors, errors)
        assertTrue("auto（保存時に自動で入る値）と expression（値の式）は同じ項目に書けません" in errors, errors)
        assertFalse("root.fields[3]" in errors || "root.fields[4]" in errors, "正しい書き方は指摘されない: $errors")
    }
}
