package index

import core.Workspace
import model.YNode
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
 * 導出グラフ（EntityGraph。R20-04。ARCHITECTURE §4.30）のテスト。
 * `sourceEntryOf` / `backlinksTo` / 出次列挙の載せ替えが**旧実装（全参照の線形走査）と同一の出力**を
 * 返すことを、テスト内に旧ロジックを再現して照合する。増分更新後・オーバーレイ経路でも一致を確認する。
 */
class EntityGraphTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-graph")
        write("_schemas/item.yaml", """
            id: item
            match: "docs/**/*.yaml"
            entity: { type: item, idPattern: "ITEM-*", defines: true }
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: title
                  type: string
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - key: id
                        type: id
                      - key: depends
                        type: array
                        item:
                          type: ref
                          relation: depends
        """)
        write("docs/a.yaml", """
            id: ITEM-A
            title: A
            items:
              - id: ITEM-A1
                depends: [ITEM-B, ITEM-B1]
              - id: ITEM-A2
                depends: [ITEM-B]
        """)
        write("docs/b.yaml", """
            id: ITEM-B
            title: B
            items:
              - id: ITEM-B1
                depends: [ITEM-A]
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

    // ---------------------------------------------------------------- 旧実装の再現（照合基準）

    private fun legacySourceEntryOf(index: IdIndex, ref: RefEntry): IdEntry? =
        index.entries.values.asSequence().flatten()
            .filter { it.document == ref.document && it.path.size <= ref.path.size && ref.path.subList(0, it.path.size) == it.path }
            .maxByOrNull { it.path.size }

    private fun legacyBacklinksTo(index: IdIndex, idSet: Set<String>): List<RefEntry> =
        index.refs.filter { it.targetId in idSet }

    private fun legacyOutgoing(index: IdIndex, id: String): List<RefEntry> =
        index.occurrences(id).flatMap { e ->
            index.refs.filter { r ->
                r.document == e.document && r.path.size >= e.path.size && r.path.subList(0, e.path.size) == e.path
            }
        }

    private fun assertGraphMatchesLegacy(index: IdIndex) {
        // 参照 → 参照元要素（最内のID出現）
        for (ref in index.refs) {
            assertEquals(legacySourceEntryOf(index, ref), index.sourceEntryOf(ref), "sourceEntryOf: $ref")
        }
        // 被参照（単一ID・複数IDの両方＝複数IDは全参照順を保つ）
        for (id in index.entries.keys) {
            assertEquals(legacyBacklinksTo(index, setOf(id)), index.backlinksTo(setOf(id)), "backlinksTo: $id")
        }
        assertEquals(legacyBacklinksTo(index, index.entries.keys), index.backlinksTo(index.entries.keys))
        // 出次（出現サブツリー配下の参照）
        for (id in index.entries.keys) {
            assertEquals(legacyOutgoing(index, id), index.outgoingRefs(id), "outgoingRefs: $id")
        }
    }

    // ---------------------------------------------------------------- テスト

    @Test
    fun `導出グラフの出力が旧実装（線形走査）と一致する`() {
        val ws = Workspace.open(dir)
        assertGraphMatchesLegacy(ws.index)
        // エンティティ表・文書ノード表の内容
        val node = ws.index.graph.entities.getValue("ITEM-A")
        assertEquals("item", node.entityType)
        assertEquals("A", node.title)
        assertEquals("docs/a.yaml", node.defining!!.document.value)
        val doc = ws.index.graph.documents.getValue("docs/a.yaml")
        assertEquals(listOf("ITEM-A", "ITEM-A1", "ITEM-A2"), doc.entityIds)
    }

    @Test
    fun `増分更新後もグラフが再導出され一致する`() {
        val ws = Workspace.open(dir)
        ws.index.graph  // 一度導出させてから増分更新する（キャッシュ無効化の確認）
        write("docs/b.yaml", """
            id: ITEM-B
            title: B改
            items:
              - id: ITEM-B1
                depends: [ITEM-A, ITEM-A2]
              - id: ITEM-B2
                depends: [ITEM-A1]
        """)
        ws.noteDiskChanges(listOf(dir.resolve("docs/b.yaml")))
        assertGraphMatchesLegacy(ws.index)
        assertEquals("B改", ws.index.graph.entities.getValue("ITEM-B").title)
        // フル再構築と同じ結果になる
        val fresh = Workspace.open(dir).index
        assertEquals(fresh.refs, ws.index.refs)
        assertEquals(fresh.backlinksTo(fresh.entries.keys), ws.index.backlinksTo(ws.index.entries.keys))
    }

    @Test
    fun `オーバーレイ索引は自分のグラフを持ち元の索引は変わらない`() {
        val ws = Workspace.open(dir)
        val before = ws.index.backlinksTo(setOf("ITEM-B")).size
        // a.yaml の編集中ツリー（ITEM-B への参照を1本追加）を重ねる
        val edited: YNode = YamlIO.parse("""
            id: ITEM-A
            title: A
            items:
              - id: ITEM-A1
                depends: [ITEM-B, ITEM-B1, ITEM-B]
              - id: ITEM-A2
                depends: [ITEM-B]
        """.trimIndent())
        val overlay = ws.overlayIndex(mapOf(dir.resolve("docs/a.yaml") to edited))
        assertGraphMatchesLegacy(overlay)
        assertEquals(before + 1, overlay.backlinksTo(setOf("ITEM-B")).size)
        // 元の索引のグラフは不変
        assertEquals(before, ws.index.backlinksTo(setOf("ITEM-B")).size)
    }

    @Test
    fun `属性付き参照もエッジとしてグラフに載る`() {
        write("_schemas/screen.yaml", """
            id: screen
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: owner
                  type: ref
                  relation: owned-by
                  fields:
                    - key: role
                      type: string
        """)
        write("screens/s.yaml", """
            id: SCR-1
            owner:
              to: ITEM-A
              role: 承認者
        """)
        val ws = Workspace.open(dir)
        assertGraphMatchesLegacy(ws.index)
        val backlink = ws.index.backlinksTo(setOf("ITEM-A")).first { it.document.value.endsWith("s.yaml") }
        assertEquals(listOf("owner", "to"), backlink.path)
        assertEquals("role: 承認者", backlink.attributeSummary())
        assertEquals("SCR-1", ws.index.sourceEntryOf(backlink)?.id)
        assertTrue(ws.index.outgoingRefs("SCR-1").any { it.targetId == "ITEM-A" })
    }
}
