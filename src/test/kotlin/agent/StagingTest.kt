package agent

import core.PatchOp
import core.Workspace
import model.YNode
import model.parseYPath
import validate.Severity
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ステージング（作業オーバーレイ）のテスト。
 * 中核の検証: ID改名＋参照元修正のような文書横断の変更が、オーバーレイ索引で「保存前に」整合確認できること。
 */
class StagingTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-staging")
        write(
            "_schemas/glossary.yaml",
            """
            id: glossary
            match: "glossary/**/*.yaml"
            coverage:
              - requiredBy: { minCount: 1 }
                level: error
                description: 用語は1箇所以上から参照されること
            root:
              type: object
              fields:
                - key: terms
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: title, type: string, required: true }
            """,
        )
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: title, type: string }
                - key: uses
                  type: array
                  item:
                    type: ref
                    scope: { schemaIds: [glossary] }
            """,
        )
        write("glossary/main.yaml", "terms:\n- id: TERM-001\n  title: 旧用語")
        write("docs/a.yaml", "id: DOC-A\ntitle: 文書A\nuses:\n- TERM-001")
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
    fun `edits stay on overlay and originals are untouched`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        staging.applyOps("glossary/main.yaml", listOf(PatchOp.Set(parseYPath("terms[0].title"), YNode.Scalar("新用語"))))
        assertTrue("新用語" in staging.proposedText("glossary/main.yaml")!!)
        assertTrue("旧用語" in Files.readString(dir.resolve("glossary/main.yaml")))  // 原本は未変更
    }

    @Test
    fun `staged index resolves coordinated cross-file rename before saving`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        // 1) 改名だけ → 影響ファイル（旧IDの参照元 docs/a）が自動的に検証対象へ入り、
        //    参照不整合とカバレッジ不足の両方が検出される
        staging.applyOps("glossary/main.yaml", listOf(PatchOp.Set(parseYPath("terms[0].id"), YNode.Scalar("TERM-100"))))
        val before = staging.validateAll()
        assertTrue(before.getValue("glossary/main.yaml").any { "参照が不足" in it.message })
        assertTrue(before.getValue("docs/a.yaml").any { "TERM-001" in it.message && it.severity == Severity.ERROR })
        // 2) 参照元も修正 → オーバーレイ索引で両方解消（ディスクは一切変わっていない）
        staging.applyOps("docs/a.yaml", listOf(PatchOp.Set(parseYPath("uses[0]"), YNode.Scalar("TERM-100"))))
        val after = staging.validateAll()
        assertTrue(after.values.flatten().none { it.severity == Severity.ERROR }, after.toString())
    }

    @Test
    fun `changeset carries base hash and created files`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        staging.applyOps("docs/a.yaml", listOf(PatchOp.Set(parseYPath("title"), YNode.Scalar("改題"))))
        staging.createDocument("docs/new.yaml", YamlIO.parse("id: DOC-NEW\ntitle: 新規\nuses: []"))
        val cs = staging.toChangeSet()
        val edited = cs.changes.first { it.file == "docs/a.yaml" }
        assertEquals(false, edited.create)
        assertTrue(edited.baseHash!!.isNotBlank())
        val created = cs.changes.first { it.file == "docs/new.yaml" }
        assertTrue(created.create)
        // 適用まで一気通貫（P1 の apply を再利用できることの確認）
        val result = ws.apply(cs)
        assertTrue(result.ok, result.results.toString())
        assertTrue(Files.exists(dir.resolve("docs/new.yaml")))
        assertTrue("改題" in Files.readString(dir.resolve("docs/a.yaml")))
    }

    @Test
    fun `create rejects existing files and non yaml`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        assertFailsWith<IllegalArgumentException> { staging.createDocument("docs/a.yaml", YNode.Map()) }
        assertFailsWith<IllegalArgumentException> { staging.createDocument("docs/x.txt", YNode.Map()) }
    }

    @Test
    fun `editing missing file fails with guidance`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        val e = assertFailsWith<IllegalArgumentException> {
            staging.applyOps("docs/none.yaml", listOf(PatchOp.Delete(parseYPath("x"))))
        }
        assertTrue("create_document" in e.message!!)
    }
}
