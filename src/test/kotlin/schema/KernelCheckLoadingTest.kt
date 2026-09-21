package schema

import core.Bench
import core.Workspace
import workspace.BuiltinSchemaResources
import workspace.FileSystemDocumentStore
import workspace.WorkspaceReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 検査の定義の読み込みを、アプリ側（root の SchemaLoader）とカーネル側（WorkspaceReader）が共有していること（P27 段B-2）。
 * 以前はカーネル側が constraints・functions・vocabulary・checkers と組込み定義体を読まず、定義体で書かれた検査を知らなかった
 * （BACKLOG UX-115）。利用者の語彙のキーが「スキーマに定義されていない項目」と警告される問題（UX-105）も同じ根だった。
 */
class KernelCheckLoadingTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-kernel-checks")
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

    private fun loadKernel() = WorkspaceReader.load(
        FileSystemDocumentStore(dir), builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.of(dir)),
    )

    @Test
    fun `カーネルのスキーマ読み込みは アプリ側と同じ検査を持つ`() {
        Bench.generate(dir, modules = 1)
        val root = Workspace.open(dir)
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.isEmpty(), kernel.schemaErrors.toString())
        val userSchemas = root.schemas.filter { !it.id.startsWith("builtin-") }
        assertTrue(userSchemas.sumOf { it.constraints.size } > 20, "語彙（多重度・対象型・非循環・coverage・matrix）が展開されていること")
        for (schema in userSchemas) {
            val k = kernel.schemas.single { it.id == schema.id }
            // 検査の中身と出どころ（どの書き方から展開された・何番目の検査か）まで一致する
            assertEquals(schema.constraints, k.constraints, "constraints: ${schema.id}")
            assertEquals(schema.checkers, k.checkers, "checkers: ${schema.id}")
            assertEquals(schema.expandedChecks, k.expandedChecks, "expandedChecks: ${schema.id}")
        }
    }

    @Test
    fun `カーネル側で検査を実行した結果は アプリ側と一致する`() {
        Bench.generate(dir, modules = 3)
        // 違反・他の出現への案内・直せる場所への案内・参照の場所への指摘・保留がひととおり出るように壊す
        fun rewrite(rel: String, transform: (String) -> String) =
            Files.writeString(dir.resolve(rel), transform(Files.readString(dir.resolve(rel))))
        rewrite("m001/tests.yaml") { it.lines().filterNot { l -> "TI-1-1-1," in l }.joinToString("\n") }
        rewrite("m001/screens/SCR-1-1.yaml") { it.replace(Regex("""max_length: \d+ }"""), "max_length: 9999 }") }
        Files.delete(dir.resolve("m002/screens/SCR-2-6.yaml"))
        rewrite("m002/processes/PROC-2-6.yaml") { it.replace("calls: []", "calls: [PROC-2-1]") }
        rewrite("m003/screens/SCR-3-1.yaml") { it.replaceFirst(Regex("""dtype: DT-\d+"""), "dtype: DT-GONE") }
        rewrite("m003/transitions.yaml") { it.lines().filterNot { l -> "from: SCR-3-4, to: SCR-3-5" in l }.joinToString("\n") }

        val root = Workspace.open(dir)
        val kernel = workspace.WorkspaceCheckRunner.deliverAll(loadKernel())
        var compared = 0
        for (file in root.files) {
            val rel = root.relPath(file)
            val result = root.validateDetailed(rel)
            // アプリ側の指摘のうち、定義体の検査から出たもの（出どころを持つもの）
            val app = result.issues.filter { it.source != null }.map { listOf(it.severity, it.message, it.path, it.source) }
            val delivery = kernel[model.DocumentId(rel)]
            val core = delivery?.issues.orEmpty().map { listOf(it.severity, it.message, it.path, it.source) }.distinct()
            assertEquals(app, core, "指摘: $rel")
            assertEquals(result.withheld, delivery?.withheld.orEmpty(), "保留: $rel")
            compared += app.size
        }
        assertTrue(compared > 20, "壊した内容の指摘を比べていること（$compared 件）")
        assertTrue(kernel.values.any { it.withheld.isNotEmpty() }, "保留が出ていること")
    }

    @Test
    fun `利用者の語彙のキーは カーネル側でも 定義されていない項目と警告されない`() {
        write(
            "_schemas/lib.yaml",
            """
            id: lib
            kind: library
            functions:
              - id: has-owner
                params: [schema]
                checks:
                  - match:
                      - node: { schema: ${'$'}schema, bind: a }
                    check:
                      - ne: [{ of: a, path: owner }, ""]
            vocabulary:
              - word: ownership
                in: "ownership[]"
                uses:
                  - use: has-owner
                    with: { schema: { schema: id } }
            """,
        )
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/*.yaml"
            entity: { type: doc, idPattern: "D-*", defines: true }
            ownership:
              - {}
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: owner, type: string }
            """,
        )
        write("docs/D-1.yaml", "id: D-1\nowner: 山田")
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.isEmpty(), kernel.schemaErrors.toString())
        // 語彙 ownership が、基本の検査へ展開されている
        assertEquals(1, kernel.schemas.single { it.id == "doc" }.constraints.size)
        // スキーマ文書の構造の検証で、語彙のキー ownership が「定義されていない項目」にならない
        val schemaDoc = kernel.derived.structuralIssues.entries.filter { it.key.value.endsWith("_schemas/doc.yaml") }
        assertTrue(schemaDoc.flatMap { it.value }.none { "ownership" in it.message }, schemaDoc.toString())
    }

    @Test
    fun `定義の誤りは カーネル側でもスキーマの読み込みエラーになる`() {
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/*.yaml"
            constraints:
              - use: no-such-definition
            root:
              type: object
              fields:
                - { key: id, type: id }
            """,
        )
        val kernel = loadKernel()
        assertTrue(kernel.schemaErrors.any { "no-such-definition" in it }, kernel.schemaErrors.toString())
    }
}
