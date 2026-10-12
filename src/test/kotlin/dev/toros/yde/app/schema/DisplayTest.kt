package dev.toros.yde.app.schema

import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.YamlIO
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

    // ---------------------------------------------------------------- 数の表示の形（display: grouped）

    @Test
    fun `int と number の display は桁区切りの宣言で 配列の display とは別の意味`() {
        val schema = schemaOf(
            """
            id: ledger
            name: 台帳
            match: "book/**/*.yaml"
            root:
              type: object
              fields:
                - { key: amount, type: int, display: grouped }
                - { key: rate, type: number }
                - { key: items, type: array, display: table, item: { type: string } }
            """
        )
        val fields = schema.resolve(schema.root)!!.fields.map { schema.resolve(it)!! }
        assertEquals(dev.toros.yde.semantic.schema.NumberDisplay.GROUPED, fields[0].hints.numberDisplay)
        assertNull(fields[0].hints.display)
        assertNull(fields[1].hints.numberDisplay)
        assertEquals(dev.toros.yde.semantic.schema.CollectionDisplay.TABLE, fields[2].hints.display)
        assertNull(fields[2].hints.numberDisplay)
        // 型に合わない値は定義エラー
        assertFails { schemaOf("id: a\nname: a\nmatch: \"x/*.yaml\"\nroot: { type: object, fields: [ { key: n, type: int, display: table } ] }") }
        assertFails { schemaOf("id: a\nname: a\nmatch: \"x/*.yaml\"\nroot: { type: object, fields: [ { key: l, type: array, display: grouped, item: { type: string } } ] }") }
    }

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

    // ---------------------------------------------------------------- 要約の強化（UX-71 / UX-77）

    @Test
    fun `タイトルがなければ titleLookup で他出現から借りる`() {
        // id しか持たない写像行・詳細行に、定義出現のタイトルを見せる（UX-71）
        val node = parse("id: FLD-001\ntype: VARCHAR")
        val lookup: (String) -> String? = { id -> if (id == "FLD-001") "ユーザー名" else null }
        assertEquals("ユーザー名", Display.summarize(node, null, null, lookup).title)
        assertTrue(Display.headerText(node, null, titleLookup = lookup).startsWith("FLD-001 ユーザー名"))
        // 要素自身にタイトルがあれば借用しない
        val withTitle = parse("id: FLD-001\nname: 自前の名前")
        assertEquals("自前の名前", Display.summarize(withTitle, null, null, lookup).title)
    }

    @Test
    fun `headerText はサブタイトルを併記し inline はタイトルの代わりに使う`() {
        val node = parse("id: T-1\nname: タスク名\ndescription: 説明文")
        assertEquals("T-1 タスク名 — 説明文（3項目）", Display.headerText(node, null))
        // タイトルがなく subtitle だけの要素は inline が subtitle で補う（UX-77）
        val subOnly = parse("id: T-2\ndescription: 概要だけ")
        assertEquals("T-2 概要だけ", Display.inline(subOnly))
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
        val (ids, _) = DocScanner.scan(root, DocumentId("docs/a.yaml"), schema)
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

    @Test
    fun `参照の値は titleLookup で参照先の名前を添える`() {
        val ref = FieldDef(type = FieldType.REF)
        val lookup: (String) -> String? = { id -> if (id == "SCR-001") "ログイン画面" else null }
        assertEquals("SCR-001 ログイン画面", Display.inline(YNode.Scalar("SCR-001"), null, ref, titleLookup = lookup))
        // 名前が引けなければ ID のまま。参照でない値・文書参照・titleLookup なしには添えない
        assertEquals("SCR-002", Display.inline(YNode.Scalar("SCR-002"), null, ref, titleLookup = lookup))
        assertEquals("SCR-001", Display.inline(YNode.Scalar("SCR-001"), null, FieldDef(type = FieldType.STRING), titleLookup = lookup))
        assertEquals("SCR-001", Display.inline(YNode.Scalar("SCR-001"), null, ref.copy(refTarget = RefTarget.DOCUMENT), titleLookup = lookup))
        assertEquals("SCR-001", Display.inline(YNode.Scalar("SCR-001"), null, ref))
        // 畳んだ参照の配列の要約にも効く
        val seq = parse("- SCR-001\n- SCR-002") as YNode.Seq
        assertEquals("2 件: SCR-001 ログイン画面, SCR-002", Display.seqHeaderText(seq, null, ref, titleLookup = lookup))
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

    // ---------------------------------------------------------------- 配列のテーブル表示（R12-06）

    /** root 直下の最初のフィールド（配列想定）を返す。 */
    private fun firstField(schema: SchemaDef): FieldDef =
        schema.resolve(schema.root)!!.fields[0]

    @Test
    fun `スカラー中心のobject配列は自動でテーブル列になる`() {
        val schema = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: columns
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: name, type: string }
                      - { key: dataType, type: string }
                      - { key: pk, type: boolean }
            """
        )
        val cols = Display.tableColumns(schema, firstField(schema))
        assertEquals(listOf("name", "dataType", "pk"), cols!!.map { it.key })
    }

    @Test
    fun `非セル型が過半・列数不足・スカラー要素の配列はテーブルにしない`() {
        // ネスト（object）が過半 → null
        val nested = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: name, type: string }
                      - { key: meta, type: object, fields: [ { key: a, type: string } ] }
                      - { key: extra, type: object, fields: [ { key: b, type: string } ] }
            """
        )
        assertNull(Display.tableColumns(nested, firstField(nested)))
        // 要素がスカラー → null
        val scalars = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: tags
                  type: array
                  item:
                    type: string
            """
        )
        assertNull(Display.tableColumns(scalars, firstField(scalars)))
    }

    /** 9列のスカラーフィールドを持つ配列スキーマ（インデントは列挙生成のため手組み）。 */
    private fun manyColumnsSchema(display: String?): SchemaDef {
        val fields = (1..9).joinToString("\n") { "        - { key: c$it, type: string }" }
        val displayLine = if (display != null) "      display: $display\n" else ""
        val text = "id: t\n" +
            "name: t\n" +
            "match: \"docs/**/*.yaml\"\n" +
            "root:\n" +
            "  type: object\n" +
            "  fields:\n" +
            "    - key: items\n" +
            "      type: array\n" +
            displayLine +
            "      item:\n" +
            "        type: object\n" +
            "        fields:\n" +
            fields
        return SchemaLoader.parseSchemaText(text, Path.of("_schemas/test.yaml"))
    }

    @Test
    fun `自動判定の列数上限を超えるとテーブルにしないが display table なら固定できる`() {
        assertNull(Display.tableColumns(manyColumnsSchema(null), firstField(manyColumnsSchema(null))))
        val forced = manyColumnsSchema("table")
        assertEquals(9, Display.tableColumns(forced, firstField(forced))!!.size)
    }

    @Test
    fun `display list は適格な配列でも縦リストに固定する`() {
        val schema = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: items
                  type: array
                  display: list
                  item:
                    type: object
                    fields:
                      - { key: name, type: string }
                      - { key: status, type: string }
            """
        )
        assertNull(Display.tableColumns(schema, firstField(schema)))
    }

    @Test
    fun `ref 先の display が合成される`() {
        val schema = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            definitions:
              cols:
                type: array
                display: table
                item:
                  type: object
                  fields:
                    - { key: name, type: string }
            root:
              type: object
              fields:
                - { key: columns, ${'$'}ref: cols }
            """
        )
        // 自動判定では列1つは不適格だが、$ref 先の display table が引き継がれて表になる
        assertEquals(listOf("name"), Display.tableColumns(schema, firstField(schema))!!.map { it.key })
    }

    @Test
    fun `display の不正値はパースエラー・非配列型への指定はメタ検証エラー`() {
        assertFails {
            schemaOf(
                """
                id: t
                name: t
                match: "docs/**/*.yaml"
                root:
                  type: object
                  fields:
                    - { key: items, type: array, display: grid, item: { type: string } }
                """
            )
        }
        val misplaced = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - { key: name, type: string, display: table }
            """
        )
        assertTrue(misplaced.validateDefinition().any { it.message.contains("displayはarray / map型") })
    }

    @Test
    fun `map（キー→object）もスカラー中心なら自動でテーブル列になる`() {
        val schema = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: environments
                  type: map
                  value:
                    type: object
                    fields:
                      - { key: purpose, type: string }
                      - { key: url, type: string }
            """
        )
        val cols = Display.tableColumns(schema, firstField(schema))
        assertEquals(listOf("purpose", "url"), cols!!.map { it.key })
    }

    @Test
    fun `map でも display list による固定とスカラー値の不適格が機能する`() {
        // display: list → 適格でも縦リスト固定
        val listed = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: environments
                  type: map
                  display: list
                  value:
                    type: object
                    fields:
                      - { key: purpose, type: string }
                      - { key: url, type: string }
            """
        )
        assertNull(Display.tableColumns(listed, firstField(listed)))
        // 値がスカラーの map → 不適格
        val scalars = schemaOf(
            """
            id: t
            name: t
            match: "docs/**/*.yaml"
            root:
              type: object
              fields:
                - key: labels
                  type: map
                  value:
                    type: string
            """
        )
        assertNull(Display.tableColumns(scalars, firstField(scalars)))
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
