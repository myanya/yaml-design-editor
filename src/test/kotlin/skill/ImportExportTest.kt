package skill

import core.Workspace
import validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * インポート・エクスポート（R5-02/03）とテンプレートパック（R5-04）の結合テスト。
 * P5の完了条件「Excel台帳をインポート→YAML原本で運用→Excel帳票を再出力の往復が原本主義で回る」を
 * ledger テンプレートの上で一気通貫に検証する。
 */
class ImportExportTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-skill")
        val result = TemplatePacks.apply(dir, TemplatePacks.pack("ledger")!!)
        assertTrue(result.skipped.isEmpty())
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun ws() = Workspace.open(dir)

    // ---------------------------------------------------------------- テンプレートパック（R5-04）

    @Test
    fun `ledger template initializes a valid workspace with all definitions`() {
        val ws = ws()
        // 全文書が検証エラー0
        ws.files.forEach { file ->
            val errors = ws.validate(ws.relPath(file)).filter { it.severity == Severity.ERROR }
            assertTrue(errors.isEmpty(), "${ws.relPath(file)}: $errors")
        }
        // エージェント・アクティビティ・評価・skill定義が読める
        assertTrue(agent.Definitions.load(dir).errors.isEmpty())
        assertTrue(agent.Evals.load(dir).errors.isEmpty())
        val skills = Skills.load(dir)
        assertTrue(skills.errors.isEmpty(), skills.errors.toString())
        assertEquals(setOf("import-issues", "export-issues", "export-issues-md"), skills.skills.map { it.id }.toSet())
    }

    // ---------------------------------------------------------------- 完了条件: Excel往復

    @Test
    fun `excel round trip - export import update and re-export under yaml canonicality`() {
        val skills = Skills.load(dir)
        val exportSkill = skills.skill("export-issues")!!
        val importSkill = skills.skill("import-issues")!!

        // 1. YAML原本 → Excel帳票を出力
        val exported = Exporter.run(ws(), exportSkill)
        assertTrue(Files.isRegularFile(exported.file))
        assertEquals(2, exported.rows)

        // 2. 出力したExcelをそのまま再インポート → 変更なし（往復の無損失）
        val roundTrip = Importer.build(ws(), importSkill, exported.file)
        assertTrue(roundTrip.ok, roundTrip.errors.toString())
        assertTrue(roundTrip.changeSet.changes.isEmpty(), "往復で差分が出ました: ${roundTrip.updated}")
        assertEquals(listOf("issues/ISSUE-001.yaml", "issues/ISSUE-002.yaml"), roundTrip.unchanged.sorted())

        // 3. 人間がYAML側に棚卸しメモを追記（原本側の運用）
        val issue1 = dir.resolve("issues/ISSUE-001.yaml")
        Files.writeString(issue1, Files.readString(issue1) + "triage_notes:\n- comment: 対応方針を確認する\n")

        // 4. Excel側で編集された台帳（状態変更＋新規行）を取り込む
        val edited = Table(
            headers = listOf("ID", "件名", "状態", "優先度", "担当", "タグ"),
            rows = listOf(
                listOf("ISSUE-001", "ログイン画面のエラーメッセージが不親切", "対応中", 2L, "yamada", "ux,login"),
                listOf("ISSUE-002", "一覧画面の表示が遅い", "in-progress", 1L, "sato", "performance"),
                listOf("ISSUE-003", "エクスポートで文字化けする", "未着手", 3L, null, "export"),
            ),
        )
        val editedFile = dir.resolve("edited.xlsx")
        Tables.write(edited, editedFile, TableFormat.XLSX, "課題一覧")
        val preview = Importer.build(ws(), importSkill, editedFile)
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(listOf("issues/ISSUE-003.yaml"), preview.created)
        assertEquals(listOf("issues/ISSUE-001.yaml"), preview.updated)     // 状態のみ変化（対応中→in-progress）
        assertEquals(listOf("issues/ISSUE-002.yaml"), preview.unchanged)

        val applied = ws().apply(preview.changeSet, requireValid = true)
        assertTrue(applied.ok, applied.results.joinToString { "${it.file}: ${it.message} ${it.issues}" })

        // 5. 原本主義の検証: マップ外の項目（description・人間の追記）はインポートで消えない
        val updatedText = Files.readString(issue1)
        assertTrue("in-progress" in updatedText)
        assertTrue("対応方針を確認する" in updatedText)          // 人間の追記が保持される
        assertTrue("パスワード誤り" in updatedText)              // description が保持される
        // 値変換: map（未着手→open）・type int・split が効いている
        val issue3 = Files.readString(dir.resolve("issues/ISSUE-003.yaml"))
        assertTrue("status: open" in issue3, issue3)
        assertTrue("priority: 3" in issue3, issue3)
        assertTrue("- export" in issue3, issue3)

        // 6. Excel帳票を原本から再出力（派生成果物の再生成）
        val reExported = Exporter.run(ws(), exportSkill)
        assertEquals(3, reExported.rows)
        val table = Tables.readXlsx(reExported.file, "課題一覧", 1)
        assertTrue(table.rows.any { it[0] == "ISSUE-003" && it[2] == "open" })
    }

    // ---------------------------------------------------------------- インポートの検証とエラー

    @Test
    fun `import reports declarative errors without producing a changeset`() {
        val def = Skills.load(dir).skill("import-issues")!!.import!!
        // 必須列の値なし＋整数でない優先度＋キー重複
        val bad = Table(
            headers = listOf("ID", "件名", "状態", "優先度", "担当", "タグ"),
            rows = listOf(
                listOf("ISSUE-101", null, null, "高", null, null),     // 件名必須が空・優先度が整数でない
                listOf("ISSUE-102", "A", null, null, null, null),
                listOf("ISSUE-102", "B", null, null, null, null),      // 同じIDが2行
            ),
        )
        val preview = Importer.build(ws(), def, bad)
        assertFalse(preview.ok)
        assertTrue(preview.changeSet.changes.isEmpty())
        assertTrue(preview.errors.any { "必須列「件名」" in it }, preview.errors.toString())
        assertTrue(preview.errors.any { "整数として解釈できません" in it }, preview.errors.toString())
        assertTrue(preview.errors.any { "同じファイル" in it }, preview.errors.toString())
    }

    @Test
    fun `import fails when referenced column is missing`() {
        val importSkill = Skills.load(dir).skill("import-issues")!!
        val table = Table(listOf("番号", "件名"), listOf(listOf("1", "x")))
        val preview = Importer.build(ws(), importSkill.import!!, table)
        assertFalse(preview.ok)
        assertTrue(preview.errors.any { "列「ID」が見つかりません" in it }, preview.errors.toString())
    }

    // ---------------------------------------------------------------- Markdownエクスポート

    @Test
    fun `markdown export renders a table from documents`() {
        val result = Exporter.run(ws(), Skills.load(dir).skill("export-issues-md")!!)
        val text = Files.readString(result.file)
        assertTrue(text.lineSequence().first().startsWith("| ID |"))
        assertTrue("ISSUE-001" in text && "ISSUE-002" in text)
    }
}
