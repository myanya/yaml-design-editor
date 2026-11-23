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
        for ((phase, kind, target) in listOf(Triple("basic", "session-design", "session-design"), Triple("detail", "action-specs", "complete-todo"))) {
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
        assertEquals(84, shown("system-document-list").size)
        assertEquals(setOf("category", "tag", "todo", "todo-task"), shown("entity-trace").map { it.id }.toSet())
        assertEquals(14, shown("action-trace").size)
        val process = raw(Files.readString(sample.resolve("design/detail/final/processes/complete-todo.yaml")))
        val individual = shown("action-design-related", mapOf("source" to "complete-todo")).single()
        assertEquals(process["steps"], cell(individual, "steps"))
        val cross = shown("system-document-content", mapOf("document" to "DOC-processes.complete-todo")).single()
        assertEquals(process, cell(cross, "内容"))
        val detailed = raw(Files.readString(sample.resolve("design/detail/final/screen-designs/operations/complete-todo.yaml")))
        val screen = shown("screen-design-related", mapOf("source" to "complete-todo")).single()
        assertEquals(detailed["form"], cell(screen, "form"))
        assertEquals(detailed["screens"], cell(screen, "screens"))
    }
    private fun store(corrections: String = "[]", additions: String = "[]", specifications: String = "[]"): InMemoryDocumentStore {
        val files = TodosSysdevFiles.inputs(sample).filter { it.toString().endsWith(".yaml") }
            .associate { sample.relativize(it).toString().replace('\\', '/') to Files.readString(it) }.toMutableMap()
        val basicKinds = setOf("action-specs", "domains", "domain-relations", "errors", "messages", "screen-lists", "screen-details", "screen-operations", "screen-transitions")
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
            - {id: SCOR-mapper, kind: table-access, target: DOC-table-access.todo, set: {mapper: TodoStore}, reason: 保存先}
            - {id: SCOR-method, kind: table-access, target: DOC-table-access.todo, select: {collection: methods, key: methodId, equals: completeTodo}, set: {name: saveCompletion}, reason: 呼出名}
            - id: SCOR-service
              kind: processes
              target: DOC-processes.complete-todo
              reason: 呼出名
              changes: [{path: [service, class], value: CompletionService}, {path: [service, method], value: finish}]
            - {id: SCOR-message, kind: messages, target: DOC-messages.messages, select: {collection: messages, key: key, equals: complete-todo.no-open-task}, set: {text: 未完了の作業があります（確認用）。}, reason: 文の見直し}
            - {id: SCOR-session, kind: session-design, target: DOC-session-design.session-design, select: {collection: attributes, key: role, equals: token}, set: {key: confirmationToken}, reason: セッションのキー}
            - id: SCOR-input
              kind: screen-operations
              target: DOC-screen-operations.complete-todo
              reason: 入力と URL
              changes: [{path: [input, items, 1, maxLength], value: 123}, {path: [execute, request], value: 'POST /todo/{id}/finish'}]
            - {id: SCOR-title, kind: screen-lists, target: DOC-screen-lists.todo-dashboard, set: {name: TODO 全体の確認, url: /overview}, reason: 一覧の名称}
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
        val plan = Generators.plan(WorkspaceSession(input))
        assertEquals(emptyList(), plan.errors)
        fun doc(path: String) = raw(plan.outputs.single { it.document.value == path }.text)
        val access = doc("design/detail/final/table-access/todo.yaml")
        val method = rows(access, "methods").single { it["methodId"] == "completeTodo" }
        assertEquals("todo_records", method["table"])
        assertEquals("completion_result", rows(method, "set").single { it["item"] == "result" }["column"])
        val process = doc("design/detail/final/processes/complete-todo.yaml")
        assertEquals("TodoStore.saveCompletion", rows(process, "steps").first { it["step"] == "write" }["call"])
        assertEquals("未完了の作業があります（確認用）。", rows(process, "steps").single { it["precondition"] == "complete-todo.no-open-task" }["text"])
        val handler = rows(doc("design/detail/final/controllers/todo.yaml"), "handlers").single { it["action"] == "complete-todo" }
        assertEquals("CompletionService.finish", handler["service"])
        val execute = rows(handler, "steps").last()
        assertEquals("POST /todo/{id}/finish", execute["request"])
        assertEquals(listOf("confirmationToken"), map(execute, "session")["reads"])
        val form = map(doc("design/detail/final/screen-designs/operations/complete-todo.yaml"), "form")
        val result = rows(form, "fields").single { it["name"] == "result" }
        assertEquals(123, rows(result, "validations").single { it["name"] == "Size" }["max"])
        val validation = rows(doc("design/basic/final/messages.yaml"), "validation").single { it["form"] == "completeTodoForm" && it["field"] == "result" }
        assertTrue(rows(validation, "messages").any { "123" in it["text"].toString() }, validation.toString())
        val screen = doc("design/basic/final/screens/operations/complete-todo.yaml")
        assertEquals("未完了の作業があります（確認用）。", rows(map(screen, "execute"), "refusals").single { it["key"] == "complete-todo.no-open-task" }["message"])
        assertEquals("TODO 全体の確認", rows(doc("design/basic/final/screens/transitions/todo-dashboard.yaml"), "screens").single { it["id"] == "todo-dashboard" }["label"])
        assertEquals("reviewPreference", rows(doc("design/detail/final/session.yaml"), "attributes").last()["key"])
        assertEquals("validated", doc("design/detail/composed/review.yaml")["status"])
        plan.outputs.forEach { input.write(it.document, it.text) }
        assertTrue(Generators.plan(WorkspaceSession(input)).upToDate, "補正後の二度目の生成は同じ結果になる")
    }

    @Test
    fun `存在しない対象と選択先と不正な型と識別子変更では前回の出力を保存したまま止まる`() {
        val cases = listOf(
            "[{id: SCOR-test, kind: processes, target: DOC-processes.missing, set: {name: 不明}, reason: 検証}]",
            "[{id: SCOR-test, kind: table-access, target: DOC-table-access.todo, select: {collection: methods, key: methodId, equals: missing}, set: {name: 不明}, reason: 検証}]",
            "[{id: SCOR-test, kind: processes, target: DOC-processes.complete-todo, set: {typo: 不明}, reason: 検証}]",
            "[{id: SCOR-test, kind: processes, target: DOC-processes.complete-todo, set: {id: cancel-todo}, reason: 検証}]",
            "[{id: SCOR-test, kind: action-specs, target: DOC-action-specs.complete-todo, set: {effects: []}, reason: 検証}]",
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
