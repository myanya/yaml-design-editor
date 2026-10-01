package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 値は項（配列を含む）・集計は配列を受け取る関数・集合から配列へは `all`・要素ごとの式は `each`（ADR-0010）のテスト。
 * カテゴリ × 状態の集計表（行＝カテゴリ・列＝状態ごとの件数と金額）を、起点を固定した照合の `all` で書く。
 */
class SemanticAllTest {

    private fun schema(id: String, yaml: String) = SchemaTreeParser.parse(yaml.trimIndent(), fallbackId = id)

    private val categoryList = schema(
        "category-list",
        """
        id: category-list
        root:
          type: object
          fields:
            - key: categories
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: name, type: string }
        """,
    )
    private val todoList = schema(
        "todo-list",
        """
        id: todo-list
        root:
          type: object
          fields:
            - key: todos
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: title, type: string }
                  - { key: category, type: ref }
                  - key: tags
                    type: array
                    item: { type: string }
        """,
    )
    private val todoDetail = schema(
        "todo-detail",
        """
        id: todo-detail
        root:
          type: object
          fields:
            - { key: id, type: id }
            - { key: status, type: string }
            - { key: amount, type: int }
        """,
    )
    private val categoryEntity = listOf(EntityDecl(type = "category", idPattern = "CAT-*", defines = true))
    private val todoDefining = listOf(EntityDecl(type = "todo", idPattern = "TODO-*", defines = true))
    private val todoDetailing = listOf(EntityDecl(type = "todo", idPattern = "TODO-*", defines = false))

    private val categoriesDoc = DocumentId("todos/category-list.yaml")
    private val todosDoc = DocumentId("todos/todo-list.yaml")
    private val details = mapOf(
        "TODO-001" to ("CLOSED" to 100), "TODO-002" to ("CLOSED" to 250), "TODO-003" to ("CLOSED" to 50),
        "TODO-004" to ("OPEN" to 30), "TODO-005" to ("IN_PROGRESS" to 70), "TODO-006" to ("OPEN" to 5), "TODO-007" to ("CANCELED" to 10),
    )
    private val trees = mapOf(
        categoriesDoc.value to YamlIO.parse("categories:\n  - { id: CAT-001, name: 環境整備 }\n  - { id: CAT-002, name: データ登録 }"),
        todosDoc.value to YamlIO.parse(
            """
            todos:
              - { id: TODO-001, title: 一覧の作成, category: CAT-001, tags: [a, b] }
              - { id: TODO-002, title: TODO の追加, category: CAT-002 }
              - { id: TODO-003, title: 個票の作成, category: CAT-002 }
              - { id: TODO-004, title: 集計表の検討, category: CAT-001, tags: [c] }
              - { id: TODO-005, title: README の更新, category: CAT-002 }
              - { id: TODO-006, title: 未分類の作業 }
              - { id: TODO-007, title: 中止した作業, category: CAT-001, tags: [] }
            """.trimIndent(),
        ),
    ) + details.map { (id, d) -> "todos/details/$id.yaml" to YamlIO.parse("id: $id\nstatus: ${d.first}\namount: ${d.second}") }

    private val snapshot = SemanticEngine.build(
        SemanticInput(
            1,
            listOf(
                SemanticDocument(categoriesDoc, trees.getValue(categoriesDoc.value), categoryList, "category-list", categoryEntity),
                SemanticDocument(todosDoc, trees.getValue(todosDoc.value), todoList, "todo-list", todoDefining),
            ) + details.keys.map { id ->
                val d = DocumentId("todos/details/$id.yaml")
                SemanticDocument(d, trees.getValue(d.value), todoDetail, "todo-detail", todoDetailing)
            },
        ),
    )

    private fun tree(d: DocumentId) = trees[d.value]

    private fun categoryRef(id: String, index: Int) =
        SemanticQueryNodeRef(QueryNodeKind.ENTITY, id, null, "category", categoriesDoc, parseYPath("categories[$index]"))

