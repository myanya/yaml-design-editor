package dev.toros.yde.app.core

import dev.toros.yde.workspace.RefactoringImpact
import dev.toros.yde.yaml.edit.YamlOperation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * リファクタリングのアプリ層影響解決（B-1・RB-01。core/RefactorImpact.kt）のテスト。
 * データ・スキーマ本体の計画はカーネル側（workspace-core RefactoringTest）が担い、ここは
 * root 残置のビュー追随（静的解決の一意な列パスの書換）・言及警告（宣言節・定義文書）だけを固定する
 * （旧 SchemaRefactorTest のビュー系ケースの移植）。
 */
class RefactorImpactTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-refimpact")
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/**/*.yaml"
            coverage:
              - { description: "全タスクは title を持つこと", idPattern: "TASK-*", detailIn: { schemaIds: [task-detail] }, level: warning }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - key: details
                  type: object
                  fields:
                    - { key: priority, type: string }
        """)
        write("_views/tasks.yaml", """
            id: task-list
            name: タスク一覧
            rows: { idPattern: "TASK-*" }
            columns:
              - { label: タイトル, path: title }
              - { label: 優先度, path: details.priority }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            title: 最初のタスク
            details:
              priority: high
        """)
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

    @Test
    fun `ルート直下フィールドの改名にビューの通常列が追随する`() {
        val ws = Workspace.open(dir)
        val impact = RefactorImpact.fieldRename(ws, "task-schema", "title", "name")
        assertEquals(1, impact.changes.size, impact.changes.toString())
        val change = impact.changes.single()
        assertEquals("_views/tasks.yaml", change.document.value)
        val op = change.operations.single() as YamlOperation.SetScalar
        assertEquals("name", op.value)
        // 宣言節（coverage）の言及は自動書換せず警告になる
        assertTrue(impact.warnings.any { "coverage" in it && "title" in it }, impact.warnings.toString())
    }

    @Test
    fun `ネストしたフィールドの改名はビューのパス全体を書き換える`() {
        val ws = Workspace.open(dir)
        val impact = RefactorImpact.fieldRename(ws, "task-schema", "details.priority", "urgency")
        val op = impact.changes.single().operations.single() as YamlOperation.SetScalar
        assertEquals("details.urgency", op.value)
    }

    @Test
    fun `定義文書の言及は警告として列挙され変更には含まれない`() {
        write("_activities/fill-title.yaml", """
            id: fill-title
            name: タイトル補完
            instruction: 各タスクの title を補完する
        """)
        val ws = Workspace.open(dir)
        val impact = RefactorImpact.fieldRename(ws, "task-schema", "title", "name")
        assertTrue(impact.warnings.any { "fill-title.yaml" in it }, impact.warnings.toString())
        assertTrue(impact.changes.none { it.document.value.startsWith("_activities/") })
    }

    @Test
    fun `フィールド削除はビューと宣言節の言及を警告する`() {
        val ws = Workspace.open(dir)
        val warnings = RefactorImpact.fieldDeleteWarnings(ws, "details.priority")
        assertTrue(warnings.any { "tasks.yaml" in it && "壊れます" in it }, warnings.toString())
    }

    @Test
    fun `値の式の列の穴とパスと参照の項目も改名に追随する`() {
        write("_schemas/person.yaml", """
            id: person-schema
            match: "people/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: name, type: string }
        """)
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - { key: owner, type: ref }
                - key: details
                  type: object
                  fields:
                    - { key: priority, type: string }
        """)
        write("_views/tasks-value.yaml", """
            id: task-value
            rows: { idPattern: "TASK-*" }
            columns:
              - { label: 要約, value: "{title}（{details.priority}）" }
              - label: 優先度
                value: { path: details.priority }
              - label: 担当者名
                value:
                  format: { via: owner, in: { schemaId: person-schema }, path: name }
                  scopes:
                    - { via: owner, in: { schemaId: person-schema } }
        """)
        val ws = Workspace.open(dir)
        fun opsOf(impact: RefactoringImpact.Impact) = impact.changes.first { it.document.value == "_views/tasks-value.yaml" }
            .operations.filterIsInstance<YamlOperation.SetScalar>().map { it.value }
        // 短い書き方の穴
        assertEquals(listOf("{name}（{details.priority}）"), opsOf(RefactorImpact.fieldRename(ws, "task-schema", "title", "name")))
        // 穴と、いまの要素からの読み出しの path
        assertEquals(
            setOf("{title}（{details.urgency}）", "details.urgency"),
            opsOf(RefactorImpact.fieldRename(ws, "task-schema", "details.priority", "urgency")).toSet(),
        )
        // 参照を辿る項目（読み出しと入れ物の両方）
        assertEquals(listOf("assignee", "assignee"), opsOf(RefactorImpact.fieldRename(ws, "task-schema", "owner", "assignee")))
        // 相手のスキーマ（in）から解決する path
        assertEquals(listOf("fullName"), opsOf(RefactorImpact.fieldRename(ws, "person-schema", "name", "fullName")))
    }
}
