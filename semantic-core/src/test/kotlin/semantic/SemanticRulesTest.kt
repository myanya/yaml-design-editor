package semantic

import model.DocumentId
import model.YNode
import schema.ConstraintRule
import schema.EntityDecl
import schema.StructuralSchemaParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 検査の判定（SemanticRules。P27 段A・CALC-31）: 検査1件 × 要素1つの結果は、違反・充足・保留・対象外のどれかになる。
 * semantic-core 単体で「検査の宣言 → 結果」が閉じることを固定する（文面と配り方は呼び出し側）。
 */
class SemanticRulesTest {

    private val schema = StructuralSchemaParser.parse(
        """
        id: node
        root:
          type: object
          fields:
            - { key: id, type: id }
            - { key: max, type: string }
            - key: uses
              type: array
              item: { type: ref, relation: uses }
        """.trimIndent(),
        fallbackId = "node",
    )

    private val entities = listOf(
        EntityDecl(type = "screen", idPattern = "SCR-*", defines = true),
        EntityDecl(type = "usecase", idPattern = "UC-*", defines = true),
        EntityDecl(type = "viewpoint", idPattern = "VP-*", defines = true),
    )

    private val trees = linkedMapOf(
        "ok.yaml" to "id: SCR-OK\nmax: 10\nuses: [UC-1]",
        "empty.yaml" to "id: SCR-EMPTY\nuses: []",
        "dangling.yaml" to "id: SCR-DANGLING\nmax: 5\nuses: [UC-GONE]",
        "big.yaml" to "id: SCR-BIG\nmax: 30\nuses: [UC-1]",
        "text.yaml" to "id: SCR-TEXT\nmax: たくさん\nuses: [UC-1]",
        "uc1.yaml" to "id: UC-1\nmax: 20",
        "uc2.yaml" to "id: UC-2",
    ).mapValues { YamlIO.parse(it.value) }

    private val snapshot: SemanticSnapshot = SemanticEngine.build(
        SemanticInput(1, trees.map { (name, tree) -> SemanticDocument(DocumentId(name), tree, schema, "node", entities) })
    )

    private val loader: (DocumentId) -> YNode? = { trees[it.value] }

    private fun rule(yaml: String): ConstraintRule = ConstraintRule.parse(YamlIO.parseToRaw(yaml.trimIndent()), "rule")

    private fun evaluate(rule: ConstraintRule, id: String): SemanticRuleResult =
        SemanticRules.evaluate(snapshot.ids, snapshot.refs, snapshot.graph, rule, id, loader = loader)

    private val usesExisting = rule(
        """
        match:
          - node: { entity: screen, bind: scr }
          - edge: { relation: uses }
          - node: { entity: usecase, bind: uc }
        expect: exists
        """
    )

    @Test
    fun `existsは解があれば充足 なければ違反 候補が失われていれば保留`() {
        assertEquals(CheckOutcome.SATISFIED, evaluate(usesExisting, "SCR-OK").outcome)
        assertEquals(CheckOutcome.VIOLATED, evaluate(usesExisting, "SCR-EMPTY").outcome)
        val dangling = evaluate(usesExisting, "SCR-DANGLING")
        assertEquals(CheckOutcome.WITHHELD, dangling.outcome)
        assertEquals(CheckReason.UNRESOLVED_REFERENCE, dangling.reason)
    }

    @Test
    fun `検査の対象でない要素は対象外`() {
        val result = evaluate(usesExisting, "UC-1")
        assertEquals(CheckOutcome.NOT_APPLICABLE, result.outcome)
        assertEquals(CheckReason.NOT_IN_SCOPE, result.reason)
    }

    @Test
    fun `countは下限の不足だけを保留にし 上限の超過は違反のまま`() {
        val atLeastOne = rule(
            """
            match:
              - node: { entity: screen, bind: scr }
              - edge: { relation: uses }
              - node: { entity: usecase, bind: uc }
            count: { min: 1 }
            """
        )
        assertEquals(CheckOutcome.WITHHELD, evaluate(atLeastOne, "SCR-DANGLING").outcome)
        assertEquals(CheckOutcome.VIOLATED, evaluate(atLeastOne, "SCR-EMPTY").outcome)
        val none = rule(
            """
            match:
              - node: { entity: screen, bind: scr }
              - edge: { relation: uses }
              - node: { entity: usecase, bind: uc }
            count: { max: 0 }
            """
        )
        assertEquals(CheckOutcome.VIOLATED, evaluate(none, "SCR-OK").outcome)
        // 相手の見つからない参照は、解には数えない（実在しない要素は上限を超えさせない）
        assertEquals(CheckOutcome.SATISFIED, evaluate(none, "SCR-DANGLING").outcome)
    }

    @Test
    fun `checkは比べる値が無ければ対象外 数値として読めなければ保留`() {
        val withinLimit = rule(
            """
            match:
              - node: { entity: screen, bind: scr }
              - edge: { relation: uses }
              - node: { entity: usecase, bind: uc }
            check:
              - lte: [{ of: scr, path: max }, { of: uc, path: max }]
            """
        )
        assertEquals(CheckOutcome.SATISFIED, evaluate(withinLimit, "SCR-OK").outcome)
        assertEquals(CheckOutcome.VIOLATED, evaluate(withinLimit, "SCR-BIG").outcome)
        // 値はあるが数値として読めない: 確かめられなかった
        val text = evaluate(withinLimit, "SCR-TEXT")
        assertEquals(CheckOutcome.WITHHELD, text.outcome)
        assertEquals(CheckReason.INCOMPARABLE_VALUE, text.reason)
        // 比べる組（解）が無い: 確かめるものが無い。ただし候補が失われていれば保留
        assertEquals(CheckReason.NO_SOLUTION, evaluate(withinLimit, "SCR-EMPTY").reason)
        assertEquals(CheckReason.UNRESOLVED_REFERENCE, evaluate(withinLimit, "SCR-DANGLING").reason)
        // 比べる値が書かれていない（任意の項目）: 対象外
        val bothWritten = rule(
            """
            match:
              - node: { entity: usecase, bind: uc }
            check:
              - lte: [{ of: uc, path: max }, 100]
            """
        )
        assertEquals(CheckOutcome.SATISFIED, evaluate(bothWritten, "UC-1").outcome)
        val noValue = evaluate(bothWritten, "UC-2")
        assertEquals(CheckOutcome.NOT_APPLICABLE, noValue.outcome)
        assertEquals(CheckReason.NO_VALUE, noValue.reason)
    }

    @Test
    fun `forAllは相手の集まりが空なら対象外`() {
        val perViewpoint = rule(
            """
            match:
              - node: { entity: screen, bind: scr }
            forAll:
              node: { entity: viewpoint, bind: vp }
              from: scr
              steps:
                - edge: { relation: uses }
                - node: { same: vp }
            """
        )
        val result = evaluate(perViewpoint, "SCR-OK")
        assertEquals(CheckOutcome.NOT_APPLICABLE, result.outcome)
        assertEquals(CheckReason.EMPTY_COLLECTION, result.reason)
    }
}
