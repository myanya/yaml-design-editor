package dev.toros.yde.app.core

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * アスペクト宣言とエンティティ合成（R23-01。architecture/graph-query.md「エンティティページ」）のテスト。
 * - 合成の決定性: 出現の並びは「定義出現 → レジストリのアスペクト宣言順 → アスペクト名 → ファイル順」
 * - 所有フィールド: owns 明示はそのまま、省略時は宣言フィールド全部（type: id を除く）
 * - 重複所有は隠さず duplicateOf で注記し、検証は「双方に宣言・少なくとも一方が明示 owns」の組だけ WARNING
 * - スキーマなし出現は unassigned へ落ちる（宣言なしとの混在で退行しない）
 */
class AspectTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-aspect")
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

    private val diskLoader: (Path) -> dev.toros.yde.yaml.model.YNode? =
        { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }

    /** 一覧（定義）＋設計詳細（owns 明示）＋メモ詳細（owns なし）＋スキーマなし出現のワークスペース。 */
    private fun writeBase(designOwns: String = "[layout]") {
        write("_schemas/relations.yaml", """
            id: registry
            kind: relations
            relations:
              - name: uses
            entities:
              - type: screen
                label: 画面
                aspects:
                  - { name: screen-list, label: 一覧定義 }
                  - { name: design, label: 詳細設計 }
        """)
        write("_schemas/screens.yaml", """
            id: screens
            match: "screens/index.yaml"
            entity: { type: screen, idPattern: "SCR-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: name, type: string, label: 画面名 }
                      - { key: status, type: string }
        """)
        write("_schemas/design.yaml", """
            id: design
            match: "details-design/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", aspect: design, owns: $designOwns }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: layout, type: string }
                - { key: memo, type: string }
        """)
        write("_schemas/memo.yaml", """
            id: memo
            match: "details-memo/**/*.yaml"
            entity: { type: screen, idPattern: "SCR-*", aspect: memo }
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: memo, type: string }
                - { key: name, type: string }
        """)
        write("screens/index.yaml", """
            screens:
              - { id: SCR-001, name: ログイン, status: approved }
              - { id: SCR-002, name: 一覧 }
        """)
        write("details-design/scr-001.yaml", """
            id: SCR-001
            layout: 2カラム
            memo: 設計側のメモ
        """)
        write("details-memo/scr-001.yaml", """
            id: SCR-001
            memo: 補足メモ
            name: ログイン画面
        """)
        // スキーマなしの出現（どのスキーマの match にも当たらない）
        write("notes/free.yaml", """
            id: SCR-001
            note: 自由記述
        """)
    }

    private fun compose(ws: Workspace, id: String): EntityComposite? =
        Aspects.compose(id, ws.index, ws.schemas, ws.entityTypes, ws::treeOf)

    @Test
    fun 合成は定義出現とアスペクト宣言順に並び座標と表示名を持つ() {
        writeBase()
        val ws = Workspace.open(dir)
        // エンティティ型レジストリの集約（LoadResult → Workspace）
        assertEquals("画面", ws.entityTypes["screen"]?.displayLabel)
        val composite = assertNotNull(compose(ws, "SCR-001"))
        assertEquals("screen", composite.entityType)
        assertEquals("画面", composite.typeLabel)
        assertEquals("ログイン", composite.title)
        // 並び: 定義出現（screens。アスペクト未宣言＝スキーマID）→ design（宣言順）→ memo（宣言外は末尾）
        assertEquals(listOf("screens", "design", "memo"), composite.sections.map { it.aspect })
        assertEquals("詳細設計", composite.sections[1].label)
        assertEquals("memo", composite.sections[2].label)
        // 所有フィールド: 定義出現は宣言フィールド全部（id を除く）・design は owns 明示のまま
        assertEquals(listOf("name", "status"), composite.sections[0].ownedKeys)
        assertEquals(listOf("layout"), composite.sections[1].ownedKeys)
        assertTrue(composite.sections[1].ownsDeclared)
        // 値と座標（原本上の位置）
        val name = composite.sections[0].fields.first { it.key == "name" }
        assertEquals("ログイン", scalarText(name.value))
        assertEquals("画面名", name.label)
        assertEquals(DocumentId("screens/index.yaml"), name.document)
        assertEquals(listOf("screens", 0, "name"), name.path)
        // スキーマなし出現は合成に混ぜず unassigned へ
        assertEquals(listOf(DocumentId("notes/free.yaml")), composite.unassigned.map { it.document })
        // 決定性: 同じ入力からは同じ合成（YNode は同値比較を持たないため構造指紋で比較する）
        fun fingerprint(c: EntityComposite) = c.sections.map { s ->
            Triple(s.aspect, s.ownedKeys, s.fields.map { f -> Triple(f.key, f.path, scalarText(f.value)) })
        }
        assertEquals(fingerprint(composite), fingerprint(assertNotNull(compose(ws, "SCR-001"))))
    }

    @Test
    fun アスペクト宣言のないスキーマ群はファイルパス順で並ぶ() {
        // entity 宣言がないとアスペクト名＝スキーマIDになり、アルファベット順では detail が概念より
        // 先に来てしまう。フォールバックはファイルパス順（番号付きディレクトリの流れ）とする（UX-80）
        write("_schemas/detail.yaml", """
            id: detail
            match: "10-detail/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: impl, type: string }
        """)
        write("_schemas/overview.yaml", """
            id: overview
            match: "00-overview/**/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: name, type: string }
        """)
        write("00-overview/x-1.yaml", "id: X-1\nname: 概要")
        write("10-detail/x-1.yaml", "id: X-1\nimpl: 実装")
        val ws = Workspace.open(dir)
        val composite = assertNotNull(compose(ws, "X-1"))
        assertEquals(listOf("overview", "detail"), composite.sections.map { it.aspect })
    }

    @Test
    fun 重複所有は合成では注記され検証では明示owns側の組だけ警告になる() {
        writeBase(designOwns = "[layout, memo]")
        val ws = Workspace.open(dir)
        val composite = assertNotNull(compose(ws, "SCR-001"))
        // memo フィールドは design（先行）と memo（後行）が双方所有 → 後行に duplicateOf 注記・値は隠さない
        val dupField = composite.sections[2].fields.first { it.key == "memo" }
        assertNotNull(dupField.duplicateOf)
        assertEquals("補足メモ", scalarText(dupField.value))
        // name は screens（先行）と memo（後行）が双方所有だが、どちらも owns 未宣言 → 検証は黙る
        val issuesMemo = ws.validate("details-memo/scr-001.yaml")
        assertTrue(issuesMemo.any { it.severity == Severity.WARNING && "重複所有" in it.message && "memo" in it.message })
        assertTrue(issuesMemo.none { "重複所有" in it.message && "name" in it.message && "memo" !in it.message })
        // design 側（明示 owns）でも同じ組が指摘される
        val issuesDesign = ws.validate("details-design/scr-001.yaml")
        assertTrue(issuesDesign.any { "重複所有" in it.message })
    }

    @Test
    fun owns未宣言同士の重複は判定しない() {
        writeBase()
        val ws = Workspace.open(dir)
        // memo の name は screens と重複所有だが、双方 owns 未宣言のため警告なし（誤検知回避）
        val issues = ws.validate("details-memo/scr-001.yaml")
        assertTrue(issues.none { "重複所有" in it.message })
    }

    @Test
    fun 書き先解決はキーを所有する最初のアスペクトを返す() {
        writeBase()
        val ws = Workspace.open(dir)
        val composite = assertNotNull(compose(ws, "SCR-001"))
        assertEquals("design", Aspects.ownerSection(composite, "layout")?.aspect)
        assertEquals("screens", Aspects.ownerSection(composite, "status")?.aspect)
        assertNull(Aspects.ownerSection(composite, "unknown"))
    }

    @Test
    fun 出現がひとつでも合成できる() {
        writeBase()
        val ws = Workspace.open(dir)
        val composite = assertNotNull(compose(ws, "SCR-002"))
        assertEquals(1, composite.sections.size)
        assertEquals("screens", composite.sections[0].aspect)
        assertNull(compose(ws, "SCR-999"))
    }
}
