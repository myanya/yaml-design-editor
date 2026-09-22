package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.core.runCli
import dev.toros.yde.semantic.schema.CheckLibrary
import dev.toros.yde.workspace.BuiltinDefinitionResources
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.app.schema.validateDefinition
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 定義体の仕組み（P29 Phase 6 第1段。CORE-CALCULUS §25「検査の書き方の3段」）のワークスペース通しのテスト。
 * 利用者が自分で書いた検査の定義（functions）と語彙の定義（vocabulary）が、組込み定義体と同じ仕組みで
 * 読み込まれ・展開され・評価されること（組込みは特権を持たない。CALC-28）を固定する。
 */
class CheckDefinitionTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-checkdefs")
        // 利用者の定義: 「値が上限以下であること」という検査の定義と、それを短く書くための語彙 limits
        write(
            "_schemas/my-checks.yaml",
            """
            id: my-checks
            kind: library
            functions:
              - id: not-greater-than
                description: 指定スキーマの文書に書かれた値が、上限以下であること
                params: [schema, path, limit]
                checks:
                  - match:
                      - node: {schema: ${'$'}schema, bind: item}
                    check:
                      - op: lte
                        left: {of: item, in: {schemaId: ${'$'}schema}, path: ${'$'}path}
                        right: {value: ${'$'}limit}
                    reportAt: {of: item, in: {schemaId: ${'$'}schema}, path: ${'$'}path}
                    message: 「${'$'}path」の値「{left}」は上限「{right}」を超えています
                    level: error
            vocabulary:
              - word: limits
                in: limits[]
                uses:
                  - use: not-greater-than
                    with:
                      schema: {schema: id}
                      path: {from: field}
                      limit: {from: max}
            """,
        )
        write(
            "_schemas/item.yaml",
            """
            id: item
            match: "items/*.yaml"
            limits:
              - {field: size, max: 10}
            constraints:
              - use: not-greater-than
                with: {schema: item, path: weight, limit: 5}
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: size, type: int }
                - { key: weight, type: int }
            """,
        )
        write("items/a.yaml", "id: IT-1\nsize: 12\nweight: 3\n")
        write("items/b.yaml", "id: IT-2\nsize: 8\nweight: 9\n")
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
    fun `利用者が定義した語彙と定義が展開されて検査になる`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val item = ws.schemas.first { it.id == "item" }
        // 基本の検査へ展開されている: constraints に書いた使用 → 語彙（limits）の順
        assertEquals(
            listOf("constraints[0]（定義 not-greater-than）", "limits[0]（定義 not-greater-than）"),
            item.constraintOrigins,
        )
        assertEquals(2, item.constraints.size)
    }

    @Test
    fun `語彙から展開された検査は値の場所へ指摘を出す`() {
        val ws = Workspace.open(dir)
        // a: size 12 > 10（語彙 limits）→ 指摘。weight 3 ≦ 5 → 指摘なし
        val a = ws.validate("items/a.yaml").filter { "上限" in it.message }
        assertEquals(1, a.size, a.toString())
        assertEquals(Severity.ERROR, a.single().severity)
        assertEquals("「size」の値「12」は上限「10」を超えています", a.single().message)
        assertEquals(parseYPath("size"), a.single().path)
        // b: size 8 ≦ 10 → 指摘なし。weight 9 > 5（constraints に直接書いた使用）→ 指摘
        val b = ws.validate("items/b.yaml").filter { "上限" in it.message }
        assertEquals("「weight」の値「9」は上限「5」を超えています", b.single().message)
        assertEquals(parseYPath("weight"), b.single().path)
    }

    @Test
    fun `利用者の語彙を使ったスキーマ文書は定義されていない項目と警告されない`() {
        // 語彙として定義されたキー（limits）は、メタスキーマの「知っている項目」に足される
        val ws = Workspace.open(dir)
        val issues = ws.validate("_schemas/item.yaml")
        assertTrue(issues.none { "定義されていない項目" in it.message }, issues.toString())
    }

    @Test
    fun `CLIのchecksは展開後の検査を由来つきで表示する`() {
        val out = runCli(arrayOf("checks", dir.toString(), "item", "--definitions"))
        assertTrue("limits[0]（定義 not-greater-than）" in out, out)
        assertTrue("schemaId: item" in out && "path: size" in out, out)
        // --definitions: 利用者の定義と、組込み定義体の語彙の両方が一覧に出る
        assertTrue("not-greater-than" in out && "word: limits" in out && "word: consistency" in out, out)
    }

    @Test
    fun `見つからない定義の使用はスキーマの読み込みエラーになる`() {
        write(
            "_schemas/item.yaml",
            """
            id: item
            match: "items/*.yaml"
            constraints:
              - use: no-such-definition
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "no-such-definition" in it && "見つかりません" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `組込み定義体は利用者の定義と同じ書き方で書かれている`() {
        // 同梱の YAML を、利用者のスキーマ文書と同じパーサで読めること（特別な読み方をしていない）
        for (name in BuiltinDefinitionResources.names) {
            val parsed = SchemaLoader.parseSchemaText(
                BuiltinDefinitionResources.text(name), Path.of("(builtin)/$name.yaml"),
                library = CheckLibrary(),
            )
            // 定義と語彙を持つか、特定のスキーマに属さない検査を直接持つ（エンティティの整合）
            assertTrue(parsed.checkLibrary.functions.isNotEmpty() || parsed.constraints.isNotEmpty(), name)
            assertTrue(parsed.validateDefinition().isEmpty(), parsed.validateDefinition().toString())
        }
        assertTrue("consistency" in SchemaLoader.builtinLibrary.topLevelKeys)
        assertEquals(3, SchemaLoader.builtinRules.size)
    }
}
