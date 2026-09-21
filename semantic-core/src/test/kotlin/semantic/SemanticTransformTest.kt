package semantic

import model.DocumentId
import model.YNode
import model.parseYPath
import schema.EntityDecl
import schema.StructuralSchemaParser
import schema.TransformDef
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「YAML から YAML への変換」（P27 段C。計算体系の6つ目の体系「組み立て」）: 選ぶ × 組み立てる ×（入れ子には）規則を子に当てる。
 * semantic-core 単体で「変換の定義 → 出力の項と出どころ」が閉じることを固定する。
 */
class SemanticTransformTest {

    private fun def(yaml: String): TransformDef = TransformDef.parse(YamlIO.parseToRaw(yaml.trimIndent()))

    private fun plain(node: YNode?): Any? = node?.let { YamlIO.toAny(it) }

    private fun expected(yaml: String): Any? = YamlIO.parseToRaw(yaml.trimIndent())

    // ---------------------------------------------------------------- 呼び出し側が渡した項1つを変換する

    @Test
    fun `ひな型は書いたとおりに出力され 値が得られない項目は落ちる`() {
        val transform = def(
            """
            id: t
            build:
              title: { ${'$'}copy: name }
              owner: { ${'$'}copy: owner }          # 入力に無い → キーごと落ちる
              fixed: 決まった値
              nested:
                tags: { ${'$'}copy: tags }          # 部分木をそのまま写す
              list: [ 1, { ${'$'}copy: missing }, 3 ]
            """
        )
        val result = SemanticTransform.run(transform, YamlIO.parse("name: 予約\ntags: [a, b]"))
        assertEquals(
            expected(
                """
                title: 予約
                fixed: 決まった値
                nested: { tags: [a, b] }
                list: [1, 3]
                """
            ),
            plain(result.term),
        )
    }

    @Test
    fun `写した部分木は入力と別の項で 入力は書き換わらない`() {
        val input = YamlIO.parse("tags: [a, b]")
        val result = SemanticTransform.run(def("id: t\nbuild: { ${'$'}copy: tags }"), input)
        ((result.term as YNode.Seq).items[0] as YNode.Scalar).value = "changed"
        assertEquals(listOf("a", "b"), plain(input.let { (it as YNode.Map).entries["tags"] }))
    }

    @Test
    fun `条件 最初に得られた値 辞書での置き換え 文字のひな型`() {
        val transform = def(
            """
            id: t
            params: [reached]
            build:
              type: { ${'$'}map: { ${'$'}copy: type }, ${'$'}using: { id: string, ref: string } }
              kept: { ${'$'}map: { ${'$'}copy: other }, ${'$'}using: { id: string } }
              required:
                ${'$'}first:
                  - { ${'$'}when: { path: required, equals: true }, ${'$'}then: true }
                  - { ${'$'}when: { path: requiredFrom, oneOf: ${'$'}reached }, ${'$'}then: true }
              heading: { ${'$'}format: "{key}（{type}）" }
              broken: { ${'$'}format: "{nothing}" }       # 埋まらない穴がある → 無い
              only-when-ref:
                ${'$'}when: [ { path: type, equals: ref }, { not: { path: key, startsWith: x } } ]
                relation: { ${'$'}copy: relation }
            """
        )
        fun run(input: String, reached: List<String>) =
            plain(SemanticTransform.run(transform, YamlIO.parse(input), params = mapOf("reached" to reached)).term)
        assertEquals(
            expected("{ type: string, kept: enum, required: true, heading: owner（ref）, only-when-ref: { relation: owns } }"),
            run("key: owner\ntype: ref\nother: enum\nrequiredFrom: 詳細設計\nrelation: owns", listOf("基本設計", "詳細設計")),
        )
        // フェーズに達していなければ必須にならない・条件に合わないマップは、キーごと出ない
        assertEquals(
            expected("{ type: string, heading: xid（id） }"),
            run("key: xid\ntype: id\nrequiredFrom: 詳細設計", listOf("基本設計")),
        )
    }

