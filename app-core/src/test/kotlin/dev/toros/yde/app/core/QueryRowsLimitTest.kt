package dev.toros.yde.app.core

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
 * クエリ行（rows: match）の上限（UX-201）: `limit` を書かなければ上限なし。書いたときだけ打ち切り、
 * 全行数（`ResolvedRows.total`）を一覧・ダッシュボード・CLI に示せる。黙って切れない。
 */
class QueryRowsLimitTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-query-limit")
        write(
            "_schemas/item.yaml",
            """
            id: item
            match: "items/*.yaml"
            entity: { type: item, idPattern: "IT-*", defines: true }
            root:
              type: object
              fields:
                - { key: itemId, type: id, required: true }
                - { key: name, type: string, required: true }
            """,
        )
        (1..120).forEach { i -> write("items/IT-%03d.yaml".format(i), "itemId: IT-%03d\nname: 品 %d".format(i, i)) }
        write(
            "_views/all.yaml",
            """
            id: all
            rows: { match: [ { node: { entity: item, bind: i } } ] }
            columns: [ { label: 名前, path: name } ]
            """,
        )
        write(
            "_views/capped.yaml",
            """
            id: capped
            rows: { match: [ { node: { entity: item, bind: i } } ], limit: 50 }
            columns: [ { label: 名前, path: name } ]
            """,
        )
        write(
            "_views/home.yaml",
            """
            id: home
            kind: dashboard
            sections:
              - { id: all, label: 全件, view: all }
              - { id: capped, label: 上限つき, view: capped }
            """,
        )
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `limit を書かなければ全行が出て 書いたときだけ打ち切りと全行数が分かる`() {
        val workspace = Workspace.open(ws)
        val loaded = Views.load(ws, workspace.schemas)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        val all = Views.resolveDetailed(loaded.view("all")!!, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(120, all.rows.size)
        assertEquals(120, all.total)
        assertNull(all.limit)
        assertFalse(all.truncated)
        val capped = Views.resolveDetailed(loaded.view("capped")!!, workspace.index, workspace::treeOf, workspace::schemaFor)
        assertEquals(50, capped.rows.size)
        assertEquals(120, capped.total)
        assertEquals(50, capped.limit)
        assertTrue(capped.truncated)
        // 従来の resolve は行だけ
        assertEquals(50, Views.resolve(loaded.view("capped")!!, workspace.index, workspace::treeOf, workspace::schemaFor).size)
        // ダッシュボードの区画: 件数は切れた後の数で、打ち切りを持つ（CLI の出力にも出る）
        val result = Dashboards.resolve(
            loaded.dashboard("home")!!, java.time.LocalDate.of(2026, 10, 26), loaded, workspace.index, workspace::schemaFor, workspace::treeOf,
        )
        val sections = result.sections.associateBy { it.definition.id }
        assertEquals("120", assertNotNull(sections["all"]).count)
        assertNull(sections["all"]!!.truncation)
        assertEquals("50", assertNotNull(sections["capped"]).count)
        assertEquals(120, assertNotNull(sections["capped"]!!.truncation).total)
        @Suppress("UNCHECKED_CAST")
        val report = Dashboards.report(loaded.dashboard("home")!!, result)["sections"] as List<Map<String, Any?>>
        assertEquals(mapOf("limit" to 50, "total" to 120), report.single { it["id"] == "capped" }["truncated"])
        assertNull(report.single { it["id"] == "all" }["truncated"])
    }

    private fun write(rel: String, content: String) {
        val file = ws.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }
}
