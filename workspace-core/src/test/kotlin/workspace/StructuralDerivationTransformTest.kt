package workspace

import schema.SchemaCatalog
import schema.StructuralSchema
import schema.StructuralSchemaParser
import structure.ValueType
import validate.Severity
import validate.StructuralIssue
import validate.StructuralIssueKind
import validate.StructuralValidator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 構造のスキーマの導出（P27 段C の C-1。意図的差分 D-30）: 構造の検証は、利用者が書いたスキーマから、組込みの変換定義
 * （builtin-transforms/structural-schema.yaml）で導出した最小の構造のスキーマ（yaml-core の structure パッケージ）で行う。
 * 導出した構造のスキーマで検証した結果が、利用者が書いたスキーマの木をそのまま読む旧い検証（StructuralValidator。
 * 段C の C-2 で利用者の木と一緒に yaml-core から無くなるまで、比べる相手として残してある）と一致することを確かめる。
 */
class StructuralDerivationTransformTest {

    private val fixtures: Path = Path.of(
        System.getProperty("fixtures.root") ?: error("システムプロパティ fixtures.root が未設定（workspace-core/build.gradle.kts が渡す）")
    )

    private val taskSchemaText: String = Files.readString(fixtures.resolve("schemas/task.yaml"))

    private fun schema(text: String, id: String = "s"): StructuralSchema = StructuralSchemaParser.parse(text.trimIndent(), id)

    private fun old(schema: StructuralSchema, document: String): List<StructuralIssue> =
        StructuralValidator.validate(YamlIO.parse(document), schema)

    private fun derived(schema: StructuralSchema, document: String): List<StructuralIssue> =
        DerivedStructures(schema).validate(YamlIO.parse(document))

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
    fun `導出したスキーマで検証した結果は 旧い検証と一致する`() {
        val original = schema(taskSchemaText, "fx-task")
        val documents = listOf(
            "id: T-1\ntitle: 手動の作業\nkind: manual\nassignee: 山田\nsteps:\n  - { name: 準備, done: true }",
            // 必須の欠落（title・cases の assignee）・型の違い・未定義の項目
            "id: T-2\nkind: manual\nsteps:\n  - { done: たぶん }\nextra: 1",
            // 選択肢に無い値・配列でない値
            "id: T-3\ntitle: 定期\nkind: weekly\nblocked_by: T-1",
            // いまの選択肢の値では使えない項目
            "id: T-4\ntitle: 定期\nkind: scheduled\ncron: '0 0 * * *'\nassignee: 使えない項目",
        )
        var compared = 0
        for (text in documents) {
            val expected = old(original, text)
            assertEquals(expected, derived(original, text), text)
            compared += expected.size
        }
        assertTrue(compared >= 6, "指摘のある文書を比べていること（$compared 件）")
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
        for (document in listOf(
            "id: SCR-1\nname: 画面\ncount: 3\nratio: 0.5\ndue: 2026-09-21\ndone: true\nlabels: { a: abc }",
            "id: screen\nname: あ\ncount: 11\nratio: 2\ndue: 9/21\ndone: はい\nlabels: { a: ABC }\nextra: 1",
            "id: SCR-2\nname: 長すぎる名前です\ncount: 多い\nlabels: []",
        )) {
            assertEquals(old(original, document), derived(original, document), document)
        }
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
        for (phase in original.phases!!.order) {
            val document = "id: X-1\nphase: $phase"
            assertEquals(old(original, document), structures.validate(YamlIO.parse(document)), "フェーズ: $phase")
        }
        // 導出した構造のスキーマは、フェーズごとに1つ（必須が素の必須になっている）
        fun required(phase: String, key: String): Boolean =
            structures.at(YamlIO.parse("phase: $phase")).root.fields.single { it.key == key }.required
        assertEquals(listOf(false, true, true), original.phases!!.order.map { required(it, "summary") })
        assertEquals(listOf(false, false, true), original.phases!!.order.map { required(it, "detail") })
        // 定義されていないフェーズの申告の警告は、意味の側（ここ）が出す。文面と場所は以前と同じ
        val unknownPhase = "id: X-1\nphase: 運用"
        assertEquals(old(original, unknownPhase).filter { it.kind == StructuralIssueKind.PHASE }, structures.validate(YamlIO.parse(unknownPhase)).filter { it.kind == StructuralIssueKind.PHASE })
        assertEquals(1, structures.validate(YamlIO.parse(unknownPhase)).count { it.kind == StructuralIssueKind.PHASE })
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
        val catalog = SchemaCatalog.linked(listOf("doc" to document.definitions, "lib" to library.definitions))
        val linked = document.copy(definitionCatalog = catalog)
        for (text in listOf("id: D-1\nprice: { amount: 100, owner: ACT-1 }", "id: D-2\nprice: { amount: -1, extra: x }", "id: D-3")) {
            assertEquals(old(linked, text), derived(linked, text), text)
        }
        assertTrue(derived(linked, "id: D-2\nprice: { amount: -1 }").any { it.kind == StructuralIssueKind.RANGE })
    }

    @Test
    fun `変わるのは 意図的差分 D-30 に登録した文面と警告だけ`() {
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
        fun only(issues: List<StructuralIssue>, path: List<Any>) = issues.single { it.path == path }
        // ① ID の項目が書かれていて値が空: 文面から「（ID）」が取れる（種類・重大度・場所は同じ）
        val emptyId = "id:\nowner: ACT-1"
        assertEquals("必須項目「画面ID」（ID）が未設定です", only(old(original, emptyId), listOf("id")).message)
        val after = only(derived(original, emptyId), listOf("id"))
        assertEquals("必須項目「画面ID」が未設定です", after.message)
        assertEquals(StructuralIssueKind.REQUIRED to Severity.ERROR, after.kind to after.severity)
        // ③ 属性付き参照の to が無い: 文面だけが変わる（場所は to・エラー）
        val missingTo = "id: SCR-1\nowner: { role: 管理者 }"
        assertEquals("「担当」の to（参照値）が未設定です", only(old(original, missingTo), listOf("owner", "to")).message)
        assertEquals("必須項目「担当 の to（参照値）」が未設定です", only(derived(original, missingTo), listOf("owner", "to")).message)
        // ③ スカラーでもオブジェクトでもない値: 警告のまま、文面が変わる
        val wrongForm = "id: SCR-1\nowner: [ACT-1]"
        assertEquals(Severity.WARNING, only(old(original, wrongForm), listOf("owner")).severity)
        val form = only(derived(original, wrongForm), listOf("owner"))
        assertEquals(Severity.WARNING to "「担当」は、スカラーかオブジェクトのどちらかで書きます", form.severity to form.message)
        // 属性付き参照の正しい形（スカラー・オブジェクト）と、属性の検証・未定義の属性の警告は、以前と同じ
        for (text in listOf("id: SCR-1\nowner: ACT-1", "id: SCR-1\nowner: { to: ACT-1, role: 管理者 }", "id: SCR-1\nowner: { to: ACT-1, extra: x }")) {
            assertEquals(old(original, text), derived(original, text), text)
        }
    }

    @Test
    fun `同じスキーマの導出は フェーズごとに1回だけ行う`() {
        val structures = DerivedStructures(schema(taskSchemaText, "fx-task"))
        val first = structures.at(null)
        assertTrue(first === structures.at(YamlIO.parse("id: T-1")), "フェーズの宣言が無いスキーマの導出は1つ")
    }
}
