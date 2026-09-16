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
 * Aggregate の Unknown 降格（CALC-17・意図的差分 D-15）のテスト。
 * 解探索の途中で未解決参照により候補が失われた場合、「解の不足を理由とする違反」
 * （exists の解なし・count の下限未達）は判定保留＝沈黙へ降格する。
 * 「解の実在を根拠とする違反」（absent で解あり・count の上限超過）は降格しない。
 */
class UnknownDemotionTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-demotion")
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
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            constraints:
              - id: uses-existing
                description: 実在するユースケースを1つ以上利用すること
                match:
                  - node: { entity: screen, bind: scr }
                  - edge: { relation: uses }
                  - node: { entity: usecase }
                expect: exists
                level: error
              - id: no-deprecated-use
                description: 廃止済みユースケースを参照しないこと
                match:
                  - node: { entity: screen, bind: scr2 }
                  - edge: { relation: uses }
                  - node: { entity: usecase, bind: uc }
                where:
                  - { of: uc, path: deprecated, equals: true }
                expect: absent
                level: error
            """,
        )
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "ucs/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: deprecated, type: boolean }
            constraints:
              - id: reachable
                description: いずれかの画面から利用されること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen }
                expect: exists
            """,
        )
        // 参照は書けるがエンティティを持たない文書（書き手をエンティティへ解決できない被参照を作る）
        write(
            "_schemas/note.yaml",
            """
            id: note
            match: "notes/*.yaml"
            root:
              type: object
              fields:
                - key: uses
                  type: array
                  item: { type: ref, relation: uses, scope: { schemaIds: [usecase] } }
            """,
        )
        write("ucs/UC-1.yaml", "id: UC-1\nname: ユース1")
        write("ucs/UC-3.yaml", "id: UC-3\nname: 孤立ユース")
        write("ucs/UC-D.yaml", "id: UC-D\nname: 廃止ユース\ndeprecated: true")
        write("ucs/UC-SHADOW.yaml", "id: UC-SHADOW\nname: 影参照ユース")
        write("screens/SCR-OK.yaml", "id: SCR-OK\nname: 正常画面\nuses:\n- UC-1")
        write("screens/SCR-DANGLING.yaml", "id: SCR-DANGLING\nname: 宙参照画面\nuses:\n- UC-GONE")
        write("screens/SCR-EMPTY.yaml", "id: SCR-EMPTY\nname: 空画面\nuses: []")
        write("notes/note1.yaml", "uses:\n- UC-SHADOW")
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
    fun `existsは未解決参照で候補が失われたら判定保留になる`() {
        val ws = Workspace.open(dir)
        // 宙参照（UC-GONE は索引になし）＝候補喪失 → exists の「解なし」は違反にしない
        val dangling = ws.validate("screens/SCR-DANGLING.yaml")
        assertTrue(dangling.none { "パターンが成立していません" in it.message }, dangling.toString())
        // 未解決参照そのものは参照検証が従来どおり指摘する（沈黙が情報喪失にならない）
        assertTrue(dangling.any { "UC-GONE" in it.message }, dangling.toString())
        // 参照ゼロ（喪失なし）は従来どおり違反
        val empty = ws.validate("screens/SCR-EMPTY.yaml").single { "パターンが成立していません" in it.message }
        assertEquals(Severity.ERROR, empty.severity)
        // 解がある画面は従来どおり沈黙
        assertTrue(ws.validate("screens/SCR-OK.yaml").none { "パターンが成立していません" in it.message })
    }

    @Test
    fun `existsは書き手を解決できない被参照でも判定保留になる`() {
        val ws = Workspace.open(dir)
        // UC-SHADOW への参照は notes/note1.yaml（エンティティを持たない文書）だけ
        // → in エッジの書き手を解決できない＝候補喪失 → reachable は沈黙
        assertTrue(ws.validate("ucs/UC-SHADOW.yaml").none { "パターンが成立していません" in it.message })
        // 被参照が本当にゼロのユースケースは従来どおり違反
        assertTrue(ws.validate("ucs/UC-3.yaml").any { "パターンが成立していません" in it.message })
    }

    @Test
    fun `countは候補喪失で下限未達を保留し上限超過は違反のまま`() {
        write(
            "_schemas/usecase.yaml",
            """
            id: usecase
            match: "ucs/*.yaml"
            entity: { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - { key: deprecated, type: boolean }
            constraints:
              - id: used-twice
                description: 2つ以上の画面から利用されること
                match:
                  - node: { entity: usecase, bind: uc }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr }
                count: { min: 2 }
              - id: at-most-one
                description: 利用する画面は1つまでであること
                match:
                  - node: { entity: usecase, bind: uc2 }
                  - edge: { relation: uses, dir: in }
                  - node: { entity: screen, bind: scr2 }
                count: { max: 1 }
                level: error
            """,
        )
        // UC-SHADOW: 画面からの参照1件＋書き手を解決できない参照（喪失）→ min 2 未達だが保留
        write("screens/SCR-S.yaml", "id: SCR-S\nname: 影も使う画面\nuses:\n- UC-SHADOW")
        // UC-1: 画面2件＋喪失（note から）→ max 1 超過は降格せず違反
        write("screens/SCR-B.yaml", "id: SCR-B\nname: 画面B\nuses:\n- UC-1")
        write("notes/note2.yaml", "uses:\n- UC-1")
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("ucs/UC-SHADOW.yaml").none { "パターンの解" in it.message }, "喪失ありの下限未達は判定保留")
        val issue = ws.validate("ucs/UC-1.yaml").single { "パターンの解" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("2 件" in issue.message && "max 1" in issue.message, issue.message)
        // 喪失がなく本当に1件のユースケースは従来どおり下限未達の違反（UC-D は SCR ゼロ・note 参照もなし
        // → 解0件・喪失なし → min 2 違反）
        assertTrue(ws.validate("ucs/UC-D.yaml").any { "パターンの解" in it.message }, "喪失なしの下限未達は従来どおり違反")
    }

    @Test
    fun `absentは候補喪失があっても解があれば違反のまま`() {
        // 廃止ユースケース（解の実在）＋宙参照（喪失）が同居 → absent 違反は降格しない
        write("screens/SCR-MIX.yaml", "id: SCR-MIX\nname: 混在画面\nuses:\n- UC-D\n- UC-GONE")
        val ws = Workspace.open(dir)
        val issue = ws.validate("screens/SCR-MIX.yaml").single { "パターンが成立しています" in it.message }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue("uc=UC-D" in issue.message, issue.message)
    }

    @Test
    fun `詳細化出現への案内も候補喪失時は出ない`() {
        // 3.9 節（他出現への案内）も定義側と同じ判定で降格する
        write(
            "_schemas/screen-detail.yaml",
            """
            id: screen-detail
            match: "screendetails/*.yaml"
            entity: { type: screen, idPattern: "SCR-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: memo, type: text }
            """,
        )
        write("screendetails/SCR-DANGLING.yaml", "id: SCR-DANGLING\nmemo: 詳細")
        write("screendetails/SCR-EMPTY.yaml", "id: SCR-EMPTY\nmemo: 詳細")
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("screendetails/SCR-DANGLING.yaml").none { "制約違反" in it.message }, "喪失ありは案内も出ない")
        val notice = ws.validate("screendetails/SCR-EMPTY.yaml").single { "制約違反" in it.message }
        assertEquals(Severity.ERROR, notice.severity)
    }
}
