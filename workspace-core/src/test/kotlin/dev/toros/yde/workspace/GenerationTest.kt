package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 生成（業務モデルから文書へ）: 生成の定義（.yde/transforms）を業務モデルの項に当てて文書を作り、作り直すたびに
 * 変わったものだけを書き、業務モデルから消えた要素の生成物は消す。書き込みはセッションの 1 トランザクション。
 */
class GenerationTest {
    private val recordSchema = "id: record\nmatch: 'design/*.yaml'\nroot: {type: object, fields: [{key: id, type: id}, {key: value, type: string}]}\n"
    private val recordView = "id: records\nrows: {idPattern: SPEC-*, schemaIds: [record]}\ncolumns: [{label: value, path: value}]\n"

    @Test
    fun `依存していない先行出力と前回の生成物は入力に混ざらない`() {
        for (fileName in listOf("a-producer", "z-producer")) {
            val inputs = mapOf(
                ".yde/schemas/design.yaml" to recordSchema,
                ".yde/views/records.yaml" to recordView,
                ".yde/transforms/$fileName.yaml" to "id: producer\noutput: design/new.yaml\nbuild: {id: SPEC-new, value: new}\n",
                ".yde/transforms/consumer.yaml" to "id: consumer\noutput: design/result.yaml\nbuild: {rows: {${'$'}view: records}}\n",
                "design/old.yaml" to "${Generators.marker("producer")}\nid: SPEC-old\nvalue: old\n",
                "design/result.yaml" to "${Generators.marker("consumer")}\nid: SPEC-self\nvalue: previous\n",
                "design/removed-generator.yaml" to "${Generators.marker("removed")}\nid: SPEC-removed\nvalue: previous\n",
                "design/manual.yaml" to "id: SPEC-manual\nvalue: manual\n",
            )
            val session = WorkspaceSession(store(inputs))
            for (only in listOf(emptySet(), setOf("consumer"))) {
                assertTrue(session.derived.semantic.ids["SPEC-new"].orEmpty().none { it.document.value.startsWith("design/") }, "生成計画前: $fileName / $only")
                val plan = Generators.plan(session, only)
                assertEquals(emptyList(), plan.errors)
                val result = plan.outputs.single { it.generator == "consumer" }.text
                assertTrue("SPEC-manual" in result, result)
                for (hidden in listOf("SPEC-new", "SPEC-old", "SPEC-self", "SPEC-removed")) assertTrue(hidden !in result, "$fileName / $only / $hidden\n$result")
            }
        }
    }

    @Test
    fun `適用後にまだ保存していない生成物も 依存先でなければ入力にしない`() {
        val session = WorkspaceSession(store(mapOf(
            ".yde/schemas/design.yaml" to recordSchema,
            ".yde/views/records.yaml" to recordView,
            ".yde/transforms/specs.yaml" to "id: specs\noutput: design/source.yaml\nbuild: {id: SPEC-source, value: fresh}\n",
            ".yde/transforms/final.yaml" to "id: final\noutput: design/final.yaml\nbuild: {rows: {${'$'}view: records}}\n",
        )))
        val first = Generators.plan(session, setOf("specs"))
        assertEquals(emptyList(), first.errors)
        assertTrue(session.apply(first.transaction).ok)
        assertEquals(null, session.storedText(DocumentId("design/source.yaml")))
        val second = Generators.plan(session, setOf("final"))
        assertEquals(emptyList(), second.errors)
        assertTrue("SPEC-source" !in second.outputs.single().text, second.outputs.single().text)
    }

