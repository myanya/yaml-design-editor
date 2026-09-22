package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.structure.ValueType
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.validate.StructuralIssue
import dev.toros.yde.yaml.validate.StructuralIssueKind
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 構造のスキーマの導出（P27 段C の C-1・C-2。意図的差分 D-30）: 構造の検証は、利用者が書いたスキーマから、組込みの変換定義
 * （builtin-transforms/structural-schema.yaml）で導出した最小の構造のスキーマ（yaml-core の structure パッケージ）で行う。
 * C-1 では、利用者の木をそのまま読む旧い検証と全221文書で結果を突き合わせて切り替えた。旧い検証は C-2 で無くしたので、
 * ここでは導出した形と、検証の結果そのもの（種類・重大度・場所・文面）を固定する。
 * cases・属性付き参照・値の形式的なチェックの細かなケースは StructuralValidatorTest・StructuralConstraintTest（旧い検証のテストを移したもの）。
 */
class StructuralDerivationTransformTest {

    private val fixtures: Path = Path.of(
        System.getProperty("fixtures.root") ?: error("システムプロパティ fixtures.root が未設定（workspace-core/build.gradle.kts が渡す）")
    )

    private val taskSchemaText: String = Files.readString(fixtures.resolve("schemas/task.yaml"))

    private fun schema(text: String, id: String = "s"): SchemaTree = SchemaTreeParser.parse(text.trimIndent(), id)

    private fun validate(schema: SchemaTree, document: String): List<StructuralIssue> =
        DerivedStructures(schema).validate(YamlIO.parse(document))

    /** 指摘を「種類 @ 場所」の一覧にする（並び順も固定する）。 */
    private fun List<StructuralIssue>.brief(): List<String> = map { "${it.kind.code}@${it.path.joinToString("/")}" }

    @Test
    fun `導出した構造のスキーマは 意味の語彙を持たない最小の形になる`() {
        val derived = DerivedStructures(schema(taskSchemaText, "fx-task")).at(null)
        val fields = derived.root.fields.associateBy { it.key }
        // ID と参照は文字列になる（参照先の範囲 scope などは、構造のスキーマの型に入れ物が無い）
        assertEquals(ValueType.STRING, fields.getValue("id").type)
        assertEquals(ValueType.STRING, fields.getValue("blocked_by").item?.type)
        // 定義への参照は写すだけで辿らない・選択肢の値ごとの項目（cases）は残る
        assertEquals("step", fields.getValue("steps").item?.ref)
        assertEquals(setOf("manual", "scheduled"), fields.getValue("kind").cases.keys)
        assertNotNull(derived.definitions["step"])
    }

    @Test
    fun `導出したスキーマで 必須 選択肢 型 未定義の項目 使えない項目を検証する`() {
        val original = schema(taskSchemaText, "fx-task")
        assertEquals(emptyList(), validate(original, "id: T-1\ntitle: 手動の作業\nkind: manual\nassignee: 山田\nsteps:\n  - { name: 準備, done: true }"))
        // 必須の欠落（title・cases の assignee・定義 step の name）・型の違い・未定義の項目
        assertEquals(
            listOf("required@title", "required@assignee", "required@steps/0/name", "type@steps/0/done", "unknown-key@extra"),
            validate(original, "id: T-2\nkind: manual\nsteps:\n  - { done: たぶん }\nextra: 1").brief(),
        )
        // 選択肢に無い値・配列でない値
        assertEquals(listOf("enum@kind", "type@blocked_by"), validate(original, "id: T-3\ntitle: 定期\nkind: weekly\nblocked_by: T-1").brief())
        // いまの選択肢の値では使えない項目
        val inactive = validate(original, "id: T-4\ntitle: 定期\nkind: scheduled\ncron: '0 0 * * *'\nassignee: 使えない項目").single()
        assertEquals(StructuralIssueKind.INACTIVE_KEY to Severity.WARNING, inactive.kind to inactive.severity)
        assertEquals("「assignee」は「種別」が manual のときのみ使用できます", inactive.message)
    }

    @Test
    fun `値の形式的なチェックは 導出した構造のスキーマにそのまま残る`() {
        val original = schema(
            """
            id: s
            root:
              type: object
              unknownFields: error
              fields:
                - { key: id, type: id, required: true, pattern: "SCR-[0-9]+" }
                - { key: name, type: string, minLength: 2, maxLength: 5 }
                - { key: count, type: int, min: 1, max: 10 }
                - { key: ratio, type: number, max: 1 }
                - { key: due, type: date }
                - { key: done, type: boolean }
                - { key: labels, type: map, value: { type: string, pattern: "[a-z]+" } }
            """
        )
        assertEquals(emptyList(), validate(original, "id: SCR-1\nname: 画面\ncount: 3\nratio: 0.5\ndue: 2026-09-21\ndone: true\nlabels: { a: abc }"))
        val issues = validate(original, "id: screen\nname: あ\ncount: 11\nratio: 2\ndue: 9/21\ndone: はい\nlabels: { a: ABC }\nextra: 1")
        assertEquals(
            listOf("format@id", "length@name", "range@count", "range@ratio", "format@due", "type@done", "format@labels/a", "unknown-key@extra"),
            issues.brief(),
        )
        // 未定義の項目は、スキーマの指定（unknownFields: error）どおりエラー。日付の形式は警告
        assertEquals(Severity.ERROR, issues.last().severity)
        assertEquals(Severity.WARNING, issues.single { it.path == listOf<Any>("due") }.severity)
        assertEquals("「count」は 10 以下で入力してください（現在値: 11）", issues.single { it.path == listOf<Any>("count") }.message)
    }

