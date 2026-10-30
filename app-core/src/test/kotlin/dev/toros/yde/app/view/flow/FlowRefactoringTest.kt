package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.workspace.BuiltinSchemaResources
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.model.DocumentId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** labsに依存せず、参照型の追随と投影の要確認箇所を計画・適用・Undoまで検証する。 */
class FlowRefactoringTest {
    @Test
    fun `ビューIDの改名で文書埋め込みも追随し一回のUndoで戻る`() {
        val s = FlowRefactoringFixture.session()
        val before = texts(s)
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("diagram", "renamed"))
        assertEquals(setOf("_views/flow.yaml", "_views/doc.yaml"), plan.changes.map { it.document.value }.toSet())
        assertTrue(s.applyRefactoring(plan).ok)
        val catalog = catalog(s)
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        assertEquals("renamed", catalog.docViews.single().sections.single().embed?.view)
        assertEquals("renamed", catalog.extensionViews.single().id)
        assertTrue(s.derived.semantic.issues.isEmpty(), s.derived.semantic.issues.toString())
        assertTrue(s.undo().ok)
        assertEquals(before, texts(s))
    }

    @Test
    fun `埋め込まれたビューの削除は既存の被参照検査で拒否する`() {
        val s = FlowRefactoringFixture.session()
        val error = assertFailsWith<IllegalStateException> {
            s.planRefactoring(RefactoringRequest.DeleteEntity("diagram"))
        }
        assertTrue("参照" in error.message.orEmpty())
    }

    @Test
    fun `活動IDの改名は原本の辺に追随しコピーによる投影も更新される`() {
        val s = FlowRefactoringFixture.session()
        val before = texts(s)
        val plan = s.planRefactoring(RefactoringRequest.RenameEntity("ACT-1", "ACT-9"))
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString())
        assertTrue(s.applyRefactoring(plan).ok)
        val input = s.snapshot.documents.getValue(DocumentId("flows/a.yaml")).document.documents.first()
        val result = FlowViews.resolve(catalog(s).extensionViews.single() as VirtualFlowDef, DocumentId("flows/a.yaml"), "process", input)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(listOf("ACT-9", "ACT-2"), result.model!!.nodes.map { it.key })
        assertEquals("ACT-9", result.model!!.edges.single().from.key)
        assertTrue(s.undo().ok)
        assertEquals(before, texts(s))
    }

    @Test
    fun `項目の改名は投影の相対パスと規則の言及を場所付きで警告する`() {
        val s = FlowRefactoringFixture.session()
        val before = texts(s)
        val plan = s.planRefactoring(RefactoringRequest.RenameField("process", "activities.title", "name"))
        assertTrue(plan.warnings.any { "projection.build.nodes.\$each" !in it && "\$format" in it && "title" in it })
        assertTrue(plan.warnings.any { "projection.rules[1].build.\$copy" in it }, plan.warnings.toString())
        assertTrue(plan.changes.none { it.document.value == "_views/flow.yaml" })
        assertEquals(before, texts(s), "影響調査は原本も投影も変更しない")
    }

    @Test
    fun `固定IDや関係名は規則の辞書キーや条件も警告し自動置換しない`() {
        val s = FlowRefactoringFixture.session(extraRule = true)
        for (request in listOf(
            RefactoringRequest.RenameEntity("ACT-1", "ACT-9"),
            RefactoringRequest.RenameRelation("next", "follows"),
            RefactoringRequest.RenameSchemaId("process", "procedure"),
        )) {
            val plan = s.planRefactoring(request)
            assertTrue(plan.warnings.any { "_views/flow.yaml: projection.rules" in it }, plan.warnings.toString())
            assertTrue(plan.changes.none { it.document.value == "_views/flow.yaml" })
        }
    }

    @Test
    fun `原本文書の移動で図と埋め込みの適用対象から外れることを警告する`() {
        val s = FlowRefactoringFixture.session()
        val plan = s.planRefactoring(RefactoringRequest.MoveDocument(DocumentId("flows/a.yaml"), DocumentId("elsewhere/a.yaml")))
        assertTrue(plan.warnings.any { "フロービュー「diagram」" in it }, plan.warnings.toString())
        assertTrue(plan.warnings.any { "文書ビュー「document」" in it }, plan.warnings.toString())
    }

    @Test
    fun `配列の改名と項目削除でも投影の言及を警告する`() {
        val s = FlowRefactoringFixture.session()
        val rename = s.planRefactoring(RefactoringRequest.RenameField("process", "activities", "steps"))
        assertTrue(rename.warnings.any { "projection.build.nodes.\$each" in it }, rename.warnings.toString())
        val delete = s.planRefactoring(RefactoringRequest.DeleteField("process", "activities.title"))
        assertTrue(delete.warnings.any { "projection" in it && "title" in it }, delete.warnings.toString())
    }

    private fun texts(s: WorkspaceSession) = s.snapshot.documents.mapValues { DefaultYamlCodec.dump(it.value.document) }
    private fun catalog(s: WorkspaceSession) = ViewDefinitions.load(
        texts(s).filterKeys { it.value.startsWith("_views/") }.toList(), s.schemas, listOf(FlowViewKind),
    )
}

internal object FlowRefactoringFixture {
    fun session(extraRule: Boolean = false): WorkspaceSession {
        val store = InMemoryDocumentStore(mapOf(
            "_schemas/process.yaml" to """
                id: process
                match: 'flows/*.yaml'
                root:
                  type: object
                  fields:
                    - {key: id, type: id}
                    - key: activities
                      type: array
                      item:
                        type: object
                        fields:
                          - {key: id, type: id}
                          - {key: title, type: string}
                    - key: transitions
                      type: array
                      item:
                        type: object
                        fields:
                          - {key: id, type: id}
                          - {key: from, type: ref, relation: next}
                          - {key: to, type: ref, relation: next}
            """.trimIndent(),
            "_schemas/relations.yaml" to "id: relations\nkind: relations\nrelations: [{name: next}]\n",
            "flows/a.yaml" to """
                id: FLOW-1
                activities: [{id: ACT-1, title: 開始}, {id: ACT-2, title: 完了}]
                transitions: [{id: EDGE-1, from: ACT-1, to: ACT-2}]
            """.trimIndent(),
            "_views/flow.yaml" to """
                id: diagram
                kind: flow
                schemaId: process
                match: 'flows/*.yaml'
                projection:
                  id: model
                  build:
                    nodes:
                      ${'$'}each: activities
                      ${'$'}build:
                        key: {${'$'}copy: id}
                        label: {${'$'}format: '{title}'}
                        detail: {${'$'}copy: .}
                    edges:
                      ${'$'}each: transitions
                      ${'$'}build:
                        key: {${'$'}copy: id}
                        from: {${'$'}copy: from}
                        to: {${'$'}copy: to}
                        detail: {${'$'}copy: .}
                  rules:
                    - id: label
                      build: {${'$'}copy: title}
            """.trimIndent() + if (extraRule) "\n" + """

                    - id: constants
                      when: [{path: id, equals: ACT-1}]
                      build:
                        ${'$'}map: next
                        ${'$'}using: {ACT-1: process}
            """.trimIndent().prependIndent("    ") else "",
            "_views/doc.yaml" to """
                id: document
                kind: document
                schemaId: process
                match: 'flows/*.yaml'
                sections: [{flow: {view: diagram}}]
            """.trimIndent(),
        ))
        val kinds = listOf(FlowViewKind)
        return WorkspaceSession(store, builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY, kinds), viewKinds = kinds) { Instant.EPOCH }
    }
}
