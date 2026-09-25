package dev.toros.yde.app.core

import dev.toros.yde.app.index.Query
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
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
 * ヘッドレス層（Workspace / Disclosure / Search / CLI / カバレッジ検証）の結合テスト。
 * P1 の完了条件「UIを起動せずに 検索 → 部分パッチ → 検証 → 保存」をここで通す。
 */
class WorkspaceCliTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-test")
        write(
            "_schemas/requirement.yaml",
            """
            id: requirement
            name: 要求
            match: "requirements/**/*.yaml"
            constraints:
              - id: req-implemented
                description: 全要求は設計から参照されること
                match:
                  - node: { id: "REQ-???", bind: req }
                  - edge: { dir: in, relation: implements }
                  - node: { schema: design }
                expect: exists
                level: error
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string, required: true }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: note, type: text }
            """,
        )
        write(
            "_schemas/design.yaml",
            """
            id: design
            name: 設計
            match: "designs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string }
                - key: implements
                  type: array
                  item:
                    type: ref
                    relation: implements
                    scope:
                      schemaIds: [requirement]
            """,
        )
        write(
            "requirements/req-a.yaml",
            """
            _document:
              id: doc-req-a
              name: 要求A
              description: ログイン要求を定義するテスト用文書
            id: REQ-001
            title: ログイン機能
            items:
              - id: REQ-001-S1
                note: サブ項目
            """,
        )
        write(
            "requirements/req-b.yaml",
            """
            id: REQ-002
            title: 検索機能
            """,
        )
        write(
            "designs/dsn-a.yaml",
            """
            id: DSN-001
            title: 認証設計
            implements:
              - REQ-001
            """,
        )
        write(
            "designs/dsn-b.yaml",
            """
            id: DSN-002
            title: 未解決参照を含む設計
            implements:
              - REQ-404
            """,
        )
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

    // ---------------------------------------------------------------- 段階的開示

    @Test
    fun `catalog lists document metadata and schema`() {
        val workspace = Workspace.open(ws)
        val catalog = Disclosure.catalog(workspace)
        val reqA = catalog.single { it.file == "requirements/req-a.yaml" }
        assertEquals("doc-req-a", reqA.documentId)
        assertEquals("要求A", reqA.name)
        assertEquals("requirement", reqA.schemaId)
        assertEquals(2, reqA.idCount) // REQ-001 と REQ-001-S1
    }

    @Test
    fun `outline shows structure without long bodies`() {
        val workspace = Workspace.open(ws)
        val doc = workspace.load("requirements/req-a.yaml")
        val outline = Disclosure.outline(doc.root!!)
        assertTrue("items: (array, 1件)" in outline, outline)
        assertTrue("id: REQ-001" in outline, outline)
    }

    @Test
    fun `nodeYaml returns subtree and enforces budget`() {
        val workspace = Workspace.open(ws)
        val doc = workspace.load("requirements/req-a.yaml")
        val yaml = Disclosure.nodeYaml(doc.root!!, parseYPath("items[0]"))
        assertTrue("id: REQ-001-S1" in yaml)
        val truncated = Disclosure.nodeYaml(doc.root!!, emptyList(), maxChars = 30)
        assertTrue("省略" in truncated)
    }

    @Test
    fun `bundle contains target refs and backlinks`() {
        val workspace = Workspace.open(ws)
        val bundle = Disclosure.bundle(workspace, "requirements/req-a.yaml", emptyList())
        assertTrue("documentId: doc-req-a" in bundle)
        assertTrue("backlinks:" in bundle, bundle)
        assertTrue("designs/dsn-a.yaml" in bundle, bundle)
    }

    // ---------------------------------------------------------------- 検索

    @Test
    fun `text search finds values with schema filter`() {
        val workspace = Workspace.open(ws)
        val hits = Search.run(workspace, SearchQuery(text = "ログイン"))
        assertTrue(hits.any { it.file == "requirements/req-a.yaml" && it.path == "title" })
        val designOnly = Search.run(workspace, SearchQuery(text = "ログイン", schemaIds = listOf("design")))
        assertTrue(designOnly.isEmpty())
    }

    @Test
    fun `ref search finds referencing locations with relation`() {
        val workspace = Workspace.open(ws)
        val hits = Search.run(workspace, SearchQuery(refsTo = "REQ-001"))
        val hit = hits.single()
        assertEquals("designs/dsn-a.yaml", hit.file)
        assertEquals("ref", hit.kind)
        assertTrue("implements" in hit.excerpt)
    }

    @Test
    fun `unresolved ref search finds broken references`() {
        val workspace = Workspace.open(ws)
        val hits = Search.run(workspace, SearchQuery(unresolvedRefs = true))
        assertEquals(listOf("designs/dsn-b.yaml"), hits.map { it.file })
        assertEquals("unresolved-ref", hits.single().kind)
    }

    // ---------------------------------------------------------------- カバレッジ検証（R1-06）

    @Test
    fun `coverage rule flags unreferenced requirement`() {
        val workspace = Workspace.open(ws)
        // REQ-001 は design から implements 関係で参照済み → 充足
        val issuesA = workspace.validate("requirements/req-a.yaml")
        assertTrue(issuesA.none { "成立していません" in it.message }, issuesA.toString())
        // REQ-002 は未参照 → 被参照制約（exists）のエラー
        val issuesB = workspace.validate("requirements/req-b.yaml")
        val violation = issuesB.single { "成立していません" in it.message }
        assertEquals(Severity.ERROR, violation.severity)
        assertTrue("REQ-002" in violation.message)
        assertTrue("全要求は設計から参照されること" in violation.message)
    }

    @Test
    fun `coverage status is queryable per id`() {
        // R29-01: 被参照数の要求は coverage.requiredBy から constraints へ移行——per-id の問い合わせはクエリ面で行う
        val workspace = Workspace.open(ws)
        val schema = workspace.schemas.single { it.id == "requirement" }
        val rule = schema.constraints.single()
        assertTrue(Query.anyMatchFrom(workspace.index, rule.spec, "REQ-001", workspace::treeOf))
        assertFalse(Query.anyMatchFrom(workspace.index, rule.spec, "REQ-002", workspace::treeOf))
        // パターン外のID（サブ項目）は母集団に入らない（対象外は黙る）
        assertFalse(Query.startMatches(workspace.index, rule.spec, "REQ-001-S1", workspace::treeOf))
    }

    // ---------------------------------------------------------------- 変更セット適用（R1-02）

    @Test
    fun `dry run reports without writing`() {
        val workspace = Workspace.open(ws)
        val before = Files.readString(ws.resolve("requirements/req-b.yaml"))
        val result = workspace.apply(
            ChangeSets.parse("changes:\n  - file: requirements/req-b.yaml\n    ops:\n      - { op: set, path: title, value: 高度な検索 }"),
            dryRun = true,
        )
        assertTrue(result.ok)
        assertFalse(result.results.single().applied)
        assertEquals(before, Files.readString(ws.resolve("requirements/req-b.yaml")))
    }

    @Test
    fun `apply writes multiple files atomically and refreshes index`() {
        val workspace = Workspace.open(ws)
        val result = workspace.apply(
            ChangeSets.parse(
                """
                changes:
                  - file: requirements/req-b.yaml
                    ops:
                      - { op: set, path: title, value: 高度な検索 }
                  - file: designs/dsn-a.yaml
                    ops:
                      - { op: insert, path: "implements[1]", value: REQ-002 }
                """.trimIndent()
            ),
        )
        assertTrue(result.ok)
        assertTrue(result.results.all { it.applied })
        assertTrue("高度な検索" in Files.readString(ws.resolve("requirements/req-b.yaml")))
        // 索引が更新され、REQ-002 の被参照制約が充足に変わる
        val issuesB = workspace.validate("requirements/req-b.yaml")
        assertTrue(issuesB.none { "成立していません" in it.message }, issuesB.toString())
    }

    @Test
    fun `base hash conflict aborts the whole changeset`() {
        val workspace = Workspace.open(ws)
        val base = ChangeSets.contentHash(Files.readString(ws.resolve("requirements/req-b.yaml")))
        // 変更セット作成後にディスク側が変わる
        write("requirements/req-b.yaml", "id: REQ-002\ntitle: 別の編集")
        val result = workspace.apply(
            ChangeSets.parse(
                """
                changes:
                  - file: requirements/req-b.yaml
                    base: $base
                    ops:
                      - { op: set, path: title, value: 上書き }
                  - file: requirements/req-a.yaml
                    ops:
                      - { op: set, path: title, value: 影響を受ける変更 }
                """.trimIndent()
            ),
        )
        assertFalse(result.ok)
        assertTrue(result.results.first { it.file == "requirements/req-b.yaml" }.message!!.contains("競合"))
        // 競合したため他ファイルも書き込まれない
        assertTrue("ログイン機能" in Files.readString(ws.resolve("requirements/req-a.yaml")))
    }

    @Test
    fun `apply creates new files from changeset`() {
        val workspace = Workspace.open(ws)
        val result = workspace.apply(
            ChangeSets.parse(
                """
                changes:
                  - file: requirements/req-c.yaml
                    create: true
                    content:
                      id: REQ-003
                      title: 通知機能
                """.trimIndent()
            ),
        )
        assertTrue(result.ok, result.results.toString())
        assertTrue(Files.exists(ws.resolve("requirements/req-c.yaml")))
        // 既存ファイルへの create は競合として全体を中止する
        val conflict = workspace.apply(
            ChangeSets.parse("changes:\n  - file: requirements/req-c.yaml\n    create: true\n    content: { id: X }")
        )
        assertFalse(conflict.ok)
        assertTrue(conflict.results.single().message!!.contains("競合"))
    }

    @Test
    fun `overlay validation accepts coordinated multi-file rename`() {
        val workspace = Workspace.open(ws)
        // REQ-001 を REQ-010 に改名し、設計側の参照も同じ変更セットで更新する。
        // 各ファイルを「変更前の索引」で検証すると偽の未解決参照になるが、
        // オーバーレイ索引での検証なら requireValid を通過できる。
        val result = workspace.apply(
            ChangeSets.parse(
                """
                changes:
                  - file: requirements/req-a.yaml
                    ops:
                      - { op: set, path: id, value: REQ-010 }
                  - file: designs/dsn-a.yaml
                    ops:
                      - { op: set, path: "implements[0]", value: REQ-010 }
                """.trimIndent()
            ),
            requireValid = true,
        )
        assertTrue(result.ok, result.results.toString())
        assertTrue("REQ-010" in Files.readString(ws.resolve("designs/dsn-a.yaml")))
    }

    @Test
    fun `requireValid blocks writes when validation errors remain`() {
        val workspace = Workspace.open(ws)
        val result = workspace.apply(
            ChangeSets.parse("changes:\n  - file: requirements/req-a.yaml\n    ops:\n      - { op: delete, path: title }"),
            requireValid = true,
        )
        assertFalse(result.ok)
        assertTrue("ログイン機能" in Files.readString(ws.resolve("requirements/req-a.yaml")))
        assertTrue(result.results.single().issues.any { it.severity == Severity.ERROR })
    }

    // ---------------------------------------------------------------- CLI（P1完了条件の一気通貫）

    @Test
    fun `cli runs search patch validate save end to end`() {
        val dir = ws.toString()
        // 1) 検索: カバレッジ不足の対象を探す
        val searchOut = runCli(arrayOf("search", dir, "--text", "検索機能"))
        assertTrue("requirements/req-b.yaml" in searchOut)
        // 2) 部分パッチ: base ハッシュ付き変更セットを適用（＝保存）
        val hashOut = runCli(arrayOf("hash", dir, "requirements/req-b.yaml"))
        val base = Regex("base: (\\w+)").find(hashOut)!!.groupValues[1]
        val changeSet = ws.resolve("changeset.yaml")
        Files.writeString(
            changeSet,
            """
            changes:
              - file: designs/dsn-a.yaml
                ops:
                  - { op: insert, path: "implements[1]", value: REQ-002 }
              - file: requirements/req-b.yaml
                base: $base
                ops:
                  - { op: set, path: title, value: 高度な検索機能 }
            """.trimIndent(),
        )
        val applyOut = runCli(arrayOf("apply", dir, changeSet.toString()))
        assertTrue("ok: true" in applyOut, applyOut)
        // 3) 検証: 被参照制約の違反が解消されている
        val validateOut = runCli(arrayOf("validate", dir))
        assertTrue("成立していません" !in validateOut, validateOut)
        // 4) 保存結果の確認
        assertTrue("高度な検索機能" in Files.readString(ws.resolve("requirements/req-b.yaml")))
        Files.deleteIfExists(changeSet)
    }

    @Test
    fun `cli catalog outline read work headlessly`() {
        val dir = ws.toString()
        assertTrue("doc-req-a" in runCli(arrayOf("catalog", dir)))
        assertTrue("items: (array, 1件)" in runCli(arrayOf("outline", dir, "requirements/req-a.yaml")))
        assertTrue("REQ-001-S1" in runCli(arrayOf("read", dir, "requirements/req-a.yaml", "items[0]")))
        assertTrue("unresolved-ref" in runCli(arrayOf("search", dir, "--unresolved")))
    }

    @Test
    fun `cli transform は クエリで選んで組み立てた YAML と その出どころを返す`() {
        // YAML から YAML への変換（P27 段C）: from＝クエリで選ぶ・build＝出力のひな型。出力は保存されない
        val dir = ws.toString()
        val def = Files.createTempFile("transform", ".yaml")
        Files.writeString(
            def,
            """
            id: design-list
            from:
              match:
                - node: { schema: design, bind: dsn }
                - edge: { relation: implements }
                - node: { bind: req }
            build:
              設計: { ${'$'}copy: id }
              要求: { ${'$'}format: "{id} {title}", ${'$'}of: req }
            """.trimIndent(),
        )
        try {
            val before = Files.walk(ws).use { s -> s.filter { Files.isRegularFile(it) }.count() }
            val out = runCli(arrayOf("transform", dir, def.toString(), "--origins"))
            assertTrue("設計: DSN-001" in out && "要求: REQ-001 ログイン機能" in out, out)
            assertTrue("file: designs/dsn-a.yaml" in out && "file: requirements/req-a.yaml" in out, out)
            // 入力の文書を受け取る定義（from なし）は --input で渡す
            Files.writeString(def, "id: titles\nbuild: { 見出し: { ${'$'}copy: title }, 明細: { ${'$'}each: items, ${'$'}build: { ${'$'}copy: id } } }")
            val single = runCli(arrayOf("transform", dir, def.toString(), "--input", "requirements/req-a.yaml"))
            assertTrue("見出し: ログイン機能" in single && "REQ-001-S1" in single, single)
            assertEquals(before, Files.walk(ws).use { s -> s.filter { Files.isRegularFile(it) }.count() }, "ワークスペースに何も書かない")
        } finally {
            Files.deleteIfExists(def)
        }
    }

    @Test
    fun `cli entity show returns composition with coordinates and backlinks`() {
        val dir = ws.toString()
        // エンティティページ・read_entity と同じ合成（R23-04。architecture/graph-query.md「エンティティページ」）をYAMLで返す
        val out = runCli(arrayOf("entity", dir, "show", "REQ-001"))
        assertTrue("id: REQ-001" in out, out)
        assertTrue("title: ログイン機能" in out, out)
        assertTrue("file: requirements/req-a.yaml" in out, out)
        assertTrue("key: title" in out, out)
        // 被参照（designs/dsn-a.yaml の implements）が relation つきで載る
        assertTrue("relation: implements" in out, out)
        assertTrue("from: DSN-001" in out, out)
    }
}
