package agent

import ai.ToolCall
import core.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AgentToolbox の表示テキスト（related / list_documents / context.include の phase）。
 * 段階的詳細化のAI導線（P7）: 出現の中身プレビューとフェーズ状態が、追加のツール呼び出しなしで
 * 1回の related / list_documents から得られることを検証する。
 */
class ToolsTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-tools")
        write(
            "_schemas/table.yaml",
            """
            id: table
            match: "tables/**/*.yaml"
            phases:
              order: [requirements, logical-design]
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: phase
                  type: enum
                  values: [requirements, logical-design]
                  default: requirements
                - { key: name, type: string, required: true }
                - key: columns
                  type: array
                  requiredFrom: logical-design
                  item: { type: string }
            """,
        )
        write("tables/t.yaml", "id: TBL-001\nphase: requirements\nname: たすく\n")
        // 参照の名前解決（R19-05）用: アクター定義とそれを参照するユースケース・ビュー
        write(
            "_schemas/actor.yaml",
            """
            id: actor
            match: "actors/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/uc.yaml",
            """
            id: uc
            match: "ucs/*.yaml"
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: actorIds
                  type: array
                  item: { type: ref, scope: { schemaIds: [actor] } }
            entity: { type: usecase, idPattern: "UC-*", defines: true, owns: [name] }
            """,
        )
        write("actors/ACT-001.yaml", "id: ACT-001\nname: テナント管理者")
        write("ucs/UC-001.yaml", "id: UC-001\nname: 削除する\nactorIds:\n- ACT-001\n- ACT-999")
        // read_entity（R23-04）用: エンティティ宣言とレジストリの entities 節（型ラベル・アスペクト表示名）
        write(
            "_schemas/registry.yaml",
            """
            id: registry
            kind: relations
            relations:
              - { name: uses, label: 利用する, inverseLabel: 利用される }
            entities:
              - type: usecase
                label: ユースケース
                aspects:
                  - { name: uc, label: 基本情報 }
            """,
        )
        write(
            "_views/uc-view.yaml",
            """
            id: uc-view
            name: ユースケース一覧
            rows:
              idPattern: "UC-*"
            columns:
              - label: 主な利用者
                schemaId: uc
                path: actorIds
            """,
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

    private fun toolbox(): AgentToolbox {
        val ws = Workspace.open(dir)
        val activity = ActivityDef(
            id = "a", name = "a", agentId = "editor", instruction = "x",
            source = Path.of("_activities/a.yaml"),
        )
        return AgentToolbox(ws, Staging(ws), activity, ToolGroup.entries.toSet())
    }

    private object NoAskHost : AgentHost {
        override suspend fun askHuman(question: String): String? = null
    }

    @Test
    fun `related includes occurrence content preview and phase state`() = runBlocking {
        val call = ToolCall("c1", "related", Json.parseToJsonElement("""{"id":"TBL-001"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        assertTrue("name: たすく" in text, text)
        assertTrue("phase=requirements" in text, text)
        assertTrue("logical-design 0/1" in text, text)
    }

    @Test
    fun `catalogText includes phase summary for phases-declared schema`() {
        val text = toolbox().catalogText()
        assertTrue("phase=requirements" in text, text)
    }

    @Test
    fun `phaseText lists phases-declared documents and respects targetMatch`() {
        val text = toolbox().phaseText(null)
        assertTrue("tables/t.yaml" in text && "phase=requirements" in text, text)
        val filtered = toolbox().phaseText("nomatch/**")
        assertTrue("phases を宣言したスキーマの対象文書がありません" in filtered, filtered)
    }

    // ---------------------------------------------------------------- 参照の名前解決とツール減量（R19-05）

    @Test
    fun `read_node appends reference resolution footer with titles`() = runBlocking {
        val call = ToolCall("c1", "read_node", Json.parseToJsonElement("""{"file":"ucs/UC-001.yaml"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        assertTrue("参照の解決:" in text, text)
        assertTrue("- ACT-001 = テナント管理者 [actors/ACT-001.yaml]" in text, text)
        assertTrue("- ACT-999（未解決）" in text, text)
        // 参照を含まないノードにはフッタが付かない
        val scoped = ToolCall("c2", "read_node", Json.parseToJsonElement("""{"file":"ucs/UC-001.yaml","path":"name"}""").jsonObject)
        val scopedText = toolbox().execute(scoped, NoAskHost, mutableListOf()).content
        assertTrue("参照の解決" !in scopedText, scopedText)
    }

    @Test
    fun `related annotates outgoing refs and caps backlinks at limit`() = runBlocking {
        // ACT-001 への被参照を25件に増やす（膨張の再現）
        (2..26).forEach { i ->
            write("ucs/UC-%03d.yaml".format(i), "id: UC-%03d\nname: UC%d\nactorIds:\n- ACT-001".format(i, i))
        }
        val call = ToolCall("c1", "related", Json.parseToJsonElement("""{"id":"UC-001"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        // この要素からの参照に参照先タイトルが付く
        assertTrue("- ACT-001 = テナント管理者（actorIds）" in text, text)
        // 被参照は既定20件で打切り
        val capped = ToolCall("c2", "related", Json.parseToJsonElement("""{"id":"ACT-001"}""").jsonObject)
        val cappedText = toolbox().execute(capped, NoAskHost, mutableListOf()).content
        assertTrue("被参照（26件）" in cappedText, cappedText)
        assertTrue("…他 6 件（related の limit 引数で拡張できます）" in cappedText, cappedText)
        // limit 引数で拡張できる
        val full = ToolCall("c3", "related", Json.parseToJsonElement("""{"id":"ACT-001","limit":30}""").jsonObject)
        val fullText = toolbox().execute(full, NoAskHost, mutableListOf()).content
        assertTrue("…他" !in fullText, fullText)
    }

    @Test
    fun `traverse tool walks the graph with default and explicit steps`() = runBlocking {
        // 既定経路: 起点の要約 → 参照先の要約
        val call = ToolCall("c1", "traverse", Json.parseToJsonElement("""{"start":"UC-001"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        assertTrue("トラバース: UC-001" in text, text)
        assertTrue("- ACT-001 — テナント管理者" in text, text)
        // steps 指定（連鎖）: アクターの被参照（＝アクターを使うユースケース）をタイトルで一覧
        val steps = ToolCall(
            "c2", "traverse",
            Json.parseToJsonElement("""{"start":"ACT-001","steps":[{"emit":"title"},{"follow":"in","emit":"title"}]}""").jsonObject,
        )
        val stepText = toolbox().execute(steps, NoAskHost, mutableListOf()).content
        assertTrue("- UC-001 — 削除する" in stepText, stepText)
    }

    // ---------------------------------------------------------------- read_entity（R23-04）

    @Test
    fun `read_entity returns aspect composition with coordinates and relations`() = runBlocking {
        val call = ToolCall("c1", "read_entity", Json.parseToJsonElement("""{"id":"UC-001"}""").jsonObject)
        val text = toolbox().execute(call, NoAskHost, mutableListOf()).content
        // 型ラベル（レジストリ entities 節）とタイトル
        assertTrue(text.startsWith("UC-001（ユースケース） — 削除する"), text)
        // アスペクト見出し（表示名＋定義マーク＋原本座標）と所有フィールド（owns 明示）
        assertTrue("## 基本情報（定義） [ucs/UC-001.yaml :: (root)]" in text, text)
        assertTrue("- name: 削除する" in text, text)
        // owns の対象外（actorIds）は所有フィールドに出ない
        assertTrue("- actorIds" !in text, text)
        // 関係（この要素からの参照）に参照先タイトルが付く
        assertTrue("- → ACT-001 = テナント管理者（actorIds）" in text, text)
        // ID が無ければその旨を返す
        val missing = ToolCall("c2", "read_entity", Json.parseToJsonElement("""{"id":"UC-999"}""").jsonObject)
        assertTrue("見つかりません" in toolbox().execute(missing, NoAskHost, mutableListOf()).content)
    }

    @Test
    fun `entity context item resolves placeholder and discloses composition`() {
        val item = ContextItemDef(kind = "entity", id = "{target}")
        val section = toolbox().contextItemSection(item, mapOf("target" to "UC-001"))
        assertTrue(section != null && "## 基本情報" in section.body, section?.body)
        // プレースホルダが解決できない実行では開示しない（既存レシピと同じ規約）
        assertTrue(toolbox().contextItemSection(item, emptyMap()) == null)
    }

    @Test
    fun `view cells annotate ref values with titles and read_view filters rows`() {
        val text = toolbox().viewText("uc-view")
        // 「（配列 1 件）」ではなく中身がタイトル注記つきで展開される（未解決IDは素のまま）
        assertTrue("ACT-001（テナント管理者）, ACT-999" in text, text)
        // rows / columns の絞り込み（read_view ツール引数と同じ経路）
        val filtered = toolbox().viewText("uc-view", rowsPattern = "UC-9*")
        assertTrue("行: 0 件" in filtered, filtered)
    }
}
