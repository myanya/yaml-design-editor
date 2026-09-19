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
        assertTrue(stale.staleBase, "CLI の exit 4 判定に使う型付き理由")
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

    private fun renameFieldStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                phases:
                  key: stage
                  order: [draft, done]
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: stage
                      type: string
                    - key: details
                      type: object
                      fields:
                        - key: priority
                          type: string
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ndetails:\n  priority: high  # 大事\n",
            "items/b.yaml" to "id: IT-002\n",
        )
    )

    @Test
    fun `rename-fieldは文書キーとスキーマ定義を1トランザクションで書き換える`() {
        val store = renameFieldStore()
        val s = session(store)
        val plan = s.planRefactoring(semantic.RefactoringRequest.RenameField("fx-item", "details.priority", "urgency"))
        assertEquals(0, s.version, "plan は不変")
        // 文書側（RenameKey）とスキーマ側（fields[i].key の Set）が1計画にまとまる
        assertEquals(listOf("_schemas/item.yaml", "items/a.yaml"), plan.changes.map { it.document.value })
        assertTrue(plan.summary.any { "文書 1 ファイル・1 箇所" in it }, "${plan.summary}")
        assertTrue(plan.warnings.any { "追随しません" in it }, "ビュー非追随の案内: ${plan.warnings}")
        assertTrue(s.applyRefactoring(plan).ok)
        val docText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertTrue("urgency: high" in docText && "大事" in docText, "キー改名でコメント保持: $docText")
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("key: urgency" in schemaText && "key: priority" !in schemaText, schemaText)
        // スキーマ再構築（WS-006）: 適用直後の検証が新スキーマで行われ、未定義キー警告が出ない
        assertTrue(
            s.derived.structuralIssues.values.flatten().isEmpty(),
            "改名後のキーは新スキーマで定義済み: ${s.derived.structuralIssues}",
        )
        // 1回の Undo で文書もスキーマも戻る
        assertTrue(s.undo().ok)
        assertTrue("priority: high" in DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document))
    }

    @Test
    fun `rename-fieldはルート直下フィールドならphasesのkeyも追随する`() {
        val s = session(renameFieldStore())
        val plan = s.planRefactoring(semantic.RefactoringRequest.RenameField("fx-item", "stage", "phase"))
        val schemaOps = plan.changes.single { it.document.value == "_schemas/item.yaml" }.operations
        assertTrue(
            schemaOps.any { it is edit.YamlOperation.SetScalar && it.path == listOf<Any>("phases", "key") },
            "$schemaOps",
        )
        assertTrue(s.applyRefactoring(plan).ok)
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("key: phase" in schemaText, schemaText)
    }

    @Test
    fun `rename-fieldは改名先キーが既存文書にあると計画段階で拒否される`() {
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
                        - key: priority
                          type: string
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\npriority: high\nurgency: 既存\n",
            )
        )
        val s = session(store)
        val e = assertFailsWith<IllegalStateException> {
            s.planRefactoring(semantic.RefactoringRequest.RenameField("fx-item", "priority", "urgency"))
        }
        assertTrue("中止します" in (e.message ?: ""), "${e.message}")
        assertTrue("items/a.yaml" in (e.message ?: ""), "${e.message}")
    }

    private fun enumStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                entity:
                  type: item
                  owns: [status, memo]
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: status
                      type: enum
                      values: [draft, review, approved]
                      default: draft
                      cases:
                        review:
                          - key: reviewer
                            type: string
                    - key: memo
                      type: string
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\nstatus: review\nreviewer: 佐藤\n",
            "items/b.yaml" to "id: IT-002\nstatus: draft\nmemo: x\n",
        )
    )

    @Test
    fun `renameEnumValueはvalues・casesキー・default・文書値を追随する`() {
        val s = session(enumStore())
        val plan = s.planRefactoring(
            semantic.RefactoringRequest.RenameEnumValue("fx-item", "status", "review", "in-review")
        )
        assertTrue(s.applyRefactoring(plan).ok)
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("in-review" in schemaText && "cases:" in schemaText, schemaText)
        assertTrue("review:" !in schemaText.replace("in-review:", ""), "casesキーも改名: $schemaText")
        val docText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertTrue("status: in-review" in docText, docText)
        // 新スキーマで検証されるため enum 値エラーが出ない（WS-006 との結合）
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
        // default: draft は対象外（改名したのは review）
        assertTrue("default: draft" in schemaText, schemaText)
    }

    @Test
    fun `deleteFieldは文書キーとentityのownsを掃除しID型は拒否する`() {
        val s = session(enumStore())
        val plan = s.planRefactoring(semantic.RefactoringRequest.DeleteField("fx-item", "memo"))
        assertTrue(plan.summary.any { "値あり 1 箇所" in it }, "データ損失の可視化: ${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("memo" !in schemaText, "定義と owns から消える: $schemaText")
        assertTrue("memo" !in DefaultYamlCodec.dump(s.snapshot.documents.getValue(b).document))
        // ID型は拒否
        assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.DeleteField("fx-item", "id"))
        }
    }

    @Test
    fun `requireFieldは必須化と欠落値の充填を1計画で行う`() {
        val s = session(enumStore())
        val plan = s.planRefactoring(
            semantic.RefactoringRequest.RequireField("fx-item", "memo", default = "未記入")
        )
        assertTrue(plan.summary.any { "1 箇所へ既定値" in it }, "a.yaml の欠落だけ充填: ${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("required: true" in schemaText, schemaText)
        assertTrue("memo: 未記入" in DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document))
        // 充填済みなので新スキーマの必須検証を満たす
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
    }

    private fun moveFieldStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: priority
                      type: string
                    - key: details
                      type: object
                      fields:
                        - key: note
                          type: string
                    - key: tasks
                      type: array
                      item:
                        type: object
                        fields:
                          - key: name
                            type: string
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\npriority: high  # 大事\n",
            "items/b.yaml" to "id: IT-002\npriority: low\ndetails:\n  note: x\n",
        )
    )

    @Test
    fun `moveFieldは中間objectを生成して値とスキーマ定義を移動し1回のUndoで戻る`() {
        val store = moveFieldStore()
        val s = session(store)
        val before = dumpAll(s)
        val plan = s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "priority", "details"))
        assertEquals(0, s.version, "plan は不変")
        assertEquals(listOf("_schemas/item.yaml", "items/a.yaml", "items/b.yaml"), plan.changes.map { it.document.value })
        assertTrue(plan.summary.any { "文書 2 ファイル・2 箇所" in it }, "${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val aText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertTrue("details:" in aText && "  priority: high" in aText && "大事" in aText, "中間 object 生成＋コメント保持: $aText")
        val bText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(b).document)
        assertTrue("  note: x" in bText && "  priority: low" in bText, bText)
        // スキーマ側も details 配下へ移動し、再構築後の検証で未定義キーが出ない（WS-006 との結合）
        assertTrue(s.derived.structuralIssues.values.flatten().isEmpty(), "${s.derived.structuralIssues}")
        // 1回の Undo で文書もスキーマも戻る
        assertTrue(s.undo().ok)
        assertEquals(before, dumpAll(s))
    }

    @Test
    fun `moveFieldは空になった親objectを削除する`() {
        val s = session(moveFieldStore())
        val plan = s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "details.note", "."))
        assertTrue(s.applyRefactoring(plan).ok)
        val bText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(b).document)
        assertTrue("note: x" in bText && "details" !in bText, "空になった details は削除: $bText")
        assertTrue(s.derived.structuralIssues.values.flatten().isEmpty(), "${s.derived.structuralIssues}")
    }

    @Test
    fun `moveFieldは移動先の既存値や境界またぎを拒否する`() {
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
                        - key: note
                          type: string
                        - key: details
                          type: object
                          fields:
                            - key: note
                              type: string
                        - key: tasks
                          type: array
                          item:
                            type: object
                            fields:
                              - key: name
                                type: string
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\nnote: 既存\ndetails:\n  note: x\n",
            )
        )
        val s = session(store)
        // 移動先（ルート）に同名フィールドの定義がある → スキーマ側の衝突で拒否
        assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "details.note", "."))
        }
        // 同じ親への移動は拒否
        assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "note", "."))
        }
        // 配列の境界をまたぐ移動（要素コンテキスト不一致）は拒否
        val e = assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "tasks.name", "details"))
        }
        assertTrue("またぐ移動はできません" in (e.message ?: ""), "${e.message}")
    }

    @Test
    fun `moveFieldは移動先の既存文書値があると計画段階で中止する`() {
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
                        - key: details
                          type: object
                          fields:
                            - key: note
                              type: string
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\nnote: 既存\ndetails:\n  note: x\n",
            )
        )
        val s = session(store)
        val e = assertFailsWith<IllegalStateException> {
            s.planRefactoring(semantic.RefactoringRequest.MoveField("fx-item", "details.note", "."))
        }
        assertTrue("中止します" in (e.message ?: ""), "${e.message}")
        assertTrue("items/a.yaml" in (e.message ?: ""), "${e.message}")
    }

    @Test
    fun `alignOrderは文書キーをスキーマ順へ並べ替え未知キーは末尾に残す`() {
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
                        - key: status
                          type: string
                """.trimIndent(),
                "items/a.yaml" to "status: draft\nextra: x\nid: IT-001\ntitle: A\n",
            )
        )
        val s = session(store)
        val plan = s.planRefactoring(semantic.RefactoringRequest.AlignOrder("fx-item"))
        assertTrue(plan.summary.any { "1 ファイル・1 要素" in it }, "${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val aText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertEquals("id: IT-001\ntitle: A\nstatus: draft\nextra: x\n", aText)
        // 冪等: 整列済みなら計画は空
        val second = s.planRefactoring(semantic.RefactoringRequest.AlignOrder("fx-item"))
        assertTrue(second.changes.isEmpty(), "${second.changes}")
    }

    private fun convertStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: tag
                      type: string
                      maxLength: 10
                      default: none
                    - key: score
                      type: string
                    - key: labels
                      type: array
                      item:
                        type: string
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ntag: hello\nscore: \"42\"\nlabels: [x, y]\n",
            "items/b.yaml" to "id: IT-002\nscore: \"7\"\nlabels: [z]\n",
        )
    )

    @Test
    fun `convertFieldTypeはスカラーをarrayへ包み制約をitemへ移す`() {
        val s = session(convertStore())
        val before = dumpAll(s)
        val plan = s.planRefactoring(semantic.RefactoringRequest.ConvertFieldType("fx-item", "tag", "array"))
        assertTrue(plan.warnings.any { "default はスカラー用" in it }, "${plan.warnings}")
        assertTrue(plan.summary.any { "文書 1 ファイル・1 箇所" in it }, "tag があるのは a だけ: ${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val aText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertTrue("- hello" in aText, "1要素の配列へ包む: $aText")
        val schemaText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("type: array" in schemaText && "item:" in schemaText, schemaText)
        assertTrue("default" !in schemaText, "default は削除: $schemaText")
        // maxLength は item 側へ移る（新スキーマの検証で ERROR が出ない）
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
        // 1回の Undo で文書もスキーマも戻る
        assertTrue(s.undo().ok)
        assertEquals(before, dumpAll(s))
    }

    @Test
    fun `convertFieldTypeはstringをintへ型付けし解析できない値では中止する`() {
        val s = session(convertStore())
        val plan = s.planRefactoring(semantic.RefactoringRequest.ConvertFieldType("fx-item", "score", "int"))
        assertTrue(s.applyRefactoring(plan).ok)
        val aText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(a).document)
        assertTrue("score: 42" in aText, "引用符が外れ int 値になる: $aText")
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
        // 解析できない値がある場合は計画段階で中止
        val bad = InMemoryDocumentStore(
            mapOf(
                "_schemas/item.yaml" to """
                    id: fx-item
                    match: "items/**/*.yaml"
                    root:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: score
                          type: string
                """.trimIndent(),
                "items/x.yaml" to "id: IT-009\nscore: abc\n",
            )
        )
        val e = assertFailsWith<IllegalStateException> {
            session(bad).planRefactoring(semantic.RefactoringRequest.ConvertFieldType("fx-item", "score", "int"))
        }
        assertTrue("中止します" in (e.message ?: "") && "items/x.yaml" in (e.message ?: ""), "${e.message}")
        // 配列の解除は複数要素があると中止（a の labels は2要素）
        val e2 = assertFailsWith<IllegalStateException> {
            session(convertStore()).planRefactoring(semantic.RefactoringRequest.ConvertFieldType("fx-item", "labels", "string"))
        }
        assertTrue("要素 2 件" in (e2.message ?: ""), "${e2.message}")
    }

    private fun definitionStore() = InMemoryDocumentStore(
        mapOf(
            "_schemas/lib.yaml" to """
                id: fx-lib
                kind: library
                definitions:
                  price:
                    type: int
                    min: 0
            """.trimIndent(),
            "_schemas/item.yaml" to """
                id: fx-item
                match: "items/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: id
                      type: id
                    - key: cost
                      ${'$'}ref: fx-lib#price
            """.trimIndent(),
            "items/a.yaml" to "id: IT-001\ncost: 5\n",
        )
    )

    @Test
    fun `renameDefinitionはdefinitionsキーと参照refを書き換える`() {
        val s = session(definitionStore())
        val plan = s.planRefactoring(semantic.RefactoringRequest.RenameDefinition("fx-lib", "price", "amount"))
        assertEquals(listOf("_schemas/item.yaml", "_schemas/lib.yaml"), plan.changes.map { it.document.value })
        assertTrue(plan.summary.any { "参照 1 箇所" in it }, "${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val libText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/lib.yaml")).document)
        assertTrue("amount:" in libText && "price" !in libText, libText)
        val itemText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("fx-lib#amount" in itemText, itemText)
        // 新スキーマでも cost の \$ref が解決され検証が通る（WS-006 との結合）
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
        // 既存の定義名への改名は拒否
        assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.RenameDefinition("fx-lib", "amount", "amount"))
        }
    }

    @Test
    fun `extractDefinitionとinlineDefinitionは往復できる`() {
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
                        - key: amount
                          type: int
                          min: 0
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\namount: 3\n",
            )
        )
        val s = session(store)
        // 抽出: 構造キーを definitions へ移し \$ref に置き換える（definitions が無ければ生成）
        val extract = s.planRefactoring(semantic.RefactoringRequest.ExtractDefinition("fx-item", "amount", "money"))
        assertTrue(extract.summary.any { "2 キーを移動" in it }, "type と min: ${extract.summary}")
        assertTrue(s.applyRefactoring(extract).ok)
        val extracted = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("definitions:" in extracted && "money:" in extracted && "\$ref: money" in extracted, extracted)
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "抽出後も amount は int として検証される: ${s.derived.structuralIssues}",
        )
        // インライン化: \$ref を内容の複製へ展開し、参照が残らないため定義自体も削除
        val inline = s.planRefactoring(semantic.RefactoringRequest.InlineDefinition("fx-item", "amount"))
        assertTrue(inline.summary.any { "自体も削除します" in it }, "${inline.summary}")
        assertTrue(s.applyRefactoring(inline).ok)
        val inlined = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("\$ref" !in inlined && "money" !in inlined, inlined)
        assertTrue("min: 0" in inlined, "構造キーがフィールドへ戻る: $inlined")
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "${s.derived.structuralIssues}",
        )
    }

    @Test
    fun `renameRelationはレジストリとスキーマのrelation参照を書き換える`() {
        val store = InMemoryDocumentStore(
            mapOf(
                "_schemas/rel.yaml" to """
                    id: fx-rel
                    kind: relations
                    relations:
                      - name: belongs
                        from: item
                        to: group
                      - name: blocks
                        from: item
                        to: item
                """.trimIndent(),
                "_schemas/item.yaml" to """
                    id: fx-item
                    match: "items/**/*.yaml"
                    entity:
                      type: item
                    root:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: group
                          type: ref
                          relation: belongs
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\n",
            )
        )
        val s = session(store)
        val plan = s.planRefactoring(semantic.RefactoringRequest.RenameRelation("belongs", "linked"))
        assertTrue(plan.summary.any { "2 箇所" in it }, "レジストリの name とスキーマの relation: ${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val relText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/rel.yaml")).document)
        assertTrue("name: linked" in relText && "belongs" !in relText, relText)
        val itemText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("relation: linked" in itemText, itemText)
        // 宣言済みの関係名への改名は拒否
        val e = assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.RenameRelation("linked", "blocks"))
        }
        assertTrue("既にレジストリに宣言" in (e.message ?: ""), "${e.message}")
    }

    @Test
    fun `renameSchemaIdはidと修飾refとschemaId系参照を書き換える`() {
        val store = InMemoryDocumentStore(
            mapOf(
                "_schemas/lib.yaml" to """
                    id: fx-lib
                    kind: library
                    definitions:
                      price:
                        type: int
                """.trimIndent(),
                "_schemas/item.yaml" to """
                    id: fx-item
                    match: "items/**/*.yaml"
                    coverage:
                      - detailIn:
                          schemaIds: [fx-lib]
                    root:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: cost
                          ${'$'}ref: fx-lib#price
                """.trimIndent(),
                "items/a.yaml" to "id: IT-001\ncost: 5\n",
            )
        )
        val s = session(store)
        val plan = s.planRefactoring(semantic.RefactoringRequest.RenameSchemaId("fx-lib", "fx-common"))
        assertTrue(plan.summary.any { "参照 2 箇所" in it }, "\$ref と detailIn.schemaIds: ${plan.summary}")
        assertTrue(s.applyRefactoring(plan).ok)
        val libText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/lib.yaml")).document)
        assertTrue("id: fx-common" in libText, libText)
        val itemText = DefaultYamlCodec.dump(s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).document)
        assertTrue("fx-common#price" in itemText && "fx-lib" !in itemText, itemText)
        assertTrue(
            s.derived.structuralIssues.values.flatten().none { it.severity == validate.Severity.ERROR },
            "改名後も \$ref が解決される: ${s.derived.structuralIssues}",
        )
        // 既存IDへの改名は拒否
        assertFailsWith<IllegalArgumentException> {
            s.planRefactoring(semantic.RefactoringRequest.RenameSchemaId("fx-item", "fx-common"))
        }
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
