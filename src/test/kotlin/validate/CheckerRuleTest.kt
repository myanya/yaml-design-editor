package validate

import core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 専用チェッカーを定義から使う（意味プリミティブ SP-13。P29 Phase 6 第4段・意図的差分 D-23）のテスト。
 * 到達の計算と循環の検出はコードの専用チェッカーで、どの値を渡して使うか・指摘の種類ごとの文面・重大度は
 * 定義（YAML）の側が宣言する。組込みの語彙（flow / acyclic）の判定の内容は FlowTest・GraphIntegrityTest が従来どおり保証する。
 * ここでは、利用者が同じチェッカーを自分の検査から使えること（組込みは特権を持たない）を確かめる。
 */
class CheckerRuleTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-checker-rule")
        write(
            "_schemas/step.yaml",
            """
            id: step
            match: "steps.yaml"
            entity: { type: step, idPattern: "ST-*", defines: true }
            constraints:
              - id: reachable-steps
                description: どの手順も最初の手順から辿れること
                checker: flow
                args:
                  nodes: { entity: step }
                  edges: { from: { relation: step-from }, to: { relation: step-to } }
                  start: { relation: first-step }
                messages:
                  unreachable: "手順「{id}」へ辿り着く流れがありません（最初の手順: {related}）"
                level: error
            root:
              type: object
              fields:
                - key: steps
                  type: array
                  item: { type: object, fields: [{ key: id, type: id, required: true }, { key: name, type: string, role: title }] }
            """,
        )
        write(
            "_schemas/order.yaml",
            """
            id: order
            match: "order.yaml"
            root:
              type: object
              fields:
                - { key: first, type: ref, relation: first-step }
                - key: flows
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: step-from }
                      - { key: to, type: ref, relation: step-to }
            """,
        )
        write("steps.yaml", "steps:\n- { id: ST-1, name: 受付 }\n- { id: ST-2, name: 審査 }\n- { id: ST-3, name: 保管 }")
        write("order.yaml", "first: ST-1\nflows:\n- { from: ST-1, to: ST-2 }")
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
    fun `利用者の検査から専用チェッカーを使え文面を書いた種類だけが指摘される`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val issues = ws.validate("steps.yaml").filter { it.severity == Severity.ERROR }
        // ST-3 は到達できない。ST-2 と ST-3 は行き止まりでもあるが、deadEnd の文面を書いていないので指摘されない
        assertEquals(
            listOf("手順「ST-3」へ辿り着く流れがありません（最初の手順: ST-1（受付）） — どの手順も最初の手順から辿れること"),
            issues.map { it.message },
        )
        assertEquals(listOf<Any>("steps", 2), issues.single().path)
        assertEquals(listOf("ST-1"), issues.single().relatedIds)
    }

    @Test
    fun `循環の検出も利用者の検査から使え指摘は参照が書かれた場所に出る`() {
        write(
            "_schemas/house-rules.yaml",
            """
            id: house-rules
            kind: library
            constraints:
              - checker: cycle
                args: { relation: step-to }
                messages:
                  cycle: "手順の流れが循環しています（{path}）"
                level: warning
            """,
        )
        // step-to の参照は ID を持たない行に書かれているため、手順どうしの循環にはならない——手順に直接の参照を足して循環を作る
        write(
            "_schemas/step.yaml",
            """
            id: step
            match: "steps.yaml"
            entity: { type: step, idPattern: "ST-*", defines: true }
            root:
              type: object
              fields:
                - key: steps
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: next, type: ref, relation: step-to }
            """,
        )
        write("steps.yaml", "steps:\n- { id: ST-1, next: ST-2 }\n- { id: ST-2, next: ST-1 }\n- { id: ST-3 }")
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val issues = ws.validate("steps.yaml").filter { "循環" in it.message }
        assertEquals(2, issues.size, issues.toString())
        assertTrue(issues.all { it.severity == Severity.WARNING && it.message == "手順の流れが循環しています（ST-1 → ST-2 → ST-1）" }, issues.toString())
        assertEquals(setOf(listOf<Any>("steps", 0, "next"), listOf<Any>("steps", 1, "next")), issues.map { it.path }.toSet())
    }

    @Test
    fun `知らないチェッカーと指摘の種類と渡す値の誤りは定義エラーになる`() {
        fun errorsWith(constraint: String): List<String> {
            write(
                "_schemas/bad.yaml",
                "id: bad\nmatch: \"bad/*.yaml\"\nroot: { type: object, fields: [{ key: id, type: id }] }\nconstraints:\n$constraint",
            )
            return Workspace.open(dir).schemaErrors.filter { "bad.yaml" in it }
        }
        val unknown = errorsWith("  - checker: temporal-logic\n    args: {}\n    messages: { x: y }")
        assertTrue(unknown.any { "checker は flow / cycle のいずれかです" in it }, unknown.toString())
        val kind = errorsWith("  - checker: cycle\n    args: { relation: r }\n    messages: { unreachable: x }")
        assertTrue(kind.any { "「unreachable」という指摘の種類はありません" in it }, kind.toString())
        val args = errorsWith("  - checker: cycle\n    args: { relations: r }\n    messages: { cycle: x }")
        assertTrue(args.any { "未知のキーです: relations" in it }, args.toString())
        val mixed = errorsWith("  - checker: cycle\n    args: { relation: r }\n    messages: { cycle: x }\n    match: [{ node: { entity: a } }]")
        assertTrue(mixed.any { "未知のキーです: match" in it }, mixed.toString())
    }
}