    @Test
    fun `繰り返しと 規則を子に当てる再帰で 深さの決まっていない木を変換できる`() {
        // 入れ子の項目の木（スキーマの形）を、型の置き換えと不要なキーの削除つきで写す
        val transform = def(
            """
            id: t
            build: { ${'$'}apply: field }
            rules:
              - id: field
                build:
                  key: { ${'$'}copy: key }
                  type: { ${'$'}map: { ${'$'}copy: type }, ${'$'}using: { id: string, ref: string } }
                  fields: { ${'$'}each: fields, ${'$'}apply: field }
                  item: { ${'$'}apply: field, ${'$'}at: item }
                  cases: { ${'$'}eachEntry: cases, ${'$'}build: { ${'$'}each: ., ${'$'}apply: field } }
            """
        )
        val input = YamlIO.parse(
            """
            type: object
            fields:
              - { key: id, type: id, role: title }
              - key: kind
                type: enum
                cases:
                  manual: [ { key: assignee, type: ref, relation: assigned } ]
              - key: steps
                type: array
                item:
                  type: object
                  fields:
                    - key: uses
                      type: array
                      item: { type: ref, relation: uses }
            """.trimIndent()
        )
        assertEquals(
            expected(
                """
                type: object
                fields:
                  - { key: id, type: string }
                  - key: kind
                    type: enum
                    cases:
                      manual: [ { key: assignee, type: string } ]
                  - key: steps
                    type: array
                    item:
                      type: object
                      fields:
                        - key: uses
                          type: array
                          item: { type: string }
                """
            ),
            plain(SemanticTransform.run(transform, input).term),
        )
    }

    @Test
    fun `同じ名前の規則は 上から順に条件を調べて最初に合ったものを使う`() {
        val transform = def(
            """
            id: t
            build: { ${'$'}each: items, ${'$'}apply: item }
            rules:
              - id: item
                when: { path: kind, equals: special }
                build: { special: { ${'$'}copy: name } }
              - id: item
                when: { path: name, exists: true }
                build: { plain: { ${'$'}copy: name }, at: ${'$'}index }
            """
        )
        val input = YamlIO.parse("items:\n  - { name: a }\n  - { name: b, kind: special }\n  - { other: c }")
        // どの規則の条件にも合わない要素は「無い」＝配列から飛ばされる
        assertEquals(expected("[ { plain: a, at: 0 }, { special: b } ]"), plain(SemanticTransform.run(transform, input).term))
    }

    @Test
    fun `配列をつなげる 出力のキーをドル記号で始める`() {
        val transform = def(
            """
            id: t
            build:
              ${'$'}${'$'}ref: { ${'$'}copy: ${'$'}${'$'}ref }
              literal: ${'$'}${'$'}not-a-param
              fields:
                ${'$'}concat:
                  - [ { key: to } ]
                  - { ${'$'}each: fields, ${'$'}build: { key: { ${'$'}copy: key } } }
                  - { ${'$'}copy: more }
            """
        )
        val result = SemanticTransform.run(transform, YamlIO.parse("${'$'}ref: step\nfields: [ { key: a } ]\nmore: [ { key: z } ]"), DocumentId("in.yaml"))
        assertEquals(
            expected("{ \"${'$'}ref\": step, literal: \"${'$'}not-a-param\", fields: [ { key: to }, { key: a }, { key: z } ] }"),
            plain(result.term),
        )
        // 出どころ: つなげた後の番号で、入力の場所を指す（まるごと写した配列は、要素ごとに）
        val origins = result.origins.associate { it.output to it.path }
        assertEquals(parseYPath("fields[0].key"), origins[parseYPath("fields[1].key")])
        assertEquals(parseYPath("more[0]"), origins[parseYPath("fields[2]")])
        assertTrue(result.origins.all { it.document == DocumentId("in.yaml") })
    }

