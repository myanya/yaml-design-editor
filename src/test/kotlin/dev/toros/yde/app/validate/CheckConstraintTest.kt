package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
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
 * check 判定述語（R28-08。CORE-CALCULUS §8 / architecture/graph-query.md「クエリコア」）のテスト。
 * 束縛間の値比較を出現割当て σ で反例量化する——反例の発見＝違反（値つきで列挙・relatedIds ジャンプ）・
 * 評価可能な組がなければ判定保留（黙る）・項目内の σ は一貫（同一束縛は同一出現から読む）。
 */
class CheckConstraintTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-check")
        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: max_length, type: int }
                - { key: min_length, type: int }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: length-within-dtype
                description: 画面項目の最大文字数は業務データ型の最大桁以内であること
                match:
                  - node: { entity: screen-item, bind: si }
                  - edge: { relation: dtype }
                  - node: { entity: data-type, bind: dt }
                check:
                  - lte: [{ of: si, path: max_length }, { of: dt, path: max_digits }]
                level: error
            """,
        )
        write(
            "_schemas/data-type.yaml",
            """
            id: data-type
            match: "types/*.yaml"
            entity: { type: data-type, idPattern: "DT-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: max_digits, type: int }
            """,
        )
        write("types/DT-10.yaml", "id: DT-10\nname: 数値10桁\nmax_digits: 10")
        write("items/SI-OK.yaml", "id: SI-OK\nname: 適合項目\nmax_length: 8\ndtype: DT-10")
        write("items/SI-NG.yaml", "id: SI-NG\nname: 超過項目\nmax_length: 20\ndtype: DT-10")
        write("items/SI-NONE.yaml", "id: SI-NONE\nname: 長さ未記入\ndtype: DT-10")
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
    fun `check違反は反例の値つきで指摘され関連IDでジャンプできる`() {
        val ws = Workspace.open(dir)
        val issue = ws.validate("items/SI-NG.yaml").single { "check を満たしません" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        // 反例＝どの出現割当てのどの値が違反したか（lte 違反は > で示す）
        assertTrue("si=SI-NG" in issue.message && "max_length" in issue.message && "20" in issue.message, issue.message)
        assertTrue(" > " in issue.message, issue.message)
        assertTrue("dt=DT-10" in issue.message && "max_digits" in issue.message && "10" in issue.message, issue.message)
        assertTrue("画面項目の最大文字数" in issue.message, issue.message)
        // relatedIds には相手側（DT-10）が入り、UIのIDジャンプが効く（自分自身は除く）
        assertTrue("DT-10" in issue.relatedIds && "SI-NG" !in issue.relatedIds, issue.relatedIds.toString())
        // 充足している項目は黙る
        assertTrue(ws.validate("items/SI-OK.yaml").none { "check" in it.message })
    }

    @Test
    fun `checkは値が読めない組を判定保留として黙る`() {
        // 検査値が未記入 → 評価可能な組がない＝Unknown（黙る）
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("items/SI-NONE.yaml").none { "check" in it.message })
        // 順序比較で数値でない値 → 同じく判定保留（黙る）
        write("types/DT-X.yaml", "id: DT-X\nname: 桁不定\nmax_digits: 未定")
        write("items/SI-X.yaml", "id: SI-X\nname: 型未定項目\nmax_length: 5\ndtype: DT-X")
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.validate("items/SI-X.yaml").none { "check" in it.message })
    }

    @Test
    fun `checkは詳細化出現の値からも反例を発見する`() {
        // 定義出現に max_digits がなく、詳細化出現にだけある → σ の走査が詳細化側で反例を見つける
        write(
            "_schemas/data-type-detail.yaml",
            """
            id: data-type-detail
            match: "typedetails/*.yaml"
            entity: { type: data-type, idPattern: "DT-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: max_digits, type: int }
            """,
        )
        write("types/DT-D.yaml", "id: DT-D\nname: 詳細定義型")
        write("typedetails/DT-D.yaml", "id: DT-D\nmax_digits: 5")
        write("items/SI-D.yaml", "id: SI-D\nname: 詳細参照項目\nmax_length: 9\ndtype: DT-D")
        val ws = Workspace.open(dir)
        val issue = ws.validate("items/SI-D.yaml").single { "check を満たしません" in it.message }
        assertTrue("max_digits" in issue.message && "5" in issue.message, issue.message)
    }

    @Test
    fun `同一束縛の比較は同一出現から読む`() {
        // σ は項目内で一貫: 同一束縛の両辺は同じ出現から読む（出現をまたいだ値の突合はしない）
        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: max_length, type: int }
                - { key: min_length, type: int }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: min-within-max
                description: 最小文字数は最大文字数以内であること
                match:
                  - node: { entity: screen-item, bind: si }
                check:
                  - gte: [{ of: si, path: max_length }, { of: si, path: min_length }]
                level: error
            """,
        )
        write(
            "_schemas/screen-item-detail.yaml",
            """
            id: screen-item-detail
            match: "itemdetails/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: max_length, type: int }
            """,
        )
        // 同一出現に両方の値がある → 反例を発見する
        write("items/SI-MM.yaml", "id: SI-MM\nname: 逆転項目\nmin_length: 5\nmax_length: 3")
        // 値が定義出現（min）と詳細化出現（max）に割れている → どの σ でも両辺が揃わず判定保留（黙る）
        write("items/SI-SP.yaml", "id: SI-SP\nname: 分割項目\nmin_length: 5")
        write("itemdetails/SI-SP.yaml", "id: SI-SP\nmax_length: 3")
        val ws = Workspace.open(dir)
        val issue = ws.validate("items/SI-MM.yaml").single { "check を満たしません" in it.message }
        assertTrue(" < " in issue.message, issue.message)  // gte 違反は < で示す
        assertTrue(ws.validate("items/SI-SP.yaml").none { "check" in it.message })
    }

    @Test
    fun `eq演算子は値が異なる組を反例にする`() {
        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: max_length, type: int }
                - { key: kind_a, type: string }
                - { key: kind_b, type: string }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: kinds-match
                match:
                  - node: { entity: screen-item, bind: si }
                check:
                  - eq: [{ of: si, path: kind_a }, { of: si, path: kind_b }]
            """,
        )
        write("items/SI-EQ.yaml", "id: SI-EQ\nname: 種別不一致\nkind_a: input\nkind_b: label")
        val ws = Workspace.open(dir)
        val issue = ws.validate("items/SI-EQ.yaml").single { "check を満たしません" in it.message }
        assertEquals(Severity.WARNING, issue.severity)  // level 省略時は warning
        assertTrue("input" in issue.message && "label" in issue.message && " ≠ " in issue.message, issue.message)
    }

    @Test
    fun `エラー級のcheck違反は出現側の文書にも反例つきで出る`() {
        // R28-05a の流儀: 指摘の報告先は定義出現のみだが、エラー級は詳細化出現側にも同じ重大度で出す
        write(
            "_schemas/screen-item-detail.yaml",
            """
            id: screen-item-detail
            match: "itemdetails/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: memo, type: text }
            """,
        )
        write("itemdetails/SI-NG.yaml", "id: SI-NG\nmemo: 詳細")
        val ws = Workspace.open(dir)
        val issue = ws.validate("itemdetails/SI-NG.yaml").single { "制約違反" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("items/SI-NG.yaml" in issue.message, issue.message)  // 報告先（定義出現）の案内
        assertTrue("反例" in issue.message && "dt=DT-10" in issue.message, issue.message)
        assertTrue("DT-10" in issue.relatedIds, issue.relatedIds.toString())
    }

    @Test
    fun `expectとcountとcheckの排他はスキーマ定義エラーになる`() {
        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: broken
                match:
                  - node: { entity: screen-item, bind: si }
                expect: exists
                check:
                  - lte: [{ of: si, path: max_length }, { of: si, path: max_length }]
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "check" in it && "いずれか一つ" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `checkの未知の束縛と不正な演算子はスキーマ定義エラーになる`() {
        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: unknown-bind
                match:
                  - node: { entity: screen-item, bind: si }
                check:
                  - lte: [{ of: nosuch, path: max_length }, { of: si, path: max_length }]
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.any { "束縛「nosuch」" in it }, ws.schemaErrors.toString())

        write(
            "_schemas/screen-item.yaml",
            """
            id: screen-item
            match: "items/*.yaml"
            entity: { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: dtype, type: ref, relation: dtype, scope: { schemaIds: [data-type] } }
            constraints:
              - id: bad-op
                match:
                  - node: { entity: screen-item, bind: si }
                check:
                  - foo: [{ of: si, path: max_length }, { of: si, path: max_length }]
            """,
        )
        val ws2 = Workspace.open(dir)
        assertTrue(ws2.schemaErrors.any { "演算子" in it }, ws2.schemaErrors.toString())
    }
}
