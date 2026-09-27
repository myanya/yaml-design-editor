package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 検査（constraints）の値の式で、1 つの要素の中の配列にわたる集計（パスに `[*]`）と順序の種類（`type: date`）を使うテスト。
 * ビューの値の式の列と同じ評価器なので、一覧で見えている「最終更新日」と検査が比べる値は常に一致する。
 * 値が書かれていない・日付として読めない材料があれば「得られない」＝判定保留（指摘しない）。
 */
class ArrayAggregateCheckTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-array-aggregate")
        write(
            "_schemas/todo.yaml",
            """
            id: todo
            match: "todos/*.yaml"
            entity: { type: todo, idPattern: "T-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: lastUpdated, type: date }
                - key: comments
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: date, type: date }
                      - { key: body, type: text }
            constraints:
              - id: last-updated-matches-latest-comment
                description: 手書きの最終更新日は、最新のコメントの日付と一致すること
                match:
                  - node: { entity: todo, bind: t }
                check:
                  - eq: [{ of: t, path: lastUpdated }, { max: { all: { of: t, path: "comments[*].date" }, type: date } }]
                level: error
              - id: has-comment
                description: コメントが 1 件以上あること
                match:
                  - node: { entity: todo, bind: t }
                check:
                  - gte: [{ count: { all: { of: t, path: "comments[*]" } } }, 1]
                level: error
            """,
        )
        write(
            "todos/T-OK.yaml",
            "id: T-OK\nname: 一致\nlastUpdated: \"2026-09-05\"\ncomments:\n  - { date: \"2026-09-02\", body: a }\n  - { date: \"2026-09-05\", body: b }\n  - { date: \"2026-09-01\", body: c }",
        )
        write(
            "todos/T-STALE.yaml",
            "id: T-STALE\nname: 古い\nlastUpdated: \"2026-09-02\"\ncomments:\n  - { date: \"2026-09-02\", body: a }\n  - { date: \"2026-09-05\", body: b }\n  - { date: \"2026-09-01\", body: c }",
        )
        write("todos/T-NONE.yaml", "id: T-NONE\nname: コメントなし\nlastUpdated: \"2026-09-01\"")
        write(
            "todos/T-BAD.yaml",
            "id: T-BAD\nname: 読めない日付\nlastUpdated: \"2026-09-05\"\ncomments:\n  - { date: \"2026-09-05\", body: a }\n  - { date: \"2026/09/01\", body: b }",
        )
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun errorsOf(ws: Workspace, rel: String) = ws.validate(rel).filter { it.severity == Severity.ERROR }

    @Test
    fun `配列の日付の最大は並び順によらず手書きの値と比べられる`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        assertEquals(emptyList(), errorsOf(ws, "todos/T-OK.yaml").map { it.message })
        val stale = errorsOf(ws, "todos/T-STALE.yaml").single()
        // 指摘には集計の材料（最大: 各コメントの日付）が添えられる
        assertTrue("（= 最大: 2026-09-02・2026-09-05・2026-09-01）" in stale.message, stale.message)
    }

    @Test
    fun `配列が無ければ件数は0で最大は得られず判定を保留する`() {
        val ws = Workspace.open(dir)
        val issues = errorsOf(ws, "todos/T-NONE.yaml")
        assertEquals(listOf("has-comment"), issues.map { it.source.ruleId }, issues.map { it.message }.toString())
    }

    @Test
    fun `日付として読めない材料があれば最大は得られず判定を保留する`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), errorsOf(ws, "todos/T-BAD.yaml").map { it.message })
    }
}
