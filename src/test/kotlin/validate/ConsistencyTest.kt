package validate

import index.IdIndex
import model.parseYPath
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 整合検証（R16-03a）のテスト。値型→写像→実装の todos ワールドを縮約した3文書で、
 * 「宣言値 = 導出値」の等値判定・判定保留・テンプレート健全性（UX-12）・activeFrom を検証する。
 * P29 Phase 6 第1段から、整合検証は専用の検査プログラムではなく**組込み定義体（YAML）**——スキーマの
 * `consistency` という語彙が、クエリ＋値の比較（check）へ展開されて評価される。このテストは、その展開結果が
 * 従来と同じ指摘（場所・文面・エラー時の対応）を出すことを固定する（変えた点は意図的差分 D-19）。
 */
class ConsistencyTest {

    private val conceptsSchema = SchemaLoader.parseSchemaText(
        """
        id: concepts
        name: コンセプト
        match: "concepts.yaml"
        coverage:
          - description: 全値型は写像を持つこと
            idPattern: "VT-*"
            detailIn:
              schemaIds: [mapping]
        root:
          type: object
          fields:
            - key: types
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: maxLength, type: int }
            - key: fields
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - key: vtype
                    type: ref
                    idPattern: "VT-*"
        """.trimIndent(),
        Path.of("_schemas/concepts.yaml"),
    )

    private val mappingSchema = SchemaLoader.parseSchemaText(
        """
        id: mapping
        name: 型写像
        match: "mapping.yaml"
        root:
          type: object
          fields:
            - key: mappings
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: to, type: string }
        """.trimIndent(),
        Path.of("_schemas/mapping.yaml"),
    )

    /** 実装設計スキーマ（consistency ルールつき）。level を差し替えられる。 */
    private fun implSchema(level: String = "warning", extra: String = "") = SchemaLoader.parseSchemaText(
        """
        id: impl
        name: 実装設計
        match: "impl.yaml"
        $extra
        consistency:
          - description: 実装型は型写像から導出した値と一致すること
            idPattern: "FLD-*"
            path: type
            expected:
              lookup: { schemaId: concepts, via: vtype }
              source: { schemaId: mapping }
              path: to
              values:
                - schemaId: concepts
            level: $level
        root:
          type: object
          fields:
            - key: rows
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: type, type: string }
        """.trimIndent(),
        Path.of("_schemas/impl.yaml"),
    )

    private val conceptsYaml = """
        types:
          - id: VT-TITLE
            maxLength: 100
        fields:
          - id: FLD-TITLE
            vtype: VT-TITLE
        """.trimIndent()

    private fun validate(
        rel: String,
        implYaml: String,
        mappingYaml: String = "mappings:\n  - id: VT-TITLE\n    to: varchar({maxLength})\n",
        schema: schema.SchemaDef = implSchema(),
        concepts: String = conceptsYaml,
        /** 非null なら「未保存編集中のツリー」として検証する（索引は content 基準のまま）。 */
        editedText: String? = null,
    ): List<Issue> = validateDetailed(rel, implYaml, mappingYaml, schema, concepts, editedText).issues

    private fun validateDetailed(
        rel: String,
        implYaml: String,
        mappingYaml: String = "mappings:\n  - id: VT-TITLE\n    to: varchar({maxLength})\n",
        schema: schema.SchemaDef = implSchema(),
        concepts: String = conceptsYaml,
        editedText: String? = null,
    ): ValidationResult {
        val content = mapOf(
            Path.of("concepts.yaml") to concepts,
            Path.of("mapping.yaml") to mappingYaml,
            Path.of("impl.yaml") to implYaml,
        )
        val schemas = listOf(conceptsSchema, mappingSchema, schema)
        val idx = index.testIndex(Path.of("."), content.keys.toList(), schemas) { f -> YamlIO.parse(content.getValue(f)) }
        val file = Path.of(rel)
        return Validator.validateDetailed(
            YamlIO.parse(editedText ?: content.getValue(file)), null, model.DocumentId(rel),
            schemas.first { it.matches(rel) }, idx,
            loader = { d -> content[Path.of(d.value)]?.let { YamlIO.parse(it) } },
            allSchemas = schemas,
        )
    }

