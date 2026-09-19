package validate

import core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 値の条件と値の計算の部品（P29 Phase 6・SP-10。BACKLOG UX-107）を、検査（constraints）から使うテスト。
 * 値の条件は check（判定）と where（ノードの絞り込み）が同じ演算子・同じ判定を共有し、
 * 評価できない値（数値として読めない・書かれていない）は判定保留＝黙る。
 */
class ValueConditionTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-value-condition")
        write(
            "_schemas/order-line.yaml",
            """
            id: order-line
            match: "lines/*.yaml"
            entity: { type: order-line, idPattern: "OL-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: code, type: string }
                - { key: status, type: string }
                - { key: price, type: number }
                - { key: qty, type: number }
                - { key: total, type: number }
                - { key: note, type: string }
            constraints:
              - id: name-length
                description: 名称は10文字以内であること
                match:
                  - node: { entity: order-line, bind: line }
                check:
                  - lte: [{ length: { of: line, path: name } }, 10]
                level: error
              - id: total-is-price-times-qty
                description: 金額は単価×数量と一致すること
                match:
                  - node: { entity: order-line, bind: line }
                check:
                  - eq:
                      - { number: { of: line, path: total } }
                      - { multiply: [{ of: line, path: price }, { of: line, path: qty }] }
                level: error
              - id: status-is-known
                description: 状態は決められた値のどれかであること
                match:
                  - node: { entity: order-line, bind: line }
                check:
                  - oneOf: [{ of: line, path: status }, [draft, approved]]
                level: error
              - id: code-format
                description: コードは大文字に直すと「英字-数字3桁」の形であること
                match:
                  - node: { entity: order-line, bind: line }
                check:
                  - matches: [{ upper: { of: line, path: code } }, "[A-Z]+-[0-9]{3}"]
                level: error
              - id: approved-needs-note
                description: 承認済みで数量が100以上の明細には備考を書くこと
                match:
                  - node: { entity: order-line, bind: line }
                where:
                  - { of: line, path: status, oneOf: [approved] }
                  - { of: line, path: qty, gte: 100 }
                  - { of: line, path: note, empty: true }
                expect: absent
                level: error
            """,
        )
        write("lines/OL-OK.yaml", "id: OL-OK\nname: 通常の明細\ncode: ab-001\nstatus: approved\nprice: 100\nqty: 3\ntotal: 300.0\nnote: 確認済み")
        write("lines/OL-NAME.yaml", "id: OL-NAME\nname: とても長い名前の注文明細です\nstatus: draft")
        write("lines/OL-TOTAL.yaml", "id: OL-TOTAL\nname: 金額違い\nstatus: draft\nprice: 100\nqty: 3\ntotal: 350")
        write("lines/OL-STATUS.yaml", "id: OL-STATUS\nname: 状態違い\nstatus: rejected")
        write("lines/OL-CODE.yaml", "id: OL-CODE\nname: コード違い\nstatus: draft\ncode: ab-1")
        write("lines/OL-NOTE.yaml", "id: OL-NOTE\nname: 備考なし\nstatus: approved\nqty: 120\nnote: \"\"")
        write("lines/OL-SMALL.yaml", "id: OL-SMALL\nname: 少量\nstatus: approved\nqty: 5")
        write("lines/OL-TEXT.yaml", "id: OL-TEXT\nname: 数量が文字\nstatus: approved\nprice: 100\nqty: 多数\ntotal: 300")
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

    private fun errorsOf(ws: Workspace, rel: String): List<Issue> = ws.validate(rel).filter { it.severity == Severity.ERROR }

    @Test
    fun `すべての条件を満たす文書には指摘が出ない`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        // total: 300.0 は number で表記を揃えてから 100 × 3 = 300 と比べる。code は大文字に直してから形を調べる
        assertEquals(emptyList(), errorsOf(ws, "lines/OL-OK.yaml").map { it.message })
    }

    @Test
    fun `文字数の計算は何を数えたかを文面に添える`() {
        val issue = errorsOf(Workspace.open(dir), "lines/OL-NAME.yaml").single()
        assertTrue("name の文字数: 14" in issue.message && " > " in issue.message && "「10」" in issue.message, issue.message)
        assertTrue("名称は10文字以内であること" in issue.message, issue.message)
    }

    @Test
    fun `四則演算の結果は式を添えて示す`() {
        val issue = errorsOf(Workspace.open(dir), "lines/OL-TOTAL.yaml").single()
        assertTrue("total: 350" in issue.message && "「300」（= 100 × 3）" in issue.message, issue.message)
    }

    @Test
    fun `選択肢のどれでもない値は選択肢を並べて指摘する`() {
        val issue = errorsOf(Workspace.open(dir), "lines/OL-STATUS.yaml").single()
        assertTrue("status: rejected" in issue.message && "が次のどれでもない: 「draft・approved」" in issue.message, issue.message)
    }

    @Test
    fun `正規表現は全体一致で調べる`() {
        val issue = errorsOf(Workspace.open(dir), "lines/OL-CODE.yaml").single()
        // AB-1 は「英字-数字3桁」の全体には一致しない（部分一致では判定しない）
        assertTrue("code の大文字: AB-1" in issue.message && "が次の正規表現に一致しない:" in issue.message, issue.message)
    }

    @Test
    fun `whereの値の条件でノードを絞れる`() {
        val ws = Workspace.open(dir)
        val issue = errorsOf(ws, "lines/OL-NOTE.yaml").single()
        assertTrue("expect: absent" in issue.message && "承認済みで数量が100以上" in issue.message, issue.message)
        // 数量が100未満の明細は母集団に入らない（備考が無くても指摘しない）
        assertEquals(emptyList(), errorsOf(ws, "lines/OL-SMALL.yaml").map { it.message })
    }

    @Test
    fun `数値として読めない値は判定保留で黙る`() {
        // qty が数値でない: 掛け算は値が得られず金額の検査は黙る・where の gte も成立しない
        // （数値でないこと自体は、フィールドの型の検証が別に指摘する）
        val messages = Workspace.open(dir).validate("lines/OL-TEXT.yaml").map { it.message }
        assertTrue(messages.none { "金額は単価×数量" in it || "承認済みで数量" in it }, messages.toString())
    }
}
