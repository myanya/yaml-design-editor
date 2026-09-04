package validate

import agent.Staging
import core.PatchOp
import core.Workspace
import model.parseYPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * パターン制約（R22-03。ARCHITECTURE §4.32）のテスト。
 * exists（起点ごとに解があること）／absent（解がないこと）・座標（起点の定義出現）・
 * activeFrom のフェーズ保留・提出ゲート（Staging.validateAll）への波及を検証する。
 */
class QueryConstraintTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-constraint")
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            constraints:
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
            constraints:
              - id: reachable
                description: いずれかの画面から利用されること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen }
                expect: exists
            """,
        )
        write("screens/SCR-1.yaml", "id: SCR-1\nname: 画面1\nuses:\n- UC-1")
        write("ucs/UC-1.yaml", "id: UC-1\nname: ユース1")
        write("ucs/UC-3.yaml", "id: UC-3\nname: 孤立ユース")
        write("ucs/UC-D.yaml", "id: UC-D\nname: 廃止ユース\ndeprecated: true")
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
    fun `exists constraint reports isolated entity at its defining occurrence`() {
        val ws = Workspace.open(dir)
        val isolated = ws.validate("ucs/UC-3.yaml")
        val issue = isolated.single { "パターンが成立していません" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("UC-3" in issue.message && "いずれかの画面から利用されること" in issue.message, issue.message)
        // 利用されているユースケース・廃止ユースケース（起点は screen 側の制約）は指摘されない
        assertTrue(ws.validate("ucs/UC-1.yaml").none { "パターン" in it.message })
        // UC-D は被参照ゼロのため exists 制約には掛かる（廃止でも「使われていない」判定は独立）
        assertTrue(ws.validate("ucs/UC-D.yaml").any { "パターンが成立していません" in it.message })
    }

    @Test
    fun `absent constraint reports pattern hit with rule level severity`() {
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-D")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens/SCR-2.yaml")
        val issue = issues.single { "パターンが成立しています" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("SCR-2" in issue.message && "廃止済み" in issue.message, issue.message)
        // 廃止ユースケースを使っていない画面は指摘されない
        assertTrue(ws.validate("screens/SCR-1.yaml").none { "パターンが成立しています" in it.message })
    }

    @Test
    fun `activeFrom defers judgement until the document reaches the phase`() {
        // フェーズ未到達（既定=先頭フェーズ）の間は判定しない。到達すると判定される
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "ucs/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            phases: [起草, 確定]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: phase, type: string }
                - { key: deprecated, type: boolean }
            constraints:
              - id: reachable
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen }
                expect: exists
                activeFrom: 確定
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("ucs/UC-3.yaml").none { "パターン" in it.message }, "フェーズ未到達では判定保留")
        write("ucs/UC-3.yaml", "id: UC-3\nname: 孤立ユース\nphase: 確定")
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.validate("ucs/UC-3.yaml").any { "パターンが成立していません" in it.message })
    }

    @Test
    fun `count制約は解の件数が下限未満のとき件数つきで指摘する`() {
        // 各ユースケースは2つ以上の画面から利用されること（R28-02 count）
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
            constraints:
              - id: used-twice
                description: 2つ以上の画面から利用されること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr }
                count: { min: 2 }
            """,
        )
        val ws = Workspace.open(dir)
        val issue = ws.validate("ucs/UC-1.yaml").single { "パターンの解" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("1 件" in issue.message && "min 2" in issue.message, issue.message)

        // 2画面目から参照されると解消される（解 = scr 束縛の distinct 件数）
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-1")
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.validate("ucs/UC-1.yaml").none { "パターンの解" in it.message })
    }

    @Test
    fun `count制約は上限超過も指摘する`() {
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
            constraints:
              - id: single-consumer
                description: 利用する画面は1つまでであること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr }
                count: { max: 1 }
                level: error
            """,
        )
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-1")
        val ws = Workspace.open(dir)
        val issue = ws.validate("ucs/UC-1.yaml").single { "パターンの解" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("2 件" in issue.message && "max 1" in issue.message, issue.message)
    }

    @Test
    fun `notEquals述語は値があり指定値でないときだけ成立する`() {
        // 参照するユースケースは status が approved 以外であってはならない（値なしは判定保留＝黙る）
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            constraints:
              - id: only-approved-use
                description: 承認済みでないユースケースを参照しないこと
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                where:
                  - { of: uc, path: status, notEquals: approved }
                expect: absent
                level: error
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
                - { key: status, type: string }
            """,
        )
        write("ucs/UC-A.yaml", "id: UC-A\nname: 承認済み\nstatus: approved")
        write("ucs/UC-B.yaml", "id: UC-B\nname: 起草中\nstatus: draft")
        write("screens/SCR-A.yaml", "id: SCR-A\nname: 画面A\nuses:\n- UC-A")
        write("screens/SCR-B.yaml", "id: SCR-B\nname: 画面B\nuses:\n- UC-B")
        val ws = Workspace.open(dir)
        // draft を参照する画面だけがエラー。approved 参照・status なし（UC-1）参照は黙る
        val issue = ws.validate("screens/SCR-B.yaml").single { "パターンが成立しています" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue(ws.validate("screens/SCR-A.yaml").none { "パターンが成立しています" in it.message })
        assertTrue(ws.validate("screens/SCR-1.yaml").none { "パターンが成立しています" in it.message }, "値なしは判定保留")
    }

    @Test
    fun `absent違反は該当の組を列挙し関連IDでジャンプできる`() {
        // R28-05（UX-98）: 違反したパターンの束縛（どの組で成立してしまったか）を明示する
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-D")
        val ws = Workspace.open(dir)
        val issue = ws.validate("screens/SCR-2.yaml").single { "パターンが成立しています" in it.message }
        assertTrue("該当の組" in issue.message, issue.message)
        assertTrue("scr=SCR-2" in issue.message && "uc=UC-D" in issue.message, issue.message)
        // relatedIds には相手側（UC-D）が入り、UIのIDジャンプが効く（自分自身は除く）
        assertTrue("UC-D" in issue.relatedIds && "SCR-2" !in issue.relatedIds, issue.relatedIds.toString())
    }

    @Test
    fun `count違反は解の組を列挙する`() {
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
            constraints:
              - id: used-twice
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr }
                count: { min: 2 }
            """,
        )
        val ws = Workspace.open(dir)
        val issue = ws.validate("ucs/UC-1.yaml").single { "パターンの解" in it.message }
        assertTrue("解の組" in issue.message && "scr=SCR-1" in issue.message, issue.message)
        assertTrue("SCR-1" in issue.relatedIds, issue.relatedIds.toString())
    }

    @Test
    fun `詳細化出現の文書には定義側の指摘がINFOで案内される`() {
        // R28-05（UX-96）: 指摘の報告先は定義出現のみだが、詳細化出現側にも案内を出す
        write(
            "_schemas/usecase-detail.yaml",
            """
            id: usecase-detail
            match: "ucdetails/*.yaml"
            entity: { type: usecase, idPattern: "UC-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: memo, type: text }
            """,
        )
        write("ucdetails/UC-3.yaml", "id: UC-3\nmemo: 詳細化のみの文書")
        val ws = Workspace.open(dir)
        val info = ws.validate("ucdetails/UC-3.yaml").single { "制約の指摘があります" in it.message }
        assertEquals(Severity.INFO, info.severity)
        assertTrue("ucs/UC-3.yaml" in info.message, info.message)  // 報告先（定義出現）の案内
        assertTrue("いずれかの画面から利用されること" in info.message, info.message)
        // 定義側の文書には従来どおり WARNING が出る（多重報告はしない＝INFO は詳細化側だけ）
        assertTrue(ws.validate("ucs/UC-3.yaml").any { "パターンが成立していません" in it.message })
        assertTrue(ws.validate("ucs/UC-3.yaml").none { "制約の指摘があります" in it.message })
    }

    @Test
    fun `エラー級の制約違反は出現側の文書にも同じ重大度で出る`() {
        // R28-05a 追補: 修正対象の文書（詳細化出現）を開いているとき INFO 止まりだと直せることに
        // 気づけない（オーナー指摘 2026-09-04）→ エラー級は出現側にも ERROR＋該当の組を出す
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 画面2\nuses:\n- UC-D")
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            match: "screendetails/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: memo, type: text }
            """,
        )
        write("screendetails/SCR-2.yaml", "id: SCR-2\nmemo: 詳細")
        val ws = Workspace.open(dir)
        val issue = ws.validate("screendetails/SCR-2.yaml").single { "制約違反" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("screens/SCR-2.yaml" in issue.message, issue.message)  // 報告先（定義出現）
        assertTrue("該当の組" in issue.message && "uc=UC-D" in issue.message, issue.message)
        assertTrue("UC-D" in issue.relatedIds, issue.relatedIds.toString())
        // 警告級（reachable）は従来どおり INFO の案内に留まる（詳細化文書のノイズを避ける）
        write("ucdetails2/UC-3.yaml", "id: UC-3\nmemo: x")
        write(
            "_schemas/usecase-detail2.yaml",
            """
            id: usecase-detail2
            match: "ucdetails2/*.yaml"
            entity: { type: usecase, idPattern: "UC-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: memo, type: text }
            """,
        )
        val ws2 = Workspace.open(dir)
        val info = ws2.validate("ucdetails2/UC-3.yaml").single { "制約の指摘" in it.message }
        assertEquals(Severity.INFO, info.severity)
    }

    @Test
    fun `expectとcountの排他はスキーマ定義エラーになる`() {
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
            constraints:
              - id: broken
                match:
                  - node: { entity: usecase, bind: uc }
                expect: exists
                count: { min: 1 }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "expect" in it && "count" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `submit gate rejects staged edit that violates an absent constraint`() {
        val ws = Workspace.open(dir)
        val staging = Staging(ws)
        // ステージング上で SCR-1 に廃止ユースケースへの参照を追加する → 提出ゲートの検証にERRORが乗る
        staging.applyOps(
            "screens/SCR-1.yaml",
            listOf(PatchOp.Insert(parseYPath("uses[1]"), model.YNode.Scalar("UC-D"))),
        )
        val issues = staging.validateAll()
        val fileIssues = issues["screens/SCR-1.yaml"].orEmpty()
        assertTrue(
            fileIssues.any { it.severity == Severity.ERROR && "パターンが成立しています" in it.message },
            issues.toString(),
        )
    }
}
