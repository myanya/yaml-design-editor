package dev.toros.yde.app.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 共有の入力の候補をマスタから取り（型の名前・参照）、複数を選べる（multiple）。値は配列を受け取る条件（includesAny）と
 * oneOf で受ける。候補と値の点検は画面・業務 Web・CLI・保存版の読み出しが共有する（DashboardInputs）。
 */
class DashboardInputCandidatesTest {
    private lateinit var ws: Path
    private val date: LocalDate = LocalDate.of(2026, 11, 14)

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-dashboard-candidates")
        write("_schemas/common.yaml", """
            id: common
            kind: library
            definitions:
              区分:
                type: enum
                values: [{ value: a, label: 甲 }, { value: b, label: 乙 }]
              タグ参照:
                type: ref
                idPattern: "TAG-*"
                scope: { schemaIds: [tags] }
        """)
        write("_schemas/tags.yaml", """
            id: tags
            match: tags.yaml
            entity: { type: tag, idPattern: "TAG-*", defines: true }
            root:
              type: object
              fields:
                - key: tags
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
        """)
        write("_schemas/task.yaml", """
            id: task
            match: "tasks/*.yaml"
            entity: { type: task, idPattern: "T-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: kind, type: common#区分 }
                - { key: tags, type: array, item: { type: common#タグ参照 } }
        """)
        write("tags.yaml", "tags:\n  - { id: TAG-1, name: 急ぎ }\n  - { id: TAG-2, name: 確認 }\n  - { id: TAG-3, name: 後回し }")
        write("tasks/T-1.yaml", "id: T-1\nkind: a\ntags: [TAG-1]")
        write("tasks/T-2.yaml", "id: T-2\nkind: b\ntags: [TAG-2, TAG-3]")
        write("tasks/T-3.yaml", "id: T-3\nkind: a")
        write("_views/tasks.yaml", """
            id: tasks
            params: [tags, kinds]
            rows:
              match: [ { node: { entity: task, bind: t } } ]
              where:
                - { of: t, path: tags, includesAny: ${'$'}tags }
                - { of: t, path: kind, oneOf: ${'$'}kinds }
            columns: [ { label: 区分, path: kind } ]
        """)
        write("_views/home.yaml", """
            id: home
            kind: dashboard
            inputs:
              - { key: tags, label: タグ, type: common#タグ参照, multiple: true }
              - { key: kinds, label: 区分, type: common#区分, multiple: true }
              - { key: tag, label: タグ（直接）, type: ref, idPattern: "TAG-*" }
            sections:
              - { id: all, label: 全件, view: tasks }
              - id: one
                label: タグ 1 つ
                view: tasks
                filter: { match: [ { node: { entity: task, bind: t } } ], where: [ { of: t, path: tags, includes: ${'$'}tag } ], expect: exists }
        """)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `候補は型と索引から取り 複数の値は配列の条件で受け 候補に無い値は誤り`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertEquals(emptyList(), loaded.errors)
        val home = assertNotNull(loaded.dashboard("home"))
        fun resolve(inputs: Map<String, String?>) = Dashboards.resolve(home, date, loaded, workspace.index, workspace::schemaFor, workspace::treeOf, inputs)
        fun counts(inputs: Map<String, String?>) = resolve(inputs).sections.associate { it.definition.id to it.count }
        // 候補: 参照は索引（ID の順と表示名）、選択肢は型の値
        val r = resolve(emptyMap())
        assertEquals(listOf("TAG-1" to "急ぎ", "TAG-2" to "確認", "TAG-3" to "後回し"), r.options["tags"])
        assertEquals(listOf("a" to "甲", "b" to "乙"), r.options["kinds"])
        assertEquals(listOf("TAG-1", "TAG-2", "TAG-3"), r.options["tag"]?.map { it.first })
        // 空は絞らない。複数はカンマ区切りで渡し、どれかを持つ・どれかに等しい
        assertEquals(mapOf("all" to "3", "one" to "3"), counts(emptyMap()))
        assertEquals("2", counts(mapOf("tags" to "TAG-1,TAG-3"))["all"])
        assertEquals("1", counts(mapOf("tags" to "TAG-3"))["all"])
        assertEquals("2", counts(mapOf("kinds" to "a"))["all"])
        assertEquals("1", counts(mapOf("tags" to "TAG-1,TAG-2", "kinds" to "b"))["all"])
        assertEquals("1", counts(mapOf("tag" to "TAG-2"))["one"])
        // 開示: 複数を選べるか・候補（見出しが無ければ値）
        @Suppress("UNCHECKED_CAST")
        val report = Dashboards.report(home, r)["inputs"] as List<Map<String, Any?>>
        assertEquals(listOf(true, true, false), report.map { it["multiple"] })
        assertEquals(mapOf("value" to "TAG-2", "label" to "確認"), (report[0]["options"] as List<*>)[1])
        // 値の点検: 候補に無い値は誤り（複数ならどれか 1 つでも）
        assertEquals(emptyList(), Dashboards.inputErrors(home, mapOf("tags" to "TAG-1,TAG-2"), workspace.index))
        assertTrue(Dashboards.inputErrors(home, mapOf("tags" to "TAG-1,TAG-9"), workspace.index).single().contains("TAG-9"))
        assertTrue(Dashboards.inputErrors(home, mapOf("kinds" to "z"), workspace.index).single().contains("「z」"))
    }

    @Test
    fun `入力の型の書き方の誤りは定義エラー`() {
        val workspace = Workspace.open(ws)
        fun errors(input: String): List<String> {
            write("_views/home.yaml", "id: home\nkind: dashboard\ninputs:\n  - $input\nsections:\n  - { id: all, label: 全件, view: tasks }\n")
            return Views.load(ws, workspace.schemas).errors
        }
        assertTrue(errors("{ key: x, type: common#無い型 }").any { "見つかりません" in it })
        assertTrue(errors("{ key: x, type: 区分 }").any { "モジュールを付けて" in it })
        assertTrue(errors("{ key: x, type: string, multiple: true }").any { "multiple" in it })
        assertTrue(errors("{ key: x, type: common#タグ参照, idPattern: \"T-*\" }").any { "idPattern" in it })
        assertTrue(errors("{ key: x, type: common#区分, values: [a] }").any { "values" in it })
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }
}