    @Test
    fun `値の場所へ出す検査の保留は その値が書かれた文書で集める`() {
        // P27 段B の残り（意図的差分 D-29 ③）: 比べる相手を辿る参照（vtype）の相手が見つからないと、整合検証は充足とも違反とも
        // 決められない。以前は、値の場所へ出す検査の保留は、どこにも残らなかった
        val dangling = "types:\n  - id: VT-TITLE\n    maxLength: 100\nfields:\n  - id: FLD-TITLE\n    vtype: VT-GONE\n"
        val result = validateDetailed("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(999)\n", concepts = dangling)
        assertTrue(result.issues.none { "実装型" in it.message }, result.issues.toString())
        val withheld = result.withheld.single()
        assertEquals("FLD-TITLE", withheld.subjectId)
        assertEquals(semantic.CheckReason.UNRESOLVED_REFERENCE, withheld.reason)
        // 保留の場所は、値が書かれた場所
        assertEquals(listOf<Any>("rows", 0, "type"), withheld.path)
        // 参照が解決できれば、保留ではなく違反として指摘される
        val resolved = validateDetailed("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(999)\n")
        assertTrue(resolved.withheld.isEmpty())
        assertTrue(resolved.issues.any { "実装型" in it.message })
    }

    @Test
    fun `比べる相手の値が複数の文書に書かれていれば全て調べ1つでも食い違えば指摘する`() {
        // 意図的差分 D-19（DL-05「共通の規則に合わせる」）: 従来は指定に合う文書のうち1つ（定義出現を優先。D-17）だけを
        // 読んでいた。定義体へ移してからは、値の比較の共通規則どおり、指定に合う文書をすべて調べる
        val itemFields = """
            root:
              type: object
              fields:
                - key: mappings
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: to, type: string }
        """.trimIndent().prependIndent("")
        val mapDetail = SchemaLoader.parseSchemaText(
            "id: map-detail\nname: 写像（詳細化）\nmatch: \"m1-map.yaml\"\n" +
                "entity: { type: vtype, idPattern: \"VT-*\" }\n" + itemFields,
            Path.of("_schemas/map-detail.yaml"),
        )
        val mapDef = SchemaLoader.parseSchemaText(
            "id: map-def\nname: 写像（定義）\nmatch: \"m2-map.yaml\"\n" +
                "entity: { type: vtype, idPattern: \"VT-*\", defines: true }\n" + itemFields,
            Path.of("_schemas/map-def.yaml"),
        )
        val usageSchema = SchemaLoader.parseSchemaText(
            """
            id: usage
            name: 利用
            match: "usage.yaml"
            consistency:
              - idPattern: "VT-*"
                path: declared
                expected:
                  source: { fileGlob: "m?-map.yaml" }
                  path: to
            root:
              type: object
              fields:
                - key: types
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: declared, type: string }
            """.trimIndent(),
            Path.of("_schemas/usage.yaml"),
        )
        val schemas = listOf(mapDetail, mapDef, usageSchema)
        fun check(usageYaml: String): List<Issue> {
            // 索引順は m1（詳細化）→ m2（定義）→ usage: 先勝ちなら m1 の varchar(50) が期待値になる
            val content = mapOf(
                Path.of("m1-map.yaml") to "mappings:\n  - id: VT-TITLE\n    to: varchar(50)\n",
                Path.of("m2-map.yaml") to "mappings:\n  - id: VT-TITLE\n    to: varchar(100)\n",
                Path.of("usage.yaml") to usageYaml,
            )
            val idx = index.testIndex(Path.of("."), content.keys.toList(), schemas) { f -> YamlIO.parse(content.getValue(f)) }
            return Validator.validate(
                YamlIO.parse(usageYaml), null, model.DocumentId("usage.yaml"), usageSchema, idx,
                loader = { d -> content[Path.of(d.value)]?.let { YamlIO.parse(it) } },
                allSchemas = schemas,
            )
        }
        // m1（varchar(50)）と m2（varchar(100)）の両方が指定に合う → どちらの値を書いても、もう一方と食い違う
        val withDefining = check("types:\n  - id: VT-TITLE\n    declared: varchar(100)\n")
            .single { "一致しません" in it.message }
        assertTrue("導出値「varchar(50)」" in withDefining.message, withDefining.message)
        val withDetail = check("types:\n  - id: VT-TITLE\n    declared: varchar(50)\n")
            .single { "一致しません" in it.message }
        assertTrue("導出値「varchar(100)」" in withDetail.message, withDetail.message)
    }

