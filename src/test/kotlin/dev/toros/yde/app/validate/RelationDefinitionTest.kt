package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 関係の宣言の検査（多重度・対象型）が組込み定義体（builtin-definitions/relations.yaml）になったことのテスト
 * （P29 Phase 6 第2段・意図的差分 D-20）。判定の内容（文面・場所・重大度）は GraphIntegrityTest が従来どおり保証する。
 * ここでは、定義体になって変わった点と、利用者が同じ仕組みを使えることを確かめる。
 */
class RelationDefinitionTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-relation-definition")
        write(
            "_schemas/relations.yaml",
            """
            id: relations
            kind: relations
            relations:
              - name: uses
                source: [usecase, batch]
                target: [screen]
                cardinality: { inMin: 1 }
              - name: owns
                source: [usecase]
                target: [screen]
                mustHaveOwner: true
            """,
        )
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "usecases/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: screens, type: array, item: { type: ref, relation: uses } }
                - { key: owned, type: array, item: { type: ref, relation: owns } }
            """,
        )
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            match: "details/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: layout, type: string }
            """,
        )
        write("usecases/UC-1.yaml", "id: UC-1\nscreens: [SCR-1]\nowned: [SCR-1]")
        write("screens/SCR-1.yaml", "id: SCR-1\nname: 一覧")
        write("screens/SCR-2.yaml", "id: SCR-2\nname: 詳細")
        write("details/SCR-2.yaml", "id: SCR-2\nlayout: two-column")
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
    fun `多重度の指摘は定義した場所に出て同じIDが書かれた他の文書には案内が出る`() {
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val report = ws.validate("screens/SCR-2.yaml").single { "被参照が不足" in it.message }
        assertEquals(Severity.WARNING, report.severity)
        assertEquals("エンティティ「SCR-2」への関係「uses」の被参照が不足しています（現在 0 件／下限 1 件）", report.message)
        // 他の検査と同じ配送規則（意図的差分 D-20 ②）: 詳細化の文書しか開いていなくても気づける
        val notice = ws.validate("details/SCR-2.yaml").single { "被参照が不足" in it.message }
        assertEquals(Severity.INFO, notice.severity)
        assertTrue("報告先: 定義出現 screens/SCR-2.yaml" in notice.message, notice.message)
        // 足りている要素には何も出ない
        assertTrue(ws.validate("screens/SCR-1.yaml").none { "被参照" in it.message })
    }

    @Test
    fun `関係の宣言の語彙は検査へ展開されCLIのchecksで確認できる`() {
        val ws = Workspace.open(dir)
        val registry = ws.schemas.single { it.kind == SchemaKind.RELATIONS }
        val origins = registry.expandedChecks.map { it.origin }
        assertTrue(origins.any { "relations[0]" in it && "relation-source-type" in it }, origins.toString())
        assertTrue(origins.any { "relations[0]" in it && "relation-target-type" in it }, origins.toString())
        assertTrue(origins.any { "relations[0]" in it && "relation-in-count" in it }, origins.toString())
        // cardinality を書いていない関係には、多重度の検査は作られない
        assertTrue(origins.none { "relations[1]" in it && "count" in it }, origins.toString())
    }

    @Test
    fun `利用者も関係の宣言に効く語彙を同じ仕組みで作れる`() {
        // 組込みは特権を持たない（CALC-28）: 関係の宣言に mustHaveOwner と書くと、target の要素に所有者の参照を要求する
        write(
            "_schemas/my-relation-words.yaml",
            """
            id: my-relation-words
            kind: library
            functions:
              - id: target-has-owner
                params: [relation, types]
                checks:
                  - match:
                      - node: { entity: ${'$'}types, bind: subject }
                    check:
                      - gte: [{ refCount: { of: subject, dir: in, relation: ${'$'}relation } }, 1]
                    message: 「{id}」（{subject.title}）には関係「${'$'}relation」の所有者がいません
                    level: error
            vocabulary:
              - word: must-have-owner
                in: relations[]
                uses:
                  - when: [mustHaveOwner, target]
                    use: target-has-owner
                    with:
                      relation: { from: name }
                      types: { from: target }
            """,
        )
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val issue = ws.validate("screens/SCR-2.yaml").single { "所有者がいません" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals("「SCR-2」（詳細）には関係「owns」の所有者がいません", issue.message)
        assertTrue(ws.validate("screens/SCR-1.yaml").none { "所有者がいません" in it.message })
        // 語彙が読むキー（mustHaveOwner）は、関係レジストリの検証で「定義されていない項目」と警告されない
        val registryIssues = ws.validate("_schemas/relations.yaml")
        assertTrue(registryIssues.none { "定義されていない項目" in it.message }, registryIssues.toString())
    }

    @Test
    fun `語彙として定義されていないキーは関係レジストリの検証で警告される`() {
        val issues = Workspace.open(dir).validate("_schemas/relations.yaml")
        assertTrue(issues.any { "定義されていない項目" in it.message && "mustHaveOwner" in it.message }, issues.toString())
    }

    @Test
    fun `関係レジストリに検査を直接書くことはできない`() {
        write(
            "_schemas/relations.yaml",
            """
            id: relations
            kind: relations
            relations:
              - name: uses
            constraints:
              - match: [{ node: { entity: screen, bind: s } }]
                expect: exists
            """,
        )
        val errors = Workspace.open(dir).schemaErrors
        assertTrue(errors.any { "検査（constraints）は直接定義できません" in it }, errors.toString())
    }
}
