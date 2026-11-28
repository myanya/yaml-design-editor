package dev.toros.yde.app.core

import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.InMemoryDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 設計書をまたいだ補正の伝達と、誤った入力を保存しない条件を実際の生成経路で確かめる。 */
class TodosSystemDocumentsTest {
    private val sample = Path.of("samples/todos-sysdev")
    private fun raw(text: String) = YamlIO.parseToRaw(text) as Map<*, *>
    private fun rows(value: Map<*, *>, key: String) = (value[key] as List<*>).map { it as Map<*, *> }
    private fun map(value: Map<*, *>, key: String) = value[key] as Map<*, *>

    @Test
    fun `設計用のコピーはアプリと表示状態を入力へ混ぜない`() {
        val original = Files.createTempDirectory("sysdev-inputs")
        val copied = Files.createTempDirectory("sysdev-copy")
        try {
            val included = listOf(".yde/workspace.yaml", ".yde/schemas/design/basic/sample.yaml", ".yde/reports/design/detail/ddl.sql", "design/basic/inputs/sample.yaml")
            val excluded = listOf(".yde/state/view.yaml", "app/src/main/resources/application.yaml", "app/build/classes/Sample.class", "app/.gradle/state.bin")
            for (name in included + excluded) {
                val target = original.resolve(name)
                Files.createDirectories(target.parent)
                Files.writeString(target, name)
            }
            TodosSysdevFiles.copy(original, copied)
            assertEquals(included.toSet(), TodosSysdevFiles.inputs(copied).map { copied.relativize(it).toString().replace('\\', '/') }.toSet())
            assertTrue(excluded.all { Files.notExists(copied.resolve(it)) })
        } finally {
            original.toFile().deleteRecursively()
            copied.toFile().deleteRecursively()
        }
    }

    @Test
    fun `別の設計段階への補正と指定と追加は確定前に止める`() {
        for ((phase, kind, target) in listOf(Triple("basic", "session-design", "session-design"), Triple("detail", "screen-transitions", "todo-dashboard"))) {
            val input = store()
            val corrections = "design/$phase/inputs/documents/corrections.yaml"
            val additions = "design/$phase/inputs/documents/additions.yaml"
            input.write(DocumentId(corrections), """
                inputId: INPUT-$phase-document-corrections
                corrections: [{id: SCOR-wrong, kind: $kind, target: DOC-$kind.$target, set: {name: 別の段階}, reason: 検証}]
                specifications: [{id: SSPC-wrong, kind: $kind, target: DOC-$kind.$target, rule: defaults, settings: {name: 別の段階}, reason: 検証}]
            """.trimIndent())
            input.write(DocumentId(additions), """
                inputId: INPUT-$phase-document-additions
                additions: [{id: SADD-wrong, kind: $kind, document: {id: extra}, reason: 検証}]
            """.trimIndent())
            val plan = Generators.plan(WorkspaceSession(input))
            val errors = plan.errors.joinToString("\n")
            assertTrue(corrections in errors && additions in errors, errors)
            assertTrue("corrections" in errors && "specifications" in errors && "additions" in errors && "kind" in errors, errors)
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `設計書別ダッシュボードは候補と表示を同じ種類に限定し確定内容を表示する`() {
        val ws = Workspace.open(sample)
        val views = Views.load(ws.root, ws.schemas)
        assertEquals(emptyList(), views.errors)
        val documents = TodosSysdevFiles.documents(sample).map { raw(Files.readString(it)) }.associateBy { it["designId"].toString() }
        val dashboards = views.dashboards.filter { it.id.startsWith("design-review-") }
        assertEquals(documents.values.map { it["designKind"] }.toSet(), dashboards.map { it.id.removePrefix("design-review-") }.toSet())
        for (dashboard in dashboards) {
            val kind = dashboard.id.removePrefix("design-review-")
            val expected = documents.filterValues { it["designKind"] == kind }.keys
            val input = dashboard.inputs.single()
            val options = dev.toros.yde.semantic.DashboardInputs.options(input, ws.index.entries).map { it.first }.toSet()
            assertEquals(expected, options, dashboard.id)
            val chosen = assertNotNull(input.default)
            val otherKind = documents.keys.first { it !in expected }
            for ((selection, shown) in listOf(chosen to setOf(chosen), expected.last() to setOf(expected.last()), null to expected, otherKind to emptySet())) {
                val result = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 19), views, ws.index, ws::schemaFor, ws::treeOf,
                    mapOf("document" to selection))
                for (section in result.sections) {
                    assertEquals(null, section.error, dashboard.id)
                    assertTrue(section.pending.isEmpty(), dashboard.id)
                    assertEquals(if (section.definition.id == "documents") expected else shown, section.rows.map { it.id }.toSet(), "${dashboard.id}/${section.definition.id}")
                    if (section.definition.id == "content") for (row in section.rows) {
                        assertEquals(documents[row.id], row.cells.single().value?.let(YamlIO::toAny))
                    }
                }
            }
        }
    }

