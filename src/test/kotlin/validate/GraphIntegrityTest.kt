package validate

import core.Workspace
import schema.SchemaLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * グラフ整合検証（R21-01。ARCHITECTURE §4.31）のテスト。
 * - 対象型（source / target）・多重度（in / out）・非循環（acyclic）の検証
 * - 強制スイッチ enforcement（undeclaredRelation / definingUniqueness）の3値
 * - relation の level による重大度・宣言のないワークスペースの非破壊
 */
class GraphIntegrityTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-graph")
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

    /** 画面（screen）→ uses → ユースケース（usecase）の2スキーマを書く。 */
    private fun writeBaseSchemas() {
        write("_schemas/screens.yaml", """
            id: screens
            match: "screens/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: uses
                  type: array
                  item: { type: ref, relation: uses }
        """)
        write("_schemas/usecases.yaml", """
            id: usecases
            match: "usecases/**/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: next
                  type: ref
                  relation: follows
        """)
    }

    // ---------------------------------------------------------------- 対象型

    @Test
    fun `対象型の不一致は参照値の座標に指摘され未解決と型なしには黙る`() {
        writeBaseSchemas()
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
                source: [screen]
                target: [usecase]
              - name: follows
        """)
        // SCR-002（screen 型）への uses 参照 = 対象型違反。UC-001 は適合。UC-404 は未解決（黙る）
        write("screens/a.yaml", """
            id: SCR-001
            uses: [UC-001, SCR-002, UC-404]
        """)
        write("screens/b.yaml", "id: SCR-002")
        write("usecases/u1.yaml", "id: UC-001")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens/a.yaml")
        val typeIssues = issues.filter { "参照先エンティティ型" in it.message }
        assertEquals(1, typeIssues.size, issues.toString())
        assertEquals(Severity.WARNING, typeIssues.single().severity)
        assertTrue("SCR-002" !in typeIssues.single().message || "screen" in typeIssues.single().message)
        // 未解決参照の指摘は V-02（解決できません）のみ = 型検証では二重計上しない
        assertTrue(issues.none { "UC-404" in it.message && "エンティティ型" in it.message }, issues.toString())
    }

    @Test
    fun `参照元エンティティ型の不一致も指摘される`() {
        writeBaseSchemas()
        // usecase 文書にも uses を書けるスキーマにする（source 違反を作る）
        write("_schemas/usecases.yaml", """
            id: usecases
            match: "usecases/**/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: uses, type: ref, relation: uses }
        """)
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
                source: [screen]
                target: [usecase]
              - name: follows
        """)
        write("usecases/u1.yaml", """
            id: UC-001
            uses: UC-002
        """)
        write("usecases/u2.yaml", "id: UC-002")
        val ws = Workspace.open(dir)
        val issues = ws.validate("usecases/u1.yaml")
        assertTrue(
            issues.any { it.severity == Severity.WARNING && "参照元エンティティ型「usecase」" in it.message },
            issues.toString(),
        )
    }

    // ---------------------------------------------------------------- 多重度

    @Test
    fun `出次の下限はエンティティ単位の合算で判定され座標は定義出現になる`() {
        writeBaseSchemas()
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
                source: [screen]
                target: [usecase]
                cardinality: { outMin: 1, outMax: 2 }
              - name: follows
        """)
        write("screens/a.yaml", "id: SCR-001")  // uses なし = outMin 違反
        write("usecases/u1.yaml", "id: UC-001")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens/a.yaml")
        assertTrue(
            issues.any { it.severity == Severity.WARNING && "関係「uses」の参照が不足" in it.message && "下限 1" in it.message },
            issues.toString(),
        )

        // 3件 = outMax 違反
        write("screens/a.yaml", """
            id: SCR-001
            uses: [UC-001, UC-001, UC-001]
        """)
        ws.refresh()
        val over = ws.validate("screens/a.yaml")
        assertTrue(over.any { "関係「uses」の参照が過剰" in it.message && "上限 2" in it.message }, over.toString())
    }

    @Test
    fun `入次の下限は対象型のエンティティに判定され充足すれば黙る`() {
        writeBaseSchemas()
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
                source: [screen]
                target: [usecase]
                cardinality: { inMin: 1 }
              - name: follows
        """)
        write("screens/a.yaml", """
            id: SCR-001
            uses: [UC-001]
        """)
        write("usecases/u1.yaml", "id: UC-001")
        write("usecases/u2.yaml", "id: UC-002")  // 被参照なし = inMin 違反
        val ws = Workspace.open(dir)
        val ok = ws.validate("usecases/u1.yaml")
        assertTrue(ok.none { "被参照が不足" in it.message }, ok.toString())
        val missing = ws.validate("usecases/u2.yaml")
        assertTrue(
            missing.any { it.severity == Severity.WARNING && "関係「uses」の被参照が不足" in it.message },
            missing.toString(),
        )
        // screen（source 側の型）には in の判定は及ばない
        val screen = ws.validate("screens/a.yaml")
        assertTrue(screen.none { "被参照が不足" in it.message }, screen.toString())
    }

    // ---------------------------------------------------------------- 非循環

    @Test
    fun `acyclicの循環は経路つきのERRORで循環参加の参照座標に指摘される`() {
        writeBaseSchemas()
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
              - name: follows
                acyclic: true
        """)
        write("usecases/u1.yaml", """
            id: UC-001
            next: UC-002
        """)
        write("usecases/u2.yaml", """
            id: UC-002
            next: UC-001
        """)
        write("usecases/u3.yaml", """
            id: UC-003
            next: UC-001
        """)
        val ws = Workspace.open(dir)
        val issues = ws.validate("usecases/u1.yaml")
        val cycle = issues.filter { "循環" in it.message }
        assertEquals(1, cycle.size, issues.toString())
        assertEquals(Severity.ERROR, cycle.single().severity)
        assertTrue("UC-001 → UC-002 → UC-001" in cycle.single().message, cycle.single().message)
        // 循環に参加しないエッジ（UC-003 → UC-001）は指摘しない
        val outside = ws.validate("usecases/u3.yaml")
        assertTrue(outside.none { "循環" in it.message }, outside.toString())
    }

    // ---------------------------------------------------------------- enforcement

    @Test
    fun `未宣言relationはenforcement宣言時だけ文書側で指摘される`() {
        writeBaseSchemas()
        // uses / follows とも未宣言のレジストリ（別名だけ宣言）
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: other
        """)
        write("screens/a.yaml", """
            id: SCR-001
            uses: [UC-001]
        """)
        write("usecases/u1.yaml", "id: UC-001")
        val ws = Workspace.open(dir)
        // 未宣言（enforcement なし）= 文書側の指摘なし（ロード時 lint のみ。P20 のまま）
        assertTrue(ws.validate("screens/a.yaml").none { "関係レジストリに宣言されていません" in it.message })
        assertTrue(ws.schemaErrors.any { "関係「uses」は関係レジストリに宣言されていません" in it })

        // enforcement: error = 参照値の座標に ERROR
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            enforcement: { undeclaredRelation: error }
            relations:
              - name: other
        """)
        ws.refresh()
        val issues = ws.validate("screens/a.yaml")
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "関係「uses」は関係レジストリに宣言されていません" in it.message },
            issues.toString(),
        )
    }

    @Test
    fun `definingUniquenessをerrorにすると浮遊詳細化と定義競合がERRORへ昇格する`() {
        write("_schemas/details.yaml", """
            id: details
            match: "details/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields: [ { key: id, type: id } ]
        """)
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            enforcement: { definingUniqueness: error }
            relations:
              - name: uses
        """)
        write("details/a.yaml", "id: SCR-001")  // 定義出現がない浮遊詳細化
        val ws = Workspace.open(dir)
        val issues = ws.validate("details/a.yaml")
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "定義出現がありません" in it.message },
            issues.toString(),
        )
    }

    @Test
    fun `enforcementのパースは未知キーと不正値と配置を拒否し重複宣言はロードエラーになる`() {
        assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                "id: r\nkind: relations\nenforcement: { unknownKey: error }\nrelations: [ { name: a } ]",
                Path.of("_schemas/r.yaml"),
            )
        }
        assertFailsWith<IllegalStateException> {
            SchemaLoader.parseSchemaText(
                "id: r\nkind: relations\nenforcement: { undeclaredRelation: fatal }\nrelations: [ { name: a } ]",
                Path.of("_schemas/r.yaml"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaLoader.parseSchemaText(
                "id: s\nmatch: \"a/*.yaml\"\nenforcement: { undeclaredRelation: error }\nroot: { type: object, fields: [] }",
                Path.of("_schemas/s.yaml"),
            )
        }
        // 同一キーの重複宣言（2つのレジストリ）はロードエラー
        write("_schemas/r1.yaml", """
            id: r1
            kind: relations
            enforcement: { undeclaredRelation: warning }
            relations: [ { name: a } ]
        """)
        write("_schemas/r2.yaml", """
            id: r2
            kind: relations
            enforcement: { undeclaredRelation: error }
            relations: [ { name: b } ]
        """)
        val loaded = SchemaLoader.loadAllWithErrors(dir)
        assertTrue(
            loaded.errors.any { "enforcement.undeclaredRelation が複数のレジストリで宣言されています" in it },
            loaded.errors.toString(),
        )
        // 先勝ちの集約は維持される
        assertEquals(schema.CoverageLevel.WARNING, loaded.enforcement.undeclaredRelation)
    }

    // ---------------------------------------------------------------- level と非破壊

    @Test
    fun `relationのlevelをerrorにすると対象型と多重度の指摘がERRORになる`() {
        writeBaseSchemas()
        write("_schemas/relations.yaml", """
            id: rels
            kind: relations
            relations:
              - name: uses
                source: [screen]
                target: [usecase]
                cardinality: { outMin: 1 }
                level: error
              - name: follows
        """)
        write("screens/a.yaml", "id: SCR-001")
        write("usecases/u1.yaml", "id: UC-001")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens/a.yaml")
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "関係「uses」の参照が不足" in it.message },
            issues.toString(),
        )
    }

    @Test
    fun `レジストリのないワークスペースはグラフ整合の指摘を出さない`() {
        writeBaseSchemas()
        write("screens/a.yaml", """
            id: SCR-001
            uses: [UC-001]
        """)
        write("usecases/u1.yaml", "id: UC-001")
        val ws = Workspace.open(dir)
        val issues = ws.validate("screens/a.yaml")
        assertTrue(issues.none { "関係「" in it.message }, issues.toString())
    }
}
