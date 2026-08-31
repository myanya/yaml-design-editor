package validate

import index.IdIndex
import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 出現ベースのカバレッジルール（R7-02 / L-15）のテスト。
 * 「一覧の全IDに詳細文書（指定スキーマの出現）があること」を索引の出現数で機械判定する。
 */
class CoverageTest {

    private val listSchema = SchemaLoader.parseSchemaText(
        """
        id: screen-list
        name: 画面一覧
        match: "screens/index.yaml"
        coverage:
          - description: 全画面は詳細文書を持つこと
            detailIn:
              schemaIds: [screen-detail]
            level: error
        root:
          type: object
          fields:
            - key: screens
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: name, type: string }
        """.trimIndent(),
        Path.of("_schemas/screen-list.yaml"),
    )

    private val detailSchema = SchemaLoader.parseSchemaText(
        """
        id: screen-detail
        name: 画面詳細
        match: "screens/detail/*.yaml"
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
        """.trimIndent(),
        Path.of("_schemas/screen-detail.yaml"),
    )

    private val listYaml = """
        screens:
          - id: SCR-LIST
            name: 一覧画面
          - id: SCR-EDIT
            name: 編集画面
        """.trimIndent()

    /** 一覧文書と（withDetail なら）SCR-LIST の詳細文書だけを持つ索引を組む。 */
    private fun index(withDetail: Boolean): IdIndex {
        val files = mutableListOf(Path.of("screens/index.yaml"))
        val content = mutableMapOf(Path.of("screens/index.yaml") to listYaml)
        if (withDetail) {
            files.add(Path.of("screens/detail/SCR-LIST.yaml"))
            content[Path.of("screens/detail/SCR-LIST.yaml")] = "id: SCR-LIST\n"
        }
        return index.testIndex(Path.of("."), files, listOf(listSchema, detailSchema)) { f -> YamlIO.parse(content.getValue(f)) }
    }

    @Test
    fun `詳細文書のないIDは出現ベースのカバレッジで指摘される`() {
        val issues = Validator.validate(
            YamlIO.parse(listYaml), null, Path.of("screens/index.yaml"), listSchema, index(withDetail = true),
        )
        assertTrue(issues.none { "SCR-LIST" in it.message }, "$issues")
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "SCR-EDIT" in it.message && "詳細文書が不足" in it.message },
            "$issues",
        )
    }

    @Test
    fun `詳細出現がminCountに達すれば充足する`() {
        val status = Validator.coverageStatuses("SCR-LIST", listSchema, index(withDetail = true)).single()
        assertEquals(1, status.count)
        assertTrue(status.satisfied)
    }

    @Test
    fun `一覧文書自身の出現は詳細スキーマの出現として数えない`() {
        // SCR-EDIT は一覧（screen-list）にのみ出現する。screen-detail の出現数は 0 のまま
        val status = Validator.coverageStatuses("SCR-EDIT", listSchema, index(withDetail = true)).single()
        assertEquals(0, status.count)
        assertFalse(status.satisfied)
    }

    @Test
    fun `detailInのminCountが解釈される`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: screen-list
            name: 画面一覧
            match: "screens/index.yaml"
            coverage:
              - detailIn: { schemaIds: [screen-detail], minCount: 2 }
            root:
              type: object
            """.trimIndent(),
            Path.of("_schemas/screen-list.yaml"),
        )
        val status = Validator.coverageStatuses("SCR-LIST", schema, index(withDetail = true)).single()
        assertEquals(2, status.rule.minCount)
        assertFalse(status.satisfied)
    }

    @Test
    fun `出現ベースのルールもactiveFromでフェーズ到達まで保留される`() {
        val phased = SchemaLoader.parseSchemaText(
            """
            id: screen-list
            name: 画面一覧
            match: "screens/index.yaml"
            phases: [draft, detail-design]
            coverage:
              - detailIn: { schemaIds: [screen-detail] }
                activeFrom: detail-design
                level: error
            root:
              type: object
            """.trimIndent(),
            Path.of("_schemas/screen-list.yaml"),
        )
        val pending = Validator.coverageStatuses(
            "SCR-EDIT", phased, index(withDetail = false), definingRoot = YamlIO.parse("phase: draft\n"),
        ).single()
        assertFalse(pending.active)
        val active = Validator.coverageStatuses(
            "SCR-EDIT", phased, index(withDetail = false), definingRoot = YamlIO.parse("phase: detail-design\n"),
        ).single()
        assertTrue(active.active)
        assertFalse(active.satisfied)
    }

    @Test
    fun `requiredByとdetailInの併用は定義エラー`() {
        val ex = assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                """
                id: s
                name: s
                match: "*.yaml"
                coverage:
                  - requiredBy: { minCount: 1 }
                    detailIn: { schemaIds: [d] }
                root:
                  type: object
                """.trimIndent(),
                Path.of("_schemas/s.yaml"),
            )
        }
        assertTrue("併用できません" in (ex.message ?: ""), ex.message ?: "")
    }

    @Test
    fun `detailInにはschemaIdsが必要`() {
        val ex = assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                """
                id: s
                name: s
                match: "*.yaml"
                coverage:
                  - detailIn: { minCount: 2 }
                root:
                  type: object
                """.trimIndent(),
                Path.of("_schemas/s.yaml"),
            )
        }
        assertTrue("schemaIds" in (ex.message ?: ""), ex.message ?: "")
    }
}
