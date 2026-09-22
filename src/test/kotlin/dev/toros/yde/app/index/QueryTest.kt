package dev.toros.yde.app.index

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.runCli
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * パターン照合エンジン（R22-01。ARCHITECTURE §4.32）のテスト。
 * 決定性（行の順序・limit）・1段分岐（also）・文書ノード（kind: document）・プロパティ述語・
 * 経路内の再訪禁止・オーバーレイ索引での評価（EntityGraph 経路差）を検証する。
 */
class QueryTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-query")
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: status, type: string }
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            """,
        )
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "ucs/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: domain, type: ref, relation: belongs, scope: { schemaIds: [domain] } }
            """,
        )
        write(
            "_schemas/domain.yaml",
            """
            id: domain
            match: "domains/*.yaml"
            entity: { type: domain, idPattern: "DOM-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        write("screens/SCR-1.yaml", "id: SCR-1\nname: 画面1\nstatus: approved\nuses:\n- UC-1\n- UC-2")
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-2")
        write("ucs/UC-1.yaml", "id: UC-1\nname: ユース1\ndomain: DOM-1")
        write("ucs/UC-2.yaml", "id: UC-2\nname: ユース2\ndomain: DOM-1")
        write("ucs/UC-3.yaml", "id: UC-3\nname: ユース3\ndomain: DOM-1")
        write("domains/DOM-1.yaml", "id: DOM-1\nname: ドメイン1")
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

    private fun spec(yamlText: String): QuerySpec =
        QuerySpec.parse(YamlIO.parseToRaw(yamlText.trimIndent()) as Map<*, *>, "query")

    private fun rowIds(result: QueryResult): List<List<String>> =
        result.rows.map { row -> result.columns.map { row.bindings.getValue(it).id } }

    @Test
    fun `two hop join returns binding rows in deterministic order`() {
        val ws = Workspace.open(dir)
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf("scr", "uc"), result.columns)
        assertEquals(
            listOf(listOf("SCR-1", "UC-1"), listOf("SCR-1", "UC-2"), listOf("SCR-2", "UC-2")),
            rowIds(result),
        )
        assertEquals(3, result.total)
        // 座標・タイトルは EntityGraph から解決される（定義出現）
        val first = result.rows.first().bindings.getValue("scr")
        assertEquals("画面1", first.title)
        assertEquals("screen", first.entityType)
        assertTrue(first.document.toString().replace('\\', '/').endsWith("screens/SCR-1.yaml"))
    }

    @Test
    fun `where equals and exists filter by any occurrence value`() {
        val ws = Workspace.open(dir)
        val approved = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: scr }
                where:
                  - { of: scr, path: status, equals: approved }
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf(listOf("SCR-1")), rowIds(approved))
        val noStatus = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: scr }
                where:
                  - { of: scr, path: status, exists: false }
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf(listOf("SCR-2")), rowIds(noStatus))
    }

    @Test
    fun `also branch joins a second chain from a bound node`() {
        val ws = Workspace.open(dir)
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                also:
                  from: uc
                  steps:
                    - edge: { relation: belongs }
                    - node: { entity: domain, bind: dom }
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf("scr", "uc", "dom"), result.columns)
        assertEquals(
            listOf(
                listOf("SCR-1", "UC-1", "DOM-1"),
                listOf("SCR-1", "UC-2", "DOM-1"),
                listOf("SCR-2", "UC-2", "DOM-1"),
            ),
            rowIds(result),
        )
    }

    @Test
    fun `dir in traverses backlinks and isolated nodes produce no row`() {
        val ws = Workspace.open(dir)
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr }
                """
            ),
            ws::treeOf,
        )
        assertEquals(
            listOf(listOf("UC-1", "SCR-1"), listOf("UC-2", "SCR-1"), listOf("UC-2", "SCR-2")),
            rowIds(result),
        )
        // UC-3 は被参照がないため行にならない（exists 制約の absent 判定の土台）
        assertTrue(rowIds(result).none { it.first() == "UC-3" })
    }

    @Test
    fun `limit truncates deterministically and total keeps full count`() {
        val ws = Workspace.open(dir)
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                limit: 2
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf(listOf("SCR-1", "UC-1"), listOf("SCR-1", "UC-2")), rowIds(result))
        assertEquals(3, result.total)
    }

    @Test
    fun `document node start follows refs written in the document`() {
        val ws = Workspace.open(dir)
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { kind: document, id: "screens/SCR-1*", bind: d }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                """
            ),
            ws::treeOf,
        )
        assertEquals(
            listOf(listOf("screens/SCR-1.yaml", "UC-1"), listOf("screens/SCR-1.yaml", "UC-2")),
            rowIds(result),
        )
        val doc = result.rows.first().bindings.getValue("d")
        assertEquals(QueryNodeKind.DOCUMENT, doc.kind)
        assertTrue(doc.path.isEmpty())
    }

    @Test
    fun `same node is not revisited within one match path`() {
        val ws = Workspace.open(dir)
        // 同じユースケースを共有する別の画面: 起点自身（SCR-1 → UC-2 → SCR-1）は再訪禁止で除かれる
        val result = Query.run(
            ws.index,
            spec(
                """
                match:
                  - node: { entity: screen, bind: a }
                  - edge: { relation: uses }
                  - node: { entity: usecase }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: b }
                """
            ),
            ws::treeOf,
        )
        assertEquals(listOf(listOf("SCR-1", "SCR-2"), listOf("SCR-2", "SCR-1")), rowIds(result))
    }

    @Test
    fun `overlay index reflects unsaved edits in enumeration`() {
        val ws = Workspace.open(dir)
        val target = dir.resolve("screens/SCR-2.yaml")
        val edited = YamlIO.parseFirst("id: SCR-2\nname: 画面2\nuses:\n- UC-2\n- UC-3\n")
        val overlay = ws.overlayIndex(mapOf(target to edited))
        val q = spec(
            """
            match:
              - node: { entity: screen, bind: scr }
              - edge: { relation: uses }
              - node: { entity: usecase, bind: uc }
            """
        )
        // ディスク索引には現れず、オーバーレイ索引には未保存の参照先（UC-3）が行として現れる
        assertTrue(rowIds(Query.run(ws.index, q, ws::treeOf)).none { it == listOf("SCR-2", "UC-3") })
        assertTrue(rowIds(Query.run(overlay, q, ws::treeOf)).any { it == listOf("SCR-2", "UC-3") })
    }

    @Test
    fun `anyMatchFrom and startMatches distinguish scope from missing solution`() {
        val ws = Workspace.open(dir)
        val q = spec(
            """
            match:
              - node: { entity: usecase, bind: uc }
              - edge: { relation: uses, dir: in }
              - node: { entity: screen }
            """
        )
        assertTrue(Query.startMatches(ws.index, q, "UC-3", ws::treeOf))
        assertFalse(Query.anyMatchFrom(ws.index, q, "UC-3", ws::treeOf))
        assertTrue(Query.anyMatchFrom(ws.index, q, "UC-1", ws::treeOf))
        // 母集団に入らないID（型不一致）は startMatches が false = 判定保留（黙る）
        assertFalse(Query.startMatches(ws.index, q, "SCR-1", ws::treeOf))
    }

    @Test
    fun `cli query outputs the binding table as yaml`() {
        val out = runCli(
            arrayOf(
                "query", dir.toString(), "--spec",
                "{match: [{node: {entity: screen, bind: scr}}, {edge: {relation: uses}}, {node: {entity: usecase, bind: uc}}]}",
            )
        )
        assertTrue("total: 3" in out, out)
        assertTrue("SCR-1" in out && "UC-2" in out, out)
        assertTrue("screens/SCR-1.yaml" in out, out)
    }

    @Test
    fun `parse rejects malformed specs with japanese errors`() {
        assertFailsWith<RuntimeException> { spec("match:\n  - edge: { dir: out }\n  - node: { bind: a }") }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { bind: a }\n  - node: { bind: b }")
        }
        assertFailsWith<RuntimeException> { spec("match:\n  - node: { bind: a, unknown: x }") }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { bind: a }\nwhere:\n  - { of: a, path: x }")
        }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { bind: a }\nwhere:\n  - { of: zzz, path: x, exists: true }")
        }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { bind: a }\n  - edge: {}\n  - node: { bind: a }")
        }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { kind: document, entity: screen, bind: a }")
        }
        assertFailsWith<RuntimeException> {
            spec("match:\n  - node: { bind: a }\nalso: { from: zzz, steps: [ { edge: {} }, { node: {} } ] }")
        }
    }
}