    @Test
    fun `宣言値が導出値と一致すれば指摘しない`() {
        val issues = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(100)\n")
        assertTrue(issues.none { "一致しません" in it.message }, "$issues")
    }

    @Test
    fun `宣言値のドリフトを導出値つきで指摘する`() {
        val issues = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: int\n")
        val issue = issues.single { "一致しません" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("「int」" in issue.message && "導出値「varchar(100)」" in issue.message, issue.message)
        assertEquals(parseYPath("rows[0].type"), issue.path, "指摘は検査した値の座標に付く")

        // エラー時の対応（SP-09）: 組込み定義体が宣言した「導出規則へ／入力値へ／導出値を適用」が、宣言の順に付く
        val (toRule, toInput, apply) = issue.responses
        toRule as CheckResponse.Jump
        assertEquals("導出規則へ: varchar({maxLength})", toRule.label)
        assertEquals(model.DocumentId("mapping.yaml"), toRule.document)
        assertEquals(parseYPath("mappings[0].to"), toRule.path)
        toInput as CheckResponse.Jump
        assertEquals("入力値へ: {maxLength} = 100", toInput.label)
        assertEquals(model.DocumentId("concepts.yaml"), toInput.document)
        assertEquals(parseYPath("types[0].maxLength"), toInput.path)
        apply as CheckResponse.ApplyValue
        assertEquals("導出値を適用: varchar(100)", apply.label)
        assertEquals("varchar(100)", apply.value)
    }

    @Test
    fun `出現先の文書に不足IDの案内が出て未保存の追加で消える`() {
        // VT-STAFF に写像行がない → 出現先（mapping.yaml）側に INFO の案内（UX-15）
        val concepts2 = """
            types:
              - id: VT-TITLE
                maxLength: 100
              - id: VT-STAFF
                maxLength: 50
            fields:
              - id: FLD-TITLE
                vtype: VT-TITLE
            """.trimIndent()
        val impl = "rows:\n  - id: FLD-TITLE\n    type: varchar(100)\n"
        val hinted = validate("mapping.yaml", impl, concepts = concepts2)
        val hint = hinted.single { "不足しているID" in it.message }
        assertEquals(Severity.INFO, hint.severity)
        assertTrue("VT-STAFF" in hint.message, hint.message)

        // 未保存編集で行を追加すると、その場で案内が消える（索引は未更新のまま）
        val edited = validate(
            "mapping.yaml", impl, concepts = concepts2,
            editedText = "mappings:\n  - id: VT-TITLE\n    to: varchar({maxLength})\n  - id: VT-STAFF\n    to: varchar(50)\n",
        )
        assertTrue(edited.none { "不足しているID" in it.message }, "$edited")

        // 定義側でも出現先でもない文書（impl.yaml）には案内を出さない
        val impl2 = validate("impl.yaml", impl, concepts = concepts2)
        assertTrue(impl2.none { "不足しているID" in it.message }, "$impl2")
    }

    @Test
    fun `level error は検証エラーになりゲートを塞げる`() {
        val issues = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: int\n", schema = implSchema("error"))
        assertEquals(Severity.ERROR, issues.single { "一致しません" in it.message }.severity)
    }

    @Test
    fun `導出できない場合は判定保留として沈黙する`() {
        // 写像行がない（存在の検査は detailIn カバレッジの領分）
        val noMapping = validate(
            "impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: int\n",
            mappingYaml = "mappings: []\n",
        )
        assertTrue(noMapping.none { "一致しません" in it.message }, "$noMapping")
        // ref（vtype）未記入・検査する値が未記入・idPattern 対象外も判定しない
        val partial = validate(
            "impl.yaml",
            "rows:\n  - id: FLD-TITLE\n  - id: X-1\n    type: junk\n",
        )
        assertTrue(partial.none { "一致しません" in it.message }, "$partial")
        // 期待値のプレースホルダが未解決（テンプレート健全性はソース文書側で指摘する）
        val incomplete = validate(
            "impl.yaml", "rows:\n  - id: FLD-TITLE\n    type: int\n",
            mappingYaml = "mappings:\n  - id: VT-TITLE\n    to: varchar({maxlen})\n",
        )
        assertTrue(incomplete.none { "一致しません" in it.message }, "$incomplete")
    }

    @Test
    fun `解決できないプレースホルダはソース文書の検証で指摘される`() {
        val issues = validate(
            "mapping.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(100)\n",
            mappingYaml = "mappings:\n  - id: VT-TITLE\n    to: varchar({maxlen})\n",
        )
        val issue = issues.single { "プレースホルダ" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("{maxlen}" in issue.message, issue.message)
        assertEquals(parseYPath("mappings[0].to"), issue.path, "指摘はテンプレートの座標に付く")
        // 解決できるテンプレートには指摘しない
        val ok = validate("mapping.yaml", "rows:\n  - id: FLD-TITLE\n    type: varchar(100)\n")
        assertTrue(ok.none { "プレースホルダ" in it.message }, "$ok")
    }

    @Test
    fun `activeFrom はフェーズ到達まで判定を保留する`() {
        val phased = SchemaLoader.parseSchemaText(
            """
            id: impl
            name: 実装設計
            match: "impl.yaml"
            phases:
              order: [design, build]
            consistency:
              - path: type
                idPattern: "FLD-*"
                activeFrom: build
                expected:
                  lookup: { schemaId: concepts, via: vtype }
                  source: { schemaId: mapping }
                  path: to
                  values:
                    - schemaId: concepts
            root:
              type: object
              fields:
                - key: phase
                  type: string
                - key: rows
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: type, type: string }
            """.trimIndent(),
            Path.of("_schemas/impl.yaml"),
        )
        val early = validate("impl.yaml", "phase: design\nrows:\n  - id: FLD-TITLE\n    type: int\n", schema = phased)
        assertTrue(early.none { "一致しません" in it.message }, "$early")
        val late = validate("impl.yaml", "phase: build\nrows:\n  - id: FLD-TITLE\n    type: int\n", schema = phased)
        assertTrue(late.any { "一致しません" in it.message }, "$late")
    }

    @Test
    fun `適合判定（lte）は導出値以下なら許し、超えたら指摘する`() {
        // 画面入力の maxLength ≦ 値型の maxLength（R16-03b）を縮約したルール
        val screenSchema = SchemaLoader.parseSchemaText(
            """
            id: impl
            name: 入力欄
            match: "impl.yaml"
            consistency:
              - description: 入力欄の最大文字数は値型の最大文字数以下であること
                idPattern: "FLD-*"
                path: cap
                assert: lte
                expected:
                  lookup: { schemaId: concepts, via: vtype }
                  source: { schemaId: concepts }
                  path: maxLength
                level: warning
            root:
              type: object
              fields:
                - key: rows
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: vtype, type: ref, idPattern: "VT-*" }
                      - { key: cap, type: int }
            """.trimIndent(),
            Path.of("_schemas/impl.yaml"),
        )
        // 等値でなくてよい: 50 ≦ 100 は適合
        val ok = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    vtype: VT-TITLE\n    cap: 50\n", schema = screenSchema)
        assertTrue(ok.none { "導出値" in it.message }, "$ok")
        // 超過は指摘（以下である必要があります）
        val over = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    vtype: VT-TITLE\n    cap: 200\n", schema = screenSchema)
        val issue = over.single { "導出値" in it.message }
        assertTrue("「200」" in issue.message && "導出値「100」以下である必要があります" in issue.message, issue.message)
        // 数値でない値は判定保留
        val nan = validate("impl.yaml", "rows:\n  - id: FLD-TITLE\n    vtype: VT-TITLE\n    cap: abc\n", schema = screenSchema)
        assertTrue(nan.none { "導出値" in it.message }, "$nan")
    }

    @Test
    fun `不正な整合ルールはパースとメタ検証で拒否される`() {
        assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                "id: x\nname: x\nmatch: \"a.yaml\"\nconsistency:\n  - expected: { source: { schemaId: s }, path: to }\nroot: { type: object, fields: [] }",
                Path.of("_schemas/x.yaml"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                "id: x\nname: x\nmatch: \"a.yaml\"\nconsistency:\n  - path: type\n    expected: { source: {}, path: to }\nroot: { type: object, fields: [] }",
                Path.of("_schemas/x.yaml"),
            )
        }
    }
}