    @Test
    fun `推移的な依存先は今回の結果だけを読み 消えた要素を前回の結果で補わない`() {
        val session = WorkspaceSession(store(mapOf(
            ".yde/schemas/design.yaml" to recordSchema,
            ".yde/views/records.yaml" to recordView,
            ".yde/transforms/specs.yaml" to "id: specs\noutput: design/source.yaml\nbuild: {id: SPEC-source, value: fresh}\n",
            ".yde/transforms/middle.yaml" to "id: middle\ndependsOn: [specs]\noutput: design/middle.yaml\nbuild: {value: middle}\n",
            ".yde/transforms/final.yaml" to "id: final\ndependsOn: [middle]\noutput: design/final.yaml\nbuild: {rows: {${'$'}view: records}}\n",
            "design/deleted.yaml" to "${Generators.marker("specs")}\nid: SPEC-deleted\nvalue: stale\n",
        )))
        val plan = Generators.plan(session, setOf("final"))
        assertEquals(emptyList(), plan.errors)
        val output = plan.outputs.single { it.generator == "final" }.text
        assertTrue("fresh" in output, output)
        assertTrue("SPEC-deleted" !in output, output)
    }

    @Test
    fun `依存先の失敗を古い生成物で補わず 計画の誤りがあれば変更を適用できない`() {
        val session = WorkspaceSession(store(mapOf(
            ".yde/schemas/design.yaml" to recordSchema,
            ".yde/views/records.yaml" to recordView,
            ".yde/transforms/specs.yaml" to "id: specs\noutput: design/{missing}.yaml\nbuild: {id: SPEC-source, value: fresh}\n",
            ".yde/transforms/final.yaml" to "id: final\ndependsOn: [specs]\noutput: design/final.yaml\nbuild: {rows: {${'$'}view: records}}\n",
            ".yde/transforms/valid.yaml" to "id: valid\noutput: design/valid.yaml\nbuild: {value: valid}\n",
            "design/source.yaml" to "${Generators.marker("specs")}\nid: SPEC-source\nvalue: old\n",
        )))
        val plan = Generators.plan(session)
        assertTrue(plan.errors.any { "依存先の生成が成功していません" in it }, plan.errors.toString())
        assertTrue(plan.outputs.none { it.generator == "final" })
        assertTrue(plan.transaction.operations.isEmpty(), "別の生成が成功していても、誤りのある計画からは変更しない")
        assertTrue(session.apply(plan.transaction).ok)
        assertEquals(null, session.storedText(DocumentId("design/valid.yaml")))
        val invalid = store(mapOf(".yde/transforms/specs.yaml" to generator + "\ndependsOn: [missing]\n"))
        assertTrue(Generators.plan(WorkspaceSession(invalid)).transaction.operations.isEmpty())
    }

    @Test
    fun `依存先の新しい出力を入力のビューから読み 一度で最新になる`() {
        val store = store(mapOf(
            ".yde/schemas/design.yaml" to "id: spec\nmatch: 'design/*.yaml'\nroot:\n  type: object\n  fields:\n    - {key: id, type: id}\n    - {key: value, type: string}\n",
            ".yde/transforms/specs.yaml" to "id: specs\noutput: design/source.yaml\nbuild: {id: SPEC-1, value: new}\n",
            ".yde/transforms/copy.yaml" to "id: copy\ndependsOn: [specs]\noutput: design/copy.yaml\ninput: {rows: {${'$'}view: records}}\nbuild: {value: {${'$'}copy: 'rows[0].values.value'}}\n",
            ".yde/views/records.yaml" to "id: records\nrows: {idPattern: SPEC-*}\ncolumns: [{label: value, path: value}]\n",
            "design/source.yaml" to "${Generators.marker("specs")}\nid: SPEC-1\nvalue: old\n",
        ))
        val session = WorkspaceSession(store)
        val plan = Generators.plan(session, setOf("copy"))
        assertEquals(emptyList(), plan.errors)
        assertEquals(listOf("specs", "copy"), plan.outputs.map { it.generator })
        assertTrue("value: new" in plan.outputs.last().text, plan.outputs.last().text)
        assertTrue(session.apply(plan.transaction).ok)
        assertTrue(session.save().ok)
        assertTrue(Generators.plan(WorkspaceSession(store), setOf("copy")).upToDate)
        val cycle = store(mapOf(".yde/transforms/specs.yaml" to generator + "\ndependsOn: [specs]\n"))
        assertTrue(Generators.plan(WorkspaceSession(cycle)).errors.any { "循環" in it })
    }

