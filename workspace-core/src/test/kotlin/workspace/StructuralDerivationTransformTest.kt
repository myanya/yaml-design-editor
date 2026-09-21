package workspace

import model.YNode
import schema.StructuralSchemaParser
import schema.TransformDef
import semantic.SemanticTransform
import validate.StructuralIssue
import validate.StructuralIssueKind
import validate.StructuralValidator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 構造のスキーマの導出を「YAML から YAML への変換」で書いた素案（test-fixtures/transforms/structural-schema.yaml）の確認
 * （P27 段C。ROADMAP の SC-01〜04・TF-03）。段C の C-1 で検証の経路を切り替える前に、導出したスキーマで検証した結果が、
 * 利用者が書いたスキーマでそのまま検証した結果と一致することを確かめておく。
 */
class StructuralDerivationTransformTest {

    private val fixtures: Path = Path.of(
        System.getProperty("fixtures.root") ?: error("システムプロパティ fixtures.root が未設定（workspace-core/build.gradle.kts が渡す）")
    )

    private val derivation: TransformDef =
        TransformDef.parse(YamlIO.parseToRaw(Files.readString(fixtures.resolve("transforms/structural-schema.yaml"))))

    private fun derive(schemaText: String, reachedPhases: List<String> = emptyList()): YNode =
        SemanticTransform.run(derivation, YamlIO.parse(schemaText), params = mapOf("reachedPhases" to reachedPhases)).term
            ?: error("導出の結果がありません")

    private val taskSchemaText: String = Files.readString(fixtures.resolve("schemas/task.yaml"))

    @Test
    fun `導出したスキーマは 意味の語彙を持たない最小の形になる`() {
        val derived = YamlIO.toAny(derive(taskSchemaText)) as Map<*, *>
        val text = YamlIO.dumpPlain(derive(taskSchemaText))
        // ID と参照は文字列になり、参照先の範囲（scope）・適用先（match）・表示名以外の表示の情報は出ない
        assertTrue("type: id" !in text && "type: ref" !in text && "scope" !in text && "match" !in text, text)
        assertEquals(setOf("id", "root", "definitions"), derived.keys)
        // 定義への参照（$ref）は写すだけで辿らない・選択肢の値ごとの項目（cases）は残る
        assertTrue("\$ref: step" in text && "cases:" in text && "assignee" in text, text)
    }

    @Test
    fun `導出したスキーマで検証した結果は 利用者が書いたスキーマで検証した結果と一致する`() {
        val original = StructuralSchemaParser.parse(taskSchemaText, "fx-task")
        val derived = StructuralSchemaParser.parse(YamlIO.dumpPlain(derive(taskSchemaText)), "fx-task")
        val documents = listOf(
            "id: T-1\ntitle: 手動の作業\nkind: manual\nassignee: 山田\nsteps:\n  - { name: 準備, done: true }",
            // 必須の欠落（title・cases の assignee）・選択肢に無い値・型の違い・未定義の項目・使えない項目
            "id: T-2\nkind: manual\nsteps:\n  - { done: たぶん }\nextra: 1",
            "id: T-3\ntitle: 定期\nkind: weekly\nblocked_by: T-1",
            "id: T-4\ntitle: 定期\nkind: scheduled\ncron: '0 0 * * *'\nassignee: 使えない項目",
        )
        var compared = 0
        for (text in documents) {
            val root = YamlIO.parse(text)
            val expected = StructuralValidator.validate(root, original)
            assertEquals(expected, StructuralValidator.validate(root, derived), text)
            compared += expected.size
        }
        assertTrue(compared >= 6, "指摘のある文書を比べていること（$compared 件）")
    }

    @Test
    fun `フェーズに達したら必須は 導出のときに素の必須へ畳まれる`() {
        val schemaText = """
            id: phased
            phases: [要件定義, 基本設計, 詳細設計]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: phase, type: enum, values: [要件定義, 基本設計, 詳細設計] }
                - { key: summary, type: string, requiredFrom: 基本設計 }
                - { key: detail, type: text, requiredFrom: 詳細設計 }
        """.trimIndent()
        val original = StructuralSchemaParser.parse(schemaText, "phased")
        val phases = original.phases!!.order
        for (phaseIndex in phases.indices) {
            val root = YamlIO.parse("id: X-1\nphase: ${phases[phaseIndex]}")
            // 意味の側が文書のフェーズを読み、到達したフェーズの一覧を渡す（構造の側はフェーズを知らない）
            val derived = StructuralSchemaParser.parse(YamlIO.dumpPlain(derive(schemaText, phases.take(phaseIndex + 1))), "phased")
            assertEquals(null, derived.phases, "導出したスキーマはフェーズの宣言を持たない")
            assertEquals(
                StructuralValidator.validate(root, original).map { it.kind to it.path },
                StructuralValidator.validate(root, derived).map { it.kind to it.path },
                "フェーズ: ${phases[phaseIndex]}",
            )
        }
    }

    @Test
    fun `いま分かっている違いは ID の必須の文面だけ`() {
        // ID の項目が書かれているが値が空のとき: 導出したスキーマでは ID が文字列になるため、文面の「（ID）」が付かない
        // （種類と場所は同じ。項目ごと書かれていないときの文面は、もともと同じ）。
        // 段C の C-1 で検証の経路を切り替えるときに、意図的差分として登録するか、文面を保つ手当てをするかを決める
        val root = YamlIO.parse("id:\ntitle: ID が空のタスク\nkind: manual\nassignee: 山田")
        fun idIssue(issues: List<StructuralIssue>) = issues.single { it.path == listOf<Any>("id") }
        val before = idIssue(StructuralValidator.validate(root, StructuralSchemaParser.parse(taskSchemaText, "fx-task")))
        val after = idIssue(StructuralValidator.validate(root, StructuralSchemaParser.parse(YamlIO.dumpPlain(derive(taskSchemaText)), "fx-task")))
        assertEquals(StructuralIssueKind.REQUIRED, before.kind)
        assertEquals(before.kind, after.kind)
        assertEquals("必須項目「タスクID」（ID）が未設定です", before.message)
        assertEquals("必須項目「タスクID」が未設定です", after.message)
    }
}
