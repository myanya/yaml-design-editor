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
 * 検査の中で、起点を固定した照合の `all`（`from` に束縛名）を使うテスト（ADR-0010）。家計簿の「当月の期首残高＝前月の期首残高＋
 * 前月の収入 − 前月の支出」のように、絞り方の違う 2 つの合計を 1 つの検査に書ける。予算超過は解にわたる `all`（of の形）で書く。
 */
class AllFromCheckTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-all-from-check")
        write(
            "_schemas/months.yaml",
            """
            id: months
            match: "months/*.yaml"
            entity: { type: month, idPattern: "M-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: yearMonth, type: string, role: title }
                - { key: prev, type: ref, idPattern: "M-*", scope: { schemaIds: [months] } }
                - { key: opening, type: int }
            constraints:
              - id: opening-continues
                description: 当月の期首残高は、前月の期首残高に前月の収入を足し支出を引いたものと一致すること
                match:
                  - node: { entity: month, bind: m }
                  - edge: { via: prev }
                  - node: { entity: month, bind: p }
                check:
                  - eq:
                      - { of: m, path: opening }
                      - subtract:
                          - add:
                              - { of: p, path: opening }
                              - sum:
                                  all:
                                    from: p
                                    of: tx
                                    path: amount
                                    match:
                                      - node: { entity: month, bind: pm }
                                      - edge: { via: month, dir: in }
                                      - node: { entity: transaction, bind: tx }
                                    where: [ { of: tx, path: kind, equals: income } ]
                          - sum:
                              all:
                                from: p
                                of: tx
                                path: amount
                                match:
                                  - node: { entity: month, bind: pm }
                                  - edge: { via: month, dir: in }
                                  - node: { entity: transaction, bind: tx }
                                where: [ { of: tx, path: kind, equals: expense } ]
                level: error
                message: "{m.title} の期首残高 {left} は、前月から計算した {right} と一致しません"
            """,
        )
        write(
            "_schemas/transactions.yaml",
            """
            id: transactions
            match: "tx/*.yaml"
            entity: { type: transaction, idPattern: "TX-*", defines: true }
            root:
              type: object
              fields:
                - key: entries
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: kind, type: enum, values: [income, expense] }
                      - { key: amount, type: int }
                      - { key: category, type: string }
                      - { key: month, type: ref, idPattern: "M-*", scope: { schemaIds: [months] } }
            """,
        )
        write(
            "_schemas/budgets.yaml",
            """
            id: budgets
            match: "budgets/*.yaml"
            entity: { type: budget, idPattern: "BG-*", defines: true }
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: month, type: ref, idPattern: "M-*", scope: { schemaIds: [months] } }
                      - { key: category, type: string }
                      - { key: amount, type: int }
            constraints:
              - id: budget-overrun
                description: 費目の支出の合計が、その月の予算を超えていないこと
                match:
                  - node: { entity: budget, bind: bg }
                  - edge: { via: month }
                  - node: { entity: month, bind: m }
                  - edge: { via: month, dir: in }
                  - node: { entity: transaction, bind: tx }
                where:
                  - { of: tx, path: kind, equals: expense }
                  - { of: tx, path: category, equals: food }
                  - { of: bg, path: category, equals: food }
                check:
                  - { lte: [ { sum: { all: { of: tx, path: amount } } }, { of: bg, path: amount } ] }
                level: warning
                message: "{m.title} の食費 {left} が予算 {right} を超えています"
            """,
        )
        write("months/2026-08.yaml", "id: M-2026-08\nyearMonth: \"2026-08\"\nopening: 100000")
        // 8 月: 収入 250000・支出 80000 + 38000 + 20000 = 138000 → 期末 212000。9 月の期首は 112000 と書いてある（食い違い）
        write("months/2026-09.yaml", "id: M-2026-09\nyearMonth: \"2026-09\"\nprev: M-2026-08\nopening: 112000")
        // 10 月: 9 月の収入 250000・支出 80000 + 45000 = 125000 → 112000 + 125000 = 237000（一致）
        write("months/2026-10.yaml", "id: M-2026-10\nyearMonth: \"2026-10\"\nprev: M-2026-09\nopening: 237000")
        write(
            "tx/2026-08.yaml",
            """
            entries:
              - { id: TX-0801, kind: income, amount: 250000, category: salary, month: M-2026-08 }
              - { id: TX-0802, kind: expense, amount: 80000, category: rent, month: M-2026-08 }
              - { id: TX-0803, kind: expense, amount: 21000, category: food, month: M-2026-08 }
              - { id: TX-0804, kind: expense, amount: 17000, category: food, month: M-2026-08 }
              - { id: TX-0805, kind: expense, amount: 20000, category: util, month: M-2026-08 }
            """,
        )
        write(
            "tx/2026-09.yaml",
            """
            entries:
              - { id: TX-0901, kind: income, amount: 250000, category: salary, month: M-2026-09 }
              - { id: TX-0902, kind: expense, amount: 80000, category: rent, month: M-2026-09 }
              - { id: TX-0903, kind: expense, amount: 24000, category: food, month: M-2026-09 }
              - { id: TX-0904, kind: expense, amount: 21000, category: food, month: M-2026-09 }
            """,
        )
        write(
            "budgets/2026-09.yaml",
            """
            items:
              - { id: BG-2026-09-FOOD, month: M-2026-09, category: food, amount: 40000 }
              - { id: BG-2026-08-FOOD, month: M-2026-08, category: food, amount: 40000 }
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
    fun `起点を固定した照合の合計を2つ組み合わせて残高の連続を検査できる`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        // 9 月の期首は前月から計算した値（212000）と食い違う。10 月は一致
        val sep = ws.validate("months/2026-09.yaml").filter { it.severity == Severity.ERROR }
        assertEquals(1, sep.size, sep.map { it.message }.toString())
        assertTrue("2026-09 の期首残高 112000 は、前月から計算した 212000 と一致しません" in sep.single().message, sep.single().message)
        assertEquals(emptyList(), ws.validate("months/2026-10.yaml").filter { it.severity == Severity.ERROR }.map { it.message })
        // 前月が無い月は解が無く判定しない
        assertEquals(emptyList(), ws.validate("months/2026-08.yaml").filter { it.severity == Severity.ERROR }.map { it.message })
    }

    @Test
    fun `解にわたるallの合計で予算超過を検査できる`() {
        val ws = Workspace.open(dir)
        val warnings = ws.validate("budgets/2026-09.yaml").filter { it.severity == Severity.WARNING }
        // 9 月の食費 45000 > 40000 は警告、8 月の食費 38000 は予算内
        assertEquals(listOf("2026-09 の食費 45000 が予算 40000 を超えています"), warnings.map { it.message.substringBefore(" — ") })
    }
}