    private val schema = """
        id: task
        match: "tasks/*.yaml"
        entity: { type: task, defines: true }
        root:
          type: object
          label: 作業
          fields:
            - { key: id, type: id, required: true }
            - { key: memo, type: string }
        actions:
          - id: note-task
            label: メモを書く
            target: { entity: task }
            params: [{ key: memo, type: string, required: true }]
            do: [{ set: { path: memo, value: ${'$'}memo, of: self } }]
          - id: clear-task
            label: メモを消す
            target: { entity: task }
            do: [{ delete: { path: memo, of: self } }]
    """.trimIndent()

    private val generator = """
        id: specs
        name: 操作の一覧
        each: actions
        output: "design/{id}.yaml"
        build:
          id: { ${'$'}copy: id }
          name: { ${'$'}copy: label }
          method: { ${'$'}case: { ${'$'}copy: id }, ${'$'}as: camel }
    """.trimIndent()

    private fun store(extra: Map<String, String> = emptyMap()) = InMemoryDocumentStore(
        mapOf(
            ".yde/workspace.yaml" to "ledger: [tasks, design]\n",
            ".yde/schemas/task.yaml" to schema,
            ".yde/transforms/specs.yaml" to generator,
        ) + extra,
    )

    @Test
    fun `作る 変わらない 作り直す 消す`() {
        val store = store()
        val s = WorkspaceSession(store)
        val plan = Generators.plan(s)
        assertEquals(emptyList(), plan.errors)
        assertEquals(listOf("design/note-task.yaml", "design/clear-task.yaml"), plan.created.map { it.value })
        val text = plan.outputs.first().text
        assertTrue(text.startsWith(Generators.marker("specs")), text)
        assertTrue("method: noteTask" in text, text)
        assertTrue(s.apply(plan.transaction).ok)
        assertTrue(s.save().ok)
        // 書いた内容（見出しのコメントつき）から作り直すと、変わらない
        val again = Generators.plan(WorkspaceSession(store))
        assertTrue(again.upToDate, again.toString())
        assertEquals(2, again.unchanged.size)
        // 業務モデルの操作の名前が変わると作り直し、消えた操作の生成物は消す（見出しの無い手書きの文書は消さない）
        store.write(DocumentId(".yde/schemas/task.yaml"), schema.replace("label: メモを書く", "label: メモを記す").replace(Regex("""  - id: clear-task[\s\S]*"""), ""))
        store.write(DocumentId("design/hand.yaml"), "id: hand\n")
        val changed = Generators.plan(WorkspaceSession(store))
        assertEquals(listOf("design/note-task.yaml"), changed.updated.map { it.value })
        assertEquals(listOf("design/clear-task.yaml"), changed.deleted.map { it.value })
        assertTrue(!changed.upToDate)
    }

    @Test
    fun `定義の誤りと 同じ場所に 2 つ作る誤り`() {
        val dup = store(mapOf(".yde/transforms/specs.yaml" to generator.replace("design/{id}.yaml", "design/{kind}.yaml")))
        assertTrue(Generators.plan(WorkspaceSession(dup)).errors.any { "同じ場所「design/action.yaml」に 2 つ" in it })
        val noHole = store(mapOf(".yde/transforms/specs.yaml" to generator.replace("design/{id}.yaml", "design/all.yaml")))
        assertTrue(Generators.plan(WorkspaceSession(noHole)).errors.any { "each を書いたときは" in it })
        val reserved = store(mapOf(".yde/transforms/specs.yaml" to generator.replace("design/{id}.yaml", ".yde/views/{id}.yaml")))
        assertTrue(Generators.plan(WorkspaceSession(reserved)).errors.any { "定義・記録の置き場" in it })
        val unknown = Generators.plan(WorkspaceSession(store()), setOf("nope"))
        assertTrue(unknown.errors.any { "生成の定義「nope」がありません" in it })
    }
}
