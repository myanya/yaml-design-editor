package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 値の式の「解の件数」（`count` に `match`。CORE-CALCULUS「Aggregate」の Count(Query)）のテスト。
 * 起点（いまの要素か of の束縛）を固定してパターン照合を評価し、解の件数を値にする。制約の count と同じ数え方で、
 * 来歴は数えたノード（座標つき）。カテゴリ × 状態の集計表（行＝カテゴリ・列＝状態ごとの件数）がこれで書ける。
 */
class SemanticCountMatchesTest {

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
        """,
    )
    private val categoryEntity = listOf(EntityDecl(type = "category", idPattern = "CAT-*", defines = true))
    private val todoDefining = listOf(EntityDecl(type = "todo", idPattern = "TODO-*", defines = true))
    private val todoDetailing = listOf(EntityDecl(type = "todo", idPattern = "TODO-*", defines = false))

    private val categoriesDoc = DocumentId("todos/category-list.yaml")
    private val todosDoc = DocumentId("todos/todo-list.yaml")
    private val statuses = mapOf(
        "TODO-001" to "CLOSED", "TODO-002" to "CLOSED", "TODO-003" to "CLOSED", "TODO-004" to "OPEN",
        "TODO-005" to "IN_PROGRESS", "TODO-006" to "OPEN", "TODO-007" to "CANCELED",
    )
    private val trees = mapOf(
        categoriesDoc.value to YamlIO.parse("categories:\n  - { id: CAT-001, name: 環境整備 }\n  - { id: CAT-002, name: データ登録 }"),
        todosDoc.value to YamlIO.parse(
            """
            todos:
              - { id: TODO-001, title: 一覧の作成, category: CAT-001 }
              - { id: TODO-002, title: TODO の追加, category: CAT-002 }
              - { id: TODO-003, title: 個票の作成, category: CAT-002 }
              - { id: TODO-004, title: 集計表の検討, category: CAT-001 }
              - { id: TODO-005, title: README の更新, category: CAT-002 }
              - { id: TODO-006, title: 未分類の作業 }
              - { id: TODO-007, title: 中止した作業, category: CAT-001 }
            """.trimIndent(),
        ),
    ) + statuses.map { (id, status) -> "todos/details/$id.yaml" to YamlIO.parse("id: $id\nstatus: $status") }

    private val snapshot = SemanticEngine.build(
        SemanticInput(
            1,
            listOf(
                SemanticDocument(categoriesDoc, trees.getValue(categoriesDoc.value), categoryList, "category-list", categoryEntity),
                SemanticDocument(todosDoc, trees.getValue(todosDoc.value), todoList, "todo-list", todoDefining),
            ) + statuses.keys.map { id ->
                val d = DocumentId("todos/details/$id.yaml")
                SemanticDocument(d, trees.getValue(d.value), todoDetail, "todo-detail", todoDetailing)
            },
        ),
    )

    private fun tree(d: DocumentId) = trees[d.value]

    /** カテゴリの行（いまの要素＝カテゴリ）の環境。withCount = false なら、解の件数を数える評価器を渡さない。 */
    private fun categoryEnv(id: String, index: Int, withCount: Boolean = true, nodes: Map<String, SemanticQueryNodeRef> = emptyMap()): SemanticValues.Environment {
        val path = parseYPath("categories[$index]")
        return SemanticValues.Environment(
            tree = ::tree,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, id, null, "category", categoriesDoc, path),
            currentOccurrence = SemanticExpression.Occurrence(categoriesDoc, path),
            nodes = nodes,
            graph = snapshot.graph,
            countFrom = if (!withCount) null else { origin, spec ->
                SemanticQuery.matchesFrom(snapshot.ids, snapshot.refs, snapshot.graph, spec, origin.id, ::tree)
            },
        )
    }

    /** カテゴリを起点に、そのカテゴリを参照する TODO を数える（status で絞る。null なら絞らない）。 */
    private fun countTodos(status: String?, of: String? = null): ValueExpr = ValueExpr.parseValue(
        mapOf(
            "count" to buildMap {
                of?.let { put("of", it) }
                put(
                    "match",
                    listOf(
                        mapOf("node" to mapOf("entity" to "category", "bind" to "cat")),
                        mapOf("edge" to mapOf("via" to "category", "dir" to "in")),
                        mapOf("node" to mapOf("entity" to "todo", "bind" to "t")),
                    ),
                )
                status?.let { put("where", listOf(mapOf("of" to "t", "path" to "status", "equals" to it))) }
            },
        ),
        "value",
    )

    @Test
    fun `起点ごとに条件で絞った解の件数を数え来歴に数えたノードを持つ`() {
        val open = assertNotNull(SemanticValues.evaluate(countTodos("OPEN"), categoryEnv("CAT-001", 0)))
        assertEquals("1", open.text)
        assertEquals("件数: TODO-004", open.raw)
        assertEquals("CAT-001", open.id)
        // 来歴は数えたノード（起点の束縛は除く）。座標は定義出現＝一覧の要素
        val input = open.inputs.single()
        assertEquals("t", input.placeholder)
        assertEquals("TODO-004", input.value)
        assertEquals(todosDoc, input.document)
        assertEquals(parseYPath("todos[3]"), input.path)
        // 件数は特定の 1 か所の値ではない（座標なし）
        assertNull(open.sourceDocument)

        // 同じ行の他の列（状態ごと）と合計。カテゴリの無い TODO-006 はどのカテゴリにも数えられない
        assertEquals("0", SemanticValues.evaluate(countTodos("IN_PROGRESS"), categoryEnv("CAT-001", 0))!!.text)
        assertEquals("1", SemanticValues.evaluate(countTodos("CLOSED"), categoryEnv("CAT-001", 0))!!.text)
        assertEquals("1", SemanticValues.evaluate(countTodos("CANCELED"), categoryEnv("CAT-001", 0))!!.text)
        val total = SemanticValues.evaluate(countTodos(null), categoryEnv("CAT-001", 0))!!
        assertEquals("3", total.text)
        assertEquals("件数: TODO-001・TODO-004・TODO-007", total.raw)
        assertEquals(listOf("TODO-001", "TODO-004", "TODO-007"), total.inputs.map { it.value })

        // もう 1 つの行
        assertEquals("0", SemanticValues.evaluate(countTodos("OPEN"), categoryEnv("CAT-002", 1))!!.text)
        assertEquals("1", SemanticValues.evaluate(countTodos("IN_PROGRESS"), categoryEnv("CAT-002", 1))!!.text)
        assertEquals("2", SemanticValues.evaluate(countTodos("CLOSED"), categoryEnv("CAT-002", 1))!!.text)
        val zero = SemanticValues.evaluate(countTodos("CANCELED"), categoryEnv("CAT-002", 1))!!
        assertEquals("0", zero.text)
        assertEquals("0", zero.raw)
        assertTrue(zero.inputs.isEmpty())
    }

    @Test
    fun `起点が先頭のnodeに合わなければ0で束縛を起点にもできる`() {
        // いまの要素が TODO（category 型ではない）: 先頭の node に合わないので解は無い
        val todoEnv = SemanticValues.Environment(
            tree = ::tree,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "TODO-001", null, "todo", todosDoc, parseYPath("todos[0]")),
            currentOccurrence = SemanticExpression.Occurrence(todosDoc, parseYPath("todos[0]")),
            graph = snapshot.graph,
            countFrom = { origin, spec -> SemanticQuery.matchesFrom(snapshot.ids, snapshot.refs, snapshot.graph, spec, origin.id, ::tree) },
        )
        assertEquals("0", SemanticValues.evaluate(countTodos("OPEN"), todoEnv)!!.text)

        // of で束縛を起点にする（クエリ行のビュー）
        val other = SemanticQueryNodeRef(QueryNodeKind.ENTITY, "CAT-002", null, "category", categoriesDoc, parseYPath("categories[1]"))
        val viaBind = SemanticValues.evaluate(countTodos("CLOSED", of = "c"), categoryEnv("CAT-001", 0, nodes = mapOf("c" to other)))!!
        assertEquals("2", viaBind.text)
        assertEquals("CAT-002", viaBind.id)
        // 無い束縛は得られない（理由つき）
        val missing = SemanticValues.evaluateDetailed(countTodos("CLOSED", of = "nosuch"), categoryEnv("CAT-001", 0))
        assertNull(missing.side)
        assertEquals("束縛「nosuch」がありません", missing.reason)
    }

    @Test
    fun `数える評価器が無い環境では得られず理由が残る`() {
        val result = SemanticValues.evaluateDetailed(countTodos("OPEN"), categoryEnv("CAT-001", 0, withCount = false))
        assertNull(result.side)
        assertEquals("解の件数はここでは数えられません（ビューの列で数えます）", result.reason)
    }

    @Test
    fun `検査の中では書けずlimitと未知のキーは定義エラー`() {
        val match = listOf(
            mapOf("node" to mapOf("entity" to "category", "bind" to "cat")),
            mapOf("edge" to mapOf("via" to "category", "dir" to "in")),
            mapOf("node" to mapOf("entity" to "todo", "bind" to "t")),
        )
        // 検査の check（current = false）: パターンの解の件数は制約の count の役目
        val inCheck = assertFailsWith<IllegalArgumentException> {
            ValueExpr.parse(mapOf("count" to mapOf("of" to "cat", "match" to match)), "x")
        }
        assertTrue("検査の中では書けません" in inCheck.message.orEmpty(), inCheck.message)
        // limit は書けない（全ての解を数える）。path など集計のキーとも混ぜられない
        assertFailsWith<IllegalStateException> { ValueExpr.parseValue(mapOf("count" to mapOf("match" to match, "limit" to 10)), "x") }
        assertFailsWith<IllegalStateException> { ValueExpr.parseValue(mapOf("count" to mapOf("match" to match, "path" to "todos[*]")), "x") }
        // クエリの決まり（where の束縛が match に無い）はそのまま定義エラー
        assertFailsWith<IllegalArgumentException> {
            ValueExpr.parseValue(mapOf("count" to mapOf("match" to match, "where" to listOf(mapOf("of" to "x", "path" to "status", "equals" to "OPEN")))), "x")
        }
        // 読めた式は、束縛（of）を使う式として点検される
        val expr = ValueExpr.parseValue(mapOf("count" to mapOf("of" to "cat", "match" to match)), "x")
        assertEquals(listOf("cat"), expr.binds())
        assertTrue(expr.sources().isEmpty())
        assertTrue(expr.currentReads().isEmpty())
    }
}
