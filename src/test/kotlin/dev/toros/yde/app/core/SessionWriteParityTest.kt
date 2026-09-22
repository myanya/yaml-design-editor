package dev.toros.yde.app.core

import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 書込経路（人間）のセッション集約（移行後リファクタリング A-2a・D-11）の一致テスト。
 * 保存・一括更新がセッション（DocumentStore）経由になっても、書かれるバイト列
 * （コメント・表記スタイル込み）が旧経路と同一であることを固定する。
 */
class SessionWriteParityTest {

    private lateinit var oldWs: Path
    private lateinit var newWs: Path

    private fun populate(ws: Path) {
        Files.createDirectories(ws.resolve("_schemas"))
        Files.createDirectories(ws.resolve("items"))
        Files.writeString(
            ws.resolve("_schemas/item.yaml"),
            """
            id: fx-item
            match: "items/**/*.yaml"
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: title
                  type: string
                - key: status
                  type: string
            """.trimIndent(),
        )
        Files.writeString(ws.resolve("items/a.yaml"), "id: IT-001\nstatus: open  # 進行中\ntitle: A\n")
        Files.writeString(ws.resolve("items/b.yaml"), "id: IT-002\nstatus:\n  - x\n")   // 複合値 → スキップ対象
        Files.writeString(ws.resolve("items/c.yaml"), "id: IT-003\ntitle: C\n")          // キーなし → 作成
    }

    @BeforeTest
    fun setup() {
        oldWs = createTempDirectory("yde-write-old").also(::populate)
        newWs = createTempDirectory("yde-write-new").also(::populate)
    }

    @AfterTest
    fun cleanup() {
        listOf(oldWs, newWs).forEach { ws ->
            Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `一括更新のセッション経路が旧経路と同じバイト列を書く`() {
        val files = listOf("items/a.yaml", "items/b.yaml", "items/c.yaml")
        // 旧経路: 逐次書込（複合値の b はスキップ）
        val oldCount = WorkspaceRecords.updateRootScalar(files.map { oldWs.resolve(it) }, "status", "done")
        // 新経路: 1トランザクション適用→保存（A-2a・D-11）
        val s = WorkspaceSession(FileSystemDocumentStore(newWs))
        val newCount = WorkspaceRecords.bulkUpdateViaSession(s, files.map { DocumentId(it) }, "status", "done")
        assertEquals(oldCount, newCount, "対象件数（複合値スキップ含む）")
        files.forEach { rel ->
            assertEquals(
                Files.readString(oldWs.resolve(rel)), Files.readString(newWs.resolve(rel)),
                "$rel の書込結果（コメント・表記込み）",
            )
        }
        assertTrue(Files.readString(newWs.resolve("items/a.yaml")).contains("# 進行中"), "コメントが維持される")
        assertTrue(Files.readString(newWs.resolve("items/a.yaml")).contains("status: done"), "値が更新される")
        assertTrue(Files.readString(newWs.resolve("items/b.yaml")).contains("- x"), "複合値はスキップされる")
    }

    @Test
    fun `変更セット適用のセッション経路が旧経路と同じ結果を書く`() {
        val cs = ChangeSet(
            listOf(
                FileChange(
                    "items/a.yaml", baseHash = null,
                    ops = listOf(PatchOp.Set(listOf("title"), YNode.Scalar("A3"))),
                ),
                FileChange(
                    "items/d.yaml", baseHash = null, ops = emptyList(),
                    create = true, content = YamlIO.fromAny(mapOf("id" to "IT-004", "title" to "D")),
                ),
            )
        )
        val cwOld = Workspace.open(oldWs)
        val oldResult = cwOld.apply(cs)
        val cwNew = Workspace.open(newWs)
        val sNew = WorkspaceSession(FileSystemDocumentStore(newWs))
        val newResult = cwNew.applyViaSession(sNew, cs)
        assertEquals(oldResult.ok, newResult.ok, "適用可否")
        assertTrue(newResult.ok, newResult.results.mapNotNull { it.message }.joinToString())
        listOf("items/a.yaml", "items/d.yaml").forEach { rel ->
            assertEquals(
                Files.readString(oldWs.resolve(rel)), Files.readString(newWs.resolve(rel)),
                "$rel の書込結果（コメント・表記込み）",
            )
        }
        assertTrue(Files.readString(newWs.resolve("items/a.yaml")).contains("# 進行中"), "コメントが維持される")

        // 競合（baseHash 不一致）: どちらの経路も何も書かず、同じ理由で拒否する
        val conflict = ChangeSet(
            listOf(
                FileChange(
                    "items/c.yaml", baseHash = "deadbeef",
                    ops = listOf(PatchOp.Set(listOf("title"), YNode.Scalar("X"))),
                )
            )
        )
        val oldC = cwOld.apply(conflict)
        val newC = cwNew.applyViaSession(sNew, conflict)
        assertEquals(false, oldC.ok)
        assertEquals(false, newC.ok)
        assertEquals(oldC.results.first().message, newC.results.first().message, "競合メッセージも同一")
        assertEquals(
            Files.readString(oldWs.resolve("items/c.yaml")), Files.readString(newWs.resolve("items/c.yaml")),
            "競合時は何も書かない",
        )
    }

    @Test
    fun `保存のセッション経路が旧経路（canonicalText）と同じバイト列を書く`() {
        // UI 編集のシミュレーション: ツリーを直接書き換える（コメント付きの値を更新）
        val text = Files.readString(newWs.resolve("items/a.yaml"))
        val root = YamlIO.parse(text) as YNode.Map
        root.entries["title"] = YNode.Scalar("A2")
        val expected = YamlIO.dumpAll(listOf(root))  // 旧経路: EditBuffer.canonicalText と同じ

        val s = WorkspaceSession(FileSystemDocumentStore(newWs))
        val id = DocumentId("items/a.yaml")
        assertTrue(s.replaceDocument(id, YDocument(id, listOf(root))).ok)
        val saved = s.save(setOf(id))
        assertTrue(saved.ok, "${saved.failures}")
        assertEquals(expected, Files.readString(newWs.resolve("items/a.yaml")), "保存バイト列が canonicalText と一致")
        assertTrue(Files.readString(newWs.resolve("items/a.yaml")).contains("# 進行中"), "コメントが維持される")
    }
}