    /** カテゴリの行（いまの要素＝カテゴリ）の環境。withSolve = false なら照合の評価器を渡さない。 */
    private fun categoryEnv(
        id: String,
        index: Int,
        withSolve: Boolean = true,
        nodes: Map<String, SemanticQueryNodeRef> = emptyMap(),
        solutions: List<SemanticQueryRow>? = null,
    ): SemanticValues.Environment = SemanticValues.Environment(
        tree = ::tree,
        current = categoryRef(id, index),
        currentOccurrence = SemanticExpression.Occurrence(categoriesDoc, parseYPath("categories[$index]")),
        nodes = nodes,
        graph = snapshot.graph,
        solutions = solutions?.let { s -> { s } },
        solveFrom = if (!withSolve) null else { origin, spec ->
            SemanticQuery.matchesFrom(snapshot.ids, snapshot.refs, snapshot.graph, spec, origin.id, loader = ::tree)
        },
    )

    private val match = listOf(
        mapOf("node" to mapOf("entity" to "category", "bind" to "cat")),
        mapOf("edge" to mapOf("via" to "category", "dir" to "in")),
        mapOf("node" to mapOf("entity" to "todo", "bind" to "t")),
    )

    /** カテゴリを起点に、そのカテゴリを参照する TODO を集める（status で絞る）。path は個票（todo-detail）から読む。 */
    private fun todosAll(status: String?, path: String? = null, from: String? = null): Map<String, Any?> = buildMap {
        from?.let { put("from", it) }
        put("match", match)
        status?.let { put("where", listOf(mapOf("of" to "t", "path" to "status", "equals" to it))) }
        path?.let {
            put("path", it)
            // 値を読む出現は定義した側を優先して 1 つに決まる（CALC-19）ので、個票の値は in で選ぶ
            put("in", mapOf("schemaId" to "todo-detail"))
        }
    }

    private fun value(raw: Any?) = ValueExpr.parseValue(raw, "value")

    @Test
    fun `起点を固定した照合の解を配列にし件数と合計を関数で求める`() {
        val open = assertNotNull(SemanticValues.evaluate(value(mapOf("count" to mapOf("all" to todosAll("OPEN")))), categoryEnv("CAT-001", 0)))
        assertEquals("1", open.text)
        assertEquals("件数: TODO-004", open.raw)
        // 来歴は数えたノード（起点の束縛は除く）。座標は定義出現＝一覧の要素
        val input = open.inputs.single()
        assertEquals("t", input.placeholder)
        assertEquals("TODO-004", input.value)
        assertEquals(todosDoc, input.document)
        assertEquals(parseYPath("todos[3]"), input.path)
        assertNull(open.sourceDocument)

        // 配列そのものも値（各要素が出どころを持つ）
        val array = assertNotNull(SemanticValues.evaluate(value(mapOf("all" to todosAll(null))), categoryEnv("CAT-001", 0)))
        assertEquals("[TODO-001, TODO-004, TODO-007]", array.text)
        assertTrue(!array.isScalar)
        assertEquals(3, assertIs<YNode.Seq>(array.node).items.size)
        assertEquals(listOf("TODO-001", "TODO-004", "TODO-007"), array.elements!!.map { it.value })

        // 金額の合計（path で各要素の出現から読む。個票の値）
        val total = assertNotNull(SemanticValues.evaluate(value(mapOf("sum" to mapOf("all" to todosAll(null, path = "amount")))), categoryEnv("CAT-001", 0)))
        assertEquals("140", total.text)
        assertEquals("合計: 100・30・10", total.raw)
        assertEquals(listOf("TODO-001", "TODO-004", "TODO-007"), total.inputs.map { it.placeholder })
        assertEquals(DocumentId("todos/details/TODO-004.yaml"), total.inputs[1].document)
        // 最大は選ばれた要素の出どころ
        val max = assertNotNull(SemanticValues.evaluate(value(mapOf("max" to mapOf("all" to todosAll(null, path = "amount")))), categoryEnv("CAT-001", 0)))
        assertEquals("100", max.text)
        assertEquals(DocumentId("todos/details/TODO-001.yaml"), max.sourceDocument)
        assertEquals(parseYPath("amount"), max.sourcePath)

        // もう 1 つの行。空の配列の件数は 0（来歴なし）
        assertEquals("370", SemanticValues.evaluate(value(mapOf("sum" to mapOf("all" to todosAll(null, path = "amount")))), categoryEnv("CAT-002", 1))!!.text)
        val zero = SemanticValues.evaluate(value(mapOf("count" to mapOf("all" to todosAll("CANCELED")))), categoryEnv("CAT-002", 1))!!
        assertEquals("0", zero.text)
        assertEquals("0", zero.raw)
        assertTrue(zero.inputs.isEmpty())
    }

