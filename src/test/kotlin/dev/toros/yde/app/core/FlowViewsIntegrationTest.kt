package dev.toros.yde.app.core

import dev.toros.yde.app.cli.runCli
import dev.toros.yde.semantic.FlowConnectionState
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowViewsIntegrationTest {
    @Test
    fun `埋め込みもCLIで同じモデルを返し適用外は診断する`() {
        write(".yde/views/document.yaml", """
            id: document
            kind: document
            schemaId: flow
            sections:
              - fields: {}
              - flow: {view: diagram}
        """)
        val output = YamlIO.parse(runCli(arrayOf("view", directory.toString(), "document", "flows/one.yaml")))
        val embedded = getAt(output, parseYPath("sections[1].flow"))
        val standalone = YamlIO.parse(runCli(arrayOf("view", directory.toString(), "diagram", "flows/one.yaml")))
        assertEquals(YamlIO.toAny(standalone), YamlIO.toAny(assertNotNull(embedded)))
        val ws = Workspace.open(directory)
        val schema = assertNotNull(ws.schemaFor(directory.resolve(".yde/views/document.yaml")))
        val root = YamlIO.parse(Files.readString(directory.resolve(".yde/views/document.yaml")))
        assertTrue(dev.toros.yde.workspace.DerivedStructures(schema.tree).validate(root).isEmpty())
        val catalog = Views.load(directory, ws.schemas)
        val outside = FlowReports.embedded("diagram", DocumentId("outside.yaml"), catalog, ws.index, { null }, { root })
        assertTrue(outside.toString().contains("not-applicable"))
        assertTrue(FlowReports.embedded("missing", DocumentId("flows/one.yaml"), catalog, ws.index, ws::schemaFor, ws::treeOf)
            .toString().contains("一意なflow"))
    }

    @Test
    fun `原本フォーム経路の編集とUndoと未保存定義を図へ反映する`() {
        val state = dev.toros.yde.desktop.AppState().also { it.openWorkspace(directory) }
        val document = DocumentId("flows/one.yaml")
        val doc = state.ensureDoc(document)
        val ctx = dev.toros.yde.desktop.ui.FormCtx(state, doc, emptyList())
        val slot = assertNotNull(dev.toros.yde.desktop.ui.slotAt(assertNotNull(doc.root), parseYPath("items[0].title")))
        ctx.setScalar(slot, "変更後", dev.toros.yde.semantic.schema.FieldType.STRING)
        fun resolve() = Views.resolveFlow(assertNotNull(Views.load(directory, state.schemas, state.unsavedFirstTree())
            .flowView("diagram")), document, state.overlayIndex(), state::schemaFor, state.unsavedFirstTree())
        assertEquals("変更後", resolve().model!!.nodes.single().label)
        assertTrue(doc.dirty)
        state.undo()
        assertEquals("活動", resolve().model!!.nodes.single().label)
        val definition = state.ensureDoc(DocumentId(".yde/views/flow.yaml"))
        val defRoot = definition.root as dev.toros.yde.yaml.model.YNode.Map
        defRoot.entries["layout"] = YamlIO.parse("direction: right")
        definition.markEdited()
        assertEquals("right", Views.load(directory, state.schemas, state.unsavedFirstTree()).flowView("diagram")?.direction)
        val readOnly = dev.toros.yde.desktop.ui.FormCtx(state, doc, emptyList(), readOnly = true)
        kotlin.test.assertFailsWith<IllegalStateException> { readOnly.markEdited() }
    }

    private lateinit var directory: Path
    private fun write(path: String, text: String) {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent())
    }

    @BeforeTest
    fun prepare() {
        directory = createTempDirectory("yde-flow-")
        write(".yde/workspace.yaml", "ledger: [flows]")
        write(".yde/schemas/flow.yaml", """
            id: flow
            match: flows/*.yaml
            root:
              type: object
              fields:
                - {key: id, type: id}
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - {key: id, type: id}
                      - {key: title, type: string}
                - key: links
                  type: array
                  item:
                    type: object
                    fields:
                      - {key: from, type: ref}
                      - {key: to, type: ref}
        """)
        write(".yde/views/flow.yaml", """
            id: diagram
            kind: flow
            schemaId: flow
            projection:
              id: model
              build:
                nodes:
                  ${'$'}each: items
                  ${'$'}build:
                    key: {${'$'}copy: id}
                    label: {${'$'}copy: title}
                    detail: {${'$'}copy: .}
                edges:
                  ${'$'}each: links
                  ${'$'}build:
                    from: {${'$'}copy: from}
                    to: {${'$'}copy: to}
                    detail: {${'$'}copy: .}
        """)
        write("flows/one.yaml", """
            id: FLOW-ONE
            items: [{id: A, title: 活動}]
            links: [{from: A, to: OUTSIDE}, {from: A, to: UNKNOWN}]
        """)
        write("flows/two.yaml", "id: OUTSIDE\nitems: []\nlinks: []")
    }

    @AfterTest
    fun cleanup() { directory.toFile().deleteRecursively() }

    @Test
    fun `CLIで相対文書IDと原本座標と参照診断を取得できる`() {
        val listing = runCli(arrayOf("view", directory.toString()))
        assertTrue(listing.contains("kind: flow"))
        val candidates = runCli(arrayOf("view", directory.toString(), "diagram"))
        assertTrue(candidates.contains("flows/one.yaml") && candidates.contains("flows/two.yaml"))
        assertFalse(candidates.contains("model:"))
        val output = YamlIO.parse(runCli(arrayOf("view", directory.toString(), "diagram", directory.resolve("flows/one.yaml").toString())))
        fun value(path: String) = scalarText(getAt(output, parseYPath(path)))
        assertEquals("flows/one.yaml", value("file"))
        assertEquals("flows/one.yaml", value("model.nodes[0].detail.origins[0].file"))
        assertEquals("items[0]", value("model.nodes[0].detail.origins[0].path"))
        assertEquals("outside", value("model.edges[0].to.state"))
        assertEquals("unresolved", value("model.edges[1].to.state"))
        assertEquals("true", value("model.nodes[0].detail.editable"))
    }

    @Test
    fun `スキーマ上の参照でない接続先は未解決IDと断定しない`() {
        val schemaPath = directory.resolve(".yde/schemas/flow.yaml")
        Files.writeString(schemaPath, Files.readString(schemaPath).replace("key: to, type: ref", "key: to, type: string"))
        val ws = Workspace.open(directory)
        val definition = assertNotNull(Views.load(directory, ws.schemas).flowView("diagram"))
        val result = Views.resolveFlow(definition, DocumentId("flows/one.yaml"), ws.index, ws::schemaFor, ws::treeOf)
        assertTrue(result.model!!.edges.all { it.to.state == FlowConnectionState.UNKNOWN })
    }

    @Test
    fun `未保存の原本を渡すと表示内容が追随する`() {
        val ws = Workspace.open(directory)
        val definition = assertNotNull(Views.load(directory, ws.schemas).flowView("diagram"))
        val input = YamlIO.parse("items: [{id: A, title: 未保存の編集}]\nlinks: []")
        val result = Views.resolveFlow(definition, DocumentId("flows/one.yaml"), ws.index, ws::schemaFor) { input }
        assertEquals("未保存の編集", result.model!!.nodes.single().label)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(Files.readString(directory.resolve("flows/one.yaml")).contains("title: 活動"))
    }

    @Test
    fun `エージェントとCLIが同じモデルと原本座標を開示する`() {
        val ws = Workspace.open(directory)
        val activity = dev.toros.yde.app.agent.ActivityDef(
            id = "test", name = "テスト", agentId = "a", instruction = "テスト",
            source = directory.resolve(".yde/activities/test.yaml"),
        )
        val toolbox = dev.toros.yde.app.agent.AgentToolbox(ws, dev.toros.yde.app.agent.Staging(ws), activity,
            dev.toros.yde.app.agent.ToolGroup.entries.toSet())
        assertTrue(toolbox.viewText(null).contains("フロービュー"))
        val agent = toolbox.viewText("diagram", "flows/one.yaml")
        val cli = runCli(arrayOf("view", directory.toString(), "diagram", "flows/one.yaml"))
        assertEquals(YamlIO.parseToRaw(cli), YamlIO.parseToRaw(agent))
    }

    @Test
    fun `組込みメタスキーマでフロー定義を検証できる`() {
        val ws = Workspace.open(directory)
        val file = directory.resolve(".yde/views/flow.yaml")
        val schema = assertNotNull(ws.schemaFor(file))
        assertEquals("builtin-view", schema.id)
        val root = YamlIO.parse(Files.readString(file))
        val issues = dev.toros.yde.workspace.DerivedStructures(schema.tree).validate(root)
        assertTrue(issues.isEmpty(), issues.toString())
    }
}
