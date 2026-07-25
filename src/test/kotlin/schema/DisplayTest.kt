package schema

import index.DocScanner
import model.YNode
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 表示解決（R10-01）と enum リッチ選択肢（R10-03）のテスト。
 * 優先順位（スキーマ role ＞ 予約語 ＞ id ＞ キー数）と、索引タイトル抽出への波及を検証する。
 */
class DisplayTest {

    private fun parse(yaml: String): YNode = YamlIO.parse(yaml.trimIndent())

    private fun schemaOf(text: String): SchemaDef =
        SchemaLoader.parseSchemaText(text.trimIndent(), Path.of("_schemas/test.yaml"))

    // ---------------------------------------------------------------- 予約語規約

    @Test
    fun `予約語からタイトル・サブタイトル・IDを解決する`() {
        val node = parse(
            """
            id: T-1
            name: タスク名
            description: 説明文
            """
        )
        val summary = Display.summarize(node, null, null)
        assertEquals("T-1", summary.id)
        assertEquals("タスク名", summary.title)
        assertEquals("説明文", summary.subtitle)
        assertEquals(3, summary.keyCount)
    }

    @Test
    fun `予約語の優先順位は title - name - label`() {
        val node = parse(
            """
            label: ラベル
            name: 名前
            title: タイトル
            """
        )
        assertEquals("タイトル", Display.titleOf(node, null))
        assertEquals("名前", Display.titleOf(parse("label: ラベル\nname: 名前"), null))
        assertEquals("ラベル", Display.titleOf(parse("label: ラベル"), null))
    }

    // ---------------------------------------------------------------- スキーマの role 上書き

    private fun roleSchema(): SchemaDef = schemaOf(
        """
        id: task
        name: タスク
        match: "docs/**/*.yaml"
        root:
          type: object
          fields:
            - key: items
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id }
                  - { key: name, type: string }
                  - { key: subject, type: string, role: title }
                  - { key: note, type: string, role: subtitle }
        """
    )

    @Test
    fun `スキーマの role 指定が予約語より優先される`() {
        val schema = roleSchema()
        val root = parse(
            """
            items:
              - id: T-1
                name: 予約語の名前
                subject: role指定の件名
                note: role指定の補足
            """
        )
        val item = ((root as YNode.Map).entries["items"] as YNode.Seq).items[0]
        val itemField = (schema.resolve(schema.root)!!.fields[0]).let { schema.resolve(it)!!.item }
        val summary = Display.summarize(item, schema, itemField)
        assertEquals("role指定の件名", summary.title)
        assertEquals("role指定の補足", summary.subtitle)
        assertEquals("T-1", summary.id)
    }

    @Test
    fun `索引のタイトル抽出が role 指定に追随する`() {
        val schema = roleSchema()
        val root = parse(
            """
            items:
              - id: T-1
                name: 予約語の名前
                subject: role指定の件名
            """
        )
        val (ids, _) = DocScanner.scan(root, Path.of("docs/a.yaml"), schema)
        assertEquals("role指定の件名", ids.single().title)
    }

    @Test
    fun `ref 先の role が合成される`() {
        val schema = schemaOf(
            """
            id: task
            name: タスク
            match: "docs/**/*.yaml"
            definitions:
              titled:
                type: string
                role: title
            root:
              type: object
              fields:
                - { key: id, type: id }
                - { key: subject, ${'$'}ref: titled }
            """
        )
        val node = parse("id: T-1\nsubject: 参照先role由来の件名")
        assertEquals("参照先role由来の件名", Display.titleOf(node, schema, schema.root))
    }

    // ---------------------------------------------------------------- 畳み込みサマリ（R10-02）

    @Test
    fun `headerText は ID タイトルとキー数の要約`() {
        val node = parse("id: T-1\nname: タスク名\nstatus: open") as YNode.Map
        assertEquals("T-1 タスク名（3項目）", Display.headerText(node, null))
        // タイトルも ID もない場合は fallback＋キー数
        val plain = parse("a: 1\nb: 2") as YNode.Map
        assertEquals("詳細（2項目）", Display.headerText(plain, null))
    }

