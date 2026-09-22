package dev.toros.yde.app

import dev.toros.yde.yaml.model.YNode
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

/**
 * 文書の新規作成のセッション経路（P29 Phase 4・RP-03／意図的差分 D-18）の AppState 統合テスト。
 * 作成が1トランザクション＋保存で行われ、**Ctrl+Z で取り消せる（ファイルも消える）**・Redo で戻る・
 * 作成がそれまでの Undo 履歴を失わせない（従来の直接書込＋reload はセッションを開き直していた）ことを固定する。
 */
class AppStateCreateDocumentTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-appcreate")
        write("_schemas/task.yaml", """
            id: task-schema
            match: "tasks/*.yaml"
            coverage:
              - detailIn: { schemaIds: [task-detail] }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: title, type: string }
                - { key: status, type: string, required: true, default: open }
        """)
        write("_schemas/task-detail.yaml", """
            id: task-detail
            match: "details/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: note, type: text }
        """)
        write("tasks/t1.yaml", """
            id: TASK-001
            title: 最初のタスク
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

    private fun openState(): AppState = AppState().also { it.openWorkspace(dir) }

    @Test
    fun `新規YAMLの作成はUndoで取り消せてRedoで戻る`() {
        val state = openState()
        val file = state.createYaml(dir.resolve("tasks"), "t2").getOrThrow()
        assertEquals(dir.resolve("tasks/t2.yaml"), file)
        assertTrue(Files.exists(file))
        assertTrue("status: open" in Files.readString(file), "スキーマ初期値で作成される: ${Files.readString(file)}")
        assertEquals(file, state.openDoc?.file, "作成した文書が開く")
        assertTrue(file in state.yamlFiles, "ファイル一覧に現れる")

        // Undo: 作成の取り消しはファイルの有無までその場で確定する（未保存●に留めない）
        assertTrue(state.canUndo())
        state.undo()
        assertFalse(Files.exists(file), "Undo でファイルが消える")
        assertNull(state.openDoc, "開いていた文書は閉じる")
        assertFalse(file in state.yamlFiles)

        // Redo: 同じ内容で作り直される
        assertTrue(state.canRedo())
        state.redo()
        assertTrue(Files.exists(file))
        assertTrue("status: open" in Files.readString(file))
        assertTrue(file in state.yamlFiles)
    }

    @Test
    fun `作成はそれまでのUndo履歴を失わせない`() {
        val state = openState()
        val t1 = state.ensureDoc(dir.resolve("tasks/t1.yaml"))
        state.bulkUpdate(setOf(dir.resolve("tasks/t1.yaml")), "status", "closed").getOrThrow()
        assertTrue("status: closed" in Files.readString(dir.resolve("tasks/t1.yaml")))

        val created = state.createYaml(dir.resolve("tasks"), "t3.yaml").getOrThrow()
        // 1回目の Undo は作成、2回目の Undo は作成より前の一括変更まで戻れる（履歴が保たれている）
        state.undo()
        assertFalse(Files.exists(created))
        assertTrue(state.canUndo(), "作成より前の履歴が残っている")
        state.undo()
        assertEquals("open", ((t1.root as YNode.Map).entries["status"] as YNode.Scalar).value)
    }

    @Test
    fun `カバレッジ指摘からの詳細文書の作成は新しいディレクトリごと作られUndoで取り消せる`() {
        val state = openState()
        val file = state.createDetailForCoverage("task-detail", "TASK-001").getOrThrow()
        assertEquals(dir.resolve("details/TASK-001.yaml"), file)
        assertTrue("id: TASK-001" in Files.readString(file))
        // 作成で索引が進み、詳細文書の出現が見える（全体の読み直しなしで派生が追随する）
        assertEquals(2, state.index.entries["TASK-001"].orEmpty().size)

        state.undo()
        assertFalse(Files.exists(file))
        assertEquals(1, state.index.entries["TASK-001"].orEmpty().size)
    }

    @Test
    fun `スキーマ推論で作ったスキーマは即座に適用されUndoで外れる`() {
        write("memos/m1.yaml", """
            id: MEMO-1
            body: メモ
        """)
        val state = openState()
        assertNull(state.schemas.firstOrNull { it.id == "memo" })
        val file = state.inferSchema("memos/*.yaml", "memo").getOrThrow()
        assertTrue(Files.exists(file))
        assertNotNull(state.schemas.firstOrNull { it.id == "memo" }, "作成したスキーマが読み込まれる")

        state.undo()
        assertFalse(Files.exists(file))
        assertNull(state.schemas.firstOrNull { it.id == "memo" }, "Undo でスキーマ束からも外れる")
    }

    @Test
    fun `同名ファイルがあれば作成は拒否される`() {
        val state = openState()
        val result = state.createYaml(dir.resolve("tasks"), "t1")
        assertTrue(result.isFailure)
        assertFalse(state.canUndo(), "拒否された作成は履歴に積まれない")
    }
}
