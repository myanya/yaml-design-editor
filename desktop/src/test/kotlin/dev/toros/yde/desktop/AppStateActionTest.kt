package dev.toros.yde.desktop

import dev.toros.yde.workspace.validate.CheckResponse
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.toros.yde.desktop.ui.action.actionPanelContext

/**
 * 画面からの操作（右ペインの「操作」タブ。architecture/actions.md）の AppState の経路。
 * - 受け手ごとの一覧（要素・文書・ワークスペース）
 * - 始める → 受け取る値を入れる → 計画（適用後の検証つき） → 適用は書いて保存まで行い、Undo 1 回で全部戻る（作った文書も消える）
 * - 前提に反すれば計画の誤りになる。対象に未保存の編集があれば適用を拒む
 */
class AppStateActionTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-appaction")
        val d = "$"
        write("_schemas/item-list.yaml", """
            id: item-list
            match: list.yaml
            entity: { type: item, idPattern: "IT-*", defines: true }
            root:
              type: object
              fields:
                - { key: phase, type: enum, values: [draft, fixed], default: draft }
                - { key: tags, type: array, item: { type: string } }
                - key: notes
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: text, type: string, required: true }
                    methods:
                      create:
                        params:
                          - { key: text, type: string, required: true }
                - key: items
                  type: array
                  required: true
                  methods:
                    insert:
                      id: { path: id, prefix: "IT-", digits: 1 }
                      key: [name]
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, required: true, role: title }
                    methods:
                      retitle:
                        label: 名前を変える
                        params:
                          - { key: name, type: string, required: true }
                        do:
                          - set: { path: name, value: ${d}name }
            constraints:
              - id: item-name-long
                match: [ { node: { entity: item, bind: it } } ]
                check: [ { gte: [ { length: { of: it, path: name } }, 3 ] } ]
                level: warning
                responses:
                  - { action: item.retitle, label: 名前を長くする, with: { name: "{id} の名前" } }
            actions:
              - id: add-item
                label: 項目を追加する
                params:
                  - { key: id, type: id, required: true }
                  - { key: name, type: string, required: true }
                require:
                  - { match: [ { node: { id: ${d}id } } ], expect: absent, message: "${d}id は既にあります" }
                do:
                  - insert: { in: { schemaId: item-list }, path: items, params: { id: ${d}id, name: ${d}name } }
                  - create: { schema: item-detail, params: { id: ${d}id } }
              - id: fix
                label: 確定する
                target: { schema: item-list }
                do:
                  - set: { path: phase, value: fixed }
              - id: add-note
                label: メモを足す
                params:
                  - { key: text, type: string, required: true }
                do:
                  - insert: { in: { schemaId: item-list }, path: notes, params: { text: ${d}text } }
        """)
        write("_schemas/item-detail.yaml", """
            id: item-detail
            match: "details/*.yaml"
            entity: { type: item, idPattern: "IT-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: note, type: string }
        """)
        write("list.yaml", """
            phase: draft
            items:
              - { id: IT-1, name: 最初 }
        """)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun openState(): AppState = AppState().also { it.openWorkspace(dir); assertEquals(emptyList(), it.schemaErrors) }

    @Test
    fun `横ぐしの選択が操作ペインへ追随し背後の文書や開始済み操作とは混ざらない`() {
        write("list.yaml", "items: [{id: IT-1, name: 最初}, {id: IT-2, name: 次の項目}]")
        val state = openState()
        state.openCrossView("IT-1")
        assertNull(state.openDoc)
        assertEquals("IT-1", actionPanelContext(state, null, emptyList()).targetId)

        state.openFile(dir.resolve("list.yaml"))
        val doc = state.openDoc!!
        val ids = listOf("IT-1", "IT-2").flatMap { state.overlayIndex().occurrences(it) }
        state.memory.navigation(state.documentOf(doc.file), false).actionTarget = "IT-1"
        state.openCrossView("IT-2")
        val selected = actionPanelContext(state, doc, ids)
        assertEquals("IT-2", selected.targetId)
        assertNull(selected.document, "背後の文書の操作を混ぜない")
        assertTrue(selected.elementTypes.isEmpty())
        state.startAction(state.actionsForEntity(selected.targetId!!).single(), selected.targetId)
        state.openCrossView("IT-1")
        assertEquals("IT-1", actionPanelContext(state, doc, ids).targetId)
        assertEquals("IT-2", state.actionWork!!.target, "開始済みの操作対象は保持する")
        state.goBack()
        assertEquals("IT-2", actionPanelContext(state, doc, ids).targetId)

        state.crossViewId = "削除済みID"
        assertNull(actionPanelContext(state, doc, ids).targetId, "無効な選択から文書のIDを推測しない")
        state.crossViewId = null
        assertNull(actionPanelContext(state, doc, ids).targetId)
        state.crossViewId = "IT-2"
        state.showSpace(UiSpace.EDIT)
        val editing = actionPanelContext(state, doc, ids)
        assertEquals("IT-1", editing.targetId, "横ぐしを離れたら文書の選択へ戻る")
        assertEquals(doc, editing.document)
    }

    @Test
    fun `入力後の前提も未保存の編集を含めて判定する`() {
        val state = openState()
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        state.addArrayElement(doc, parseYPath("items"), doc.schema!!.root.fields.first { it.key == "items" }).getOrThrow()
        state.startAction(state.actionsForWorkspace().first { it.name == "add-item" }, null)
        state.actionWork!!.values.entries["id"] = YNode.Scalar("IT-2")
        state.actionWork!!.values.entries["name"] = YNode.Scalar("重複する名前")
        state.planAction()
        assertEquals("IT-2 は既にあります", state.actionWork!!.error)
        assertNull(state.actionWork!!.plan)
    }

    @Test
    fun `受け手ごとに使える操作が並び 始めるとパネルが開く`() {
        val state = openState()
        assertEquals(listOf("add-item", "add-note"), state.actionsForWorkspace().map { it.name })
        assertEquals(listOf("fix"), state.actionsForDocument(dir.resolve("list.yaml")).map { it.name })
        assertEquals(listOf("item.retitle"), state.actionsForEntity("IT-1").map { it.name })
        // 台帳の「要素の操作」は型で引く（受け手はあとで選ぶ）
        assertEquals(listOf("item.retitle"), state.actionsForEntityType("item").map { it.name })
        assertEquals("item", state.actionsForEntityType("item").single().entityType)
        assertEquals(emptyList(), state.actionsForEntityType("no-such"))
        state.startAction(state.actionsForEntity("IT-1").single(), "IT-1")
        val work = assertNotNull(state.actionWork)
        assertEquals("名前を変える", work.title)
        assertEquals("IT-1", work.target)
        assertTrue(state.actionPanelOpen)
        // 隠しても作業は残り、開き直せる。やめると消える
        state.hideActionPanel()
        assertFalse(state.actionPanelOpen)
        assertNotNull(state.actionWork)
        state.showActionPanel()
        assertTrue(state.actionPanelOpen)
        state.dismissAction()
        assertNull(state.actionWork)
        assertFalse(state.actionPanelOpen)
    }

    @Test
    fun `対象を選ばずに確認すると案内し 対象の欄で選ぶと計画できる`() {
        val state = openState()
        // 候補はその型の要素だけ（ID と 1 行要約）
        assertEquals(listOf("IT-1" to "最初"), state.entityCandidates("item"))
        assertEquals(emptyList(), state.entityCandidates("no-such"))
        state.startAction(state.actionsForEntityType("item").single(), null)
        val work = assertNotNull(state.actionWork)
        assertNull(work.target)
        work.values.entries["name"] = YNode.Scalar("長い名前")
        state.planAction()
        assertTrue("対象を選んでください" in work.error.orEmpty(), work.error)
        assertNull(work.plan)
        work.targetHolder.entries[AppState.ActionWork.TARGET_KEY] = YNode.Scalar("IT-1")
        assertEquals("IT-1", work.target)
        state.planAction()
        assertNull(work.error, work.error)
        val op = work.plan!!.changeSet.changes.single().ops.single() as PatchOp.Set
        assertEquals(parseYPath("items[0].name"), op.path)
        // 空白だけの対象は未選択
        work.targetHolder.entries[AppState.ActionWork.TARGET_KEY] = YNode.Scalar("  ")
        assertNull(work.target)
    }

    @Test
    fun `計画して適用すると書いて保存され Undo 1 回で作った文書ごと戻る`() {
        val state = openState()
        state.startAction(state.actionsForWorkspace().first { it.name == "add-item" }, null)
        val work = assertNotNull(state.actionWork)
        work.values.entries["id"] = YNode.Scalar("IT-2")
        work.values.entries["name"] = YNode.Scalar("次の項目")
        state.planAction()
        assertNull(work.error, work.error)
        val plan = assertNotNull(work.plan)
        assertEquals(listOf("list.yaml", "details/IT-2.yaml"), plan.changeSet.changes.map { it.file })
        assertNotNull(work.preview)
        assertFalse(Files.exists(dir.resolve("details/IT-2.yaml")), "計画では原本は変わらない")

        // 一覧の文書を開いている利用者（未保存の編集は無いので適用できる）
        val listDoc = state.ensureDoc(dir.resolve("list.yaml"))
        val message = state.applyAction().getOrThrow()
        assertTrue("適用して保存しました" in message, message)
        assertTrue(Files.exists(dir.resolve("details/IT-2.yaml")))
        assertTrue("IT-2" in Files.readString(dir.resolve("list.yaml")))
        assertEquals(2, state.index.entries["IT-2"].orEmpty().size, "索引が追随する")
        assertFalse(state.hasDirtyDocs(), "保存まで行う（未保存の●は付かない）")

        state.undo()
        assertFalse(Files.exists(dir.resolve("details/IT-2.yaml")), "Undo で作った文書が消える")
        val items = (listDoc.root as YNode.Map).entries["items"] as YNode.Seq
        assertEquals(1, items.items.size, "書き換えた文書は編集中の木が戻る（未保存の編集として残り、保存で書かれる）")
        assertTrue(listDoc.dirty, "戻した内容は未保存（●）になる")
    }

    @Test
    fun `検査の指摘の対応から操作を始めると 受け手と値が先に入る`() {
        val state = openState()
        // IT-1 の名前「最初」は 2 文字 → 警告に、型の操作 retitle を起動する対応が付く
        val issues = state.validateWorkspace().flatMap { it.second }
        val run = issues.flatMap { it.responses }.filterIsInstance<CheckResponse.RunAction>().single()
        assertEquals("item.retitle", run.action)
        assertEquals("IT-1", run.target)
        state.startAction(run.action, run.target, run.params)
        val work = assertNotNull(state.actionWork)
        assertEquals("IT-1", work.target)
        assertEquals("IT-1 の名前", (work.values.entries["name"] as YNode.Scalar).value)
        state.planAction()
        assertNull(work.error, work.error)
        val op = work.plan!!.changeSet.changes.single().ops.single() as PatchOp.Set
        assertEquals(parseYPath("items[0].name"), op.path)
        // 無い操作を指すと案内だけ
        state.dismissAction()
        state.startAction("no-such", null)
        assertNull(state.actionWork)
        assertTrue("見つかりません" in state.status)
    }

    @Test
    fun `配列の追加は 型の create に受け取る値があればカードで入力し 無ければその場で採番して足す`() {
        val state = openState()
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        val schema = doc.schema!!
        val itemsField = schema.root.fields.first { it.key == "items" }
        // 要素の型（item）に create は無い → その場で足す。配列の insert の決まりで ID が採番される
        val index = state.addArrayElement(doc, parseYPath("items"), itemsField).getOrThrow()
        assertEquals(1, index)
        val items = (doc.root as YNode.Map).entries["items"] as YNode.Seq
        assertEquals("IT-2", ((items.items[1] as YNode.Map).entries["id"] as YNode.Scalar).value)
        assertTrue(doc.dirty, "未保存の編集として足す")
        // 文字列の配列: 空の要素
        assertEquals(0, state.addArrayElement(doc, parseYPath("tags"), schema.root.fields.first { it.key == "tags" }).getOrThrow())
        assertEquals("", (((doc.root as YNode.Map).entries["tags"] as YNode.Seq).items[0] as YNode.Scalar).value)
        state.undo()
        state.undo()
        assertEquals(1, ((doc.root as YNode.Map).entries["items"] as YNode.Seq).items.size)
    }

    @Test
    fun `閲覧専用でも操作は使え 適用は保存まで行い 取り消しとやり直しも保存まで戻す`() {
        val state = openState()
        state.readOnlyMode = true
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        // 編集（配列の追加）は閲覧専用では拒む
        val refused = state.addArrayElement(doc, parseYPath("items"), doc.schema!!.root.fields.first { it.key == "items" })
        assertTrue(refused.isFailure)
        assertTrue("閲覧専用" in refused.exceptionOrNull()!!.message!!, refused.exceptionOrNull()!!.message)
        // 操作は始められ、適用は書いて保存まで
        state.startAction(state.actionsForWorkspace().first { it.name == "add-item" }, null)
        val work = assertNotNull(state.actionWork)
        work.values.entries["id"] = YNode.Scalar("IT-2")
        work.values.entries["name"] = YNode.Scalar("閲覧専用から")
        state.planAction()
        assertNull(work.error, work.error)
        state.applyAction().getOrThrow()
        assertTrue("IT-2" in Files.readString(dir.resolve("list.yaml")))
        assertFalse(state.hasDirtyDocs())
        // 取り消しも保存まで（閲覧専用では未保存の編集を残さない）
        state.undo()
        assertFalse("IT-2" in Files.readString(dir.resolve("list.yaml")), "戻した内容がディスクに書かれる")
        assertFalse(Files.exists(dir.resolve("details/IT-2.yaml")))
        assertFalse(state.hasDirtyDocs(), "閲覧専用では未保存の編集を残さない")
        state.redo()
        assertTrue("IT-2" in Files.readString(dir.resolve("list.yaml")), "やり直しも保存まで")
        assertTrue(Files.exists(dir.resolve("details/IT-2.yaml")))
        assertFalse(state.hasDirtyDocs())
    }

    @Test
    fun `追加の作業から その配列へ足すアクションへ値を持ち越して切り替えられる`() {
        val state = openState()
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        val notesField = doc.schema!!.root.fields.first { it.key == "notes" }
        assertNull(state.addArrayElement(doc, parseYPath("notes"), notesField).getOrThrow(), "create に受け取る値があるのでパネル")
        val work = assertNotNull(state.actionWork)
        assertTrue(work.title.endsWith("に行を追加"), "追加のパネルは編集（行を追加）の題名: ${work.title}")
        val into = assertNotNull(work.insertInto)
        assertEquals(listOf("add-note"), into.alternatives.map { it.name }, "notes へ足すアクションが橋渡しの候補になる")
        work.values.entries["text"] = YNode.Scalar("持ち越す値")
        state.switchInsertToAction(into.alternatives.single())
        val next = assertNotNull(state.actionWork)
        assertEquals("add-note", next.entry.name)
        assertNull(next.insertInto)
        assertEquals("持ち越す値", (next.values.entries["text"] as YNode.Scalar).value)
        state.planAction()
        assertNull(next.error, next.error)
        assertEquals(listOf("list.yaml"), next.plan!!.changeSet.changes.map { it.file })
    }

    @Test
    fun `前提に反する計画は誤りになり 未保存の編集がある対象には適用しない`() {
        val state = openState()
        state.startAction(state.actionsForWorkspace().first { it.name == "add-item" }, null)
        val work = assertNotNull(state.actionWork)
        work.values.entries["id"] = YNode.Scalar("IT-1")
        work.values.entries["name"] = YNode.Scalar("重複")
        state.planAction()
        assertEquals("IT-1 は既にあります", work.error)
        assertNull(work.plan)

        // 文書の操作（受け手は文書）。対象に未保存の編集があると適用を拒む
        state.startAction(state.actionsForDocument(dir.resolve("list.yaml")).single(), "list.yaml")
        val fix = assertNotNull(state.actionWork)
        state.planAction()
        assertNull(fix.error, fix.error)
        val doc = state.ensureDoc(dir.resolve("list.yaml"))
        ((doc.root as YNode.Map).entries["phase"] as YNode.Scalar).value = "x"
        doc.markEdited()
        val refused = state.applyAction()
        assertTrue(refused.isFailure)
        assertTrue("未保存" in refused.exceptionOrNull()!!.message!!, refused.exceptionOrNull()!!.message)
    }
}
