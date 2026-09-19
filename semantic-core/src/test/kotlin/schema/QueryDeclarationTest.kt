package schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P29 Phase 6 第2段で足した書き方（SP-02 否定と「または」・SP-03 参照の本数と集計・参照の束縛・語彙の with）のパースのテスト。
 * 書き間違いは定義エラー（実行時に黙って空にならない）。
 */
class QueryDeclarationTest {

    private fun rule(vararg extra: Pair<String, Any?>, match: List<Any?> = DEFAULT_MATCH): ConstraintRule =
        ConstraintRule.parse(mapOf("match" to match) + extra.toMap(), "c")

    @Test
    fun `nodeのentityは型の並びを書け、notはその絞り込みに合わないノードを残す`() {
        val step = QueryNodeStep.parse(mapOf("entity" to listOf("usecase", "batch"), "bind" to "s"), "m")
        assertEquals(listOf("usecase", "batch"), step.entities)
        assertEquals(listOf("screen"), QueryNodeStep.parse(mapOf("entity" to "screen"), "m").entities)
        val negated = QueryNodeStep.parse(mapOf("not" to mapOf("entity" to listOf("screen"))), "m")
        assertEquals(QueryNodeFilter(entities = listOf("screen")), negated.not)
        assertTrue(negated.hasFilter)
        // 空の並び・空の not・文書ノードへの型の否定は定義エラー
        assertFailsWith<IllegalArgumentException> { QueryNodeStep.parse(mapOf("entity" to emptyList<String>()), "m") }
        assertFailsWith<IllegalArgumentException> { QueryNodeStep.parse(mapOf("not" to emptyMap<String, Any>()), "m") }
        assertFailsWith<IllegalStateException> {
            QueryNodeStep.parse(mapOf("kind" to "document", "not" to mapOf("entity" to "screen")), "m")
        }
    }

    @Test
    fun `edgeのbindは参照を束縛しreportAtのrefで指摘の場所にできる`() {
        val match = listOf(
            mapOf("node" to mapOf("entity" to "order", "bind" to "o")),
            mapOf("edge" to mapOf("relation" to "has-line", "bind" to "r")),
            mapOf("node" to mapOf("bind" to "l")),
        )
        val parsed = rule("expect" to "absent", "reportAt" to mapOf("ref" to "r"), match = match)
        assertEquals(listOf("r"), parsed.spec.edgeBinds)
        assertEquals("r", parsed.reportAt?.ref)
        // 参照の束縛名はノードの束縛名と重複できない・見つからない束縛・exists との組合せは定義エラー
        assertFailsWith<IllegalStateException> {
            rule("expect" to "absent", match = match.map { step ->
                if ("edge" in step) mapOf("edge" to mapOf("relation" to "has-line", "bind" to "o")) else step
            })
        }
        assertFailsWith<IllegalArgumentException> { rule("expect" to "absent", "reportAt" to mapOf("ref" to "x"), match = match) }
        assertFailsWith<IllegalArgumentException> { rule("expect" to "exists", "reportAt" to mapOf("ref" to "r"), match = match) }
    }

    @Test
    fun `whereとcheckの否定とまたはは入れ子1段まで`() {
        val clause = QueryWhereClause.parse(
            mapOf("or" to listOf(
                mapOf("of" to "s", "path" to "status", "equals" to "a"),
                mapOf("not" to mapOf("of" to "s", "path" to "status", "exists" to true)),
            )),
            "w",
        )
        assertEquals(listOf(false, true), clause.alternatives.map { it.negated })
        // or の条件は同じ束縛について書く
        assertFailsWith<IllegalArgumentException> {
            QueryWhereClause.parse(mapOf("or" to listOf(
                mapOf("of" to "s", "path" to "status", "equals" to "a"),
                mapOf("of" to "t", "path" to "status", "equals" to "a"),
            )), "w")
        }
        val get = mapOf("of" to "s", "path" to "code")
        val check = ConstraintCheck.parse(mapOf("or" to listOf(
            mapOf("eq" to listOf(get, "x")),
            mapOf("not" to mapOf("startsWith" to listOf(get, "TMP-"))),
        )), "c")
        assertEquals(listOf(false, true), check.terms.map { it.negated })
        assertEquals("が次で始まる:", check.terms[1].violationSymbol)
        assertFailsWith<IllegalStateException> {
            ConstraintCheck.parse(mapOf("not" to mapOf("not" to mapOf("eq" to listOf(get, "x")))), "c")
        }
        assertFailsWith<IllegalStateException> {
            ConstraintCheck.parse(mapOf("or" to listOf(mapOf("eq" to listOf(get, "x")))), "c")
        }
    }