    @Test
    fun `起点が先頭のnodeに合わなければ空でfromで束縛を起点にでき解の一覧の形も同じ関数で畳む`() {
        // いまの要素が TODO（category 型ではない）: 先頭の node に合わないので解は無い
        val todoEnv = SemanticValues.Environment(
            tree = ::tree,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "TODO-001", null, "todo", todosDoc, parseYPath("todos[0]")),
            currentOccurrence = SemanticExpression.Occurrence(todosDoc, parseYPath("todos[0]")),
            graph = snapshot.graph,
            solveFrom = { origin, spec -> SemanticQuery.matchesFrom(snapshot.ids, snapshot.refs, snapshot.graph, spec, origin.id, loader = ::tree) },
        )
        assertEquals("0", SemanticValues.evaluate(value(mapOf("count" to mapOf("all" to todosAll("OPEN")))), todoEnv)!!.text)

        // from で束縛を起点にする（クエリ行のビュー・検査の中）
        val other = categoryRef("CAT-002", 1)
        val viaBind = SemanticValues.evaluate(value(mapOf("count" to mapOf("all" to todosAll("CLOSED", from = "c")))), categoryEnv("CAT-001", 0, nodes = mapOf("c" to other)))!!
        assertEquals("2", viaBind.text)
        val missing = SemanticValues.evaluateDetailed(value(mapOf("count" to mapOf("all" to todosAll("CLOSED", from = "nosuch")))), categoryEnv("CAT-001", 0))
        assertNull(missing.side)
        assertEquals("束縛「nosuch」がありません", missing.reason)

