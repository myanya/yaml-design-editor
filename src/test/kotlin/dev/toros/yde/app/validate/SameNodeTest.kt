package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ノード一致 `same:`（R28-07）のテスト。
 * 「2経路が同一ノードに到達すること」の照合（型不一致検査の土台）を、
 * also 分岐形と連鎖形（in エッジでサイクルを閉じる）の両評価経路で検証する。
 * same は「同じノードを二度通らない」規則の意図的な例外——一致ケースが黙ることが
 * 例外の成立（束縛済みノードへの再到達が許されること）の証明になる。
 */
class SameNodeTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-same")
        write(
            "_schemas/types.yaml",
            """
            id: types
            match: "types.yaml"
            entity: { type: dtype, idPattern: "DT-*", defines: true }
            root:
              type: object
              fields:
                - key: types
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
            """,
        )
        write(
            "_schemas/attrs.yaml",
            """
            id: attrs
            match: "attrs.yaml"
            entity: { type: attr, idPattern: "EA-*", defines: true }
            root:
              type: object
              fields:
                - key: attrs
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [types] } }
            """,
        )
        write(
            "_schemas/cols.yaml",
            """
            id: cols
            match: "cols.yaml"
            entity: { type: col, idPattern: "COL-*", defines: true }
            root:
              type: object
              fields:
                - key: cols
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: implements, type: ref, relation: implements, scope: { schemaIds: [attrs] } }
                      - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [types] } }
            constraints:
              - id: type-consistent-also
                description: カラムの型が実装する属性の型と一致すること（also形）
                match:
                  - node: { entity: col, bind: c }
                  - edge: { relation: implements }
                  - node: { entity: attr }
                  - edge: { relation: dtype }
                  - node: { entity: dtype, bind: t }
                also:
                  from: c
                  steps:
                    - edge: { relation: dtype }
                    - node: { same: t }
                expect: exists
                level: error
              - id: type-consistent-chain
                description: カラムの型が実装する属性の型と一致すること（連鎖形）
                match:
                  - node: { entity: col, bind: c }
                  - edge: { relation: implements }
                  - node: { entity: attr }
                  - edge: { relation: dtype }
                  - node: { entity: dtype }
                  - edge: { dir: in, relation: dtype }
                  - node: { same: c }
                expect: exists
                level: error
            """,
        )
        write("types.yaml", "types:\n- { id: DT-A }\n- { id: DT-B }")
        write("attrs.yaml", "attrs:\n- { id: EA-1, dtype: DT-A }\n- { id: EA-2, dtype: DT-A }")
        // COL-1 は一致（DT-A = DT-A）・COL-2 は不一致（属性は DT-A・カラムは DT-B）
        write(
            "cols.yaml",
            "cols:\n- { id: COL-1, implements: EA-1, dtype: DT-A }\n- { id: COL-2, implements: EA-2, dtype: DT-B }",
        )
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
    fun `型不一致のカラムだけが報告される（also形と連鎖形の両方）`() {
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val issues = ws.validate("cols.yaml")
        val alsoIssues = issues.filter { "also形" in it.message }
        val chainIssues = issues.filter { "連鎖形" in it.message }
        // COL-2（不一致）は両形式で報告される
        assertTrue(alsoIssues.any { "COL-2" in it.message && it.severity == Severity.ERROR }, issues.toString())
        assertTrue(chainIssues.any { "COL-2" in it.message && it.severity == Severity.ERROR }, issues.toString())
        // COL-1（一致）は黙る＝same による束縛済みノードへの再到達（二度通らない規則の例外）が効いている
        assertTrue(alsoIssues.none { "COL-1" in it.message }, issues.toString())
        assertTrue(chainIssues.none { "COL-1" in it.message }, issues.toString())
    }

    @Test
    fun `sameに他のキーを同居するとスキーマ定義エラーになる`() {
        writeBrokenSchema(
            """
                - node: { entity: col, bind: c }
                - edge: { relation: dtype }
                - node: { same: c, entity: dtype }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "same には他のキーを同居できません" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `sameが先行する束縛を参照しないとスキーマ定義エラーになる`() {
        writeBrokenSchema(
            """
                - node: { entity: col, bind: c }
                - edge: { relation: dtype }
                - node: { same: nothing }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "same の束縛「nothing」" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `起点nodeにsameは書けない`() {
        writeBrokenSchema(
            """
                - node: { same: x }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "先頭 node に same は書けません" in it }, ws.schemaErrors.toString())
    }

    /** match だけ差し替えた壊れ制約のスキーマを追加する（パースエラー系テスト用）。 */
    private fun writeBrokenSchema(matchYaml: String) {
        val indented = matchYaml.trimIndent().lines().joinToString("\n") { "                  $it" }
        write(
            "_schemas/broken.yaml",
            """
            id: broken
            match: "broken/*.yaml"
            entity: { type: broken-entity, idPattern: "BX-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            constraints:
              - id: broken-rule
                match:
$indented
                expect: exists
            """,
        )
    }
}
