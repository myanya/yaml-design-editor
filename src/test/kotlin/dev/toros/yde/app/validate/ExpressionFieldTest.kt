package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 利用者のスキーマの項目に `expression: true` の印を付けたとき（P31・R31-05）: 値は短い書き方（文字列）でも完全な書き方
 * （式の object）でもよく（構造の検証は「文字列か object」の形として通す）、式として読めない値は検証の指摘になる。
 * 印は string / text 型でのみ使える（スキーマの定義の点検）。
 */
class ExpressionFieldTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-exprfield")
        write("_schemas/report.yaml", """
            id: report
            match: "reports/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: output, label: 出力先, type: string, expression: true }
        """)
        write("reports/r1.yaml", "id: R-1\noutput: \"{id}_{title}.md\"\n")
        write("reports/r2.yaml", "id: R-2\noutput: { format: \"{n}.md\", with: { n: { lower: { path: id } } } }\n")
        write("reports/r3.yaml", "id: R-3\noutput: { format: \"{id}.md\", bogus: 1 }\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `印のある項目は 文字列でも式の object でも通り 読めない式だけが指摘になる`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        assertTrue(ws.validate("reports/r1.yaml").none { it.severity.name == "ERROR" }, ws.validate("reports/r1.yaml").toString())
        assertTrue(ws.validate("reports/r2.yaml").none { it.severity.name == "ERROR" }, ws.validate("reports/r2.yaml").toString())
        val issue = ws.validate("reports/r3.yaml").single { it.severity.name == "ERROR" }
        assertTrue("「出力先」（output）の式が読めません" in issue.message, issue.message)
    }

    @Test
    fun `印は string と text の項目にだけ書ける`() {
        write("_schemas/bad.yaml", """
            id: bad
            match: "bad/*.yaml"
            root:
              type: object
              fields:
                - { key: n, type: int, expression: true }
        """)
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "expression（値の式を受け取る印）は string / text 型でのみ" in it }, ws.schemaErrors.toString())
    }
}
