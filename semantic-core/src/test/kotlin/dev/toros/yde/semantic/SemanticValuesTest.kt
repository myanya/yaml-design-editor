package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.FileNames
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 1つの環境での値の式の評価（P31・R31-01。SemanticValues）: クエリの外で「いまの要素」を前提に、
 * 短い書き方（`{パス}` を含む文字列）と完全な書き方（値の式）が同じ評価器に落ちることを固定する。
 */
class SemanticValuesTest {

    private val doc = DocumentId("30_basic-design/screen-list.yaml")
    private val tree = YamlIO.parse(
        """
        screens:
          - id: SCR-ROOM-LIST
            name: 空き状況照会画面
            domain: 予約
            owner: { team: 予約/受付 }
        """.trimIndent()
    )

    private fun env(reserved: Map<String, String> = mapOf("id" to "SCR-ROOM-LIST", "title" to "空き状況照会画面"), fallback: String? = null, holeValue: (String) -> String = { it }) =
        SemanticValues.Environment(
            tree = { d -> tree.takeIf { d == doc } },
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "SCR-ROOM-LIST", "空き状況照会画面", "screen", doc, parseYPath("screens[0]")),
            currentOccurrence = SemanticExpression.Occurrence(doc, parseYPath("screens[0]")),
            reserved = reserved,
            holeValue = holeValue,
            fallback = fallback,
        )

    @Test
    fun `短い書き方はいまの要素の値と予約の名前で穴を埋める`() {
        val expr = ValueExpr.parseValue("40_detail-design/screens/{id}_{name}.yaml", "filePattern")
        val side = SemanticValues.evaluate(expr, env())!!
        assertEquals("40_detail-design/screens/SCR-ROOM-LIST_空き状況照会画面.yaml", side.text)
        // 出どころ: 要素の項目の穴は、その値が書かれた場所を指す。予約の名前は場所を持たない
        val name = side.inputs.single { it.placeholder == "name" }
        assertEquals(doc, name.document)
        assertEquals(parseYPath("screens[0].name"), name.path)
        assertNull(side.inputs.single { it.placeholder == "id" }.document)
        // 入れ子のパスも書ける
        assertEquals("予約/受付.yaml", SemanticValues.evaluate(ValueExpr.parseValue("{owner.team}.yaml", "x"), env())!!.text)
    }

    @Test
    fun `予約の名前は要素の項目より先に埋まる`() {
        // 要素に name という項目があっても、{title} は表示の解決結果（予約）が先
        val expr = ValueExpr.parseValue("{title}", "x")
        assertEquals("タイトル", SemanticValues.evaluate(expr, env(reserved = mapOf("title" to "タイトル")))!!.text)
        // 予約が無ければ要素の項目
        assertEquals("空き状況照会画面", SemanticValues.evaluate(ValueExpr.parseValue("{name}", "x"), env(reserved = emptyMap()))!!.text)
    }

    @Test
    fun `完全な書き方はofを省略するといまの要素を読みwithで計算した値を穴に差し込める`() {
        val expr = ValueExpr.parseValue(
            mapOf(
                "format" to "screens/{dir}/{id}.yaml",
                "with" to mapOf("dir" to mapOf("map" to mapOf("path" to "domain"), "using" to mapOf("予約" to "reservation"))),
            ),
            "filePattern",
        )
        val side = SemanticValues.evaluate(expr, env())!!
        assertEquals("screens/reservation/SCR-ROOM-LIST.yaml", side.text)
        // with で差し込んだ値の出どころは、計算の元の値が書かれた場所
        assertEquals(parseYPath("screens[0].domain"), side.inputs.single { it.placeholder == "dir" }.path)
        // of を省略した読み出しと計算
        assertEquals("8", SemanticValues.evaluate(ValueExpr.parseValue(mapOf("length" to mapOf("path" to "name")), "x"), env())!!.text)
        assertEquals("空き状況照会画面", SemanticValues.evaluate(ValueExpr.parseValue(mapOf("first" to listOf(mapOf("path" to "missing"), mapOf("path" to "name"))), "x"), env())!!.text)
    }

    @Test
    fun `埋まらない穴があれば得られないとしてその名前を残す`() {
        val expr = ValueExpr.parseValue("screens/{id}_{code}_{owner.name}.yaml", "filePattern")
        val result = SemanticValues.evaluateDetailed(expr, env())
        assertNull(result.side)
        assertEquals(listOf("code", "owner.name"), result.missing)
        // 代表値（fallback）があれば、埋まらない穴をそれで埋める（定義の点検用）
        val filled = SemanticValues.evaluateDetailed(expr, env(fallback = "X"))
        assertEquals("screens/SCR-ROOM-LIST_X_X.yaml", filled.side!!.text)
        assertEquals(listOf("code", "owner.name"), filled.missing)
        // いまの要素が無い環境では、of を省略した読み出しは得られない
        val nowhere = SemanticValues.Environment(tree = { null })
        assertNull(SemanticValues.evaluate(ValueExpr.parseValue("{name}", "x"), nowhere))
    }

    @Test
    fun `穴に入る値はholeValueで直せる`() {
        val expr = ValueExpr.parseValue("screens/{id}_{owner.team}.yaml", "filePattern")
        val side = SemanticValues.evaluate(expr, env(holeValue = FileNames::safeToken))!!
        assertEquals("screens/SCR-ROOM-LIST_予約-受付.yaml", side.text)
        assertEquals("予約-受付", side.inputs.single { it.placeholder == "owner.team" }.value)
        assertEquals("a-b-c", FileNames.safeToken(" a/b:c "))
    }
}
