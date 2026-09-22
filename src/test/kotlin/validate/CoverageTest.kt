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
 * P29 Phase 6 第3段で、coverage は組込み定義体（builtin-definitions/coverage.yaml）が作る検査になった——
 * 充足の状況は「status を宣言した検査」の一般の仕組み（Validator.ruleStatuses）で読む。
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
            YamlIO.parse(listYaml), null, model.DocumentId("screens/index.yaml"), listSchema, index(withDetail = true),
        )
        assertTrue(issues.none { "SCR-LIST" in it.message }, "$issues")
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "SCR-EDIT" in it.message && "詳細文書が不足" in it.message },
            "$issues",
        )
    }

    @Test
    fun `詳細出現がminCountに達すれば充足する`() {
        val status = Validator.ruleStatuses("SCR-LIST", listSchema, index(withDetail = true)).single()
        assertEquals("出現 1 件（必要 1 件）", status.summary)
        assertTrue(status.satisfied)
        assertEquals("出現 1 件（必要 1 件） — 全画面は詳細文書を持つこと", status.text)
    }

    @Test
    fun `一覧文書自身の出現は詳細スキーマの出現として数えない`() {
        // SCR-EDIT は一覧（screen-list）にのみ出現する。screen-detail の出現数は 0 のまま
        val status = Validator.ruleStatuses("SCR-EDIT", listSchema, index(withDetail = true)).single()
        assertEquals("出現 0 件（必要 1 件）", status.summary)
        assertFalse(status.satisfied)
        // 不足しているときの表示は、検証パネルに出る指摘の文面と同じ
        assertTrue("ID「SCR-EDIT」の詳細文書が不足しています（現在 0 件／必要 1 件" in status.text, status.text)
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
        val status = Validator.ruleStatuses("SCR-LIST", schema, index(withDetail = true)).single()
        assertEquals("出現 1 件（必要 2 件）", status.summary)
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
        val pending = Validator.ruleStatuses(
            "SCR-EDIT", phased, index(withDetail = false), definingRoot = YamlIO.parse("phase: draft\n"),
        ).single()
        assertFalse(pending.active)
        // フェーズ未到達でも、いまの値は状況に出す
        assertEquals("フェーズ未到達（detail-design から判定。現在 出現 0 件（必要 1 件））", pending.text)
        val active = Validator.ruleStatuses(
            "SCR-EDIT", phased, index(withDetail = false), definingRoot = YamlIO.parse("phase: detail-design\n"),
        ).single()
        assertTrue(active.active)
        assertFalse(active.satisfied)
    }

    @Test
    fun `idPattern外のIDにはルールが適用されない`() {
        val patterned = SchemaLoader.parseSchemaText(
            """
            id: screen-list
            name: 画面一覧
            match: "screens/index.yaml"
            coverage:
              - idPattern: "SCR-*"
                detailIn: { schemaIds: [screen-detail] }
            root:
              type: object
            """.trimIndent(),
            Path.of("_schemas/screen-list.yaml"),
        )
        assertTrue(Validator.ruleStatuses("OTHER-1", patterned, index(withDetail = false)).isEmpty())
        assertFalse(Validator.ruleStatuses("SCR-EDIT", patterned, index(withDetail = false)).isEmpty())
    }

    @Test
    fun `廃止されたrequiredByは移行先を案内する定義エラーになる`() {
        // 語彙の刈り込み（R29-01・D-16）: 黙って無視せず、正準の綴り（cardinality / constraints）を案内する
        val ex = assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                """
                id: s
                name: s
                match: "*.yaml"
                coverage:
                  - requiredBy: { minCount: 1 }
                root:
                  type: object
                """.trimIndent(),
                Path.of("_schemas/s.yaml"),
            )
        }
        val message = ex.message ?: ""
        assertTrue("廃止されました" in message, message)
        assertTrue("cardinality" in message && "constraints" in message, message)
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
