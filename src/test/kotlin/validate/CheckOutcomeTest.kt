package validate

import agent.Staging
import core.PatchOp
import core.Workspace
import model.parseYPath
import semantic.CheckOutcome
import semantic.CheckReason
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 検査の結果の4つ（違反・充足・保留・対象外）と、指摘の出どころ（P27 段A・意図的差分 D-26。BACKLOG UX-113・UX-114）。
 * - 指摘は、どの検査の・どの要素についての・どの配り方の指摘かをデータとして持つ
 * - 判定を保留した検査は、指摘にはならないが、検証の結果に内訳つきで残る（準備完了レポート・検査の状況に出る）
 * - 提出ゲート no-new-errors は、出どころを持つ指摘を（検査, 要素, 配り方）で見分け、根拠に関わる要素が増えたときだけ新しいエラーとする
 * - コードで実装された検査（構造の検証・参照の解決など）の指摘も出どころを持ち、場所の番号がずれても同じ指摘として見分けられる
 *   （P27 段B の残り・意図的差分 D-29）
 */
class CheckOutcomeTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-outcome")
        write(
            "_schemas/screens.yaml",
            """
            id: screens
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                      - key: uses
                        type: array
                        item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            constraints:
              - id: uses-existing
                description: 実在するユースケースを1つ以上利用すること
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                expect: exists
                level: error
                status: "利用 {count} 件"
              - id: no-deprecated-use
                description: 廃止済みユースケースを参照しないこと
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                where:
                  - { of: uc, path: deprecated, equals: true }
                expect: absent
                level: error
            """,
        )
        // 画面の詳細文書（同じ ID の、もう1つの出現）: エラー級の検査は、こちらにも同格の案内が出る
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            match: "details/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: layout, type: string }
            """,
        )
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "ucs/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: deprecated, type: boolean }
            """,
        )
        write("ucs/UC-1.yaml", "id: UC-1\nname: ユース1")
        write("ucs/UC-D1.yaml", "id: UC-D1\nname: 廃止1\ndeprecated: true")
        write("ucs/UC-D2.yaml", "id: UC-D2\nname: 廃止2\ndeprecated: true")
        write("ucs/UC-D3.yaml", "id: UC-D3\nname: 廃止3\ndeprecated: true")
        write(
            "screens.yaml",
            """
            screens:
              - id: SCR-OK
                name: 正常画面
                uses: [UC-1]
              - id: SCR-EMPTY
                name: 空画面
                uses: []
              - id: SCR-DANGLING
                name: 宙参照画面
                uses: [UC-GONE]
              - id: SCR-OLD
                name: 廃止を使う画面
                uses: [UC-1, UC-D1, UC-D2]
            """,
        )
        write("details/SCR-EMPTY.yaml", "id: SCR-EMPTY\nlayout: 一覧")
        write("details/SCR-DANGLING.yaml", "id: SCR-DANGLING\nlayout: 一覧")
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
    fun `指摘は検査と対象の要素と配り方をデータとして持つ`() {
        val ws = Workspace.open(dir)
        val report = ws.validate("screens.yaml").single { "SCR-EMPTY" in it.message && "成立していません" in it.message }
        val source = assertNotNull(report.source)
        assertEquals("screens:constraints[0]#0", source.rule?.key)
        assertEquals("uses-existing", source.ruleId)
        assertEquals("SCR-EMPTY", source.subjectId)
        assertEquals(IssueDelivery.REPORT, source.delivery)
        // 同じ要素の他の出現への案内は、同じ検査・同じ要素で、配り方だけが違う
        val notice = ws.validate("details/SCR-EMPTY.yaml").single { "SCR-EMPTY" in it.message && "制約違反" in it.message }
        assertEquals(Severity.ERROR, notice.severity)
        assertEquals(source.rule, notice.source?.rule)
        assertEquals("SCR-EMPTY", notice.source?.subjectId)
        assertEquals(IssueDelivery.NOTICE, notice.source?.delivery)
        // 根拠に関わった要素（あってはならない解の相手）は全件を持つ
        val old = ws.validate("screens.yaml").single { "SCR-OLD" in it.message && "成立しています" in it.message }
        assertEquals(listOf("UC-D1", "UC-D2"), old.source?.evidence)
        // 参照の解決の指摘（コードで実装された検査）: 対象の要素は参照を書いた要素・場所は番号を除いたキーの並び・根拠は参照の相手
        val unresolved = ws.validate("screens.yaml").single { "UC-GONE" in it.message }
        val coded = assertNotNull(unresolved.source)
        assertTrue(coded.coded)
        assertEquals("(code):reference#0", coded.rule?.key)
        assertEquals("unresolved", coded.kind)
        assertEquals("SCR-DANGLING", coded.subjectId)
        assertEquals("uses[]", coded.within)
        assertEquals(IssueDelivery.REF, coded.delivery)
        assertEquals(listOf("UC-GONE"), coded.evidence)
        // 定義体の検査の指摘は、コードで実装された検査ではない
        assertFalse(source.coded)
    }

    @Test
    fun `判定を保留した検査は指摘にならず内訳つきで残る`() {
        val ws = Workspace.open(dir)
        val result = ws.validateDetailed("screens.yaml")
        // 宙参照の画面: 「利用が無い」のか「参照の書き間違い」なのかを決められない → 指摘しない（CALC-17）が、保留として残る
        assertTrue(result.issues.none { "SCR-DANGLING" in it.message && "成立していません" in it.message })
        val withheld = result.withheld.single()
        assertEquals("SCR-DANGLING", withheld.subjectId)
        assertEquals("uses-existing", withheld.ruleId)
        assertEquals(CheckReason.UNRESOLVED_REFERENCE, withheld.reason)
        assertTrue("保留" in withheld.text && "相手の見つからない参照" in withheld.text, withheld.text)
        // 報告先でない文書（詳細文書）では集めない（二重に数えない）
        assertTrue(ws.validateDetailed("details/SCR-DANGLING.yaml").withheld.isEmpty())
    }

    @Test
    fun `検査の状況は保留を充足と表示しない`() {
        val ws = Workspace.open(dir)
        val schema = ws.schemas.first { it.id == "screens" }
        val root = ws.load("screens.yaml").root
        fun status(id: String) = Validator.ruleStatuses(id, schema, ws.index, root, ws.schemas, ws::treeOf).single()
        assertEquals(CheckOutcome.SATISFIED, status("SCR-OK").outcome)
        assertTrue(status("SCR-OK").satisfied)
        assertEquals(CheckOutcome.VIOLATED, status("SCR-EMPTY").outcome)
        val dangling = status("SCR-DANGLING")
        assertEquals(CheckOutcome.WITHHELD, dangling.outcome)
        assertFalse(dangling.satisfied)
        assertTrue(dangling.text.startsWith("判定保留"), dangling.text)
    }

    @Test
    fun `準備完了レポートは案内を重ねて数えず保留の件数を出す`() {
        val ws = Workspace.open(dir)
        val report = Readiness.report(ws)
        val empty = report.entities.single { it.id == "SCR-EMPTY" }
        // 違反は1件（詳細文書の側に出る同格の案内は、報告先の指摘の写しなので数えない）
        assertEquals(1, empty.errors)
        assertFalse(empty.ready)
        val dangling = report.entities.single { it.id == "SCR-DANGLING" }
        assertEquals(1, dangling.withheld)
        assertEquals(1, report.withheld.size)
    }

    @Test
    fun `提出ゲートは同じ検査と要素のエラーを既存として扱い根拠が増えたときだけ新しいとする`() {
        val ws = Workspace.open(dir)
        // 1) 廃止ユースケースの利用を1つ減らす（文面は変わるが、同じ検査 × 要素のエラーが残っているだけ）→ 新しいエラーなし
        Staging(ws).let { staging ->
            staging.applyOps("screens.yaml", listOf(PatchOp.Delete(parseYPath("screens[3].uses[2]"))))
            assertTrue(staging.validateAll().getValue("screens.yaml").any { "SCR-OLD" in it.message && it.severity == Severity.ERROR })
            assertTrue(staging.newErrors().isEmpty(), staging.newErrors().toString())
        }
        // 2) 先頭に要素を挿入して場所の番号がずれても（SCR-OLD は screens[3] → screens[4]）、既存のエラーは既存のまま。
        //    参照の解決の指摘（SCR-DANGLING の UC-GONE。文面に場所を含む）も、出どころで見分けるので既存のまま（意図的差分 D-29 ①）
        Staging(ws).let { staging ->
            staging.applyOps(
                "screens.yaml",
                listOf(PatchOp.Insert(parseYPath("screens[0]"), YamlIO.parse("id: SCR-NEW\nname: 新画面\nuses: [UC-1]"))),
            )
            assertTrue(staging.newErrors().isEmpty(), staging.newErrors().toString())
        }
        // 3) 廃止ユースケースの利用を増やす（根拠に関わる要素が増える）→ 新しいエラー
        Staging(ws).let { staging ->
            staging.applyOps("screens.yaml", listOf(PatchOp.Insert(parseYPath("screens[3].uses[3]"), YamlIO.parse("UC-D3"))))
            val fresh = staging.newErrors().getValue("screens.yaml")
            assertEquals(listOf("UC-D1", "UC-D2", "UC-D3"), fresh.single().source?.evidence)
        }
        // 4) 別の要素に同じ検査の違反を作る → 新しいエラー
        Staging(ws).let { staging ->
            staging.applyOps("screens.yaml", listOf(PatchOp.Insert(parseYPath("screens[0].uses[1]"), YamlIO.parse("UC-D1"))))
            val fresh = staging.newErrors().getValue("screens.yaml")
            assertEquals("SCR-OK", fresh.single().source?.subjectId)
        }
    }

    @Test
    fun `提出ゲートは構造と参照のエラーも場所のずれに左右されずに見分ける`() {
        // 前からある構造のエラー（uses が配列でない）を1つ足しておく
        Files.writeString(
            dir.resolve("screens.yaml"),
            Files.readString(dir.resolve("screens.yaml")) + "  - id: SCR-BAD\n    name: 形の違う画面\n    uses: oops\n",
        )
        val ws = Workspace.open(dir)
        val structural = ws.validate("screens.yaml").single { "配列である必要" in it.message }
        assertEquals("(code):structure#0", structural.source?.rule?.key)
        assertEquals("type", structural.source?.kind)
        assertEquals("SCR-BAD", structural.source?.subjectId)
        assertEquals("uses", structural.source?.within)
        // 1) 先頭に正しい要素を挿入する: 構造のエラーも参照のエラーも場所がずれるが、新しいエラーは無い
        Staging(ws).let { staging ->
            staging.applyOps(
                "screens.yaml",
                listOf(PatchOp.Insert(parseYPath("screens[0]"), YamlIO.parse("id: SCR-NEW\nname: 新画面\nuses: [UC-1]"))),
            )
            assertTrue(staging.newErrors().isEmpty(), staging.newErrors().toString())
        }
        // 2) 同じ要素に、相手の見つからない参照をもう1つ、前からある参照より手前に足す: 足した参照だけが新しいエラー
        Staging(ws).let { staging ->
            staging.applyOps("screens.yaml", listOf(PatchOp.Insert(parseYPath("screens[2].uses[0]"), YamlIO.parse("UC-GONE2"))))
            val fresh = staging.newErrors().getValue("screens.yaml")
            assertEquals(listOf("UC-GONE2"), fresh.single().source?.evidence)
        }
        // 3) 別の要素に同じ種類の構造のエラーを作る → 新しいエラー（前からある SCR-BAD のエラーは数えない）
        Staging(ws).let { staging ->
            staging.applyOps(
                "screens.yaml",
                listOf(PatchOp.Insert(parseYPath("screens[0]"), YamlIO.parse("id: SCR-BAD2\nname: 形の違う画面2\nuses: oops"))),
            )
            val fresh = staging.newErrors().getValue("screens.yaml")
            // 新しい要素のエラーだけ（構造のエラーと、利用が1つも無いことへの検査の違反）
            assertEquals(setOf("SCR-BAD2"), fresh.map { it.source?.subjectId }.toSet())
            assertEquals(1, fresh.count { it.source?.rule?.key == "(code):structure#0" && it.source?.kind == "type" })
        }
    }
}