    @Test
    fun `参照の本数と集計は値の式として書け束縛が見つからなければ定義エラーになる`() {
        val refCount = ValueExpr.parse(mapOf("refCount" to mapOf("of" to "s", "dir" to "in", "relation" to "uses")), "x")
        assertEquals(ValueExpr.RefCount("s", QueryDir.IN, "uses", null), refCount)
        val sum = assertIs<ValueExpr.Aggregate>(ValueExpr.parse(mapOf("sum" to mapOf("of" to "l", "path" to "amount")), "x"))
        assertEquals(ValueExpr.AggregateFn.SUM, sum.fn)
        assertTrue(sum.hasAggregate())
        // 出現を選ぶ対象にはならないが、束縛の点検の対象にはなる
        assertEquals(emptyList(), sum.sources())
        assertEquals(listOf("l"), sum.binds())
        assertNull(assertIs<ValueExpr.Aggregate>(ValueExpr.parse(mapOf("count" to mapOf("of" to "l")), "x")).path)
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("sum" to mapOf("of" to "l")), "x") }
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("count" to mapOf("of" to "l", "path" to "a")), "x") }
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("refCount" to mapOf("of" to "s")), "x") }
        assertFailsWith<IllegalArgumentException> {
            rule("check" to listOf(mapOf("gte" to listOf(mapOf("refCount" to mapOf("of" to "nobody", "dir" to "out")), 1))))
        }
    }

    @Test
    fun `文面はどの判定にも書け、参照を書き足せそうな文書への移動はcheckがなくても書ける`() {
        val parsed = rule(
            "count" to mapOf("min" to 1),
            "message" to "「{id}」の解は {count} 件です",
            "responses" to listOf(mapOf("jump" to "referenceSources", "relation" to "uses", "label" to "開く: {file}", "note" to "補足")),
        )
        assertEquals("「{id}」の解は {count} 件です", parsed.message)
        assertEquals(ResponseDecl(ResponseDecl.Kind.JUMP, "referenceSources", "開く: {file}", "uses", "補足"), parsed.responses.single())
        // 比べた値を使う対応は check が必要・referenceSources には relation が必要
        assertFailsWith<IllegalArgumentException> {
            rule("expect" to "exists", "responses" to listOf(mapOf("jump" to "left.source", "label" to "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            rule("expect" to "exists", "responses" to listOf(mapOf("jump" to "referenceSources", "label" to "x")))
        }
    }

    @Test
    fun `語彙のwhenは並びを書け空の並びは書かれていない扱いでjoinは並びをつなぐ`() {
        val library = CheckLibrary.parse(
            mapOf(
                "functions" to listOf(mapOf(
                    "id" to "f", "params" to listOf("types", "text"),
                    "checks" to listOf(mapOf(
                        "match" to listOf(mapOf("node" to mapOf("entity" to "\$types", "bind" to "s"))),
                        "expect" to "exists", "description" to "型: \$text",
                    )),
                )),
                "vocabulary" to listOf(mapOf(
                    "word" to "w", "in" to "relations[]",
                    "uses" to listOf(mapOf(
                        "when" to listOf("flag", "source"), "use" to "f",
                        "with" to mapOf("types" to mapOf("from" to "source"), "text" to mapOf("from" to "source", "join" to " / ")),
                    )),
                )),
            ),
            "lib.yaml",
        )
        val expanded = library.expand(
            mapOf("relations" to listOf(
                mapOf("name" to "a", "flag" to true, "source" to listOf("usecase", "batch")),
                mapOf("name" to "b", "flag" to true, "source" to emptyList<String>()),  // 空の並び＝書かれていない
                mapOf("name" to "c", "source" to listOf("usecase")),                       // flag が無い
            )),
            "relations",
        )
        assertEquals(1, expanded.size)
        assertEquals("型: usecase / batch", expanded.single().raw["description"])
        assertEquals(listOf("usecase", "batch"), ConstraintRule.parse(expanded.single().raw, "x").spec.start.entities)
    }

    @Test
    fun `書かれた場所の数と状況の表示と直せる場所への案内と文書を作る対応を宣言できる`() {
        val count = ValueExpr.parse(
            mapOf("occurrenceCount" to mapOf("of" to "s", "schema" to listOf("detail-a", "detail-b"), "role" to "defining")), "x",
        )
        val parsedCount = assertIs<ValueExpr.OccurrenceCount>(count)
        assertEquals(listOf("detail-a", "detail-b"), parsedCount.schemas)
        assertEquals(EntityRole.DEFINING, parsedCount.role)
        assertTrue(parsedCount.matches("detail-a", "x.yaml", EntityRole.DEFINING))
        assertTrue(!parsedCount.matches("detail-a", "x.yaml", EntityRole.DETAIL))
        assertTrue(!parsedCount.matches("other", "x.yaml", EntityRole.DEFINING))
        assertFailsWith<IllegalStateException> { ValueExpr.parse(mapOf("occurrenceCount" to mapOf("of" to "s", "role" to "owner")), "x") }
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("occurrenceCount" to mapOf("of" to "s", "distinct" to "schema")), "x") }

        val parsed = rule(
            "check" to listOf(mapOf("gte" to listOf(mapOf("occurrenceCount" to mapOf("of" to "s", "schema" to "detail")), 1))),
            "status" to "出現 {left} 件（必要 {right} 件）",
            "fixSite" to mapOf("schema" to "detail", "message" to "不足が {count} 件あります: {ids}"),
            "levelFrom" to "definingUniqueness",
            "responses" to listOf(mapOf("create" to "detailDocument", "schemas" to listOf("detail"), "label" to "作成: {id}（{schema}）")),
            match = listOf(mapOf("node" to mapOf("typed" to true, "bind" to "s"))),
        )
        assertEquals(true, parsed.spec.start.typed)
        assertEquals("出現 {left} 件（必要 {right} 件）", parsed.status)
        assertEquals(FixSite(listOf("detail"), "不足が {count} 件あります: {ids}"), parsed.fixSite)
        assertEquals("definingUniqueness", parsed.levelFrom)
        assertEquals(ResponseDecl.Kind.CREATE, parsed.responses.single().kind)
        assertEquals(listOf("detail"), parsed.responses.single().schemas)
        // 誤り: 知らない強制スイッチ・作る文書のスキーマなし・値の場所への指摘との組合せ
        assertFailsWith<IllegalArgumentException> { rule("expect" to "exists", "levelFrom" to "noSuchSwitch") }
        assertFailsWith<IllegalArgumentException> {
            rule("expect" to "exists", "responses" to listOf(mapOf("create" to "detailDocument", "label" to "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            rule(
                "check" to listOf(mapOf("eq" to listOf(mapOf("of" to "s", "path" to "a"), 1))),
                "reportAt" to mapOf("of" to "s", "path" to "a"), "status" to "x",
            )
        }
    }

    @Test
    fun `語彙は必ず書くキーと廃止したキーを宣言でき値があるときだけ文言を添えられる`() {
        val library = CheckLibrary.parse(
            mapOf(
                "functions" to listOf(mapOf(
                    "id" to "f", "params" to listOf("target", "note"),
                    "checks" to listOf(mapOf(
                        "match" to listOf(mapOf("node" to mapOf("schema" to "\$target", "bind" to "s"))),
                        "expect" to "exists", "message" to "不足\$note",
                    )),
                )),
                "vocabulary" to listOf(mapOf(
                    "word" to "needs", "in" to "needs[]",
                    "requires" to listOf("target.schema"),
                    "removed" to mapOf("legacy" to "target を使ってください"),
                    "uses" to listOf(mapOf(
                        "use" to "f",
                        "with" to mapOf("target" to mapOf("from" to "target.schema"), "note" to mapOf("from" to "why", "format" to "（{value}）")),
                    )),
                )),
            ),
            "lib.yaml",
        )
        val ok = library.expand(mapOf("needs" to listOf(
            mapOf("target" to mapOf("schema" to "a"), "why" to "理由"),
            mapOf("target" to mapOf("schema" to "b")),
        )), "s")
        assertEquals(listOf("不足（理由）", "不足"), ok.map { it.raw["message"] })
        val missing = assertFailsWith<IllegalStateException> { library.expand(mapOf("needs" to listOf(mapOf("why" to "x"))), "s") }
        assertTrue("needs[0] には target.schema が必要です" in missing.message.orEmpty(), missing.message)
        val removed = assertFailsWith<IllegalStateException> {
            library.expand(mapOf("needs" to listOf(mapOf("legacy" to true, "target" to mapOf("schema" to "a")))), "s")
        }
        assertTrue("needs[0].legacy は廃止されました。target を使ってください" in removed.message.orEmpty(), removed.message)
    }

    private companion object {
        val DEFAULT_MATCH: List<Any?> = listOf(mapOf("node" to mapOf("entity" to "screen", "bind" to "s")))
    }
}
