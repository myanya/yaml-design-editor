package validate

import core.Workspace
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * まだ文書に入れていない内容（AI 生成の提案など）の検証（BACKLOG UX-109）: 定義体の検査は索引にある要素だけを判定するため、
 * 提案で新しく書かれた ID に検査を効かせるには、提案を重ねた索引を渡す（アプリは AppState.overlayIndexWith）。
 */
class PreviewValidationTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-preview")
        write(
            "_schemas/screens.yaml",
            """
            id: screens
            match: "screens.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - key: uses
                        type: array
                        item: { type: ref, relation: uses }
            constraints:
              - id: uses-something
                description: 何かを1つ以上利用すること
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { bind: t }
                expect: exists
                level: error
            """,
        )
        write("_schemas/uc.yaml", "id: uc\nmatch: \"ucs/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id, required: true }")
        write("ucs/UC-1.yaml", "id: UC-1")
        write("screens.yaml", "screens:\n  - id: SCR-1\n    uses: [UC-1]")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    @Test
    fun `提案を重ねた索引で検証すると 新しく書かれた ID にも検査が効く`() {
        val ws = Workspace.open(dir)
        val file = dir.resolve("screens.yaml")
        val proposal = YamlIO.parse("screens:\n  - id: SCR-1\n    uses: [UC-1]\n  - id: SCR-NEW\n    uses: []")
        fun issues(index: index.IdIndex) = Validator.validate(
            proposal, null, ws.documentOf(file), ws.schemaFor(file), index, loader = ws::treeOf, allSchemas = ws.schemas,
            relations = ws.relations, enforcement = ws.enforcement,
        )
        // ディスクの索引のまま: SCR-NEW は索引に無いため、検査の対象にならない（指摘が出ない）
        assertTrue(issues(ws.index).none { "SCR-NEW" in it.message }, issues(ws.index).toString())
        // 提案を重ねた索引: SCR-NEW にも検査が効く
        val overlaid = issues(ws.overlayIndex(mapOf(file to proposal)))
        assertTrue(overlaid.any { "SCR-NEW" in it.message && it.severity == Severity.ERROR }, overlaid.toString())
    }
}
