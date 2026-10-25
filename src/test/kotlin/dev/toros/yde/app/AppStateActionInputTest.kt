package dev.toros.yde.app

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 操作入力の現在値・日時・差分と、確認から適用までの変更検知。 */
class AppStateActionInputTest {
    private lateinit var dir: Path
    private val now = LocalDateTime.of(2026, 10, 25, 23, 59)

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-action-input")
        write("_schemas/items.yaml", """
            id: items
            match: list.yaml
            entity: {type: item, idPattern: 'I-*', defines: true}
            definitions:
              required-date: {type: date, required: true}
            root:
              type: array
              item:
                type: object
                fields:
                  - {key: id, type: id}
                  - {key: name, type: string}
                  - {key: note, type: string}
                  - {key: status, type: string}
            actions:
              - id: edit
                target: {entity: item}
                params:
                  - {key: name, type: string}
                  - {key: note, type: string}
                  - {key: date, type: required-date}
                  - {key: clear, type: boolean}
                input:
                  submit: changed
                  initial:
                    name: {${'$'}copy: name, ${'$'}of: self}
                    note: {${'$'}copy: note, ${'$'}of: self}
                    date: {${'$'}copy: today, ${'$'}of: context}
                require:
                  - match: [{node: {entity: item, bind: item}}]
                    where: [{of: item, path: status, equals: open}]
                    expect: exists
                    message: 対象は未完了である必要があります
                do:
                  - when: {path: name, exists: true}
                    set: {of: self, path: name, value: ${'$'}name}
                  - when: {path: note, exists: true}
                    set: {of: self, path: note, value: ${'$'}note}
                  - when: {path: clear, equals: true}
                    delete: {of: self, path: note}
        """)
        write("list.yaml", """
            - {id: I-1, name: 最初, note: 残すメモ, status: open}
            - {id: I-2, name: 次, note: 次のメモ, status: closed}
        """)
    }

    @AfterTest
    fun cleanup() = Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }

    private fun write(rel: String, text: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    private fun open(): AppState = AppState().also { state ->
        state.openWorkspace(dir)
        assertEquals(emptyList(), state.schemaErrors)
        state.startAction(state.actionsForEntity("I-1").single(), "I-1", now)
        assertNull(state.actionWork!!.error)
    }

    private fun value(state: AppState, key: String) = state.actionWork!!.values.entries[key]?.let(YamlIO::toAny)
    private fun input(state: AppState, key: String, value: Any?) {
        state.actionWork!!.values.entries[key] = YamlIO.fromAny(value)
        state.actionWork!!.form.markEdited()
    }

    @Test
    fun `現在値を表示し変更なしと空文字と明示消去を区別して保存しUndoできる`() {
        val state = open()
        state.readOnlyMode = true
        val work = state.actionWork!!
        assertEquals("最初", value(state, "name"))
        assertFalse(state.actionInputModified())
        state.planAction()
        assertNull(work.error, work.error)
        assertTrue(work.plan!!.isEmpty)
        input(state, "name", "変更した名前")
        state.planAction()
        assertNull(work.error, work.error)
        val op = work.plan!!.changeSet.changes.single().ops.single() as PatchOp.Set
        assertEquals(parseYPath("[0].name"), op.path)
        input(state, "name", "最初")
        assertNull(work.plan, "入力変更は差分を破棄する")
        state.planAction()
        assertTrue(work.plan!!.isEmpty, "元に戻せば変更しない")
        input(state, "note", "")
        state.planAction()
        assertEquals("", YamlIO.toAny((work.plan!!.changeSet.changes.single().ops.single() as PatchOp.Set).value))
        state.applyAction().getOrThrow()
        assertFalse(state.hasDirtyDocs())
        state.undo()
        assertTrue("残すメモ" in Files.readString(dir.resolve("list.yaml")))
        state.startAction(state.actionsForEntity("I-1").single(), "I-1", now)
        input(state, "clear", true)
        state.planAction()
        assertIs<PatchOp.Delete>(state.actionWork!!.plan!!.changeSet.changes.single().ops.single())
        state.applyAction().getOrThrow()
        assertNull(getAt(YamlIO.parse(Files.readString(dir.resolve("list.yaml"))), parseYPath("[0].note")))
    }

    @Test
    fun `対象を変えると初期値が変わり入力元変更は読み直すまで拒む`() {
        val state = open()
        input(state, "name", "未確定")
        assertTrue(state.actionInputModified())
        state.changeActionTarget("I-2").getOrThrow()
        assertEquals("次", value(state, "name"))
        assertFalse(state.actionInputModified())
        val available = state.actionAvailability(state.actionWork!!.entry, "I-2")
        assertFalse(available.enabled)
        assertEquals("対象は未完了である必要があります", available.reason)
        state.planAction()
        assertEquals("対象は未完了である必要があります", state.actionWork!!.error)
        state.changeActionTarget("I-1").getOrThrow()
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        (getAt(doc.root!!, parseYPath("[0].name")) as YNode.Scalar).value = "別の編集"
        doc.markEdited()
        state.planAction()
        assertTrue("入力元が変更" in state.actionWork!!.error.orEmpty())
        state.reloadActionInput().getOrThrow()
        assertEquals("別の編集", value(state, "name"))
    }

    @Test
    fun `隠すと再表示と読み直しで日時を維持し新しい操作で更新する`() {
        val state = open()
        assertEquals("2026-10-25", value(state, "date"))
        input(state, "date", "2026-10-20")
        state.hideActionPanel()
        state.showActionPanel()
        assertEquals("2026-10-20", value(state, "date"))
        state.reloadActionInput().getOrThrow()
        assertEquals("2026-10-25", value(state, "date"))
        state.changeActionTarget("I-2").getOrThrow()
        assertEquals("2026-10-25", value(state, "date"))
        state.startAction(state.actionWork!!.entry, "I-1", now.plusDays(1))
        assertEquals("2026-10-26", value(state, "date"))
        state.startAction("edit", "I-1", mapOf("name" to "指摘への対応", "date" to "2026-10-19"))
        state.planAction()
        assertNull(state.actionWork!!.error, state.actionWork!!.error)
        assertEquals("指摘への対応", YamlIO.toAny((state.actionWork!!.plan!!.changeSet.changes.single().ops.single() as PatchOp.Set).value))
        assertEquals("2026-10-19", value(state, "date"))
    }

    @Test
    fun `確認後に入力や対象データが変わった計画を適用できない`() {
        val state = open()
        input(state, "name", "確認した名前")
        state.planAction()
        state.actionWork!!.values.entries["name"] = YNode.Scalar("確認していない名前")
        assertTrue(state.applyAction().isFailure, "UI通知を経ない変更も検出する")
        assertNull(state.actionWork!!.plan)
        state.planAction()
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        (getAt(doc.root!!, parseYPath("[0].status")) as YNode.Scalar).value = "closed"
        doc.markEdited()
        assertFalse(state.actionAvailability(state.actionWork!!.entry, "I-1").enabled)
        state.refreshActionPlan()
        assertNull(state.actionWork!!.plan)
        assertTrue(state.applyAction().isFailure)
        assertTrue("最初" in Files.readString(dir.resolve("list.yaml")))
    }
}
