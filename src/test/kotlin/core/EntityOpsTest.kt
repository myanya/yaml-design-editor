package core

import model.scalarText
import model.getAt
import model.YNode
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * エンティティ操作（R21-02。ARCHITECTURE §4.31）のテスト。
 * rename / merge / delete が全出現・全参照（スカラー／属性付き to）を1つの変更セットへ束ね、
 * 既存の Workspace.apply（baseハッシュ・オール・オア・ナッシング・オーバーレイ検証）で適用できること。
 */
class EntityOpsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-entityops")
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

    private fun read(rel: String): String = Files.readString(dir.resolve(rel))

    /** 画面一覧（定義）＋詳細＋参照元（スカラー参照・属性付き参照）のワークスペースを組む。 */
    private fun writeWorkspace() {
        write("_schemas/screens.yaml", """
            id: screens
            match: "screens/index.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: name, type: string }
        """)
        write("_schemas/details.yaml", """
            id: details
            match: "details/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: memo, type: string }
        """)
        write("_schemas/flows.yaml", """
            id: flows
            match: "flows/**/*.yaml"
            root:
              type: object
              fields:
                - key: from
                  type: ref
                  relation: uses
                - key: steps
                  type: array
                  item:
                    type: ref
                    relation: uses
                    fields:
                      - { key: role, type: string }
        """)
        write("screens/index.yaml", """
            screens:
              - id: SCR-001
                name: 一覧画面
              - id: SCR-002
                name: 編集画面
        """)
        write("details/scr-001.yaml", """
            id: SCR-001
            memo: 詳細メモ
        """)
        write("flows/f1.yaml", """
            from: SCR-001
            steps:
              - to: SCR-001
                role: 起点
              - to: SCR-002
                role: 遷移先
        """)
    }

    // ---------------------------------------------------------------- rename

    @Test
    fun `renameは全出現と全参照を1つの変更セットに束ねゲートを通って適用できる`() {
        writeWorkspace()
        val ws = Workspace.open(dir)
        val plan = EntityOps.rename(ws, "SCR-001", "SCR-100")
        // 3ファイル（一覧・詳細・参照元）が1つの変更セットに束ねられる
        assertEquals(3, plan.changeSet.changes.size)
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString())

        val result = ws.apply(plan.changeSet)
        assertTrue(result.ok, result.results.toString())
        assertTrue("SCR-100" in read("screens/index.yaml"))
        assertTrue("SCR-001" !in read("screens/index.yaml"))
        assertTrue("id: SCR-100" in read("details/scr-001.yaml"))
        // スカラー参照・属性付き参照の to の両方が書き換わる
        val flow = read("flows/f1.yaml")
        assertTrue("from: SCR-100" in flow, flow)
        assertTrue("to: SCR-100" in flow, flow)
        assertTrue("to: SCR-002" in flow, flow)
        // 属性（role）は保たれる
        assertTrue("role: 起点" in flow, flow)
        // 適用後の索引でも改名が反映される
        assertTrue(ws.index.entries.containsKey("SCR-100"))
        assertTrue(!ws.index.entries.containsKey("SCR-001"))
    }

    @Test
    fun `renameは既存IDへの衝突を拒否しidPattern逸脱を警告する`() {
        writeWorkspace()
        val ws = Workspace.open(dir)
        assertFailsWith<IllegalArgumentException> { EntityOps.rename(ws, "SCR-001", "SCR-002") }
        assertFailsWith<IllegalArgumentException> { EntityOps.rename(ws, "SCR-404", "SCR-500") }
        val plan = EntityOps.rename(ws, "SCR-001", "MEMO-1")
        assertTrue(plan.warnings.any { "idPattern" in it }, plan.warnings.toString())
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun `mergeは参照を張り替え出現を詳細化として残せる`() {
        writeWorkspace()
        val ws = Workspace.open(dir)
        val plan = EntityOps.merge(ws, "SCR-001", "SCR-002", EntityOps.MergeMode.RETAIN)
        val result = ws.apply(plan.changeSet)
        assertTrue(result.ok, result.results.toString())
        val flow = read("flows/f1.yaml")
        assertTrue("from: SCR-002" in flow, flow)
        assertTrue("to: SCR-002" in flow, flow)
        // 出現は SCR-002 のIDへ書き換え（詳細化として残る）
        assertTrue("id: SCR-002" in read("details/scr-001.yaml"))
        // 一覧の SCR-001 行も SCR-002 になる（定義競合は検証が指摘する領分）
        assertTrue("SCR-001" !in read("screens/index.yaml"))
    }

    @Test
    fun `mergeは文書ルート出現の削除を拒否し配列要素の削除は後方から行う`() {
        writeWorkspace()
        val ws = Workspace.open(dir)
        // 文書ルート出現（details/scr-001.yaml）が含まれる → DELETE は拒否
        val ex = assertFailsWith<IllegalStateException> {
            EntityOps.merge(ws, "SCR-001", "SCR-002", EntityOps.MergeMode.DELETE)
        }
        assertTrue("文書ルート" in (ex.message ?: ""), ex.message ?: "")

        // ルート出現のないID（SCR-002）で DELETE を検証（参照は flows の to: SCR-002 のみ）
        val plan = EntityOps.merge(ws, "SCR-002", "SCR-001", EntityOps.MergeMode.DELETE)
        val result = ws.apply(plan.changeSet)
        assertTrue(result.ok, result.results.toString())
        val index = YamlIO.parse(read("screens/index.yaml"))
        val screens = getAt(index, listOf("screens")) as YNode.Seq
        assertEquals(1, screens.items.size)
        assertEquals("SCR-001", scalarText(getAt(screens.items[0], listOf("id"))))
        // 参照は SCR-001 へ張り替え
        val flow = read("flows/f1.yaml")
        assertTrue("to: SCR-002" !in flow, flow)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun `deleteは被参照があると参照元一覧つきで拒否し参照がなければ出現を削除する`() {
        writeWorkspace()
        val ws = Workspace.open(dir)
        val ex = assertFailsWith<IllegalStateException> { EntityOps.delete(ws, "SCR-002") }
        assertTrue("参照" in (ex.message ?: "") && "flows/f1.yaml" in (ex.message ?: ""), ex.message ?: "")

        // 参照のないIDを追加して削除する
        write("screens/index.yaml", """
            screens:
              - id: SCR-001
                name: 一覧画面
              - id: SCR-002
                name: 編集画面
              - id: SCR-003
                name: 削除予定
        """)
        ws.refresh()
        val plan = EntityOps.delete(ws, "SCR-003")
        val result = ws.apply(plan.changeSet)
        assertTrue(result.ok, result.results.toString())
        assertTrue("SCR-003" !in read("screens/index.yaml"))
    }
}
