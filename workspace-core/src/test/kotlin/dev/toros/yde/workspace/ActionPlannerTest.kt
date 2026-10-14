package dev.toros.yde.workspace

import dev.toros.yde.workspace.validate.CheckResponse
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.patch.PatchOp
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 操作の計画（P41 段階 1）のテスト。定義は src/test/resources/action-fixtures（`$名前` を Kotlin の文字列に書かずに済ませる）。
 * - 横断の操作: 配列へ足す（要素の型の create）・文書を作る（スキーマのルートの create）・when で手を飛ばす・前提（違反は止める）
 * - 配列の insert の決まり: 採番（ID が空なら接頭辞＋連番）・同じ明細は足さない
 * - 受け手を持つ操作と型の操作: call・each（照合の解ごと）・each（自分の配列）・前提の判定保留は止める
 * - 文書の操作（on: schema）・型の操作を直接呼ぶ（型名.操作名）・定義の誤り・受け取った値の誤り
 * - 計画の変更セットは既存の適用器（ChangeSetApplier）をそのまま通る
 */
class ActionPlannerTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/action-fixtures/$name")) { "fixture not found: $name" }.bufferedReader().readText()

    private fun files(): MutableMap<String, String> = mutableMapOf(
        "_schemas/screen-list.yaml" to fixture("_schemas/screen-list.yaml"),
        "_schemas/screen-def.yaml" to fixture("_schemas/screen-def.yaml"),
        "_schemas/transitions.yaml" to fixture("_schemas/transitions.yaml"),
        "_schemas/rule.yaml" to fixture("_schemas/rule.yaml"),
        "_schemas/usecase.yaml" to fixture("_schemas/usecase.yaml"),
        "screen-list.yaml" to """
            phase: 基本設計
            screens:
              - { id: SCR-001, name: 一覧, derived_from: [] }
              - { id: SCR-002, name: 入力, derived_from: [] }
        """.trimIndent() + "\n",
        "screens/SCR-001.yaml" to "id: SCR-001\nurl: /list\n",
        "transitions.yaml" to "transitions:\n  - { from: SCR-001, to: SCR-002, trigger: 押下 }\n",
        "rules.yaml" to """
            rules:
              - { id: BR-001, name: 営業時間, status: approved }
              - { id: BR-002, name: 重複不可, status: approved }
        """.trimIndent() + "\n",
        "usecases/UC-001.yaml" to "id: UC-001\nname: 予約する\nbased_on: [BR-001]\n",
        "usecases/UC-002.yaml" to "id: UC-002\nname: 取り消す\nbased_on: [BR-001, BR-009]\n",
        "usecases/UC-003.yaml" to "id: UC-003\nname: 照会する\nbased_on: [BR-009]\n",
    )

    private val now = LocalDateTime.of(2026, 10, 14, 9, 0)

    private fun session(files: Map<String, String> = files()) = WorkspaceSession(InMemoryDocumentStore(files)).also {
        assertEquals(emptyList(), it.schemaErrors)
    }

    private fun plan(session: WorkspaceSession, name: String, target: String? = null, vararg params: Pair<String, Any?>): ActionPlan =
        ActionPlanner.plan(session, ActionRequest(name, target, params.toMap()), now)

    private fun ActionPlan.change(file: String) = changeSet.changes.firstOrNull { it.file == file } ?: error("変更がありません: $file（${changeSet.changes.map { it.file }}）")

    @Test
    fun `横断の操作は 配列へ足し 文書を作り 条件に合う手だけを当てる`() {
        val s = session()
        val plan = plan(s, "add-screen", null, "id" to "SCR-010", "name" to "詳細", "from" to "SCR-001", "trigger" to "クリック")
        assertEquals(listOf("screen-list.yaml", "transitions.yaml", "screens/SCR-010.yaml"), plan.changeSet.changes.map { it.file })
        // 配列へ足す: 要素の型の create（build）で組み立て、末尾へ
        val inserted = plan.change("screen-list.yaml").ops.single() as PatchOp.Insert
        assertEquals(parseYPath("screens[2]"), inserted.path)
        assertEquals("SCR-010", scalarText(getAt(inserted.value, parseYPath("id"))))
        assertEquals("詳細", scalarText(getAt(inserted.value, parseYPath("name"))))
        assertEquals("", (getAt(inserted.value, parseYPath("summary")) as YNode.Scalar).value)
        // 文書を作る: スキーマの match から置き場所を導き、ルートの型の create（build）で中身を作る
        val created = plan.change("screens/SCR-010.yaml")
        assertTrue(created.create)
        assertEquals("SCR-010", scalarText(getAt(created.content!!, parseYPath("id"))))
        assertEquals("/screens/SCR-010", scalarText(getAt(created.content!!, parseYPath("url"))))
        // 遷移の行: create を書かない型の既定（必須の初期値＋受け取った値）
        val transition = plan.change("transitions.yaml").ops.single() as PatchOp.Insert
        assertEquals("SCR-001", scalarText(getAt(transition.value, parseYPath("from"))))
        assertEquals("SCR-010", scalarText(getAt(transition.value, parseYPath("to"))))
        assertEquals("クリック", scalarText(getAt(transition.value, parseYPath("trigger"))))
        assertEquals("画面を追加する", plan.title)
        assertTrue(plan.summary.any { it.startsWith("作成: screens/SCR-010.yaml") }, plan.summary.toString())

        // 遷移元を省くと、when の条件に合わない手は飛ばす
        val without = plan(s, "add-screen", null, "id" to "SCR-011", "name" to "確認")
        assertEquals(listOf("screen-list.yaml", "screens/SCR-011.yaml"), without.changeSet.changes.map { it.file })
        assertTrue(without.summary.any { it.startsWith("飛ばした") && "when" in it }, without.summary.toString())
    }

    @Test
    fun `前提に反する操作は 理由を示して止まる`() {
        val s = session()
        val e = assertFailsWith<ActionFailure> { plan(s, "add-screen", null, "id" to "SCR-001", "name" to "重複") }
        assertEquals("SCR-001 は既にあります", e.message)
        // 受け取る値の誤り（必須の name が無い）
        val missing = assertFailsWith<ActionFailure> { plan(s, "add-screen", null, "id" to "SCR-012") }
        assertTrue("受け取る値に誤りがあります" in missing.message!!, missing.message)
        // 宣言に無い値
        val unknown = assertFailsWith<ActionFailure> { plan(s, "add-screen", null, "id" to "SCR-012", "name" to "x", "color" to "red") }
        assertTrue("「color」を受け取りません" in unknown.message!!, unknown.message)
    }

    @Test
    fun `配列の insert の決まりで ID が空なら採番し 同じ明細は足さない`() {
        val s = session()
        val numbered = plan(s, "add-numbered", null, "name" to "確認")
        val op = numbered.change("screen-list.yaml").ops.single() as PatchOp.Insert
        assertEquals("SCR-003", scalarText(getAt(op.value, parseYPath("id"))))
        val duplicate = plan(s, "add-numbered", null, "name" to "入力")
        assertTrue(duplicate.isEmpty)
        assertTrue(duplicate.summary.any { "同じ明細" in it }, duplicate.summary.toString())
    }

    @Test
    fun `受け手を持つ操作は 前提の違反と判定保留で止まり 型の操作を呼ぶ`() {
        val s = session()
        // BR-001 は UC-001 / UC-002 が根拠として参照している → 廃止できない（文面に最初の解）
        val referenced = assertFailsWith<ActionFailure> { plan(s, "deprecate-rule", "BR-001", "reason" to "不要") }
        assertEquals("BR-001 はまだ UC-001 から参照されています", referenced.message)
        // BR-002 は参照されていない → 型の操作 deprecate が自分の項目だけを変える
        val ok = plan(s, "deprecate-rule", "BR-002", "reason" to "不要")
        val ops = ok.change("rules.yaml").ops
        assertEquals(listOf(parseYPath("rules[1].status"), parseYPath("rules[1].note")), ops.map { (it as PatchOp.Set).path })
        assertEquals("deprecated", scalarText((ops[0] as PatchOp.Set).value))
        assertEquals("廃止: 不要", scalarText((ops[1] as PatchOp.Set).value))
        assertEquals("ビジネスルールを廃止する（BR-002）", ok.title)
        // 受け手の型が違う
        val wrongType = assertFailsWith<ActionFailure> { plan(s, "deprecate-rule", "UC-001", "reason" to "x") }
        assertTrue("rule ではありません" in wrongType.message!!, wrongType.message)
        // 判定保留（相手の見つからない参照 BR-009 だけを辿る）は止める
        val withheld = assertFailsWith<ActionFailure> { plan(s, "needs-basis", "UC-003") }
        assertTrue("確かめられません" in withheld.message!!, withheld.message)
        // 解のある受け手では通る
        val satisfied = plan(s, "needs-basis", "UC-001")
        assertEquals(1, satisfied.change("usecases/UC-001.yaml").ops.size)
    }

    @Test
    fun `each は照合の解ごとに相手の型の操作を呼び 型の操作は自分の配列を回れる`() {
        val s = session()
        val plan = plan(s, "drop-rule-everywhere", "BR-001", "reason" to "統合")
        assertEquals(setOf("usecases/UC-001.yaml", "usecases/UC-002.yaml", "rules.yaml"), plan.changeSet.changes.map { it.file }.toSet())
        // UC-001: based_on[0] を消して、コメントを足す（comments はまだ無いので、要素 1 つの配列を置く 1 つの操作になる）
        val uc1 = plan.change("usecases/UC-001.yaml").ops
        assertEquals(parseYPath("based_on[0]"), (uc1[0] as PatchOp.Delete).path)
        val comment = uc1[1] as PatchOp.Set
        assertEquals(parseYPath("comments"), comment.path)
        assertEquals("根拠 BR-001 を外した", scalarText(getAt(comment.value, parseYPath("[0].text"))))
        assertEquals(2, uc1.size)
        // UC-002: BR-001 だけを消し、BR-009 は残る（要素ごとの when）
        val uc2 = plan.change("usecases/UC-002.yaml").ops
        assertEquals(parseYPath("based_on[0]"), (uc2[0] as PatchOp.Delete).path)
        assertEquals(2, uc2.size)
        // 適用器をそのまま通る（検証は求めない: BR-009 は元から相手の見つからない参照）
        val applied = ChangeSetApplier.apply(s, plan.changeSet, requireValid = false, now = now)
        assertTrue(applied.ok, applied.results.toString())
        val after = YamlIO.parseFirst(s.storedText(DocumentId("usecases/UC-002.yaml"))!!)!!
        assertEquals(listOf("BR-009"), (getAt(after, parseYPath("based_on")) as YNode.Seq).items.map { scalarText(it) })
        assertEquals("deprecated", scalarText(getAt(YamlIO.parseFirst(s.storedText(DocumentId("rules.yaml"))!!)!!, parseYPath("rules[0].status"))))
    }

    @Test
    fun `文書の操作は 受け手の文書が 1 つなら省略でき 型の操作は型名で直接呼べる`() {
        val s = session()
        val advanced = plan(s, "advance")
        val set = advanced.change("screen-list.yaml").ops.single() as PatchOp.Set
        assertEquals(parseYPath("phase"), set.path)
        assertEquals("詳細設計", scalarText(set.value))
        // 型の操作を直接呼ぶ
        val retitled = plan(s, "screen.retitle", "SCR-002", "name" to "予約入力")
        val op = retitled.change("screen-list.yaml").ops.single() as PatchOp.Set
        assertEquals(parseYPath("screens[1].name"), op.path)
        assertEquals("名前を変える（SCR-002）", retitled.title)
        // 型が違う・受け手が無い・操作が無い
        assertTrue("型「usecase」ではありません" in assertFailsWith<ActionFailure> { plan(s, "usecase.retitle", "SCR-002", "name" to "x") }.message!!)
        assertTrue("受け手" in assertFailsWith<ActionFailure> { plan(s, "screen.retitle", null, "name" to "x") }.message!!)
        assertTrue("操作「rename」はありません" in assertFailsWith<ActionFailure> { plan(s, "screen.rename", "SCR-002") }.message!!)
        assertTrue("見つかりません" in assertFailsWith<ActionFailure> { plan(s, "no-such-action") }.message!!)
        // 一覧
        val names = ActionPlanner.catalog(s.schemas).map { it.name }
        assertTrue("add-screen" in names && "screen.retitle" in names && "rule.deprecate" in names && "usecase.drop-basis" in names, names.toString())
        assertEquals("create", ActionPlanner.catalog(s.schemas).first { it.name == "screen.create" }.kind)
        // 受け手ごとの一覧（画面の操作メニュー）: 要素はその型のアクションと型の操作（create 以外）、文書はそのスキーマのアクション、ワークスペースは受け手の無いもの
        assertEquals(listOf("deprecate-rule", "drop-rule-everywhere", "rule.deprecate"), ActionPlanner.forEntity(s, "BR-001").map { it.name })
        assertEquals(listOf("screen.retitle"), ActionPlanner.forEntity(s, "SCR-001").map { it.name })
        assertEquals(listOf("advance"), ActionPlanner.forDocument(s, "screen-list.yaml").map { it.name })
        assertEquals(listOf("add-screen", "add-numbered"), ActionPlanner.forWorkspace(s.schemas).map { it.name })
        assertEquals(emptyList(), ActionPlanner.forEntity(s, "NO-SUCH").map { it.name })
    }

    @Test
    fun `計画の変更セットは適用器を通り 新しい文書と保存時に自動で入る値を含めて書かれる`() {
        val s = session()
        val plan = plan(s, "add-screen", null, "id" to "SCR-010", "name" to "詳細", "from" to "SCR-001", "trigger" to "クリック")
        val applied = ChangeSetApplier.apply(s, plan.changeSet, requireValid = true, now = now)
        assertTrue(applied.ok, applied.results.joinToString { "${it.file}: ${it.message} ${it.issues.map { i -> i.message }}" })
        assertNotNull(s.storedText(DocumentId("screens/SCR-010.yaml")))
        val list = YamlIO.parseFirst(s.storedText(DocumentId("screen-list.yaml"))!!)!!
        assertEquals("SCR-010", scalarText(getAt(list, parseYPath("screens[2].id"))))
        assertEquals("SCR-010", scalarText(getAt(YamlIO.parseFirst(s.storedText(DocumentId("transitions.yaml"))!!)!!, parseYPath("transitions[1].to"))))
    }

    @Test
    fun `検査の対応に action を書くと 指摘から操作を起動する対応になる`() {
        val ws = WorkspaceReader.load(InMemoryDocumentStore(files()))
        assertEquals(emptyList(), ws.schemaErrors)
        // BR-002 はどこからも参照されていない → 警告に、受け手と渡す値（文面を埋めたもの）を持つ対応が付く
        val issues = WorkspaceCheckRunner.validateAll(ws).getValue(DocumentId("rules.yaml")).issues
        val run = issues.flatMap { it.responses }.filterIsInstance<CheckResponse.RunAction>().single()
        assertEquals("deprecate-rule", run.action)
        assertEquals("BR-002", run.target)
        assertEquals("BR-002 を廃止にする", run.label)
        assertEquals(mapOf("reason" to "BR-002 は参照されていない"), run.params)
        // 無い操作を指す対応は、読み込みの誤り
        val broken = files().also { it["_schemas/rule.yaml"] = it.getValue("_schemas/rule.yaml").replace("action: deprecate-rule", "action: no-such") }
        val errors = WorkspaceReader.load(InMemoryDocumentStore(broken)).schemaErrors
        assertTrue(errors.any { "操作「no-such」が見つかりません" in it }, errors.toString())
    }

    @Test
    fun `追加ボタンと新規行の要素は 型の create と配列の insert の決まりで作る`() {
        val s = session()
        val schema = s.schemas.first { it.id == "screen-list" }
        val tree = schema.tree
        val arrayDef = (tree.resolve(tree.root) ?: tree.root).fields.first { it.key == "screens" }.let { tree.resolve(it) ?: it }
        val array = getAt(s.snapshot.documents.getValue(DocumentId("screen-list.yaml")).document.documents.first(), parseYPath("screens")) as YNode.Seq
        val ids = s.derived.semantic.graph.entities.keys
        // 受け取った値あり: create の build で組み立て、ID が空なら採番
        val made = ActionPlanner.newElement(tree, arrayDef, array, mapOf("name" to "確認"), ids, now)
        assertEquals("SCR-003", scalarText(getAt(made.node!!, parseYPath("id"))))
        assertEquals("確認", scalarText(getAt(made.node!!, parseYPath("name"))))
        // 同じ明細（name）は足さない
        assertNotNull(ActionPlanner.newElement(tree, arrayDef, array, mapOf("name" to "入力"), ids, now).skipped)
        // 受け取った値なし（create に受け取る値の無い型の追加ボタン）: 必須の初期値だけの要素。空の要素どうしを「同じ」にしない
        val transitions = s.schemas.first { it.id == "transitions" }
        val tArray = (transitions.tree.resolve(transitions.tree.root) ?: transitions.tree.root).fields.first { it.key == "transitions" }.let { transitions.tree.resolve(it) ?: it }
        val existingRows = getAt(s.snapshot.documents.getValue(DocumentId("transitions.yaml")).document.documents.first(), parseYPath("transitions")) as YNode.Seq
        val blank = ActionPlanner.newElement(transitions.tree, tArray, existingRows, emptyMap(), ids, now, dedupe = false)
        assertEquals(setOf("from", "to"), (blank.node as YNode.Map).entries.keys)
        // create が受け取る値を求める型に値を渡さなければ、追加ボタンはカードを開く（ここでは誤り）
        assertFailsWith<ActionFailure> { ActionPlanner.newElement(tree, arrayDef, array, emptyMap(), ids, now, dedupe = false) }
        // 文書の作成（詳細文書を作成・新規行のファイルモード）はルートの型の create を通る
        val detail = s.schemas.first { it.id == "screen-def" }
        val scaffold = DocumentScaffolds.scaffold(DocumentScaffolds.filePatternOf(detail)!!, ScaffoldSource.idOnly("SCR-009"), s.schemas)
        assertEquals("screens/SCR-009.yaml", scaffold.rel)
        assertEquals("/screens/SCR-009", scalarText(getAt(scaffold.root, parseYPath("url"))))
    }

    @Test
    fun `型に使える操作は 受け手をあとで選ぶ入口のために型名で引ける`() {
        val s = session()
        val forRule = ActionPlanner.forEntityType(s, "rule")
        assertEquals(listOf("deprecate-rule", "drop-rule-everywhere", "rule.deprecate"), forRule.map { it.name })
        assertTrue(forRule.all { it.entityType == "rule" }, "受け手の型が引ける: ${forRule.map { it.target }}")
        assertEquals(listOf("screen.create"), ActionPlanner.catalog(s.schemas).filter { it.kind == "create" }.map { it.name })
        assertTrue(ActionPlanner.forEntityType(s, "screen").none { it.kind == "create" }, "create は直接呼べないので並べない")
        assertEquals(emptyList(), ActionPlanner.forEntityType(s, "no-such"))
        // 文書を受け手にする操作は documentSchema で、受け手の無い操作はどちらも null
        assertEquals("screen-list", ActionPlanner.forDocument(s, "screen-list.yaml").single().documentSchema)
        val addScreen = ActionPlanner.forWorkspace(s.schemas).first { it.name == "add-screen" }
        assertNull(addScreen.entityType)
        assertNull(addScreen.documentSchema)
    }

    @Test
    fun `定義の誤りは読み込みで分かる`() {
        fun errorsOf(schema: String): List<String> = WorkspaceSession(InMemoryDocumentStore(mapOf("_schemas/x.yaml" to schema))).schemaErrors
        // 型の操作に of / in は書けない
        val scoped = errorsOf(
            """
            id: x
            match: "x/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
              methods:
                touch:
                  do:
                    - set: { of: other, path: name, value: a }
            """.trimIndent()
        )
        assertTrue(scoped.any { "型の操作では of / in を書けません" in it }, scoped.toString())
        // 受け取る値の宣言が無い
        val undeclared = errorsOf(
            """
            id: x
            match: "x/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
            actions:
              - id: a
                do:
                  - set: { in: { schemaId: x }, path: name, value: ${'$'}name }
            """.trimIndent()
        )
        assertTrue(undeclared.any { "params に宣言されていません" in it }, undeclared.toString())
        // 型の操作では文書を作れない
        val create = errorsOf(
            """
            id: x
            match: "x/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
              methods:
                spawn:
                  do:
                    - create: { schema: x, params: {} }
            """.trimIndent()
        )
        assertTrue(create.any { "文書を作るのはアクションだけです" in it }, create.toString())
        // 配列の項目に書けるのは insert だけ
        val array = errorsOf(
            """
            id: x
            match: "x/*.yaml"
            root:
              type: object
              fields:
                - key: items
                  type: array
                  methods: { touch: { do: [] } }
                  item: { type: string }
            """.trimIndent()
        )
        assertTrue(array.any { "配列の項目に書ける操作は insert だけです" in it }, array.toString())
    }
}
