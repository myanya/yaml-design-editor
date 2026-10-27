package dev.toros.yde.app.core

import dev.toros.yde.app.skill.Importer
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.scalarText
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
 * 家計簿の試作（labs/kakeibo）の基準を守るテスト。README「わざと入れてある食い違い」の件数、ビューと skill が読めること、
 * 取り込みのプレビュー（採番・費目の規則）、クロス集計と「列を広げる書き方」が同じ表になること。
 * labs は読み取り専用に扱い、一時ディレクトリへ写してから開く。
 */
class KakeiboLabTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-kakeibo")
        val source = Path.of("labs/kakeibo")
        Files.walk(source).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `検査の件数は README の基準どおり（エラー 2・警告 1）`() {
        val ws = Workspace.open(dir)
        val issues = ws.files.flatMap { ws.validate(ws.relPath(it)) }
        assertEquals(2, issues.count { it.severity == Severity.ERROR }, issues.toString())
        assertEquals(1, issues.count { it.severity == Severity.WARNING }, issues.toString())
        // ビューと skill の定義は誤りなく読める
        val views = Views.load(dir, ws.schemas)
        assertTrue(views.errors.isEmpty(), views.errors.toString())
        assertEquals(
            setOf("monthly-summary", "transactions", "month-by-category", "month-by-category-b", "month-by-kind", "budget-vs-actual"),
            views.views.map { it.id }.toSet(),
        )
        val skills = Skills.load(dir)
        assertTrue(skills.errors.isEmpty(), skills.errors.toString())
        assertEquals(setOf("import-smbc", "import-smbc-month"), skills.skills.map { it.id }.toSet())
    }

    @Test
    fun `CSV から月の文書を作ってから取引を取り込むと 検証つきの適用が通る`() {
        val ws = Workspace.open(dir)
        val csv = dir.resolve("inbox/smbc-sample.csv")
        val month = Importer.build(ws, Skills.load(dir).skill("import-smbc-month")!!, csv)
        assertTrue(month.ok, month.errors.toString())
        assertEquals(listOf("book/months/M-2021-08.yaml"), month.created)
        val root = month.changeSet.changes.single().content!!
        assertEquals("M-2021-08", scalarText(getAt(root, parseYPath("id"))))
        assertEquals("2021-08", scalarText(getAt(root, parseYPath("yearMonth"))))
        assertEquals("0", scalarText(getAt(root, parseYPath("opening"))))
        assertTrue(ws.apply(month.changeSet, requireValid = true).ok)

        val after = Workspace.open(dir)
        val tx = Importer.build(after, Skills.load(dir).skill("import-smbc")!!, csv)
        assertTrue(tx.ok, tx.errors.toString())
        val applied = after.apply(tx.changeSet, requireValid = true)
        assertTrue(applied.ok, applied.results.joinToString { "${it.file}: ${it.message} ${it.issues}" })
        // 取り込んだ 2 件は未分類のまま → 警告 2 件増える（README の筋書き）
        val again = Workspace.open(dir)
        val issues = again.files.flatMap { again.validate(again.relPath(it)) }
        assertEquals(2, issues.count { it.severity == Severity.ERROR })
        assertEquals(3, issues.count { it.severity == Severity.WARNING })
    }

    @Test
    fun `クロス集計と列を広げる書き方は同じ表になる`() {
        val ws = Workspace.open(dir)
        val views = Views.load(dir, ws.schemas)
        fun table(id: String): Map<String, Map<String, String>> =
            Views.resolve(views.view(id)!!, ws.index, ws::treeOf)
                .filter { it.occurrences.isNotEmpty() }  // 合計の行は除く
                .associate { row -> row.id to row.cells.associate { it.column.label to Views.cellText(it.value) } }
        val matrix = table("month-by-category")
        val expanded = table("month-by-category-b")
        assertEquals(matrix.keys, expanded.keys)
        matrix.forEach { (month, cells) ->
            // 費目の列（合計を除く）は、どちらの書き方でも同じ値
            cells.filterKeys { it != "合計" }.forEach { (label, value) -> assertEquals(value, expanded.getValue(month)[label], "$month / $label") }
            assertEquals(cells["合計"], expanded.getValue(month)["合計"], "$month / 合計")
        }
        // 2026-09 の内訳（README の値）
        assertEquals("250000", matrix.getValue("M-2026-09")["給与"])
        assertEquals("45000", matrix.getValue("M-2026-09")["食費"])
        assertEquals("80000", matrix.getValue("M-2026-09")["家賃"])
    }

    @Test
    fun `取り込みのプレビューは採番と費目の規則が効く`() {
        val ws = Workspace.open(dir)
        val skill = Skills.load(dir).skill("import-smbc")!!
        val first = Importer.build(ws, skill, dir.resolve("inbox/smbc-sample.csv"))
        assertTrue(first.ok, first.errors.toString())
        assertEquals(listOf("book/tx/2021-08.yaml"), first.created)
        assertEquals(1, first.skippedRows)
        val entries = first.changeSet.changes.single().content!!.let { getAt(it, parseYPath("entries")) as YNode.Seq }.items
        assertEquals(listOf("TX-20210821-01", "TX-20210831-01"), entries.map { scalarText(getAt(it, parseYPath("id"))) })
        assertEquals(listOf("CAT-UNSORTED", "CAT-UNSORTED"), entries.map { scalarText(getAt(it, parseYPath("category"))) })

        val second = Importer.build(ws, skill, dir.resolve("inbox/smbc-sample-2.csv"))
        assertTrue(second.ok, second.errors.toString())
        val categories = second.changeSet.changes.single().content!!.let { getAt(it, parseYPath("entries")) as YNode.Seq }.items
            .map { scalarText(getAt(it, parseYPath("category"))) }
        assertEquals(listOf("CAT-FOOD", "CAT-UTIL", "CAT-SALARY", "CAT-RENT"), categories)
        assertEquals("2021-09-03", YamlIO.toAny(getAt(second.changeSet.changes.single().content!!, parseYPath("entries[0].date"))!!))
    }
}
