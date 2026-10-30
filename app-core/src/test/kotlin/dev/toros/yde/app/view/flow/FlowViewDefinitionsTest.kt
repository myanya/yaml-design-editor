package dev.toros.yde.app.view.flow

import dev.toros.yde.workspace.ViewDefinitions
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** フローの定義の読み取りと点検（元は workspace-core。flow を差し込み口を通る種類にしたので app.view.flow と一緒に置く。ADR-0014）。 */
class FlowViewDefinitionsTest {
    private val kinds = listOf(FlowViewKind)

    @Test
    fun `文書埋め込みは一意なフローだけを参照し適用条件は静的に比較しない`() {
        val doc = "id: doc\nkind: document\nmatch: 'other/*.yaml'\nsections: [{flow: {view: flow}}]"
        val source = DocumentId("doc.yaml")
        val good = ViewDefinitions.load(listOf(source to doc, this.source to flow), kinds = kinds)
        assertTrue(good.errors.isEmpty(), good.errors.toString())
        assertEquals("flow", good.docViews.single().sections.single().embed?.view)
        assertTrue(ViewDefinitions.load(listOf(source to doc), kinds = kinds).errors.single().contains("flow"))
        val grid = "id: flow\nrows: {idPattern: '*'}\ncolumns: [{label: ID, path: id}]"
        assertTrue(ViewDefinitions.load(listOf(source to doc, this.source to grid), kinds = kinds).errors.single().contains("flow"))
    }

    @Test
    fun `フローセクションの混在と空の参照を拒否する`() {
        for (section in listOf("{fields: {}, flow: {view: flow}}", "{flow: {}}", "{flow: {view: ''}}", "{flow: {view: flow, file: x}}")) {
            val text = "id: doc\nkind: document\nmatch: '*.yaml'\nsections: [$section]"
            assertTrue(ViewDefinitions.load(listOf(source to text), kinds = kinds).docViews.isEmpty(), section)
        }
    }

    private val source = DocumentId(".yde/views/flow.yaml")
    private val flow = """
        id: flow
        kind: flow
        schemaId: example
        match: 'flows/*.yaml'
        projection:
          id: model
          build: {nodes: [], edges: []}
    """.trimIndent()

    @Test
    fun `既存のビューとフローを同じカタログで扱う`() {
        val catalog = ViewDefinitions.load(listOf(
            source to flow,
            DocumentId(".yde/views/grid.yaml") to "id: grid\nrows: {idPattern: '*'}\ncolumns: [{label: ID, path: id}]",
            DocumentId(".yde/views/doc.yaml") to "id: doc\nkind: document\nschemaId: example\nsections: [{fields: {}}]",
        ), kinds = kinds)
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        assertNotNull(catalog.view("grid"))
        assertNotNull(catalog.docView("doc"))
        val definition = assertNotNull(catalog.extensionView("flow") as? VirtualFlowDef)
        assertEquals("down", definition.direction)
        assertTrue(definition.appliesTo(DocumentId("flows/test.yaml"), "example"))
        assertTrue(!definition.appliesTo(DocumentId("elsewhere/test.yaml"), "example"))
        assertTrue(!definition.appliesTo(DocumentId("flows/test.yaml"), "other"))
    }

    @Test
    fun `未対応の入力選択と配置と欠落を拒否する`() {
        val invalid = listOf(
            flow.replace("schemaId: example\nmatch: 'flows/*.yaml'\n", ""),
            flow + "\nlayout: {direction: left}",
            flow + "\nlayout: {positions: []}",
            flow + "\nrows: {}",
            flow.replace("id: model", "id: model\n  params: [other]"),
            flow.replace("id: model", "id: model\n  from: {match: [{node: {bind: doc}}]}"),
            flow.replace("build: {nodes: [], edges: []}", "build: {nodes: {${'$'}unknown: a}}"),
        )
        invalid.forEach { text ->
            val catalog = ViewDefinitions.load(listOf(source to text), kinds = kinds)
            assertTrue(catalog.extensionViews.isEmpty(), text)
            assertTrue(catalog.errors.isNotEmpty(), text)
        }
    }

    @Test
    fun `フローと他種別のビューでIDが重複しても指摘する`() {
        val result = ViewDefinitions.load(listOf(source to flow,
            DocumentId(".yde/views/doc.yaml") to "id: flow\nkind: document\nschemaId: example\nsections: [{fields: {}}]"), kinds = kinds)
        assertTrue(result.errors.single().contains("重複"))
    }

    @Test
    fun `存在しない適用先スキーマをリントする`() {
        val schemas = WorkspaceReader.parseSchemas(listOf(
            DocumentId("other.yaml") to "id: other\nmatch: '*.yaml'\nroot: {type: object}",
        )).schemas
        val result = ViewDefinitions.load(listOf(source to flow), schemas, kinds)
        assertTrue(result.errors.single().contains("example"))
        assertTrue(ViewDefinitions.lintDocument(YamlIO.parse(flow), source, schemas, kinds).single().contains("example"))
    }
}