        // 解の一覧の形（of）: 同じ起点の全ての解にわたる。解は評価器が渡す
        val rows = listOf("TODO-001", "TODO-004").mapIndexed { i, id ->
            SemanticQueryRow(mapOf("t" to SemanticQueryNodeRef(QueryNodeKind.ENTITY, id, null, "todo", todosDoc, parseYPath("todos[$i]"))))
        }
        val env = categoryEnv("CAT-001", 0, solutions = rows)
        assertEquals("130", SemanticValues.evaluate(value(mapOf("sum" to mapOf("all" to mapOf("of" to "t", "in" to mapOf("schemaId" to "todo-detail"), "path" to "amount")))), env)!!.text)
        assertEquals("2", SemanticValues.evaluate(value(mapOf("count" to mapOf("all" to mapOf("of" to "t")))), env)!!.text)
        // 要素の中の配列（[*]）は平らに広がる。tags を持たない要素は場所が 0 件
        assertEquals("[a, b, c]", SemanticValues.evaluate(value(mapOf("all" to mapOf("of" to "t", "path" to "tags[*]"))), env)!!.text)
        // 解の一覧が無ければ得られない（理由つき）
        val noRows = SemanticValues.evaluateDetailed(value(mapOf("count" to mapOf("all" to mapOf("of" to "t")))), categoryEnv("CAT-001", 0))
        assertNull(noRows.side)
        assertTrue("解の一覧がありません" in noRows.reason.orEmpty(), noRows.reason)
    }

    @Test
    fun `eachは各要素をいまの要素にして式を当て読み出しは配列を値として返す`() {
        // all で集めたノードを each で回す: 各 TODO の要素から title を読む
        val titles = SemanticValues.evaluate(
            value(mapOf("each" to mapOf("all" to todosAll("CLOSED")), "value" to mapOf("path" to "title"))),
            categoryEnv("CAT-002", 1),
        )!!
        assertEquals("[TODO の追加, 個票の作成]", titles.text)
        assertEquals(todosDoc, titles.elements!![0].document)
        assertEquals(parseYPath("todos[1].title"), titles.elements!![0].path)
        // 個票の値を読む（in で出現を選ぶ）と、四則演算を要素ごとに当てて合計する
        val doubled = SemanticValues.evaluate(
            value(
                mapOf(
                    "sum" to mapOf(
                        "each" to mapOf("all" to todosAll(null)),
                        "value" to mapOf("multiply" to listOf(mapOf("in" to mapOf("schemaId" to "todo-detail"), "path" to "amount"), 2)),
                    ),
                ),
            ),
            categoryEnv("CAT-001", 0),
        )!!
        assertEquals("280", doubled.text)

        // 読み出しは配列を値として返す（要素ごとに出どころ）。each はその要素の場所をいまの要素にする
        val todoEnv = SemanticValues.Environment(
            tree = ::tree,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "TODO-001", null, "todo", todosDoc, parseYPath("todos[0]")),
            currentOccurrence = SemanticExpression.Occurrence(todosDoc, parseYPath("todos[0]")),
            graph = snapshot.graph,
        )
        val tags = SemanticValues.evaluate(value(mapOf("path" to "tags")), todoEnv)!!
        assertEquals("[a, b]", tags.text)
        assertEquals(parseYPath("todos[0].tags[1]"), tags.elements!![1].path)
        assertEquals("2", SemanticValues.evaluate(value(mapOf("count" to mapOf("path" to "tags"))), todoEnv)!!.text)
        // 型が合わない関数は得られない: length は文字数（配列の件数は count）・比較の材料にもならない
        val len = SemanticValues.evaluateDetailed(value(mapOf("length" to mapOf("path" to "tags"))), todoEnv)
        assertNull(len.side)
        assertTrue("count で数えます" in len.reason.orEmpty(), len.reason)
        val sumOfScalar = SemanticValues.evaluateDetailed(value(mapOf("sum" to mapOf("path" to "title"))), todoEnv)
        assertNull(sumOfScalar.side)
        assertTrue("配列ではありません" in sumOfScalar.reason.orEmpty(), sumOfScalar.reason)
    }

    @Test
    fun `照合の評価器が無い環境では得られず理由が残る`() {
        val result = SemanticValues.evaluateDetailed(value(mapOf("count" to mapOf("all" to todosAll("OPEN")))), categoryEnv("CAT-001", 0, withSolve = false))
        assertNull(result.side)
        assertTrue("起点を固定した照合はここでは評価できません" in result.reason.orEmpty(), result.reason)
    }

    @Test
    fun `古い書き方とlimitと未知のキーは定義エラーで新しい書き方を案内する`() {
        // 集計の中の of（解にわたる集計の古い書き方）
        val old = assertFailsWith<IllegalStateException> { value(mapOf("sum" to mapOf("of" to "t", "path" to "amount"))) }
        assertTrue("{sum: {all: {of: t, path: amount}}}" in old.message.orEmpty(), old.message)
        // count に match（起点を固定した照合の古い書き方）
        val oldCount = assertFailsWith<IllegalStateException> { value(mapOf("count" to mapOf("match" to match))) }
        assertTrue("{count: {all: {match:" in oldCount.message.orEmpty(), oldCount.message)
        // limit は書けない（全ての解を集める）。of も match も無い all
        assertFailsWith<IllegalStateException> { value(mapOf("all" to mapOf("match" to match, "limit" to 10))) }
        assertFailsWith<IllegalStateException> { value(mapOf("all" to mapOf("path" to "amount"))) }
        // 検査の中（current = false）: match の形には from が要る。of の形はそのまま書ける
        assertFailsWith<IllegalArgumentException> { ValueExpr.parse(mapOf("all" to mapOf("match" to match)), "x") }
        assertIs<ValueExpr.All>(ValueExpr.parse(mapOf("all" to mapOf("from" to "c", "match" to match)), "x"))
        assertIs<ValueExpr.All>(ValueExpr.parse(mapOf("all" to mapOf("of" to "t", "path" to "amount")), "x"))
        // of が match の束縛に無い
        assertFailsWith<IllegalArgumentException> { value(mapOf("all" to mapOf("match" to match, "of" to "nosuch"))) }
        // 読めた式は、外側の束縛（from）を使う式として点検される
        val expr = ValueExpr.parse(mapOf("count" to mapOf("all" to mapOf("from" to "c", "match" to match))), "x")
        assertEquals(listOf("c"), expr.binds())
        assertTrue(expr.sources().isEmpty())
        assertTrue(expr.currentReads().isEmpty())
        assertTrue(!expr.needsSolutions())
        assertTrue(ValueExpr.parse(mapOf("sum" to mapOf("all" to mapOf("of" to "t", "path" to "amount"))), "x").needsSolutions())
    }
}
