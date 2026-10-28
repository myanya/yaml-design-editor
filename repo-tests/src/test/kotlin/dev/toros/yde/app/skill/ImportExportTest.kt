package dev.toros.yde.app.skill

import dev.toros.yde.agent.Definitions
import dev.toros.yde.agent.Evals
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.app.ext.ReadOptions
import dev.toros.yde.app.ext.Table
import dev.toros.yde.desktop.ext.DesktopExtensions
import dev.toros.yde.format.docx.DocxFormat
import dev.toros.yde.format.text.CsvFormat
import dev.toros.yde.format.text.JsonFormat
import dev.toros.yde.format.xlsx.XlsxFormat
import dev.toros.yde.workspace.InitRequest
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.WorkspaceTemplates
import dev.toros.yde.yaml.validate.Severity
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
    init { Extensions.install(DesktopExtensions.registry) }   // 形式（csv・xlsx …）は入口の一覧で登録する

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-skill")
        // テンプレート「課題管理」（templates/issues）を展開した状態から始める
        val template = WorkspaceTemplates.get("issues") ?: error("テンプレート issues が資源にありません")
        val result = WorkspaceInit.apply(dir, WorkspaceInit.plan(dir, InitRequest.FromTemplate(template)))
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
        assertTrue(Definitions.load(dir).errors.isEmpty())
        assertTrue(Evals.load(dir).errors.isEmpty())
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
        Tables.write(edited, editedFile, "課題一覧")
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
        val table = XlsxFormat.read(Files.readAllBytes(reExported.file), ReadOptions(sheet = "課題一覧", headerRow = 1))
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

    // ---------------------------------------------------------------- 入出力形式の拡張（R9-05）

    @Test
    fun `json export preserves cell types and round trips through readJson`() {
        Files.writeString(dir.resolve(".yde/skills/export-json.yaml"), """
            id: export-json
            name: JSON出力
            kind: export
            source: { match: "issues/**/*.{yaml,yml}" }
            columns:
              - { header: ID, path: id }
              - { header: 件名, path: title }
              - { header: 優先度, path: priority }
            output: { file: out/issues.json }
        """.trimIndent())
        val result = Exporter.run(ws(), Skills.load(dir).skill("export-json")!!)
        val table = JsonFormat.readJson(Files.readString(result.file), null)
        assertEquals(listOf("ID", "件名", "優先度"), table.headers)
        // 型が保持される（優先度は数値のまま）
        assertTrue(table.rows.any { it[0] == "ISSUE-001" && it[2] is Long }, table.rows.toString())
    }

    @Test
    fun `html export renders an escaped standalone table`() {
        Files.writeString(dir.resolve("issues/ISSUE-009.yaml"), """
            id: ISSUE-009
            title: <b>タグを含む</b> & 記号
        """.trimIndent() + "\n")
        Files.writeString(dir.resolve(".yde/skills/export-html.yaml"), """
            id: export-html
            name: HTML出力
            kind: export
            source: { match: "issues/**/*.{yaml,yml}" }
            columns:
              - { header: ID, path: id }
              - { header: 件名, path: title }
            output: { file: out/issues.html }
        """.trimIndent())
        val result = Exporter.run(ws(), Skills.load(dir).skill("export-html")!!)
        val text = Files.readString(result.file)
        assertTrue("<table>" in text)
        assertTrue("ISSUE-009" in text)
        // セルはエスケープされる（HTMLとして解釈されない）
        assertTrue("&lt;b&gt;タグを含む&lt;/b&gt; &amp; 記号" in text, text)
    }

    @Test
    fun `docx import reads a word table and feeds the import pipeline`() {
        // Word 文書（表1つ）をPOIで作成する
        val docxFile = dir.resolve("input.docx")
        org.apache.poi.xwpf.usermodel.XWPFDocument().use { doc ->
            val table = doc.createTable(3, 6)
            val headers = listOf("ID", "件名", "状態", "優先度", "担当", "タグ")
            headers.forEachIndexed { i, h -> table.getRow(0).getCell(i).setText(h) }
            listOf(
                listOf("ISSUE-201", "Wordからの課題", "未着手", "2", "suzuki", "word,import"),
                listOf("ISSUE-202", "2件目", "対応中", "1", "", ""),
            ).forEachIndexed { r, row ->
                row.forEachIndexed { c, v -> table.getRow(r + 1).getCell(c).setText(v) }
            }
            Files.newOutputStream(docxFile).use { doc.write(it) }
        }
        val table = DocxFormat.read(Files.readAllBytes(docxFile), ReadOptions())
        assertEquals(listOf("ID", "件名", "状態", "優先度", "担当", "タグ"), table.headers)
        assertEquals(2, table.rows.size)
        // 既存のインポート定義（列マッピング・値辞書・split）にそのまま流せる
        val def = Skills.load(dir).skill("import-issues")!!.import!!
        val preview = Importer.build(ws(), def, table)
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(listOf("issues/ISSUE-201.yaml", "issues/ISSUE-202.yaml"), preview.created.sorted())
        val applied = ws().apply(preview.changeSet, requireValid = true)
        assertTrue(applied.ok)
        val text = Files.readString(dir.resolve("issues/ISSUE-201.yaml"))
        assertTrue("status: open" in text, text)     // 値辞書（未着手→open）
        assertTrue("- word" in text, text)           // split による配列化
    }

    @Test
    fun `view referenced export writes the view rows and columns as a table`() {
        Files.createDirectories(dir.resolve(".yde/views"))
        Files.writeString(dir.resolve(".yde/views/issues-board.yaml"), """
            id: issues-board
            name: 課題ボード
            rows: { idPattern: "ISSUE-*" }
            columns:
              - { label: 件名, path: title }
              - { label: 状態, path: status }
        """.trimIndent())
        Files.writeString(dir.resolve(".yde/skills/export-board.yaml"), """
            id: export-board
            name: ビューをそのまま帳票へ
            kind: export
            source: { view: issues-board }
            output: { file: out/board.csv }
        """.trimIndent())
        val result = Exporter.run(ws(), Skills.load(dir).skill("export-board")!!)
        assertEquals(2, result.rows)
        val table = CsvFormat.readCsv(Files.readString(result.file))
        assertEquals(listOf("id", "件名", "状態"), table.headers)
        assertTrue(table.rows.any { it[0] == "ISSUE-001" && it[1] == "ログイン画面のエラーメッセージが不親切" })
    }

    @Test
    fun `view referenced export rejects columns in the definition`() {
        Files.writeString(dir.resolve(".yde/skills/export-bad.yaml"), """
            id: export-bad
            kind: export
            source: { view: issues-board }
            columns:
              - { header: ID, path: id }
            output: { file: out/bad.csv }
        """.trimIndent())
        val loaded = Skills.load(dir)
        assertTrue(loaded.errors.any { "columns を書けません" in it }, loaded.errors.toString())
    }
}