    @Test
    fun `横断と個別のビューは確定文書を表示し 設計の型を業務の対応表へ混ぜない`() {
        val ws = Workspace.open(sample)
        val views = Views.load(ws.root, ws.schemas)
        assertEquals(emptyList(), views.errors)
        fun shown(name: String, params: Map<String, Any?> = emptyMap()) = Views.resolve(
            assertNotNull(views.view(name)), ws.index, ws::treeOf, ws::schemaFor, params)
        fun cell(row: dev.toros.yde.semantic.VirtualRow, label: String) =
            row.cells.single { it.column.label == label }.value?.let(YamlIO::toAny)
        assertEquals(28, shown("system-document-list").size)
        assertEquals(setOf("category", "tag", "todo", "todo-task"), shown("entity-trace").map { it.id }.toSet())
        assertEquals(15, shown("action-trace").size)
        val process = raw(Files.readString(sample.resolve("design/detail/final/processes/complete-todo.yaml")))
        val individual = shown("action-design-related", mapOf("source" to "complete-todo")).single()
        assertEquals(process["steps"], cell(individual, "steps"))
        assertEquals(listOf("PRC-complete-todo", "ACT-complete-todo"), listOf(process["id"], process["source"]), "処理の手順は ID 付きレコードで、基本設計の操作を参照する")
        val detailed = raw(Files.readString(sample.resolve("design/detail/final/screen-designs/operations/complete-todo.yaml")))
        val screen = shown("screen-design-related", mapOf("source" to "complete-todo")).single()
        assertEquals(detailed["form"], cell(screen, "form"))
        assertEquals(detailed["screens"], cell(screen, "screens"))
    }
    private fun store(corrections: String = "[]", additions: String = "[]", specifications: String = "[]"): InMemoryDocumentStore {
        val files = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
            .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) }.toMutableMap()
        val basicKinds = setOf("screen-transitions")
        fun selected(text: String, phase: String) = (YamlIO.parseToRaw(text) as List<*>).filter {
            ((it as Map<*, *>)["kind"] in basicKinds) == (phase == "basic")
        }
        for (phase in listOf("basic", "detail")) {
            files["design/$phase/inputs/documents/corrections.yaml"] = YamlIO.dumpPlain(YamlIO.fromAny(mapOf(
                "inputId" to "INPUT-$phase-document-corrections", "corrections" to selected(corrections, phase), "specifications" to selected(specifications, phase))))
            files["design/$phase/inputs/documents/additions.yaml"] = YamlIO.dumpPlain(YamlIO.fromAny(mapOf(
                "inputId" to "INPUT-$phase-document-additions", "additions" to selected(additions, phase))))
        }
        return InMemoryDocumentStore(files)
    }

    @Test
    fun `物理名と呼出先と画面入力とメッセージとセッションの補正を後段へ渡す`() {
        val input = store("""
            - {id: SCOR-session, kind: session-design, target: DOC-session-design.session-design, select: {collection: attributes, key: role, equals: token}, set: {key: confirmationToken}, reason: セッションのキー}
        """.trimIndent().prependIndent("\n  "), """
            - id: SADD-preference
              kind: session-design
              target: DOC-session-design.session-design
              collection: attributes
              items: [{key: reviewPreference, role: reviewPreference, contents: 表示設定, purpose: 追加の検証}]
              reason: 方式による追加
        """.trimIndent().prependIndent("\n  "))
        input.write(DocumentId("design/detail/inputs/tables/corrections.yaml"), """
            corrections:
              - {id: DCOR-901, target: TBL-todo, set: {name: todo_records}, reason: 物理名}
              - {id: DCOR-902, target: COL-todo.result, set: {name: completion_result}, reason: 物理名}
        """.trimIndent())
        // メッセージの文への個別の判断は、基本設計の項目（メッセージ MSG-）を ID で指す
        input.write(DocumentId("design/basic/inputs/decisions.yaml"), """
            inputId: INPUT-basic-decisions
            decisions:
              - {id: BDEC-901, target: MSG-complete-todo.no-open-task, set: {text: 未完了の作業があります（確認用）。}, reason: 文の見直し}
        """.trimIndent())
        // テーブルへの読み書きへの個別の判断は、詳細設計の項目（まとまり ACC-・メソッド MTH-）を ID で指す
        input.write(DocumentId("design/detail/inputs/decisions.yaml"), """
            inputId: INPUT-detail-decisions
            decisions:
              - {id: DDEC-901, target: ACC-todo, set: {mapper: TodoStore}, reason: 保存先}
              - {id: DDEC-902, target: MTH-todo.completeTodo, set: {name: saveCompletion}, reason: 呼出名}
              - {id: DDEC-903, target: PRC-complete-todo, set: {serviceClass: CompletionService, serviceMethod: finish}, reason: 呼出名}
        """.trimIndent())
        // 操作への個別の判断は、基本設計の「操作」の項目（操作 ACT-・入力 PRM-）を ID で指す
        input.write(DocumentId("design/basic/inputs/operations/corrections.yaml"), """
            inputId: INPUT-basic-operation-corrections
            corrections:
              - {id: COR-901, target: ACT-cancel-todo, set: {label: TODOを取りやめる}, reason: 操作の名称}
              - {id: COR-902, target: PRM-cancel-todo.reason, set: {maxLength: 200}, reason: 入力の上限}
              - {id: COR-903, target: PRM-complete-todo.result, set: {maxLength: 123}, reason: 入力の上限}
        """.trimIndent())
        // 画面への個別の判断は、基本設計の「画面」の項目（画面 SCR-・絞り込み FLT-・区画と表 SEC-・ボタン BTN-）を ID で指す。
        // 置き換えられるのは名称・説明・URL・リクエストで、入力の決まりは操作の入力（PRM-）に書く（画面と処理で検査がずれない）
        input.write(DocumentId("design/basic/inputs/screens/corrections.yaml"), """
            inputId: INPUT-basic-screen-corrections
            corrections:
              - {id: COR-911, target: SCR-todo-dashboard, set: {label: TODO 全体の確認, url: /overview}, reason: 一覧の名称}
              - {id: COR-912, target: SCR-complete-todo, set: {executeRequest: 'POST /todo/{id}/finish'}, reason: URL}
              - {id: COR-913, target: BTN-todo.complete-todo, set: {label: 完了}, reason: ボタンの名称}
        """.trimIndent())
        val plan = Generators.plan(WorkspaceSession(input))
        assertEquals(emptyList(), plan.errors)
        fun doc(path: String) = raw(plan.outputs.single { it.document.value == path }.text)
        val access = doc("design/detail/final/table-access/todo.yaml")
        val method = rows(access, "methods").single { it["id"] == "MTH-todo.completeTodo" }
        assertEquals(listOf("TodoStore", "saveCompletion"), listOf(access["mapper"], method["name"]), "名前の判断は項目へ直接当たる")
        assertEquals("TBL-todo", method["table"], "テーブルは ID で指す（名前を写さない）")
        assertEquals("COL-todo.result", rows(method, "set").single { it["item"] == "result" }["column"])
        val mapperSql = plan.outputs.single { it.document.value == "design/detail/final/application-access.yaml" }.text
        assertTrue("todo_records" in mapperSql && "completion_result" in mapperSql, "物理名の補正は、テーブルの項目から Mapper のプログラム設計へ届く")
        val process = doc("design/detail/final/processes/complete-todo.yaml")
        // ステップは、Mapper のメソッドとメッセージを ID で参照する（名前とテキストはコピーしない）。変更後の名前とテキストは、参照先のレコードと、下流のプログラム設計に出る
        assertEquals("MTH-todo.completeTodo", rows(process, "steps").first { it["step"] == "write" }["call"])
        assertEquals("MSG-complete-todo.no-open-task", rows(process, "steps").single { it["precondition"] == "PRE-complete-todo.no-open-task" }["message"])
        assertEquals(listOf<Any?>("CompletionService", "finish"), map(process, "service").let { listOf(it["class"], it["method"]) })
        val serviceBindings = plan.outputs.single { it.document.value == "design/detail/final/application-service-bindings.yaml" }.text
        val servicePrograms = plan.outputs.single { it.document.value == "design/detail/final/application-service-programs.yaml" }.text
        assertTrue("TodoStore.saveCompletion" in serviceBindings && "CompletionService" in servicePrograms, "名前のオーバーライドは、ID から解決して Service のプログラム設計へ届く")
        val handler = rows(doc("design/detail/final/controllers/todo.yaml"), "handlers").single { it["action"] == "complete-todo" }
        assertEquals("CompletionService.finish", handler["service"])
        val execute = rows(handler, "steps").last()
        assertEquals("POST /todo/{id}/finish", execute["request"])
        assertEquals(listOf("confirmationToken"), map(execute, "session")["reads"])
        val form = map(doc("design/detail/final/screen-designs/operations/complete-todo.yaml"), "form")
        val result = rows(form, "fields").single { it["name"] == "result" }
        assertEquals(123, rows(result, "validations").single { it["name"] == "Size" }["max"])
        val validation = rows(doc("design/basic/final/messages.yaml"), "messages").filter { it["input"] == "PRM-complete-todo.result" }
        assertTrue(validation.any { it["check"] == "length" && "123" in it["text"].toString() }, validation.toString())
        val formMessages = rows(doc("design/detail/final/application-web-forms.yaml"), "validationMessages").associate { it["key"] to it["text"] }
        assertTrue("123" in formMessages["validation.Size.completeTodoForm.result"].toString(), "入力の検査のキーは、Form のプログラム設計が組み立てる")
        // 画面の項目に当たって、当てた判断が項目に残る。断りの文は項目に写さず、前提を ID で指す（文はメッセージ定義書が持つ）
        val screen = doc("design/basic/final/screens/complete-todo.yaml")
        assertEquals("POST /todo/{id}/finish", map(screen, "execute")["request"])
        assertEquals(listOf(listOf("COR-912")), rows(screen, "decisions").map { it["by"] })
        assertEquals("PRE-complete-todo.no-open-task", rows(map(screen, "execute"), "refusals").single { it["key"] == "complete-todo.no-open-task" }["precondition"])
        assertEquals(listOf("TODO 全体の確認", "/overview"), doc("design/basic/final/screens/todo-dashboard.yaml").let { listOf(it["label"], it["url"]) })
        // ボタンの名称は、この画面でだけ変わる（ほかの画面のボタンと、操作の名称は変わらない）
        assertEquals("完了", rows(doc("design/basic/final/screens/todo.yaml"), "buttons").single { it["id"] == "BTN-todo.complete-todo" }["label"])
        val resolvedScreens = doc("design/basic/composed/screens-resolved.yaml")
        fun resolvedRecord(kind: String, id: String) = rows(resolvedScreens, kind).map { map(it, "record") }.single { it["id"] == id }
        assertEquals("完了", rows(resolvedRecord("details", "todo"), "buttons").single { it["operation"] == "complete-todo" }["label"])
        assertEquals("TODOを完了する", rows(rows(resolvedRecord("lists", "todo-dashboard"), "sections").first(), "rowButtons").single { it["operation"] == "complete-todo" }["label"])
        // 詳細設計の画面設計書まで届く（詳細画面のそのボタンだけが変わり、操作の名称を写すほかの箇所は変わらない）
        val detailDesign = plan.outputs.single { it.document.value == "design/detail/final/screen-designs/details/todo.yaml" }.text
        assertTrue(Regex("""- operation: complete-todo\n\s+label: 完了\n""").containsMatchIn(detailDesign), "詳細画面のボタン")
        assertTrue("label: TODOを完了する" in detailDesign, "状態の履歴の操作の名称は変わらない")
        val listDesign = plan.outputs.single { it.document.value == "design/detail/final/screen-designs/lists/todo-dashboard.yaml" }.text
        assertTrue(Regex("""- operation: complete-todo\n\s+label: TODOを完了する\n""").containsMatchIn(listDesign), "一覧画面の行のボタンは変わらない")
        assertEquals("TODO 全体の確認", rows(doc("design/basic/final/screens/transitions/todo-dashboard.yaml"), "screens").single { it["id"] == "todo-dashboard" }["label"])
        // 操作への個別の判断は、項目に当たって、当てた判断が項目に残り、詳細設計まで届く（詳細設計は操作を項目から読む）
        val operation = doc("design/basic/final/operations/cancel-todo.yaml")
        assertEquals("TODOを取りやめる", operation["label"])
        assertEquals(200, rows(operation, "inputs").single { it["id"] == "PRM-cancel-todo.reason" }["maxLength"])
        assertEquals(setOf(listOf("COR-901"), listOf("COR-902")), rows(operation, "decisions").map { it["by"] }.toSet())
        assertEquals("TODOを取りやめる", doc("design/detail/final/processes/cancel-todo.yaml")["label"])
        val cancelForm = map(doc("design/detail/final/screen-designs/operations/cancel-todo.yaml"), "form")
        assertEquals(200, rows(rows(cancelForm, "fields").single { it["name"] == "reason" }, "validations").single { it["name"] == "Size" }["max"])
        assertEquals("reviewPreference", rows(doc("design/detail/final/session.yaml"), "attributes").last()["key"])
        assertEquals("validated", doc("design/detail/composed/review.yaml")["status"])
        plan.outputs.forEach { input.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(input)).upToDate, "補正後の二度目の生成は同じ結果になる")
    }

    @Test
    fun `存在しない対象と選択先と不正な型と識別子変更では前回の出力を保存したまま止まる`() {
        val cases = listOf(
            "[{id: SCOR-test, kind: session-design, target: DOC-session-design.missing, set: {name: 不明}, reason: 検証}]",
            "[{id: SCOR-test, kind: session-design, target: DOC-session-design.session-design, set: {typo: 不明}, reason: 検証}]",
            "[{id: SCOR-test, kind: session-design, target: DOC-session-design.session-design, set: {id: other}, reason: 検証}]",
            "[{id: SCOR-test, kind: screen-designs-operations, target: DOC-screen-designs-operations.complete-todo, changes: [{path: [form, class], value: OtherForm}], reason: 検証}]",
        )
        for (corrections in cases) {
            val input = store(corrections)
            val id = DocumentId("design/detail/final/processes/complete-todo.yaml")
            val before = input.read(id)?.text
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(plan.errors.isNotEmpty(), corrections)
            assertTrue(plan.transaction.operations.isEmpty(), plan.errors.toString())
            assertEquals(before, input.read(id)?.text)
        }
        // 操作の項目への判断: 無い対象・入力以外の項目・書けないキー・識別する値・振る舞い・対象に合わないキーは、どれも止める
        val operationCases = listOf(
            "{id: COR-test, target: ACT-missing, set: {label: 不明}, reason: 検証}" to "ACT-missing",
            "{id: COR-test, target: PRM-complete-todo.missing, set: {maxLength: 5}, reason: 検証}" to "PRM-complete-todo.missing",
            "{id: COR-test, target: EFF-complete-todo.01, set: {label: 不明}, reason: 検証}" to "target",
            "{id: COR-test, target: ACT-complete-todo, set: {typo: 不明}, reason: 検証}" to "typo",
            "{id: COR-test, target: PRM-complete-todo.result, set: {key: other}, reason: 検証}" to "correction-keeps-identity",
            "{id: COR-test, target: ACT-complete-todo, set: {effects: []}, reason: 検証}" to "correction-keeps-behavior",
            "{id: COR-test, target: ACT-complete-todo, set: {maxLength: 5}, reason: 検証}" to "correction-fits-operation",
        )
        for ((correction, expected) in operationCases) {
            val input = store()
            input.write(DocumentId("design/basic/inputs/operations/corrections.yaml"), "inputId: INPUT-basic-operation-corrections\ncorrections: [$correction]\n")
            val id = DocumentId("design/detail/final/processes/complete-todo.yaml")
            val before = input.read(id)?.text
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(expected in plan.errors.joinToString("\n"), "$correction: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty(), plan.errors.toString())
            assertEquals(before, input.read(id)?.text)
        }
        // 画面の項目への判断: 無い対象・その対象では置き換えられない値・書けないキー（入力の決まりなど）は、どれも止める
        val screenCases = listOf(
            "{id: COR-test, target: BTN-missing, set: {label: 不明}, reason: 検証}" to "BTN-missing",
            "{id: COR-test, target: SCR-complete-todo, set: {url: /x}, reason: 検証}" to "url は、この対象では置き換えられません",
            "{id: COR-test, target: SCR-todo, set: {executeRequest: POST /x}, reason: 検証}" to "executeRequest は、この対象では置き換えられません",
            "{id: COR-test, target: SCR-start-todo, set: {backRequest: POST /x}, reason: 検証}" to "backRequest は、この対象では置き換えられません",
            "{id: COR-test, target: SCR-complete-todo.confirm, set: {label: 不明}, reason: 検証}" to "label は、この対象では置き換えられません",
            "{id: COR-test, target: BTN-todo.edit-todo, set: {request: GET /x}, reason: 検証}" to "correction-fits-part",
            "{id: COR-test, target: SCR-complete-todo.input, set: {maxLength: 3}, reason: 検証}" to "maxLength",
            "{id: COR-test, target: PRM-complete-todo.result, set: {label: 不明}, reason: 検証}" to "target",
        )
        for ((correction, expected) in screenCases) {
            val input = store()
            input.write(DocumentId("design/basic/inputs/screens/corrections.yaml"), "inputId: INPUT-basic-screen-corrections\ncorrections: [$correction]\n")
            val id = DocumentId("design/detail/final/processes/complete-todo.yaml")
            val before = input.read(id)?.text
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(expected in plan.errors.joinToString("\n"), "$correction: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty(), plan.errors.toString())
            assertEquals(before, input.read(id)?.text)
        }
    }

    @Test
    fun `方式の指定は区分のエンティティに項目を足し テーブルの列まで届く`() {
        fun entities(text: String) = rows(raw(text), "entities").associateBy { it["id"] }
        fun items(entity: Map<*, *>) = rows(entity, "items")
        // サンプルは、区分 CLS-transaction を対象に、同時更新を版で検出する方式（optimistic-lock）を指定している
        val final = entities(Files.readString(sample.resolve("design/basic/final/entities.yaml")))
        val version = items(final.getValue("ENT-todo")).single { it["id"] == "FLD-todo.version" }
        assertEquals("SPC-optimistic-lock", version["addedBy"])
        assertEquals(listOf("int", true, 0, "revision"), listOf(version["base"], version["required"], version["default"], version["auto"]))
        assertEquals(items(final.getValue("ENT-todo")).size.toString().padStart(2, '0'), version["seq"], "項番は最後")
        assertEquals(listOf("rule" to "SPC-optimistic-lock"), rows(version, "origins").map { it["kind"] to it["source"] })
        // 入れ子で持つ要素（親で守る）と、区分の違うエンティティには足さない
        for (id in listOf("ENT-todo-task", "ENT-category", "ENT-tag")) assertTrue(items(final.getValue(id)).none { it["addedBy"] != null }, id.toString())
        val column = rows(raw(Files.readString(sample.resolve("design/detail/final/tables.yaml"))), "tables")
            .flatMap { rows(it, "columns") }.single { it["id"] == "COL-todo.version" }
        assertEquals(listOf("FLD-todo.version", "SQL-int"), listOf(column["source"], column["sqlType"]))
        // 受け手がそのエンティティの操作には、読んだときの版を運ぶ入力・版が同じという前提・版を進める効果が足される
        fun operation(id: String) = raw(Files.readString(sample.resolve("design/basic/final/operations/$id.yaml")))
        val complete = operation("complete-todo")
        val carried = rows(complete, "inputs").single { it["id"] == "PRM-complete-todo.version" }
        assertEquals(listOf(true, "SPC-optimistic-lock", mapOf("\$copy" to "version", "\$of" to "self")), listOf(carried["carried"], carried["addedBy"], carried["initial"]))
        val unchanged = rows(complete, "preconditions").single { it["id"] == "PRE-complete-todo.version-unchanged" }
        assertEquals(listOf(mapOf("of" to "t", "path" to "version", "equals" to "\$version")), map(unchanged, "rule")["where"])
        val advance = rows(complete, "effects").single { it["id"] == "EFF-complete-todo.version" }
        assertEquals(listOf("revision", "FLD-todo.version", null), listOf(advance["auto"], advance["item"], advance["value"]), "値の式は持たない（前の値の次）")
        // 受け手が入れ子の要素（作業項目）の操作は、親の TODO のバージョンを運び、親の行で確かめ、親の項目を進める
        val finish = operation("todo-task.finish")
        assertEquals(mapOf("\$copy" to "version", "\$of" to "owner"), rows(finish, "inputs").single { it["id"] == "PRM-todo-task.finish.version" }["initial"])
        val ownerRule = map(rows(finish, "preconditions").single { it["id"] == "PRE-todo-task.finish.version-unchanged" }, "rule")
        assertEquals(listOf(mapOf("of" to "owner", "path" to "version", "equals" to "\$version")), ownerRule["where"])
        assertEquals("ENT-todo", rows(finish, "effects").single { it["id"] == "EFF-todo-task.finish.version" }["entity"])
        val finishSteps = rows(raw(Files.readString(sample.resolve("design/detail/final/processes/todo-task.finish.yaml"))), "steps")
        assertEquals(listOf("read", "read-owner"), finishSteps.take(2).map { it["step"] }, "受け手を読んだ後に、親の行を読む")
        assertEquals("owner", finishSteps.single { it["precondition"] == "PRE-todo-task.finish.version-unchanged" }["checkMode"])
        // 登録（受け手が無い）とマスタの操作には足さない
        for (id in listOf("register-todo", "add-category")) {
            val other = operation(id)
            assertTrue(rows(other, "inputs").none { it["carried"] == true } && rows(other, "effects").none { it["auto"] == "revision" }, id)
        }
        // 画面設計書では、入力画面と確認画面の hidden 項目になり、入力欄と確認の表示には出ない
        val design = raw(Files.readString(sample.resolve("design/detail/final/screen-designs/operations/complete-todo.yaml")))
        assertTrue("label: 版" !in Files.readString(sample.resolve("design/detail/final/screen-designs/operations/complete-todo.yaml")))
        assertTrue(rows(map(design, "form"), "fields").any { it["item"] == "version" })
        // 入力画面の無い操作も、画面が持ち回す入力として持つ。読み元は操作の入力が持ち、読む側の形（解いた形）で carried になる（確認画面を出すときに読む）
        assertEquals(listOf("PRM-start-todo.version"), raw(Files.readString(sample.resolve("design/basic/final/screens/start-todo.yaml")))["hidden"])
        val startResolved = rows(raw(Files.readString(sample.resolve("design/basic/composed/screens-resolved.yaml"))), "operations").map { map(it, "record") }.single { it["id"] == "start-todo" }
        assertEquals(mapOf("version" to mapOf("\$copy" to "version", "\$of" to "self")), startResolved["carried"])
        // 業務モデルを写したモデルには入らない（業務の画面の表示項目にならない）
        assertTrue("version" !in Files.readString(sample.resolve("design/basic/composed/documents/model.yaml")).lines().filter { it.trim().startsWith("key:") }.joinToString())
        // 指定を外すと、項目は出ない
        val corrections = "design/basic/inputs/entities/corrections.yaml"
        val without = store()
        val text = without.read(DocumentId(corrections))!!.text
        val start = text.indexOf("  # 全体の判断（方式）")
        assertTrue(start > 0, "サンプルの指定の位置")
        without.write(DocumentId(corrections), text.substring(0, start))
        val plan = Generators.plan(WorkspaceSession(without))
        assertEquals(emptyList(), plan.errors)
        val regenerated = entities(plan.outputs.single { it.document.value == "design/basic/final/entities.yaml" }.text)
        assertTrue(regenerated.values.flatMap(::items).none { it["key"] == "version" })
        // 接続していない方式は、確定の前に止める
        val unknown = store()
        unknown.write(DocumentId(corrections), text.replace("rule: optimistic-lock", "rule: audit-trail"))
        val stopped = Generators.plan(WorkspaceSession(unknown))
        assertTrue(stopped.errors.any { "specifications" in it }, stopped.errors.toString())
        assertTrue(stopped.transaction.operations.isEmpty())
    }

    @Test
    fun `別のダッシュボードの画面を遷移定義へ混ぜない`() {
        val input = store()
        input.write(DocumentId(".yde/views/business/category-only.yaml"), """
            id: category-only
            name: カテゴリだけ
            kind: dashboard
            actions: [add-category]
            sections: [{id: categories, view: category-management}]
        """.trimIndent())
        val plan = Generators.plan(WorkspaceSession(input), setOf("screen-transitions"))
        assertEquals(emptyList(), plan.errors)
        val definition = raw(plan.outputs.single { it.document.value == "design/basic/final/screens/transitions/category-only.yaml" }.text)
        assertEquals(setOf("category-only", "category", "add-category/input", "add-category/confirm"), rows(definition, "screens").map { it["id"] }.toSet())
    }

    @Test
    fun `追加の重複と未対応の追加先を拒否する`() {
        for ((collection, item) in listOf("attributes" to "{key: token}", "unknown" to "{key: extra}")) {
            val input = store(additions = "[{id: SADD-test, kind: session-design, target: DOC-session-design.session-design, collection: $collection, items: [$item], reason: 検証}]")
            val plan = Generators.plan(WorkspaceSession(input))
            assertTrue(plan.errors.isNotEmpty(), "$collection: ${plan.errors}")
            assertTrue(plan.transaction.operations.isEmpty())
        }
    }

    @Test
    fun `方式の既定値の競合は個別指定で解決し不採用の候補と理由も残す`() {
        val specifications = "[{id: SSPC-a, kind: session-design, target: DOC-session-design.session-design, rule: defaults, settings: {name: A}, reason: 方式 A}, {id: SSPC-b, kind: session-design, target: DOC-session-design.session-design, rule: defaults, settings: {name: B}, reason: 方式 B}]"
        val failed = Generators.plan(WorkspaceSession(store(specifications = specifications)))
        assertTrue(failed.errors.any { "異なる規則の既定値" in it }, failed.errors.toString())
        val plan = Generators.plan(WorkspaceSession(store("[{id: SCOR-choice, kind: session-design, target: DOC-session-design.session-design, set: {name: 採用した名前}, reason: 代表値を採用}]", specifications = specifications)))
        assertEquals(emptyList(), plan.errors)
        val output = plan.outputs.single { it.document.value == "design/detail/final/session.yaml" }.text
        assertTrue(listOf("採用した名前", "SSPC-a", "SSPC-b", "方式 A", "方式 B", "代表値を採用").all { it in output }, output)
    }
}
