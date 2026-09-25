package dev.toros.yde.app.schema

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * エンティティ型宣言（R20-01。architecture/graph-query.md「エンティティ型と関係レジストリ」）のテスト。
 * - `entity:` のパース（単一／リスト・定義検証）
 * - DocScanner の型・役割付与と、IdIndex.resolve の定義出現優先
 * - Validator のエンティティ整合 lint（浮遊詳細化・定義競合・idPattern 逸脱・型競合。すべて WARNING）
 * - 宣言のないスキーマ・ワークスペースは挙動不変（非破壊）
 */
class EntityDeclTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-entity")
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

    // ---------------------------------------------------------------- パース

    @Test
    fun `entity宣言は単一objectとリストの両形式をパースできる`() {
        val single = SchemaLoader.parseSchemaText(
            """
            id: s1
            match: "a/**/*.yaml"
            entity:
              type: screen
              idPattern: "SCR-*"
              defines: true
            root: { type: object, fields: [ { key: id, type: id } ] }
            """.trimIndent(),
            Path.of("_schemas/s1.yaml"),
        )
        assertEquals(listOf(EntityDecl("screen", "SCR-*", true)), single.entities)

        val list = SchemaLoader.parseSchemaText(
            """
            id: s2
            match: "b/**/*.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*", defines: true }
              - { type: usecase, idPattern: "UC-*" }
            root: { type: object, fields: [ { key: id, type: id } ] }
            """.trimIndent(),
            Path.of("_schemas/s2.yaml"),
        )
        assertEquals(2, list.entities.size)
        assertEquals(false, list.entities[1].defines)  // defines 省略時は詳細化
        assertEquals(emptyList(), list.validateDefinition())
    }

    @Test
    fun `type欠落とlibraryへのentity宣言はパースエラーになる`() {
        assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                "id: x\nentity: { idPattern: \"A-*\" }\nroot: { type: object, fields: [] }",
                Path.of("_schemas/x.yaml"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                "id: x\nkind: library\nentity: { type: a }\ndefinitions: { n: { type: string } }",
                Path.of("_schemas/x.yaml"),
            )
        }
    }

    @Test
    fun `型の重複とcatch-allの多重宣言は定義検証で指摘される`() {
        val dup = SchemaLoader.parseSchemaText(
            """
            id: s
            match: "a/**/*.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*" }
              - { type: screen, idPattern: "S2-*" }
            root: { type: object, fields: [ { key: id, type: id } ] }
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(dup.validateDefinition().any { "エンティティ型「screen」が重複" in it.message })

        val catchAll = SchemaLoader.parseSchemaText(
            """
            id: s
            match: "a/**/*.yaml"
            entity:
              - { type: a }
              - { type: b }
            root: { type: object, fields: [ { key: id, type: id } ] }
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(catchAll.validateDefinition().any { "idPattern のないエンティティ型宣言は1つまで" in it.message })
    }

    // ---------------------------------------------------------------- 走査時の付与と resolve 優先

    @Test
    fun `DocScannerがエンティティ型と役割を付与し宣言なしスキーマではnullのまま`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: list
            match: "list.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields: [ { key: id, type: id } ]
            """.trimIndent(),
            Path.of("_schemas/list.yaml"),
        )
        val root = YamlIO.parse("screens:\n  - id: SCR-001\n  - id: MEMO-1")
        val (ids, _) = DocScanner.scan(root, DocumentId("list.yaml"), schema)
        val scr = ids.first { it.id == "SCR-001" }
        assertEquals("screen", scr.entityType)
        assertEquals(EntityRole.DEFINING, scr.role)
        // idPattern に一致しないIDは型なし（lint が指摘する）
        val memo = ids.first { it.id == "MEMO-1" }
        assertNull(memo.entityType)
        assertNull(memo.role)

        // 宣言のないスキーマは従来どおり（非破壊）
        val plain = SchemaLoader.parseSchemaText(
            "id: p\nmatch: \"p.yaml\"\nroot: { type: object, fields: [ { key: id, type: id } ] }",
            Path.of("_schemas/p.yaml"),
        )
        val (plainIds, _) = DocScanner.scan(YamlIO.parse("id: X-1"), DocumentId("p.yaml"), plain)
        assertNull(plainIds.single().entityType)
        assertNull(plainIds.single().role)
    }

    @Test
    fun `resolveは定義出現を優先する`() {
        // ファイル名順では詳細化（a-detail）が先に来るが、resolve は定義出現（z-list 側）を返す
        write("_schemas/list.yaml", """
            id: list
            match: "z-list.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields: [ { key: id, type: id }, { key: name, type: string } ]
        """)
        write("_schemas/detail.yaml", """
            id: detail
            match: "a-detail.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        write("a-detail.yaml", "id: SCR-001")
        write("z-list.yaml", """
            screens:
              - id: SCR-001
                name: 一覧画面
        """)
        val ws = Workspace.open(dir)
        val resolved = ws.index.resolve("SCR-001")!!
        assertEquals(EntityRole.DEFINING, resolved.role)
        assertEquals("z-list.yaml", resolved.document.value)
        // グラフのエンティティ表も定義出現を持つ
        val node = ws.index.graph.entities.getValue("SCR-001")
        assertEquals("screen", node.entityType)
        assertEquals("z-list.yaml", node.defining!!.document.value)
    }

    // ---------------------------------------------------------------- lint（Validator）

    @Test
    fun `浮遊詳細化とidPattern逸脱と型競合がWARNINGで指摘される`() {
        write("_schemas/detail.yaml", """
            id: detail
            match: "details/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        write("_schemas/actors.yaml", """
            id: actors
            match: "actors/**/*.yaml"
            entity: { type: actor, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        // 定義出現がどこにもない詳細化（浮遊）＋ idPattern 逸脱
        write("details/a.yaml", "id: SCR-001")
        write("details/b.yaml", "id: MEMO-9")
        val ws = Workspace.open(dir)
        val a = ws.validate("details/a.yaml")
        assertTrue(a.any { it.severity == Severity.WARNING && "定義出現がありません" in it.message }, a.toString())
        val b = ws.validate("details/b.yaml")
        assertTrue(b.any { it.severity == Severity.WARNING && "idPattern" in it.message }, b.toString())

        // 同一IDが別の型でも出現 → 型競合（両側の文書に出る）
        write("actors/x.yaml", "id: SCR-001")
        ws.refresh()
        val conflict = ws.validate("details/a.yaml")
        assertTrue(conflict.any { "複数のエンティティ型" in it.message }, conflict.toString())
    }

    @Test
    fun `定義競合は定義出現側の文書に指摘され宣言なしワークスペースは無変更`() {
        write("_schemas/list.yaml", """
            id: list
            match: "lists/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        write("lists/a.yaml", "id: SCR-001")
        write("lists/b.yaml", "id: SCR-001")
        val ws = Workspace.open(dir)
        val issues = ws.validate("lists/a.yaml")
        assertTrue(issues.any { it.severity == Severity.WARNING && "定義競合" in it.message }, issues.toString())

        // 宣言のないスキーマでは何も出ない（非破壊）
        write("_schemas/plain.yaml", """
            id: plain
            match: "plain/**/*.yaml"
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        write("plain/a.yaml", "id: P-1")
        write("plain/b.yaml", "id: P-1")
        ws.refresh()
        assertEquals(emptyList(), ws.validate("plain/a.yaml").filter { it.severity == Severity.WARNING })
    }
}
