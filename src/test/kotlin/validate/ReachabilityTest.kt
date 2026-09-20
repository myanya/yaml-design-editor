package validate

import core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 何段でも辿る線（edge の repeat。SP-06）と、行を線とみなす指定（edge の row。SP-07）のテスト（P29 Phase 6・R29-15）。
 * 辿る計算はコードの専用アルゴリズム（辿り着ける要素の集まりを求める）が行い、判定・文面・重大度は利用者が YAML で書く。
 * オーナーの2つの例を、利用者のスキーマだけで書けることを確かめる:
 * - どの詳細画面からも、メイン画面へ辿り着けること（遷移の表＝ID を持たない行に from と to が並ぶ書き方）
 * - 画面項目は、コントローラ → 処理（処理が処理を何段でも呼ぶ）→ DB アクセスを経て、いずれかの DB 項目に対応付くこと
 */
class ReachabilityTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-reachability")
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

    private fun screenSchemas(extraConstraints: String = "") {
        // 行頭ぞろえで組み立てる（constraints を末尾に置き、追加の検査をそのまま続けて書けるようにする）
        val schema = listOf(
            "id: screen",
            "match: \"screens.yaml\"",
            "entity: { type: screen, idPattern: \"SCR-*\", defines: true }",
            "root:",
            "  type: object",
            "  fields:",
            "    - key: screens",
            "      type: array",
            "      item:",
            "        type: object",
            "        fields:",
            "          - { key: id, type: id, required: true }",
            "          - { key: name, type: string, role: title }",
            "          - { key: kind, type: string }",
            "constraints:",
            "  - id: detail-returns-to-main",
            "    description: どの詳細画面からも、メイン画面へ戻る流れがあること",
            "    match:",
            "      - node: { entity: screen, bind: a }",
            "      - edge: { row: { from: { relation: t-from }, to: { relation: t-to } }, repeat: true }",
            "      - node: { id: SCR-MAIN }",
            "    where:",
            "      - { of: a, path: kind, equals: detail }",
            "    expect: exists",
            "    message: 画面「{id}」（{a.title}）からメイン画面へ戻る流れがありません",
            "    level: error",
        ).joinToString("\n") + (if (extraConstraints.isEmpty()) "" else "\n" + extraConstraints)
        write("_schemas/screen.yaml", schema)
        write(
            "_schemas/transition.yaml",
            """
            id: transition
            match: "transitions.yaml"
            root:
              type: object
              fields:
                - key: transitions
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: from, type: ref, relation: t-from }
                      - { key: to, type: ref, relation: t-to }
            """,
        )
        write(
            "screens.yaml",
            "screens:\n- { id: SCR-MAIN, name: メイン, kind: main }\n- { id: SCR-LIST, name: 一覧, kind: list }\n" +
                "- { id: SCR-D1, name: 詳細1, kind: detail }\n- { id: SCR-D2, name: 詳細2, kind: detail }\n- { id: SCR-D3, name: 詳細3, kind: detail }",
        )
    }

    @Test
    fun `どの詳細画面からもメイン画面へ辿り着けることを利用者のYAMLで検査できる`() {
        screenSchemas()
        // D1 → LIST → MAIN（2段）・D2 → D1 → …（3段）は辿り着ける。D3 は D2 へ行けるだけで…D2 経由で着ける。D3 から出る線を消すと着けない
        write(
            "transitions.yaml",
            "transitions:\n- { from: SCR-MAIN, to: SCR-LIST }\n- { from: SCR-LIST, to: SCR-D1 }\n- { from: SCR-LIST, to: SCR-MAIN }\n" +
                "- { from: SCR-D1, to: SCR-LIST }\n- { from: SCR-D2, to: SCR-D1 }\n- { from: SCR-LIST, to: SCR-D3 }",
        )
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = ws.validate("screens.yaml").filter { it.severity == Severity.ERROR }
        // D3 へは入れるが、D3 から出る遷移が無い＝メインへ戻れない。一覧（kind: list）は where の対象外
        assertEquals(
            listOf("画面「SCR-D3」（詳細3）からメイン画面へ戻る流れがありません — どの詳細画面からも、メイン画面へ戻る流れがあること"),
            errors.map { it.message },
        )
        assertEquals(listOf<Any>("screens", 4), errors.single().path)
    }

    @Test
    fun `辿った範囲に相手の見つからない参照があれば判定を保留する`() {
        screenSchemas()
        // D3 から出る遷移の相手が見つからない（綴りの誤りかもしれない）——それがメインへの道かもしれないため、D3 は指摘しない
        write(
            "transitions.yaml",
            "transitions:\n- { from: SCR-LIST, to: SCR-MAIN }\n- { from: SCR-D1, to: SCR-LIST }\n- { from: SCR-D2, to: SCR-D1 }\n- { from: SCR-D3, to: SCR-MIAN }",
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("screens.yaml").none { "戻る流れがありません" in it.message })
        // 見つからない参照そのものは、参照解決の検証が指摘する
        assertTrue(ws.validate("transitions.yaml").any { it.severity == Severity.ERROR && "SCR-MIAN" in it.message })
    }

    @Test
    fun `循環は自分自身へ戻る流れとして書け道すじを文面に出せる`() {
        screenSchemas(
            listOf(
                "  - id: no-loop-without-main",
                "    match:",
                "      - node: { entity: screen, bind: a }",
                "      - edge: { row: { from: { relation: t-from }, to: { relation: t-to } }, repeat: true }",
                "      - node: { same: a }",
                "    where:",
                "      - { of: a, path: kind, equals: detail }",
                "    expect: absent",
                "    message: \"画面「{id}」は遷移が循環しています（{path}）\"",
                "    level: warning",
            ).joinToString("\n"),
        )
        write(
            "transitions.yaml",
            "transitions:\n- { from: SCR-D1, to: SCR-D2 }\n- { from: SCR-D2, to: SCR-D1 }\n- { from: SCR-D1, to: SCR-MAIN }\n- { from: SCR-D3, to: SCR-MAIN }",
        )
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val loops = ws.validate("screens.yaml").filter { "循環しています" in it.message }
        assertEquals(
            listOf(
                "画面「SCR-D1」は遷移が循環しています（SCR-D1 → SCR-D2 → SCR-D1） — no-loop-without-main",
                "画面「SCR-D2」は遷移が循環しています（SCR-D2 → SCR-D1 → SCR-D2） — no-loop-without-main",
            ),
            loops.map { it.message },
        )
    }

    @Test
    fun `途中が何段になるか決まらない対応付けを検査できる`() {
        write(
            "_schemas/design.yaml",
            """
            id: design
            match: "design.yaml"
            entity:
              - { type: screen-item, idPattern: "SI-*", defines: true }
              - { type: controller, idPattern: "CTL-*", defines: true }
              - { type: process, idPattern: "PRC-*", defines: true }
              - { type: db-access, idPattern: "DA-*", defines: true }
              - { type: db-column, idPattern: "COL-*", defines: true }
            constraints:
              - id: item-mapped-to-db
                description: 画面項目は、コントローラと処理（何段でも）と DB アクセスを経て、いずれかの DB 項目に対応付くこと
                match:
                  - node: { entity: screen-item, bind: item }
                  - edge: { relation: handled-by }
                  - node: { entity: controller }
                  - edge: { relation: calls }
                  - node: { entity: process }
                  - edge: { relation: calls, repeat: { min: 0 } }
                  - node: { entity: process }
                  - edge: { relation: accesses }
                  - node: { entity: db-access }
                  - edge: { relation: maps-to }
                  - node: { entity: db-column, bind: column }
                expect: exists
                message: 画面項目「{id}」は、どの DB 項目にも対応付いていません
                level: error
            root:
              type: object
              fields:
                - key: elements
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: controller, type: ref, relation: handled-by }
                      - { key: calls, type: array, item: { type: ref, relation: calls } }
                      - { key: access, type: ref, relation: accesses }
                      - { key: column, type: ref, relation: maps-to }
            """,
        )
        write(
            "design.yaml",
            """
            elements:
              - { id: SI-1, controller: CTL-1 }
              - { id: SI-2, controller: CTL-2 }
              - { id: SI-3, controller: CTL-3 }
              - { id: CTL-1, calls: [PRC-1] }
              - { id: CTL-2, calls: [PRC-2] }
              - { id: CTL-3, calls: [PRC-9] }
              - { id: PRC-1, access: DA-1 }
              - { id: PRC-2, calls: [PRC-3] }
              - { id: PRC-3, calls: [PRC-4] }
              - { id: PRC-4, access: DA-1 }
              - { id: PRC-9, calls: [PRC-9] }
              - { id: SI-4, controller: CTL-4 }
              - { id: CTL-4, calls: [PRC-10] }
              - { id: PRC-10, calls: [PRC-11], access: DA-1 }
              - { id: PRC-11, calls: [PRC-10] }
              - { id: DA-1, column: COL-1 }
              - { id: COL-1 }
            """,
        )
        val ws = Workspace.open(dir)
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = ws.validate("design.yaml").filter { it.severity == Severity.ERROR }
        // SI-1 は処理1段・SI-2 は処理3段（PRC-2 → PRC-3 → PRC-4）で DB 項目に着く。SI-3 の処理は DB アクセスへ着かない。
        // SI-4 の処理（PRC-10）は、呼び出しが循環していても、0回（その処理そのもの）で DB アクセスへ着く
        assertEquals(listOf("画面項目「SI-3」は、どの DB 項目にも対応付いていません — 画面項目は、コントローラと処理（何段でも）と DB アクセスを経て、いずれかの DB 項目に対応付くこと"), errors.map { it.message })
    }

    @Test
    fun `repeatとrowの書き方の誤りは定義エラーになる`() {
        fun errorsWith(edge: String, node: String = "{ entity: screen }"): List<String> {
            write(
                "_schemas/bad.yaml",
                "id: bad\nmatch: \"bad/*.yaml\"\nroot: { type: object, fields: [{ key: id, type: id }] }\nconstraints:\n" +
                    "  - match:\n      - node: { entity: screen, bind: a }\n      - edge: $edge\n      - node: $node\n    expect: exists\n",
            )
            return Workspace.open(dir).schemaErrors.filter { "bad.yaml" in it }
        }
        assertTrue(errorsWith("{ relation: r, repeat: true, bind: x }").any { "repeat を書いた線に bind は書けません" in it })
        assertTrue(errorsWith("{ relation: r, row: { from: { relation: a }, to: { relation: b } } }").any { "row を書いた線に relation / via は書けません" in it })
        assertTrue(errorsWith("{ relation: r, repeat: { min: 2 } }").any { "repeat.min は 0 か 1 です" in it })
        assertTrue(errorsWith("{ relation: r, repeat: true }", node = "{ kind: document }").any { "kind: entity の node です" in it })
    }
}
