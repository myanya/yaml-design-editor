package validate

import index.IdIndex
import model.YNode
import schema.SchemaLoader
import schema.validateDefinition
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * フェーズ機構（P7）: requiredFrom の累積必須判定、activeFrom のカバレッジ保留、
 * フェーズ別記入進捗（phaseProgress）を検証する。
 */
class PhaseTest {

    private val entitySchema = SchemaLoader.parseSchemaText(
        """
        id: entity
        name: エンティティ
        match: "models/**/*.yaml"
        phases:
          key: phase
          order: [requirements, logical-design, physical-design]
        root:
          type: object
          fields:
            - key: id
              type: id
              required: true
            - key: phase
              type: enum
              values: [requirements, logical-design, physical-design]
              default: requirements
            - key: name
              type: string
              required: true
            - key: columns
              type: array
              requiredFrom: logical-design
              item:
                type: object
                fields:
                  - key: name
                    type: string
                    required: true
            - key: physicalName
              type: string
              requiredFrom: physical-design
        coverage:
          - detailIn: { schemaIds: [table-design] }
            activeFrom: physical-design
            level: error
        """.trimIndent(),
        Path.of("_schemas/entity.yaml"),
    )

    private val tableSchema = SchemaLoader.parseSchemaText(
        """
        id: table-design
        name: テーブル設計
        match: "tables/**/*.yaml"
        root:
          type: object
          fields:
            - key: id
              type: id
        """.trimIndent(),
        Path.of("_schemas/table-design.yaml"),
    )

    private fun validate(yaml: String, index: IdIndex = IdIndex()) =
        Validator.validate(YamlIO.parse(yaml), null, model.DocumentId("models/a.yaml"), entitySchema, index)

    /** models/a.yaml に entityYaml、tables/t.yaml に tableYaml（null なら文書なし）を置いた索引を組む。 */
    private fun indexWith(entityYaml: String, tableYaml: String? = null): IdIndex {
        val files = mutableListOf(Path.of("models/a.yaml"))
        val content = mutableMapOf(Path.of("models/a.yaml") to entityYaml)
        if (tableYaml != null) {
            files.add(Path.of("tables/t.yaml"))
            content[Path.of("tables/t.yaml")] = tableYaml
        }
        return index.testIndex(Path.of("."), files, listOf(entitySchema, tableSchema)) { f -> YamlIO.parse(content.getValue(f)) }
    }

    @Test
    fun `未来フェーズのrequiredFromはエラーにならない`() {
        val issues = validate(
            """
            id: ENT-001
            phase: requirements
            name: 顧客
            """.trimIndent()
        )
        assertTrue(issues.none { it.message.contains("columns") }, "$issues")
        assertTrue(issues.none { it.message.contains("physicalName") }, "$issues")
    }

