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
 * 到達の計算（遷移検証）はコードの専用チェッカーで、どの値を渡して使うか・指摘の種類ごとの文面・重大度は
 * 定義（YAML）の側が宣言する。組込みの語彙 flow の判定の内容は FlowTest が従来どおり保証する
 * （循環の検出は専用チェッカーではなく、クエリの「何段でも辿る線」で書く——ReachabilityTest・GraphIntegrityTest）。
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
    fun `知らないチェッカーと指摘の種類と渡す値の誤りは定義エラーになる`() {
        fun errorsWith(constraint: String): List<String> {
            write(
                "_schemas/bad.yaml",
                "id: bad\nmatch: \"bad/*.yaml\"\nroot: { type: object, fields: [{ key: id, type: id }] }\nconstraints:\n$constraint",
            )
            return Workspace.open(dir).schemaErrors.filter { "bad.yaml" in it }
        }
        val flowArgs = "    args: { nodes: { entity: step }, edges: { from: { relation: a }, to: { relation: b } } }"
        val unknown = errorsWith("  - checker: temporal-logic\n    args: {}\n    messages: { x: y }")
        assertTrue(unknown.any { "checker は flow のいずれかです" in it }, unknown.toString())
        // 循環の検出は専用チェッカーではなくなった（edge の repeat と node の same で書く。意図的差分 D-24）
        val cycle = errorsWith("  - checker: cycle\n    args: { relation: r }\n    messages: { cycle: x }")
        assertTrue(cycle.any { "checker は flow のいずれかです" in it }, cycle.toString())
        val kind = errorsWith("  - checker: flow\n$flowArgs\n    messages: { looped: x }")
        assertTrue(kind.any { "「looped」という指摘の種類はありません" in it }, kind.toString())
        val args = errorsWith("  - checker: flow\n    args: { node: { entity: step } }\n    messages: { deadEnd: x }")
        assertTrue(args.any { "未知のキーです: node" in it }, args.toString())
        val mixed = errorsWith("  - checker: flow\n$flowArgs\n    messages: { deadEnd: x }\n    match: [{ node: { entity: a } }]")
        assertTrue(mixed.any { "未知のキーです: match" in it }, mixed.toString())
    }
}