    @Test
    fun `フェーズに達したら必須は 導出のときに素の必須へ畳まれる`() {
        val original = schema(
            """
            id: phased
            phases: [要件定義, 基本設計, 詳細設計]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: phase, type: enum, values: [要件定義, 基本設計, 詳細設計] }
                - { key: summary, type: string, requiredFrom: 基本設計 }
                - { key: detail, type: text, requiredFrom: 詳細設計 }
            """,
            "phased",
        )
        val structures = DerivedStructures(original)
        fun missing(phase: String) = structures.validate(YamlIO.parse("id: X-1\nphase: $phase")).brief()
        assertEquals(emptyList(), missing("要件定義"))
        assertEquals(listOf("required@summary"), missing("基本設計"))
        assertEquals(listOf("required@summary", "required@detail"), missing("詳細設計"))
        // 導出した構造のスキーマは、フェーズごとに1つ（必須が素の必須になっている）。同じフェーズの導出は1回だけ
        fun required(phase: String, key: String): Boolean =
            structures.at(YamlIO.parse("phase: $phase")).root.fields.single { it.key == key }.required
        assertEquals(listOf(false, true, true), original.phases!!.order.map { required(it, "summary") })
        assertEquals(listOf(false, false, true), original.phases!!.order.map { required(it, "detail") })
        assertTrue(structures.at(YamlIO.parse("phase: 基本設計")) === structures.at(YamlIO.parse("id: X-2\nphase: 基本設計")))
        // 定義されていないフェーズの申告の警告は、意味の側（ここ）が出す。先頭のフェーズとして扱う
        val unknownPhase = structures.validate(YamlIO.parse("id: X-1\nphase: 運用"))
        assertEquals(listOf("phase@phase", "enum@phase"), unknownPhase.brief())
        assertEquals("フェーズ「運用」は phases（要件定義 → 基本設計 → 詳細設計）に定義されていません（先頭フェーズとして扱います）", unknownPhase.first().message)
    }

    @Test
    fun `他のモジュールの定義への参照も 導出してつながる`() {
        val library = schema(
            """
            id: lib
            kind: library
            definitions:
              money:
                type: object
                fields:
                  - { key: amount, type: int, required: true, min: 0 }
                  - { key: owner, type: ref, relation: owned-by }
            """,
            "lib",
        )
        val document = schema(
            """
            id: doc
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: price, ${'$'}ref: "lib#money", required: true }
            """,
            "doc",
        )
        val linked = document.copy(definitionCatalog = SchemaCatalog.linked(listOf("doc" to document.definitions, "lib" to library.definitions)))
        assertEquals(emptyList(), validate(linked, "id: D-1\nprice: { amount: 100, owner: ACT-1 }"))
        assertEquals(listOf("range@price/amount", "unknown-key@price/extra"), validate(linked, "id: D-2\nprice: { amount: -1, extra: x }").brief())
        // 使う側に書いた必須は、定義の側より優先して効く
        assertEquals(listOf("required@price"), validate(linked, "id: D-3").brief())
    }

    @Test
    fun `ID と属性付き参照の文面は 意図的差分 D-30 のとおり`() {
        val original = schema(
            """
            id: s
            root:
              type: object
              fields:
                - { key: id, label: 画面ID, type: id, required: true }
                - key: owner
                  label: 担当
                  type: ref
                  fields:
                    - { key: role, type: string }
            """
        )
        fun only(document: String, path: List<Any>) = validate(original, document).single { it.path == path }
        // ① ID の項目が書かれていて値が空: 「（ID）」の付かない、ふつうの必須の文面
        val emptyId = only("id:\nowner: ACT-1", listOf("id"))
        assertEquals(Triple(StructuralIssueKind.REQUIRED, Severity.ERROR, "必須項目「画面ID」が未設定です"), Triple(emptyId.kind, emptyId.severity, emptyId.message))
        // ② ID にスカラーでない値: 文字列の項目と同じ警告
        assertEquals("「画面ID」はスカラー値を想定しています", only("id: [SCR-1]\nowner: ACT-1", listOf("id")).message)
        // ③ 属性付き参照の to が無い・スカラーでもオブジェクトでもない値
        assertEquals("必須項目「担当 の to（参照値）」が未設定です", only("id: SCR-1\nowner: { role: 管理者 }", listOf("owner", "to")).message)
        val form = only("id: SCR-1\nowner: [ACT-1]", listOf("owner"))
        assertEquals(Severity.WARNING to "「担当」は、スカラーかオブジェクトのどちらかで書きます", form.severity to form.message)
        // 属性付き参照の正しい形（スカラー・オブジェクト）は指摘なし。未定義の属性は警告
        assertEquals(emptyList(), validate(original, "id: SCR-1\nowner: ACT-1"))
        assertEquals(emptyList(), validate(original, "id: SCR-1\nowner: { to: ACT-1, role: 管理者 }"))
        assertEquals(listOf("unknown-key@owner/extra"), validate(original, "id: SCR-1\nowner: { to: ACT-1, extra: x }").brief())
    }
}
