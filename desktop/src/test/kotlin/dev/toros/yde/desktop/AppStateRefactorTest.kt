package dev.toros.yde.desktop

import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * リファクタリングのセッション一本化（B-1・RB-01）の AppState 統合テスト。
 * 計画（planEntityRename / planSchemaFieldRename）がカーネル＋アプリ層補強で生成され、
 * 適用（applyEntityPlan）が1トランザクション＋保存＝ **Undo 1回で全変更が戻る**（D-12）こと、
 * 一括更新（bulkUpdate）も同様に Undo 1回で戻る（D-11）ことを固定する。
 */
class AppStateRefactorTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-apprefactor")
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - { key: depends_on, type: array, item: { type: ref } }
                - { key: spec, type: ref, target: document }
                - { key: status, type: string }
        """)
        write("_views/tasks.yaml", """
            id: task-list
            name: タスク一覧
            rows: { idPattern: "TASK-*" }
            columns:
              - { label: タイトル, path: title }
              - { label: 状態, path: status, fileGlob: "tasks/t1.yaml" }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            title: 最初のタスク
            status: open
        """)
        write("tasks/t2.yaml", """
            id: TASK-002
            title: 二番目
            depends_on:
              - TASK-001
            spec: tasks/t1.yaml
            status: open
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

    private fun read(rel: String): String = Files.readString(dir.resolve(rel))

    private fun openState(): AppState = AppState().also { it.openWorkspace(dir) }

    @Test
    fun `エンティティ改名は全出現と参照を書き換えUndo1回で戻る`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        val t2 = state.ensureDoc(dir.resolve("tasks/t2.yaml"))

        val plan = state.planEntityRename("TASK-001", "TASK-100").getOrThrow()
        assertEquals(setOf("tasks/t1.yaml", "tasks/t2.yaml"), plan.changes.map { it.document.value }.toSet())

        state.applyEntityPlan(plan).getOrThrow()
        assertTrue("id: TASK-100" in read("tasks/t1.yaml"), read("tasks/t1.yaml"))
        assertTrue("TASK-100" in read("tasks/t2.yaml"), read("tasks/t2.yaml"))
        // 適用後は開いている文書のツリーも追随し、dirty ではない（保存済み）
        assertEquals("TASK-100", ((t1.root as YNode.Map).entries["id"] as YNode.Scalar).value)
        assertFalse(t1.dirty || t2.dirty)

        // Undo 1回でリファクタリング全体（両文書）が working 上で戻る（保存は通常の操作）
        assertTrue(state.canUndo())
        state.undo()
        assertEquals("TASK-001", ((t1.root as YNode.Map).entries["id"] as YNode.Scalar).value)
        assertTrue((t2.root as YNode.Map).let { root ->
            ((root.entries["depends_on"] as YNode.Seq).items.first() as YNode.Scalar).value == "TASK-001"
        })
        assertTrue(t1.dirty && t2.dirty)
    }

    @Test
    fun `フィールド一括改名はビュー列を追随して1トランザクションで適用される`() {
        val state = openState()
        val plan = state.planSchemaFieldRename("task-schema", "title", "name").getOrThrow()
        // カーネルの計画（スキーマ＋文書）にアプリ層のビュー追随が合成される（D-10 の再接続）
        assertTrue(plan.changes.any { it.document.value == "_views/tasks.yaml" }, plan.changes.toString())
        assertTrue(plan.summary.any { "ビュー" in it && "追随" in it }, plan.summary.toString())

        state.applyEntityPlan(plan).getOrThrow()
        assertTrue("key: name" in read("_schemas/task.yaml"))
        assertTrue("name: 最初のタスク" in read("tasks/t1.yaml"))
        assertTrue("path: name" in read("_views/tasks.yaml"))
    }

    @Test
    fun `対象に未保存編集があると適用は拒否される`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        val plan = state.planEntityRename("TASK-001", "TASK-100").getOrThrow()
        (t1.root as YNode.Map).entries["title"] = YNode.Scalar("編集中")
        t1.markEdited()
        val result = state.applyEntityPlan(plan)
        assertTrue(result.isFailure)
        assertTrue("未保存" in (result.exceptionOrNull()?.message ?: ""), result.exceptionOrNull()?.message ?: "")
    }

    @Test
    fun `一括更新はUndo1回で全ファイルが戻る`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        val t2 = state.ensureDoc(dir.resolve("tasks/t2.yaml"))
        val count = state.bulkUpdate(
            setOf(dir.resolve("tasks/t1.yaml"), dir.resolve("tasks/t2.yaml")), "status", "closed",
        ).getOrThrow()
        assertEquals(2, count)
        assertTrue("status: closed" in read("tasks/t1.yaml"))
        assertTrue("status: closed" in read("tasks/t2.yaml"))

        assertTrue(state.canUndo())
        state.undo()
        assertEquals("open", ((t1.root as YNode.Map).entries["status"] as YNode.Scalar).value)
        assertEquals("open", ((t2.root as YNode.Map).entries["status"] as YNode.Scalar).value)
        assertTrue(t1.dirty && t2.dirty)
    }

    @Test
    fun `renameImpactはカーネルの補足とビュー警告を合成する`() {
        val state = openState()
        val impact = state.renameImpact(dir.resolve("tasks/t1.yaml"), "renamed.yaml")
        // カーネル計画の補足（パスIDの文書参照の書き換え予告）＋アプリ層のビュー対象外れ警告
        assertTrue(impact.any { "文書参照" in it && "1 件" in it }, impact.toString())
        assertTrue(impact.any { "注意: " in it && "ビュー「task-list」" in it && "fileGlob" in it }, impact.toString())
    }
}
