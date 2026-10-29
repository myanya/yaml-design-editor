package dev.toros.yde.desktop.ui.schema

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.form.FormCtx

class SchemaAuthoringHistoryTest {
    @Test
    fun `フォームで組み立てた操作をUndoで戻し保存後は同じ計画器で実行できる`() {
        val dir = createTempDirectory("yde-schema-authoring")
        try {
            val file = dir.resolve("_schemas/todo.yaml")
            Files.createDirectories(file.parent)
            Files.writeString(file, """
                # このコメントを保持する
                id: todo
                match: todo.yaml
                root:
                  type: object
                  entity: {type: todo, defines: true}
                  fields:
                    - {key: id, type: id, required: true}
                    - {key: status, type: enum, values: [OPEN, IN_PROGRESS]}
            """.trimIndent() + "\n")
            Files.writeString(dir.resolve("todo.yaml"), "id: TODO-1\nstatus: OPEN\n")
            val state = AppState().also { it.openWorkspace(dir) }
            val doc = state.ensureDoc(file)
            val ctx = FormCtx(state, doc, emptyList())
            val step = SchemaValueForms.presets.first { it.label == "値を設定（set）" }.node() as YNode.Map
            MapSlot(step.entries.getValue("set") as YNode.Map, "value").set(YNode.Scalar("IN_PROGRESS"))
            val action = YamlIO.parse("id: start\ntarget: {entity: todo}") as YNode.Map
            action.entries["do"] = YNode.Seq(mutableListOf(step))
            MapSlot(doc.root as YNode.Map, "actions").set(YNode.Seq(mutableListOf(action)))
            ctx.markEdited()
            assertTrue(doc.dirty)
            state.undo()
            assertFalse("actions" in (doc.root as YNode.Map).entries)
            state.redo()
            assertTrue("actions" in (doc.root as YNode.Map).entries)
            val valuePath = listOf("actions", 0, "do", 0, "set", "value")
            val items = schemaAdditionalOutline(doc.root as YNode.Map, doc.schema, false)
            val selected = items.single { it.path == valuePath }
            assertEquals(valuePath, schemaAdditionalSelection(items, valuePath, emptyList()))
            ctx.setScalar(schemaSlot(doc.root, selected.path)!!, "OPEN", selected.field!!.type)
            assertEquals("OPEN", (schemaSlot(doc.root, valuePath)!!.get() as YNode.Scalar).value)
            state.undo()
            assertEquals("IN_PROGRESS", (schemaSlot(doc.root, valuePath)!!.get() as YNode.Scalar).value)
            assertEquals(valuePath, schemaAdditionalSelection(
                schemaAdditionalOutline(doc.root as YNode.Map, doc.schema, false), valuePath, items))
            state.saveAll()
            assertFalse(doc.dirty, state.status)
            assertEquals(emptyList(), state.schemaErrors)
            assertTrue(Files.readString(file).contains("# このコメントを保持する"))
            state.startAction(state.actionsForEntity("TODO-1").single(), "TODO-1")
            state.planAction()
            assertNull(state.actionWork!!.error)
            state.applyAction().getOrThrow()
            assertTrue(Files.readString(dir.resolve("todo.yaml")).contains("IN_PROGRESS"))
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
