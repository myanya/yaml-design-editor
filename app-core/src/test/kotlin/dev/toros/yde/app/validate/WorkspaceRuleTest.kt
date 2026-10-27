package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
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
 * 特定のスキーマに属さない検査（P29 Phase 6 第3段）のテスト: 組込み定義体に直接書かれたエンティティの整合
 * （浮遊詳細化・定義競合・型競合。意図的差分 D-22）と、利用者が kind: library の文書に直接書いた検査。
 * どちらも同じ仕組み——ワークスペースのどの文書に定義された要素にも効き、配送は他の検査と同じ。
 */
class WorkspaceRuleTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-workspace-rule")
        write(
            "_schemas/list.yaml",
            """
            id: list
            match: "lists/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root: { type: object, fields: [{ key: id, type: id }, { key: name, type: string, role: title }, { key: owner, type: string }] }
            """,
        )
        write(
            "_schemas/detail.yaml",
            """
            id: detail
            match: "details/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root: { type: object, fields: [{ key: id, type: id }, { key: layout, type: string }] }
            """,
        )
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
    fun `定義競合は最初の定義出現に報告され他の出現には案内が出る`() {
        write("lists/a.yaml", "id: SCR-1\nname: 一覧")
        write("lists/b.yaml", "id: SCR-1\nname: 一覧（重複）")
        write("details/d.yaml", "id: SCR-1\nlayout: grid")
        val ws = Workspace.open(dir)
        val report = ws.validate("lists/a.yaml").single { "定義競合" in it.message }
        assertEquals(Severity.WARNING, report.severity)
        assertEquals("ID「SCR-1」の定義出現が 2 件あります（定義競合）", report.message)
        for (other in listOf("lists/b.yaml", "details/d.yaml")) {
            val notice = ws.validate(other).single { "定義競合" in it.message }
            assertEquals(Severity.INFO, notice.severity, other)
            assertTrue("報告先: 定義出現 lists/a.yaml" in notice.message, notice.message)
        }
    }

    @Test
    fun `強制スイッチがerrorなら定義のない詳細化はどの出現の文書でもエラーになる`() {
        write("_schemas/relations.yaml", "id: rels\nkind: relations\nenforcement: { definingUniqueness: error }\nrelations:\n  - name: uses")
        write("details/d1.yaml", "id: SCR-9\nlayout: grid")
        write("details/d2.yaml", "id: SCR-9\nlayout: list")
        val ws = Workspace.open(dir)
        for (file in listOf("details/d1.yaml", "details/d2.yaml")) {
            val issue = ws.validate(file).single { "定義出現がありません" in it.message }
            assertEquals(Severity.ERROR, issue.severity, file)
            assertTrue("エンティティ型「screen」のID「SCR-9」" in issue.message, issue.message)
        }
    }

    @Test
    fun `libraryに直接書いた検査はどのスキーマの文書の要素にも効く`() {
        // 組込みは特権を持たない（CALC-28）: エンティティの整合と同じ形で、利用者もワークスペース全体の検査を書ける
        write(
            "_schemas/house-rules.yaml",
            """
            id: house-rules
            kind: library
            constraints:
              - id: screen-has-owner
                match:
                  - node: { entity: screen, bind: s }
                where:
                  - { of: s, path: owner, empty: true }
                expect: absent
                message: 画面「{id}」（{s.title}）の担当者が書かれていません
                level: error
            """,
        )
        write("lists/a.yaml", "id: SCR-1\nname: 一覧\nowner: 田中")
        write("lists/b.yaml", "id: SCR-2\nname: 詳細")
        write("details/d.yaml", "id: SCR-2\nlayout: grid")
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        assertTrue(ws.validate("lists/a.yaml").none { "担当者" in it.message })
        val report = ws.validate("lists/b.yaml").single { "担当者" in it.message }
        assertEquals("画面「SCR-2」（詳細）の担当者が書かれていません — screen-has-owner", report.message)
        assertEquals(Severity.ERROR, report.severity)
        // エラー級の指摘は、同じ ID が書かれた他の文書にも同格で出る
        assertEquals(Severity.ERROR, ws.validate("details/d.yaml").single { "担当者" in it.message }.severity)
    }
}
