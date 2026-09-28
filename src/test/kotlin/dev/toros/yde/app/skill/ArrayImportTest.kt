package dev.toros.yde.app.skill

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.patch.PatchOp
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 行をひな型で組み立てて配列へ追記するインポート（build・target.path・target.id・target.key・input.encoding）。
 * 題材は銀行の入出金明細の CSV（ID が無い・年月日が別の列・金額に ¥ と桁区切り・摘要が空白で埋められている）。
 */
class ArrayImportTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-import-array")
        write(".yde/workspace.yaml", "ledger: book\n")
        write(".yde/schemas/transactions.yaml", SCHEMA)
        write(".yde/skills/import-bank.yaml", SKILL)
        write("book/tx/2021-08.yaml", EXISTING)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, text: String) {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private fun ws() = Workspace.open(dir)

    private fun skill(id: String): SkillDef {
        val loaded = Skills.load(dir)
        assertTrue(loaded.errors.isEmpty(), loaded.errors.toString())
        return loaded.skill(id) ?: error("skill $id がありません")
    }

    private fun expected(yaml: String): Any? = YamlIO.parseToRaw(yaml.trimIndent())

    @Test
    fun `build で行を組み立て 配列の末尾へ追記し ID を採番する`() {
        val csv = dir.resolve("bank.csv")
        Files.writeString(csv, CSV)
        val preview = Importer.build(ws(), skill("import-bank"), csv)
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(1, preview.skippedRows, "月が空の行（期首の残高行）は飛ばす")
        assertEquals(listOf("book/tx/2021-09.yaml"), preview.created)
        assertEquals(listOf("book/tx/2021-08.yaml"), preview.updated)
        assertEquals("追加 2 件", preview.details["book/tx/2021-08.yaml"])

        // 既存の配列の末尾へ Insert。ID は既存の TX-20210821-01 の次・空白だけの摘要は書かない
        val aug = preview.changeSet.changes.single { it.file == "book/tx/2021-08.yaml" }
        val inserted = aug.ops.map { it as PatchOp.Insert }
        assertEquals(listOf(parseYPath("entries[1]"), parseYPath("entries[2]")), inserted.map { it.path })
        assertEquals(
            expected("""{ id: TX-20210821-02, date: "2021-08-21", kind: income, amount: 10000 }"""),
            YamlIO.toAny(inserted[0].value),
        )
        assertEquals(
            expected("""{ id: TX-20210831-01, date: "2021-08-31", kind: expense, amount: 10000, memo: 振込 トウキヨウ }"""),
            YamlIO.toAny(inserted[1].value),
        )
        // 無いファイルは配列ごと作る
        val sep = preview.changeSet.changes.single { it.file == "book/tx/2021-09.yaml" }
        assertTrue(sep.create)
        assertEquals(
            expected("""{ entries: [ { id: TX-20210901-01, date: "2021-09-01", kind: income, amount: 3000, memo: ミセ } ] }"""),
            YamlIO.toAny(sep.content!!),
        )

        val applied = ws().apply(preview.changeSet, requireValid = true)
        assertTrue(applied.ok, applied.results.joinToString { "${it.file}: ${it.message} ${it.issues}" })
        val text = Files.readString(dir.resolve("book/tx/2021-08.yaml"))
        assertTrue("TX-20210821-02" in text && "コーヒー" in text, text)

        // 同じ CSV をもう一度: key が同じ明細は飛ばし、変更セットは空（手で直した値を戻さない）
        val again = Importer.build(ws(), skill("import-bank"), csv)
        assertTrue(again.ok, again.errors.toString())
        assertTrue(again.changeSet.changes.isEmpty())
        assertEquals(listOf("book/tx/2021-08.yaml", "book/tx/2021-09.yaml"), again.unchanged.sorted())
        assertEquals("追加 0 件（同じ明細 2 件を飛ばした）", again.details["book/tx/2021-08.yaml"])
    }

    @Test
    fun `文字コードを指定して読む 合わない文字コードは読めないことで分かる`() {
        write(
            ".yde/skills/import-bank-sjis.yaml",
            SKILL.replace("id: import-bank", "id: import-bank-sjis").replace("input: { format: csv }", "input: { format: csv, encoding: windows-31j }"),
        )
        val csv = dir.resolve("bank-sjis.csv")
        Files.write(csv, CSV.removePrefix("﻿").toByteArray(Charset.forName("windows-31j")))
        val preview = Importer.build(ws(), skill("import-bank-sjis"), csv)
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals("追加 2 件", preview.details["book/tx/2021-08.yaml"])

        val wrong = Importer.build(ws(), skill("import-bank"), csv)
        assertFalse(wrong.ok)
        assertTrue(wrong.errors.single().contains("文字コード UTF-8 として読めません"), wrong.errors.toString())

        // 見出しがまるごと違う表（別の銀行の CSV など）は、ひな型が読む列がどれも無いことで分かる
        val other = Table(listOf("日付", "摘要内容", "支払い金額"), listOf(listOf("2021/8/21", "x", "100")))
        val mismatch = Importer.build(ws(), skill("import-bank").import!!, other)
        assertFalse(mismatch.ok)
        assertTrue(mismatch.errors.single().contains("どれも見出しにありません"), mismatch.errors.toString())
    }

    @Test
    fun `build で同じ内容に組み立てられた行は 1 つの文書に畳まれ 内容が違えば誤り`() {
        // 期間の見出し（対象日の年月）から月の文書を 1 つ作る。どの行も同じ月なので 1 文書
        write(
            ".yde/skills/import-month.yaml",
            """
            id: import-month
            kind: import
            input: { format: csv }
            target: { file: "book/months/{id}.yaml" }
            build:
              ${'$'}when: { path: 月, exists: true }
              ${'$'}then:
                id: { ${'$'}format: "M-{ym}", ${'$'}with: { ym: { ${'$'}date: { ${'$'}format: "{対象日（自）_年}-{月}-1" }, ${'$'}as: yyyy-MM } } }
            """ + "\n",
        )
        val csv = dir.resolve("bank.csv")
        Files.writeString(csv, CSV)
        val preview = Importer.build(ws(), skill("import-month"), csv)
        // 8 月の行 2 つは同じ内容 → 1 文書。9 月の行は別の文書。月の無い行は飛ばす
        assertTrue(preview.ok, preview.errors.toString())
        assertEquals(listOf("book/months/M-2021-08.yaml", "book/months/M-2021-09.yaml"), preview.created.sorted())
        assertEquals(1, preview.skippedRows)

        // 同じ文書に違う内容 → 誤り
        write(
            ".yde/skills/import-month-bad.yaml",
            """
            id: import-month-bad
            kind: import
            input: { format: csv }
            target: { file: "book/months/{id}.yaml" }
            build:
              ${'$'}when: { path: 月, exists: true }
              ${'$'}then:
                id: { ${'$'}format: "M-{ym}", ${'$'}with: { ym: { ${'$'}date: { ${'$'}format: "{対象日（自）_年}-{月}-1" }, ${'$'}as: yyyy-MM } } }
                day: { ${'$'}copy: 日 }
            """ + "\n",
        )
        val bad = Importer.build(ws(), skill("import-month-bad"), csv)
        assertFalse(bad.ok)
        assertTrue(bad.errors.single().contains("同じファイル"), bad.errors.toString())
    }

    @Test
    fun `定義の誤りは読み込みで分かる`() {
        fun parse(yaml: String) = Skills.parseSkill(YamlIO.parseToRaw(yaml.trimIndent()) as Map<*, *>, dir.resolve("x.yaml"))
        val head = "id: a\nkind: import\ninput: { format: csv }\n"
        assertFailsWith<IllegalArgumentException>("mapping と build は一緒に書けない") {
            parse(head + "target: { file: a.yaml }\nmapping: [ { column: A, path: a } ]\nbuild: { a: 1 }")
        }
        assertFailsWith<IllegalArgumentException>("採番は配列への取り込みだけ") {
            parse(head + "target: { file: a.yaml, id: { path: id, prefix: T- } }\nbuild: { a: 1 }")
        }
        assertFailsWith<IllegalArgumentException>("同じ明細の見分け方は配列への取り込みだけ") {
            parse(head + "target: { file: a.yaml, key: [a] }\nbuild: { a: 1 }")
        }
        assertFailsWith<IllegalStateException>("分からない文字コード") {
            parse("id: a\nkind: import\ninput: { format: csv, encoding: nope }\ntarget: { file: a.yaml }\nbuild: { a: 1 }")
        }
        assertFailsWith<IllegalArgumentException>("予約ディレクトリへは取り込めない") {
            parse(head + "target: { file: _schemas/a.yaml }\nbuild: { a: 1 }")
        }
        // 正しい定義は読める（build の代わりに mapping でも配列へ追記できる）
        val ok = parse(head + "target: { file: a.yaml, path: rows, key: [name] }\nmapping: [ { column: 名前, path: name } ]")
        assertEquals(parseYPath("rows"), ok.import!!.targetPath)
        assertEquals(listOf(parseYPath("name")), ok.import!!.key)
    }

    companion object {
        private val SCHEMA = """
            id: transactions
            name: 取引
            kind: document
            match: "book/tx/*.yaml"
            entity: { type: transaction, idPattern: "TX-*", defines: true }
            root:
              type: object
              fields:
                - key: entries
                  label: 明細
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, label: ID, type: id, required: true }
                      - { key: date, label: 日付, type: date, required: true }
                      - { key: kind, label: 区分, type: enum, values: [income, expense], required: true }
                      - { key: amount, label: 金額, type: int, required: true }
                      - { key: memo, label: メモ, type: string }
        """.trimIndent() + "\n"

        private val SKILL = """
            id: import-bank
            kind: import
            input: { format: csv }
            target:
              file: { ${'$'}format: "book/tx/{ym}.yaml", ${'$'}with: { ym: { ${'$'}date: { ${'$'}copy: date }, ${'$'}as: yyyy-MM } } }
              path: entries
              id: { path: id, prefix: { ${'$'}format: "TX-{ymd}-", ${'$'}with: { ymd: { ${'$'}date: { ${'$'}copy: date }, ${'$'}as: yyyyMMdd } } }, digits: 2 }
              key: [date, kind, amount, memo]
            build:
              ${'$'}when: { path: 月, exists: true }
              ${'$'}then:
                date: { ${'$'}date: { ${'$'}format: "{対象日（自）_年}-{月}-{日}" } }
                kind: { ${'$'}first: [ { ${'$'}when: { path: ご入金, exists: true }, ${'$'}then: income }, expense ] }
                amount: { ${'$'}number: { ${'$'}first: [ { ${'$'}copy: ご入金 }, { ${'$'}copy: ご出金 } ] } }
                memo: { ${'$'}trim: { ${'$'}copy: 摘要 } }
        """.trimIndent() + "\n"

        private val EXISTING = """
            entries:
              - { id: TX-20210821-01, date: "2021-08-21", kind: expense, amount: 500, memo: コーヒー }
        """.trimIndent() + "\n"

        /** 銀行の CSV（先頭に BOM。2 行目は月の無い期首の残高行・摘要は全角の空白で埋められている）。 */
        private val CSV = "﻿" + """
            対象日（自）_年,月,日,ご入金,ご出金,摘要,残高
            2021,,,,,,¥0
            2021,8,21,"¥10,000",,　　　　　　　　,"¥10,000"
            2021,8,31,,"¥10,000",振込　　　　　　　トウキヨウ　　　　,¥0
            2021,9,1,"¥3,000",,ミセ　　　,"¥3,000"
        """.trimIndent() + "\n"
    }
}
