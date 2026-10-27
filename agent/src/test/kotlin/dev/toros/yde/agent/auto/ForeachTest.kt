package dev.toros.yde.agent.auto

import dev.toros.yde.agent.ForeachDef
import dev.toros.yde.agent.ForeachSource
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 一括実行の対象列挙（foreach。R18-01）のテスト。
 * 列挙元（view / issues / search / files / ids）ごとに、対象集合・テンプレート置換・limit を検証する。
 */
class ForeachTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-foreach")
        write(
            "_schemas/list.yaml",
            """
            id: list
            match: "list.yaml"
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string }
            """,
        )
        write(
            "_schemas/detail.yaml",
            """
            id: detail
            match: "details/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, required: true }
            """,
        )
        write(
            "_views/items.yaml",
            """
            id: items-view
            rows:
              idPattern: "IT-*"
            columns:
              - label: 名前
                schemaId: list
                path: name
            detail:
              schemaId: detail
              filePattern: "details/{id}.yaml"
            """,
        )
        write(
            "list.yaml",
            """
            items:
              - { id: IT-001, name: 項目A }
              - { id: IT-002, name: 項目B }
              - { id: IT-003, name: 項目C }
            """,
        )
        write("details/IT-002.yaml", "id: IT-002\nname: 項目Bの詳細")
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
    fun `view source enumerates rows missing detail`() {
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(ws, ForeachDef(source = ForeachSource.VIEW, view = "items-view"))
        assertEquals(listOf("IT-001", "IT-003"), result.items.map { it.key })
        assertEquals(2, result.total)
        assertEquals("対象: IT-001", result.items[0].instruction)  // 既定テンプレート
        // コンテキストレシピ向けのプレースホルダ値（R19-02）: {target} は id 優先で決まる
        assertEquals("IT-001", result.items[0].values["target"])
        assertEquals("IT-001", result.items[0].values["id"])
    }

    @Test
    fun `view source where all enumerates every row`() {
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(
            ws, ForeachDef(source = ForeachSource.VIEW, view = "items-view", where = "all")
        )
        assertEquals(listOf("IT-001", "IT-002", "IT-003"), result.items.map { it.key })
    }

    @Test
    fun `ids source applies pattern limit and template`() {
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(
            ws,
            ForeachDef(source = ForeachSource.IDS, idPattern = "IT-*", asTemplate = "ID {id} を処理", limit = 2),
        )
        assertEquals(2, result.items.size)   // limit で切られる
        assertEquals(3, result.total)        // 全件数は保持
        assertEquals("ID IT-001 を処理", result.items[0].instruction)
    }

    @Test
    fun `files source lists matching documents only outside reserved dirs`() {
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(ws, ForeachDef(source = ForeachSource.FILES, match = "**/*.yaml"))
        // 予約ディレクトリ（_schemas / _views）は列挙されない
        assertEquals(listOf("details/IT-002.yaml", "list.yaml"), result.items.map { it.key })
        assertEquals("対象ファイル: details/IT-002.yaml", result.items[0].instruction)
    }

    @Test
    fun `issues source enumerates validation errors`() {
        write("details/IT-009.yaml", "id: IT-009")  // name（必須）が無い → 検証エラー
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(ws, ForeachDef(source = ForeachSource.ISSUES, match = "details/**"))
        assertTrue(result.items.isNotEmpty(), "検証エラーが列挙されること")
        assertTrue(result.items.all { "details/IT-009.yaml" in it.key }, result.items.toString())
        assertTrue("details/IT-009.yaml" in result.items[0].instruction)
    }

    @Test
    fun `search source enumerates text hits`() {
        val ws = Workspace.open(dir)
        val result = Foreach.enumerate(ws, ForeachDef(source = ForeachSource.SEARCH, text = "項目Bの詳細"))
        assertEquals(1, result.items.size)
        assertTrue("details/IT-002.yaml" in result.items[0].key, result.items.toString())
    }

    @Test
    fun `query source enumerates binding rows and supplies bind placeholders`() {
        val ws = Workspace.open(dir)
        // schema フィルタはエンティティ宣言なしでも使える（出現スキーマID）
        val spec = QuerySpec.parse(
            YamlIO.parseToRaw(
                """
                match:
                  - node: { schema: list, id: "IT-*", bind: item }
                """.trimIndent()
            ) as Map<*, *>,
            "foreach.query",
        )
        val result = Foreach.enumerate(
            ws,
            ForeachDef(source = ForeachSource.QUERY, query = spec, asTemplate = "対象 {item}", limit = 2),
        )
        assertEquals(listOf("item=IT-001", "item=IT-002"), result.items.map { it.key })
        assertEquals(3, result.total)
        assertEquals("対象 IT-001", result.items[0].instruction)
        // プレースホルダ: 先頭束縛の既定（id / file / target）＋束縛名（{item} / {item_file}）
        assertEquals("IT-001", result.items[0].values["id"])
        assertEquals("IT-001", result.items[0].values["item"])
        assertEquals("list.yaml", result.items[0].values["item_file"])
        assertEquals("IT-001", result.items[0].values["target"])
    }
}
