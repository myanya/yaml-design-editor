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
 * 遷移検証（R28-04。architecture/graph-query.md「クエリコア」）のテスト。
 * 宣言エッジ集合の到達閉包による到達不能・行き止まり・宣言外遷移の検出——ノードの定義出現への報告・
 * 終端宣言による除外・開始データなしの判定保留・未解決参照による候補喪失時の降格（CALC-17）を検証する。
 */
class FlowTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-flow")
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            flow:
              - id: screen-flow
                description: 全画面は開始画面から到達でき、行き止まりは終端宣言があること
                nodes: { entity: screen }
                edges: { from: { relation: t-from }, to: { relation: t-to } }
                start: { relation: s-start }
                terminal: { relation: s-terminal }
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
            """,
        )
        write(
            "_schemas/transition.yaml",
            """
            id: transition
            match: "transitions.yaml"
            root:
              type: object
              fields:
                - key: start
                  type: array
                  item: { type: ref, relation: s-start, scope: { schemaIds: [screen] } }
                - key: terminal
                  type: array
                  item: { type: ref, relation: s-terminal, scope: { schemaIds: [screen] } }
                - key: transitions
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: t-from, scope: { schemaIds: [screen] } }
                      - { key: to, type: ref, relation: t-to, scope: { schemaIds: [screen] } }
            """,
        )
        write("screens.yaml", "screens:\n- { id: SCR-A, name: 一覧 }\n- { id: SCR-B, name: 入力 }\n- { id: SCR-C, name: 詳細 }")
        write(
            "transitions.yaml",
            "start: [SCR-A]\ntransitions:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-B, to: SCR-A }",
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
    fun `到達不能と行き止まりはノードの定義出現に報告される`() {
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens.yaml")
        val unreachable = issues.single { "到達できません" in it.message }
        assertEquals(Severity.WARNING, unreachable.severity)
        assertTrue("SCR-C" in unreachable.message, unreachable.message)
        assertTrue("SCR-A（一覧）" in unreachable.message, unreachable.message)
        assertTrue("行き止まりは終端宣言があること" in unreachable.message, unreachable.message)
        assertEquals(listOf("SCR-A"), unreachable.relatedIds)
        val deadEnd = issues.single { "出る遷移がなく" in it.message }
        assertTrue("SCR-C" in deadEnd.message, deadEnd.message)
        // エッジ文書側には報告しない（ノードの定義出現のみ）
        assertTrue(ws.validate("transitions.yaml").none { "到達できません" in it.message || "出る遷移" in it.message })
    }

    @Test
    fun `詳細化出現の文書には定義出現への報告が案内される`() {
        // R28-05a（UX-96）の配送: 報告先はノードの定義出現のみ・同じIDの詳細化出現には INFO の案内
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
            """,
        )
        write("details/SCR-C.yaml", "id: SCR-C")
        val ws = Workspace.open(dir)
        val notices = ws.validate("details/SCR-C.yaml")
        val unreachable = notices.single { "到達できません" in it.message }
        assertEquals(Severity.INFO, unreachable.severity)
        assertTrue("報告先: 定義出現 screens.yaml" in unreachable.message, unreachable.message)
        assertTrue("行き止まりは終端宣言があること" in unreachable.message, unreachable.message)
        assertEquals(listOf("SCR-C", "SCR-A"), unreachable.relatedIds)
        val deadEnd = notices.single { "出る遷移がなく" in it.message }
        assertEquals(Severity.INFO, deadEnd.severity)
        assertEquals(listOf("SCR-C"), deadEnd.relatedIds)
    }

    @Test
    fun `全ノードが到達可能で終端宣言があれば黙る`() {
        write(
            "transitions.yaml",
            "start: [SCR-A]\nterminal: [SCR-C]\ntransitions:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-B, to: SCR-C }",
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("screens.yaml").none { "到達できません" in it.message || "出る遷移" in it.message })
    }

    @Test
    fun `開始データがなければ到達不能は判定保留になり行き止まりは判定される`() {
        write("transitions.yaml", "transitions:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-B, to: SCR-A }")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens.yaml")
        assertTrue(issues.none { "到達できません" in it.message }, issues.joinToString { it.message })
        assertTrue(issues.single { "出る遷移がなく" in it.message }.message.contains("SCR-C"))
    }

    @Test
    fun `宣言外遷移はusageエッジと宣言エッジの差で検出される`() {
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            flow:
              - id: screen-flow
                nodes: { entity: screen }
                edges: { from: { relation: t-from }, to: { relation: t-to } }
                usage: { from: { relation: u-from }, to: { relation: u-to } }
                level: error
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
            """,
        )
        write(
            "_schemas/usage.yaml",
            """
            id: usage
            match: "usage.yaml"
            root:
              type: object
              fields:
                - key: links
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: u-from, scope: { schemaIds: [screen] } }
                      - { key: to, type: ref, relation: u-to, scope: { schemaIds: [screen] } }
            """,
        )
        // A→B は宣言済み・A→C が宣言外
        write("usage.yaml", "links:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-A, to: SCR-C }")
        val ws = Workspace.open(dir)
        val issue = ws.validate("screens.yaml").single { "宣言にない遷移" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("SCR-A" in issue.message && "SCR-C（詳細）" in issue.message, issue.message)
        assertTrue("SCR-B" !in issue.message, issue.message)
    }

    @Test
    fun `エッジの参照先が未解決なら到達不能は黙り行き止まりは出のあるノードを責めない`() {
        // B→（未解決）でエッジ候補が失われる → 到達不能（解の不足系）は降格。
        // B は from 側が解決しているので「出るエッジあり」＝行き止まりにならない。C は依然行き止まり
        write(
            "transitions.yaml",
            "start: [SCR-A]\ntransitions:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-B, to: SCR-GONE }",
        )
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens.yaml")
        assertTrue(issues.none { "到達できません" in it.message }, issues.joinToString { it.message })
        val deadEnds = issues.filter { "出る遷移がなく" in it.message }
        assertEquals(1, deadEnds.size, deadEnds.joinToString { it.message })
        assertTrue("SCR-C" in deadEnds.single().message)
    }

    @Test
    fun `終端の参照先が未解決なら行き止まりは黙る`() {
        write(
            "transitions.yaml",
            "start: [SCR-A]\nterminal: [SCR-GONE]\ntransitions:\n- { from: SCR-A, to: SCR-B }\n- { from: SCR-B, to: SCR-C }",
        )
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens.yaml")
        assertTrue(issues.none { "出る遷移がなく" in it.message }, issues.joinToString { it.message })
        // 到達不能はエッジ・開始が健在なので判定される（全ノード到達可能＝指摘なし）
        assertTrue(issues.none { "到達できません" in it.message })
    }

    @Test
    fun `状態遷移にも同じ宣言で一般化できる`() {
        write(
            "_schemas/status.yaml",
            """
            id: status
            match: "statuses.yaml"
            entity: { type: status, idPattern: "ST-*", defines: true }
            flow:
              - id: status-flow
                description: 全状態は初期状態から到達できること
                nodes: { entity: status }
                edges: { from: { relation: st-from }, to: { relation: st-to } }
                start: { relation: st-start }
                terminal: { relation: st-terminal }
                level: error
            root:
              type: object
              fields:
                - key: statuses
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/status-transition.yaml",
            """
            id: status-transition
            match: "status-transitions.yaml"
            root:
              type: object
              fields:
                - key: start
                  type: array
                  item: { type: ref, relation: st-start, scope: { schemaIds: [status] } }
                - key: terminal
                  type: array
                  item: { type: ref, relation: st-terminal, scope: { schemaIds: [status] } }
                - key: transitions
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: st-from, scope: { schemaIds: [status] } }
                      - { key: to, type: ref, relation: st-to, scope: { schemaIds: [status] } }
            """,
        )
        write("statuses.yaml", "statuses:\n- { id: ST-DRAFT, name: 下書き }\n- { id: ST-DONE, name: 完了 }\n- { id: ST-VOID, name: 無効 }")
        write(
            "status-transitions.yaml",
            "start: [ST-DRAFT]\nterminal: [ST-DONE]\ntransitions:\n- { from: ST-DRAFT, to: ST-DONE }",
        )
        val ws = Workspace.open(dir)
        val issues = ws.validate("statuses.yaml")
        val unreachable = issues.single { "到達できません" in it.message }
        assertEquals(Severity.ERROR, unreachable.severity)
        assertTrue("ST-VOID" in unreachable.message, unreachable.message)
        // 画面のフローと状態のフローは独立に判定される（screens 側は従来どおり）
        assertTrue(ws.validate("screens.yaml").any { "SCR-C" in it.message && "到達できません" in it.message })
    }

    @Test
    fun `flowの未知キーとedgesの形式はスキーマ定義エラーになる`() {
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            flow:
              - id: screen-flow
                nodes: { entity: screen }
                edges: { from: { relation: t-from }, to: { relation: t-to } }
                goal: { relation: s-goal }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "flow[0]" in it && "goal" in it }, ws.schemaErrors.toString())

        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            flow:
              - id: screen-flow
                nodes: { entity: screen }
                edges: { from: t-from, to: t-to }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        val ws2 = Workspace.open(dir)
        // flow は組込み定義体が作る語彙（P29 Phase 6 第4段）——誤りの場所は「語彙の要素（定義の名前）.args.…」で示される
        assertTrue(ws2.schemaErrors.any { "flow[0]" in it && "edges.from" in it && "relation" in it }, ws2.schemaErrors.toString())
    }
}