    @Test
    fun `seqHeaderText は件数と先頭要素の要約`() {
        val seq = parse(
            """
            - name: A
            - name: B
            - name: C
            - name: D
            """
        ) as YNode.Seq
        assertEquals("4 件: A, B, C, …", Display.seqHeaderText(seq, null))
        val scalars = parse("- x\n- y") as YNode.Seq
        assertEquals("2 件: x, y", Display.seqHeaderText(scalars, null))
        assertEquals("0 件", Display.seqHeaderText(YNode.Seq(), null))
    }

    @Test
    fun `inline はスカラー値・オブジェクトのタイトル・配列件数を返す`() {
        assertEquals("値", Display.inline(YNode.Scalar("値")))
        assertEquals("（未設定）", Display.inline(null))
        assertEquals("（配列 2 件）", Display.inline(parse("- 1\n- 2")))
        assertEquals("T-1 タイトル", Display.inline(parse("id: T-1\ntitle: タイトル")))
        assertEquals("（2項目）", Display.inline(parse("a: 1\nb: 2")))
    }

    // ---------------------------------------------------------------- role のメタ検証

    @Test
    fun `object 内の重複 role はメタ検証エラー`() {
        val schema = schemaOf(
            """
            id: task
            name: タスク
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: a, type: string, role: title }
                - { key: b, type: string, role: title }
            """
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("role「title」が複数") })
    }

    @Test
    fun `role の不正値はパースエラー`() {
        assertFails {
            schemaOf(
                """
                id: task
                name: タスク
                match: "docs/**/*.yaml"
                root:
                  type: object
                  fields:
                    - { key: a, type: string, role: heading }
                """
            )
        }
    }

    // ---------------------------------------------------------------- enum リッチ選択肢（R10-03）

    @Test
    fun `リッチ選択肢を後方互換で読み込める`() {
        val schema = schemaOf(
            """
            id: task
            name: タスク
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: status
                  type: enum
                  values:
                    - open
                    - { value: closed, label: 完了, description: 対応が終わった状態 }
            """
        )
        val status = schema.resolve(schema.root)!!.fields[0].let { schema.resolve(it)!! }
        // 検証・cases・AI制約が使う values は常に値のリスト
        assertEquals(listOf("open", "closed"), status.values)
        assertEquals("完了", status.optionFor("closed")?.displayLabel)
        assertEquals("対応が終わった状態", status.optionFor("closed")?.description)
        // 文字列だけの選択肢は label なし＝値がそのまま表示ラベルになる
        assertEquals("open", status.optionFor("open")?.displayLabel)
        assertNull(status.optionFor("open")?.label)
        assertEquals(2, status.enumOptions().size)
    }

    @Test
    fun `リッチ選択肢の value 欠落はパースエラー`() {
        assertFails {
            schemaOf(
                """
                id: task
                name: タスク
                match: "docs/**/*.yaml"
                root:
                  type: object
                  fields:
                    - key: status
                      type: enum
                      values:
                        - { label: 完了 }
                """
            )
        }
    }

    @Test
    fun `選択肢の値の重複はメタ検証エラー`() {
        val schema = schemaOf(
            """
            id: task
            name: タスク
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: status
                  type: enum
                  values:
                    - open
                    - { value: open, label: 別ラベル }
                """
        )
        assertTrue(schema.validateDefinition().any { it.message.contains("選択肢の値「open」が重複") })
    }

    @Test
    fun `リッチ選択肢でも values ベースの検証と cases が機能する`() {
        val schema = schemaOf(
            """
            id: task
            name: タスク
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: kind
                  type: enum
                  values:
                    - { value: bug, label: 不具合 }
                    - { value: feature, label: 機能 }
                  cases:
                    bug:
                      - { key: severity, type: string }
            """
        )
        assertTrue(schema.validateDefinition().isEmpty())
        val node = parse("kind: bug\nseverity: high") as YNode.Map
        val effective = schema.effectiveFields(schema.resolve(schema.root)!!.fields, node)
        assertTrue(effective.fields.any { it.key == "severity" })
    }
}
