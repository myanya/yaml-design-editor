package core

import model.YNode
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * オーバーレイ索引（UX-03）のテスト。未保存編集（dirty 文書のツリー）を重ねた索引で
 * 「見たままの状態」の判定（カバレッジ・行集合）ができ、ディスク索引が不変であることを検証する。
 */
class OverlayIndexTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-overlay-test")
        write(
            "_schemas/screen-list.yaml",
            """
            id: screen-list
            name: 画面一覧
            match: "screens/index.yaml"
            coverage:
              - description: 全画面は詳細文書を持つこと
                detailIn:
                  schemaIds: [screen-detail]
                level: error
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string }
            """,
        )
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            name: 画面詳細
            match: "screens/detail/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        write(
            "screens/index.yaml",
            """
            screens:
              - id: SCR-A
                name: 画面A
              - id: SCR-B
                name: 画面B
            """,
        )
        write("screens/detail/SCR-A.yaml", "id: SCR-A")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `オーバーレイ索引は未保存編集を重ね、ディスク索引は変えない`() {
        val workspace = Workspace.open(ws)
        val root = workspace.root
        val listFile = root.resolve("screens/index.yaml")

        // ディスク基準: SCR-B の詳細不足がカバレッジエラーになる
        val diskIssues = workspace.validate("screens/index.yaml")
        assertTrue(diskIssues.any { "SCR-B" in it.message && "詳細文書" in it.message }, "$diskIssues")

        // 未保存の詳細文書（ディスクに無い新規パス）を重ねると出現として数えられる
        val newDetail = root.resolve("screens/detail/SCR-B.yaml")
        val overlay = workspace.overlayIndex(mapOf(newDetail to YamlIO.parse("id: SCR-B\n")))
        assertEquals(1, overlay.occurrences("SCR-B").count { it.schemaId == "screen-detail" })

        // オーバーレイ索引で検証すると、保存前でもカバレッジが充足する（UIの「見たまま判定」）
        val overlayIssues = Validator.validate(
            YamlIO.parse(Files.readString(listFile)), null, listFile,
            workspace.schemaFor(listFile), overlay,
            workspace = root, allSchemas = workspace.schemas,
        )
        assertTrue(overlayIssues.none { "SCR-B" in it.message && "詳細文書" in it.message }, "$overlayIssues")

        // 既存文書の未保存編集で増えた新しいIDも行集合（entries）に現れる
        val editedList: YNode = YamlIO.parse(
            """
            screens:
              - id: SCR-A
                name: 画面A
              - id: SCR-B
                name: 画面B
              - id: SCR-C
                name: 画面C（未保存）
            """.trimIndent(),
        )
        val overlay2 = workspace.overlayIndex(mapOf(listFile to editedList))
        assertTrue("SCR-C" in overlay2.entries, "未保存の新規IDが行集合に現れる")

        // ディスク索引（正）は一切変わらない
        assertTrue("SCR-C" !in workspace.index.entries)
        assertTrue(workspace.index.occurrences("SCR-B").none { it.schemaId == "screen-detail" })

        // 等価性: オーバーレイは「同じ内容で全再構築した索引」と一致する
        // 注意: Path は Iterable<Path>（セグメント列）のため `files + path` はセグメント分解される。listOf で足す
        val fresh = index.IdIndex().apply {
            rebuild(root, workspace.files + listOf(newDetail), workspace.schemas) { f ->
                if (f == newDetail) YamlIO.parse("id: SCR-B\n")
                else runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull()
            }
        }
        assertEquals(fresh.entries.keys, overlay.entries.keys)
        assertEquals(fresh.refs.size, overlay.refs.size)
    }
}