    @Test
    fun `現在フェーズ以降に到達したrequiredFromは未記入だとエラーになる`() {
        val issues = validate(
            """
            id: ENT-001
            phase: logical-design
            name: 顧客
            """.trimIndent()
        )
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("columns") }, "$issues")
        // physical-design はまだ未到達なのでエラーにならない
        assertTrue(issues.none { it.message.contains("physicalName") }, "$issues")
    }

    @Test
    fun `フェーズが進むと過去に必須化した項目も引き続き必須`() {
        val issues = validate(
            """
            id: ENT-001
            phase: physical-design
            name: 顧客
            """.trimIndent()
        )
        assertTrue(issues.any { it.message.contains("columns") }, "$issues")
        assertTrue(issues.any { it.message.contains("physicalName") }, "$issues")
    }

    @Test
    fun `phaseSummaryTextは現在フェーズと進捗を1行で表す`() {
        val summary = Validator.phaseSummaryText(
            YamlIO.parse("id: ENT-001\nphase: logical-design\nname: 顧客\ncolumns:\n  - name: id\n"),
            entitySchema,
        )
        assertEquals("phase=logical-design（logical-design 1/1, physical-design 0/1）", summary)
    }

    @Test
    fun `phasesを宣言していないスキーマではphaseSummaryTextはnull`() {
        assertEquals(null, Validator.phaseSummaryText(YamlIO.parse("entity: ENT-001\n"), tableSchema))
    }

    @Test
    fun `requiredFromを満たせばエラーは出ない`() {
        // phase: physical-design の coverage（activeFrom: physical-design）も満たすため参照つきの索引を使う
        val entityYaml = """
            id: ENT-001
            phase: physical-design
            name: 顧客
            columns:
              - name: id
            physicalName: t_customer
            """.trimIndent()
        val issues = validate(entityYaml, indexWith(entityYaml, tableYaml = "id: ENT-001\n"))
        assertTrue(issues.none { it.severity == Severity.ERROR }, "$issues")
    }

    @Test
    fun `フェーズ未申告は先頭フェーズとして扱われる`() {
        val issues = validate(
            """
            id: ENT-001
            name: 顧客
            """.trimIndent()
        )
        assertTrue(issues.none { it.message.contains("columns") }, "$issues")
    }

    @Test
    fun `未知のフェーズ値は警告になり先頭フェーズとして扱われる`() {
        val issues = validate(
            """
            id: ENT-001
            phase: unknown-phase
            name: 顧客
            """.trimIndent()
        )
        assertTrue(issues.any { it.severity == Severity.WARNING && it.message.contains("phases") }, "$issues")
        assertTrue(issues.none { it.message.contains("columns") }, "$issues")
    }

    @Test
    fun `カバレッジのactiveFromに達するまでは詳細文書の不足が判定されない`() {
        // 詳細文書の存在は、他の検査と同じく索引にある要素を判定する（P29 Phase 6 第3段・意図的差分 D-21 ⑤）
        val yaml = """
            id: ENT-001
            phase: logical-design
            name: 顧客
            columns:
              - name: id
            """.trimIndent()
        val issues = validate(yaml, indexWith(yaml))
        assertTrue(issues.none { it.message.contains("詳細文書が不足") }, "$issues")
    }

    @Test
    fun `カバレッジのactiveFromに達すると詳細文書の不足が判定される`() {
        val yaml = """
            id: ENT-001
            phase: physical-design
            name: 顧客
            columns:
              - name: id
            physicalName: t_customer
            """.trimIndent()
        val issues = validate(yaml, indexWith(yaml))
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("詳細文書が不足") }, "$issues")
    }

    @Test
    fun `activeFromに達し詳細文書があれば充足する`() {
        val entityYaml = """
            id: ENT-001
            phase: physical-design
            name: 顧客
            columns:
              - name: id
            physicalName: t_customer
            """.trimIndent()
        val index = indexWith(entityYaml, tableYaml = "id: ENT-001\n")
        val issues = validate(entityYaml, index)
        assertTrue(issues.none { it.message.contains("詳細文書が不足") }, "$issues")
    }

    @Test
    fun `phaseProgressはrequiredFromを持つフィールドの記入状況をフェーズごとに集計する`() {
        val root = YamlIO.parse(
            """
            id: ENT-001
            phase: logical-design
            name: 顧客
            columns:
              - name: id
            """.trimIndent()
        )
        val progress = Validator.phaseProgress(root, entitySchema)
        val logical = progress.single { it.phase == "logical-design" }
        assertEquals(1, logical.filled)
        assertEquals(1, logical.total)
        val physical = progress.single { it.phase == "physical-design" }
        assertEquals(0, physical.filled)
        assertEquals(1, physical.total)
        val requirements = progress.single { it.phase == "requirements" }
        assertEquals(0, requirements.total)
    }

    @Test
    fun `requiredFromにはphases宣言が必要というメタ検証`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            root:
              type: object
              fields:
                - key: x
                  type: string
                  requiredFrom: later
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("phases の宣言が必要") })
    }

    @Test
    fun `constraintsのactiveFromにもphases宣言が必要というメタ検証`() {
        // R29-03: activeFrom の静的検査は全検証語彙（coverage / consistency / constraints / matrix / flow）で共通
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            constraints:
              - match:
                  - node: { id: "X-*", bind: x }
                  - edge: { dir: in }
                  - node: { id: "Y-*" }
                expect: exists
                activeFrom: later
            root:
              type: object
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("phases の宣言が必要") })
    }

    @Test
    fun `requiredとrequiredFromの併用は定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            phases:
              order: [a, b]
            root:
              type: object
              fields:
                - key: x
                  type: string
                  required: true
                  requiredFrom: b
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("併用できません") })
    }

    @Test
    fun `requiredFromが未知のフェーズ名だと定義エラー`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            phases:
              order: [a, b]
            root:
              type: object
              fields:
                - key: x
                  type: string
                  requiredFrom: c
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("含まれていません") })
    }

    @Test
    fun `phasesの簡易記法（リスト直書き）も既定キーphaseで解釈される`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: s
            name: s
            match: "*.yaml"
            phases: [a, b]
            root:
              type: object
              fields:
                - key: y
                  type: string
                  requiredFrom: b
            """.trimIndent(),
            Path.of("_schemas/s.yaml"),
        )
        assertEquals("phase", schema.phases?.key)
        assertEquals(listOf("a", "b"), schema.phases?.order)
        val issues = Validator.validate(
            YamlIO.parse("phase: a\n") as YNode,
            null, model.DocumentId("x.yaml"), schema, IdIndex(),
        )
        assertFalse(issues.any { it.message.contains("必須項目「y」") })
    }
}
