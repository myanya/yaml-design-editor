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