    @Test
    fun `規則を同じ場所へ当て続ける定義は 止まらずに回る前に定義エラーになる`() {
        val transform = def(
            """
            id: t
            build: { ${'$'}apply: a }
            rules:
              - { id: a, build: { x: { ${'$'}apply: b } } }
              - { id: b, build: { y: { ${'$'}apply: a } } }
            """
        )
        val error = assertFailsWith<IllegalStateException> { SemanticTransform.run(transform, YamlIO.parse("k: v")) }
        assertTrue("もう一度当てています" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `定義の誤りは 読み込みの段階で分かる`() {
        fun failure(yaml: String): String = assertFailsWith<IllegalStateException> { def(yaml) }.message.orEmpty()
            .ifEmpty { error("メッセージがありません") }
        fun illegal(yaml: String): String = assertFailsWith<IllegalArgumentException> { def(yaml) }.message.orEmpty()
        assertTrue("規則「nothing」" in illegal("id: t\nbuild: { ${'$'}apply: nothing }"))
        assertTrue("params に宣言されていません" in illegal("id: t\nbuild: { v: ${'$'}undeclared }"))
        assertTrue("未知の指示" in failure("id: t\nbuild: { ${'$'}copi: name }"))
        assertTrue("一緒に書けません" in failure("id: t\nbuild: { ${'$'}copy: name, other: 1 }"))
        assertTrue("一緒に書けません" in failure("id: t\nbuild: { ${'$'}copy: name, ${'$'}first: [1] }"))
        assertTrue("from のクエリで付けた名前ではありません" in illegal("id: t\nbuild: { ${'$'}copy: name, ${'$'}of: scr }"))
        // 受け取らない値を渡すのも誤り
        assertFailsWith<IllegalStateException> {
            SemanticTransform.run(def("id: t\nbuild: { v: 1 }"), YamlIO.parse("k: v"), params = mapOf("extra" to 1))
        }
    }

    // ---------------------------------------------------------------- クエリで選んだ行ごとに組み立てる

    private val schema = StructuralSchemaParser.parse(
        """
        id: node
        root:
          type: object
          fields:
            - { key: id, type: id }
            - { key: name, type: string }
            - key: uses
              type: array
              item: { type: ref, relation: uses }
            - key: items
              type: array
              item:
                type: object
                fields:
                  - { key: label, type: string }
        """.trimIndent(),
        fallbackId = "node",
    )

    private val detailSchema = StructuralSchemaParser.parse(
        "id: detail\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }\n    - key: items\n      type: array\n      item:\n        type: object\n        fields:\n          - { key: label, type: string }",
        fallbackId = "detail",
    )

    private val entities = listOf(
        EntityDecl(type = "screen", idPattern = "SCR-*", defines = true),
        EntityDecl(type = "usecase", idPattern = "UC-*", defines = true),
    )

    private val trees = linkedMapOf(
        "screens/a.yaml" to ("node" to "id: SCR-A\nname: 一覧画面\nuses: [UC-1, UC-2]"),
        "screens/b.yaml" to ("node" to "id: SCR-B\nname: 登録画面\nuses: [UC-1]"),
        "ucs/1.yaml" to ("node" to "id: UC-1\nname: 予約する"),
        "ucs/2.yaml" to ("node" to "id: UC-2\nname: 取り消す"),
        // 同じ画面の詳細文書（同じ ID の、もう1つの出現）
        "details/a.yaml" to ("detail" to "id: SCR-A\nitems:\n  - { label: 日付 }\n  - { label: 会議室 }"),
    ).mapValues { (_, v) -> v.first to YamlIO.parse(v.second) }

    private val snapshot: SemanticSnapshot = SemanticEngine.build(
        SemanticInput(
            1,
            trees.map { (name, v) ->
                val (schemaId, tree) = v
                SemanticDocument(
                    DocumentId(name), tree, if (schemaId == "node") schema else detailSchema, schemaId,
                    if (schemaId == "node") entities else listOf(EntityDecl(type = "screen", idPattern = "SCR-*", defines = false)),
                )
            },
        )
    )

    private fun runQuery(transform: TransformDef): TransformResult =
        SemanticTransform.run(transform, snapshot.ids, snapshot.refs, snapshot.graph) { trees[it.value]?.second }

    @Test
    fun `クエリで選んだ行ごとに組み立てる 平らな表も 入れ子の出力も`() {
        val transform = def(
            """
            id: t
            from:
              match:
                - node: { entity: screen, bind: scr }
                - edge: { relation: uses }
                - node: { entity: usecase, bind: uc }
            build:
              画面: { ${'$'}copy: name }                       # いまの場所＝クエリの起点の要素
              ユースケース: { ${'$'}copy: name, ${'$'}of: uc }
              項目:                                            # 同じ画面の、詳細文書の側の出現から読む
                ${'$'}each: items
                ${'$'}of: scr
                ${'$'}in: { schemaId: detail }
                ${'$'}build: { ${'$'}copy: label }
            """
        )
        val result = runQuery(transform)
        assertEquals(
            expected(
                """
                - { 画面: 一覧画面, ユースケース: 予約する, 項目: [日付, 会議室] }
                - { 画面: 一覧画面, ユースケース: 取り消す, 項目: [日付, 会議室] }
                - { 画面: 登録画面, ユースケース: 予約する }
                """
            ),
            plain(result.term),
        )
        // 出どころ: 出力の場所 → 入力の文書と場所
        val origin = result.origins.single { it.output == parseYPath("[1].項目[1]") }
        assertEquals(DocumentId("details/a.yaml"), origin.document)
        assertEquals(parseYPath("items[1].label"), origin.path)
    }

    @Test
    fun `入力の渡し方を取り違えると分かる`() {
        val query = def("id: t\nfrom: { match: [ { node: { entity: screen, bind: scr } } ] }\nbuild: { id: { ${'$'}copy: id } }")
        assertFailsWith<IllegalArgumentException> { SemanticTransform.run(query, YamlIO.parse("k: v")) }
        assertFailsWith<IllegalStateException> { runQuery(def("id: t\nbuild: { v: 1 }")) }
        assertNull(SemanticTransform.run(def("id: t\nbuild: { ${'$'}copy: nothing }"), YamlIO.parse("k: v")).term)
    }
}
