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
 * P29 Phase 6 第2段で足した意味プリミティブを、検査（constraints）から使うテスト。
 * - 条件の否定と「または」（SP-02）: where と check に `{not: 条件}` `{or: [条件, ...]}` を書ける（入れ子は1段）
 * - 参照の本数と集計（SP-03）: 値の式 `refCount`・`sum` / `min` / `max` / `count`
 * - 参照の束縛（edge の bind）と、参照の場所への指摘（reportAt: {ref}）
 * - 文面のひな型（message）は、どの判定にも書ける（`{id}` `{束縛名.id}` `{count}` `{left}` `{right}`）
 */
class ConditionAndAggregateTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-condition")
        write(
            "_schemas/order.yaml",
            """
            id: order
            match: "orders/*.yaml"
            entity: { type: order, idPattern: "ORD-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: status, type: string }
                - { key: total, type: number }
                - key: lines
                  type: array
                  item: { type: ref, relation: has-line, scope: { schemaIds: [line] } }
            constraints:
              - id: total-is-sum-of-lines
                description: 注文の合計は明細の金額の合計と一致すること
                match:
                  - node: { entity: order, bind: o }
                  - edge: { relation: has-line }
                  - node: { entity: line, bind: l }
                check:
                  - eq: [{ number: { of: o, path: total } }, { sum: { all: { of: l, path: amount } } }]
                level: error
              - id: confirmed-order-has-lines
                match:
                  - node: { entity: order, bind: o }
                where:
                  - or:
                      - { of: o, path: status, equals: approved }
                      - { of: o, path: status, equals: shipped }
                check:
                  - gte: [{ refCount: { of: o, dir: out, relation: has-line } }, 1]
                message: 確定した注文「{id}」に明細がありません（現在 {left} 件）
                level: error
              - id: no-void-line
                match:
                  - node: { entity: order, bind: o }
                  - edge: { relation: has-line, bind: r }
                  - node: { entity: line, bind: l }
                where:
                  - { of: l, path: status, equals: void }
                  - not: { of: o, path: status, equals: draft }
                expect: absent
                reportAt: { ref: r }
                message: 注文「{o.id}」は無効な明細「{l.id}」（{l.title}）を参照しています
                level: error
            """,
        )
        write(
            "_schemas/line.yaml",
            """
            id: line
            match: "lines/*.yaml"
            entity: { type: line, idPattern: "LN-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: kind, type: string }
                - { key: code, type: string }
                - { key: status, type: string }
                - { key: amount, type: number }
            constraints:
              - id: amount-positive-unless-discount
                description: 明細の金額は正であること（値引きの明細を除く）
                match:
                  - node: { entity: line, bind: l }
                check:
                  - or:
                      - gt: [{ of: l, path: amount }, 0]
                      - eq: [{ of: l, path: kind }, discount]
                level: error
              - id: no-temporary-code
                description: 仮のコードのまま残さないこと
                match:
                  - node: { entity: line, bind: l }
                check:
                  - not: { startsWith: [{ of: l, path: code }, "TMP-"] }
                level: error
            """,
        )
        write("lines/LN-1.yaml", "id: LN-1\nname: 本体\nkind: goods\ncode: A-1\namount: 100")
        write("lines/LN-2.yaml", "id: LN-2\nname: 送料\nkind: fee\ncode: A-2\namount: 20.5")
        write("lines/LN-DISCOUNT.yaml", "id: LN-DISCOUNT\nname: 値引き\nkind: discount\ncode: A-3\namount: -10")
        write("lines/LN-NEG.yaml", "id: LN-NEG\nname: 負の金額\nkind: goods\ncode: A-4\namount: -5")
        write("lines/LN-TMP.yaml", "id: LN-TMP\nname: 仮コード\nkind: goods\ncode: TMP-9\namount: 1")
        write("lines/LN-VOID.yaml", "id: LN-VOID\nname: 取消済み\nkind: goods\ncode: A-5\nstatus: void\namount: 1")
        write("lines/LN-NOCODE.yaml", "id: LN-NOCODE\nname: コードなし\nkind: goods\namount: 1")
        write("orders/ORD-OK.yaml", "id: ORD-OK\nstatus: approved\ntotal: 110.5\nlines: [LN-1, LN-2, LN-DISCOUNT]")
        write("orders/ORD-SUM.yaml", "id: ORD-SUM\nstatus: draft\ntotal: 999\nlines: [LN-1, LN-2]")
        write("orders/ORD-EMPTY.yaml", "id: ORD-EMPTY\nstatus: shipped\ntotal: 0")
        write("orders/ORD-DRAFT-EMPTY.yaml", "id: ORD-DRAFT-EMPTY\nstatus: draft\ntotal: 0")
        write("orders/ORD-VOID.yaml", "id: ORD-VOID\nstatus: approved\ntotal: 101\nlines: [LN-1, LN-VOID]")
        write("orders/ORD-DRAFT-VOID.yaml", "id: ORD-DRAFT-VOID\nstatus: draft\ntotal: 1\nlines: [LN-VOID]")
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
    fun `条件をすべて満たす文書には指摘が出ない`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        // 合計 100 + 20.5 - 10 = 110.5（小数の誤差なし）・値引きの明細は負でもよい
        assertEquals(emptyList(), errorsOf(ws, "orders/ORD-OK.yaml").map { it.message })
        assertEquals(emptyList(), errorsOf(ws, "lines/LN-DISCOUNT.yaml").map { it.message })
    }

    @Test
    fun `合計は同じ起点の全ての解にわたって計算し同じ組を解の数だけ並べない`() {
        val issue = errorsOf(Workspace.open(dir), "orders/ORD-SUM.yaml").single()
        // 明細は2件（解も2つ）だが、合計の食い違いは1組として示す
        assertTrue("（1 組）" in issue.message && "total: 999" in issue.message, issue.message)
        assertTrue("「120.5」（= 合計: 100・20.5）" in issue.message, issue.message)
    }

    @Test
    fun `参照の本数の検査はwhereのまたはで絞った要素にだけ効き宣言した文面で指摘する`() {
        val ws = Workspace.open(dir)
        val issue = errorsOf(ws, "orders/ORD-EMPTY.yaml").single()
        assertEquals("確定した注文「ORD-EMPTY」に明細がありません（現在 0 件） — confirmed-order-has-lines", issue.message)
        // 下書きは「または」のどちらにも当たらない＝対象外
        assertEquals(emptyList(), errorsOf(ws, "orders/ORD-DRAFT-EMPTY.yaml").map { it.message })
    }

    @Test
    fun `束縛した参照の場所へ見つかった解ごとに指摘し文面に解のノードを埋め込める`() {
        val ws = Workspace.open(dir)
        val issue = errorsOf(ws, "orders/ORD-VOID.yaml").single { "無効な明細" in it.message }
        assertEquals(listOf<Any>("lines", 1), issue.path)
        assertEquals("注文「ORD-VOID」は無効な明細「LN-VOID」（取消済み）を参照しています — no-void-line", issue.message)
        // where の否定: 下書きの注文は対象外
        assertTrue(errorsOf(ws, "orders/ORD-DRAFT-VOID.yaml").none { "無効な明細" in it.message })
    }

    @Test
    fun `checkのまたはは全ての条件を満たさないときだけ違反になる`() {
        val issue = errorsOf(Workspace.open(dir), "lines/LN-NEG.yaml").single()
        assertTrue("amount: -5" in issue.message && "（「または」のどの条件も満たさない）" in issue.message, issue.message)
    }

    @Test
    fun `checkの否定は成り立ってしまった関係を示し値が無ければ判定保留で黙る`() {
        val ws = Workspace.open(dir)
        val issue = errorsOf(ws, "lines/LN-TMP.yaml").single()
        assertTrue("code: TMP-9" in issue.message && "が次で始まる: 「TMP-」" in issue.message, issue.message)
        // コードが書かれていない明細は、否定しても評価できないまま（指摘しない）
        assertEquals(emptyList(), errorsOf(ws, "lines/LN-NOCODE.yaml").map { it.message })
    }

    @Test
    fun `入れ子が2段の条件と束縛の違うまたはは定義エラーになる`() {
        write(
            "_schemas/bad.yaml",
            """
            id: bad
            match: "bad/*.yaml"
            root: { type: object, fields: [{ key: id, type: id }] }
            constraints:
              - match: [{ node: { entity: order, bind: o } }]
                where:
                  - or:
                      - { of: o, path: status, equals: a }
                      - or: [{ of: o, path: status, equals: b }, { of: o, path: status, equals: c }]
                expect: exists
            """,
        )
        val errors = Workspace.open(dir).schemaErrors
        assertTrue(errors.any { "bad.yaml" in it && "入れ子は1段まで" in it }, errors.toString())
    }
}
