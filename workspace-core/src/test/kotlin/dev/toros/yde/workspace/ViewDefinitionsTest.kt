package dev.toros.yde.workspace

import dev.toros.yde.semantic.VirtualGridDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ビュー定義の読み取りとリント（P27 段D の D-c で root の core/Views.kt から移した）が、カーネルだけで動くこと。
 * 書き方の細かなケースは、root の ViewsTest が、今までどおり保証する（root の core.Views は、ファイルを読んでここを呼ぶ窓口）。
 */
class ViewDefinitionsTest {

    private val screenSchema = """
        id: screen
        match: "screens/*.yaml"
        definitions:
          item:
            type: object
            fields:
              - { key: id, type: id, required: true }
              - { key: label, label: 表示名, type: string }
              - { key: required, type: boolean }
              - { key: notes, type: array, item: { type: string } }
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: name, type: string }
            - key: items
              type: array
              item: { ${'$'}ref: item }
    """.trimIndent()

    private fun schemas() = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/screen.yaml" to screenSchema))).schemas

    private val grid = """
        id: screen-list
        name: 画面一覧
        rows: { idPattern: "SCR-*", schemaIds: [screen] }
        columns:
          - { label: 名前, schemaId: screen, path: name, editable: true }
        detail: { schemaId: screen, filePattern: "screens/{id}.yaml" }
    """.trimIndent()

    private val document = """
        id: screen-doc
        kind: document
        schemaId: screen
        sections:
          - label: 項目
            fields: { path: items, definition: item, include: [label, required] }
        attachments:
          - at: "items[*]"
            backlinks: { relation: uses }
    """.trimIndent()

    @Test
    fun `グリッドと文書ビューを読み取り 定義ファイル名順に並べる`() {
        val catalog = ViewDefinitions.load(
            listOf(DocumentId("_views/a-grid.yaml") to grid, DocumentId("_views/b-doc.yaml") to document),
            schemas(),
        )
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val view = assertNotNull(catalog.view("screen-list"))
        assertEquals(DocumentId("_views/a-grid.yaml"), view.source)
        assertEquals("screens/{id}.yaml", view.detail?.filePattern?.text)
        val doc = assertNotNull(catalog.docViewFor("screens/SCR-1.yaml", "screen"))
        assertEquals("screen#item", doc.sections.single().fieldsDefinition)
        assertTrue(doc.attachments.single().matches(listOf("items", 3)))
        assertEquals(null, catalog.docViewFor("screens/SCR-1.yaml", "other"))
    }

    @Test
    fun `definition を参照する列は 定義のセル型の項目へ展開される`() {
        val raw = YamlIO.parseToRaw(
            "id: items\nrows: { idPattern: \"IT-*\" }\ncolumns:\n  - { definition: \"screen#item\", schemaId: screen, editable: true }"
        ) as Map<*, *>
        val view = ViewDefinitions.parseView(raw, DocumentId("_views/items.yaml"), schemas()) as VirtualGridDef
        // ID の項目と、配列の項目は列にならない。ラベルはスキーマから
        assertEquals(listOf("表示名" to listOf<Any>("label"), "required" to listOf<Any>("required")), view.columns.map { it.label to it.path })
        assertTrue(view.columns.all { it.editable })
    }

    @Test
    fun `リントは スキーマから解決できない参照と 同じ ID の重複を報告する`() {
        val broken = """
            id: screen-list
            rows: { idPattern: "SCR-*", schemaIds: [nothing] }
            columns:
              - { label: 名前, schemaId: screen, path: title }
            newRow: { filePattern: "elsewhere/{id}.yaml" }
        """.trimIndent()
        val catalog = ViewDefinitions.load(
            listOf(DocumentId("_views/a-grid.yaml") to grid, DocumentId("_views/z-broken.yaml") to broken, DocumentId("_views/bad.yaml") to "- not-a-map"),
            schemas(),
        )
        val errors = catalog.errors
        assertTrue(errors.any { it == "bad.yaml: ルートはobjectである必要があります" }, errors.toString())
        assertTrue(errors.any { "ビューID「screen-list」が重複しています（a-grid.yaml, z-broken.yaml）" in it }, errors.toString())
        assertTrue(errors.any { it == "z-broken.yaml: rows.schemaIds のスキーマ「nothing」が見つかりません" }, errors.toString())
        assertTrue(errors.any { "z-broken.yaml: 列「名前」のパス「title」はスキーマ「screen」のID要素から解決できません" in it }, errors.toString())
        assertTrue(errors.any { "newRow.filePattern「elsewhere/{id}.yaml」の作成先にマッチするスキーマがありません" in it }, errors.toString())
        // 編集中の1件の検査（保存を待たない）も、同じリント
        val lint = ViewDefinitions.lintDocument(YamlIO.parse(broken), DocumentId("_views/z-broken.yaml"), schemas())
        assertEquals(3, lint.size, lint.toString())
    }

    @Test
    fun `レンズの見え方は関係ごとの矢印の向きを持ち レジストリに無い関係名はリントが指摘する`() {
        val lens = "id: lens\nkind: lens\nrelations:\n  - { name: uses, arrow: reverse }\n  - { name: owns }\n"
        val withRegistry = WorkspaceReader.load(
            InMemoryDocumentStore(
                mapOf(
                    "_schemas/screen.yaml" to screenSchema,
                    "_schemas/relations.yaml" to "id: relations\nkind: relations\nrelations:\n  - { name: uses, label: 利用する, inverseLabel: 利用される }\n",
                )
            )
        ).schemas
        val catalog = ViewDefinitions.load(listOf(DocumentId("_views/lens.yaml") to lens), withRegistry)
        val def = catalog.lensViews.single()
        assertEquals(setOf("uses"), def.reversedRelations)
        assertEquals(dev.toros.yde.semantic.LensArrow.FORWARD, def.relations.last().arrow, "既定は参照を書いた側から相手へ")
        assertEquals(listOf("lens.yaml: 関係「owns」は関係レジストリに宣言されていません"), catalog.errors)
        // レジストリが無ければ関係名は判定しない。知らない向き・知らないキーは読み取りの誤り
        assertTrue(ViewDefinitions.load(listOf(DocumentId("_views/lens.yaml") to lens), schemas()).errors.isEmpty())
        val bad = ViewDefinitions.load(listOf(DocumentId("_views/lens.yaml") to lens.replace("reverse", "both")), schemas()).errors
        assertTrue(bad.any { "arrow" in it }, bad.toString())
        val badKey = ViewDefinitions.load(listOf(DocumentId("_views/lens.yaml") to "id: lens\nkind: lens\ncolumns: []\n"), schemas()).errors
        assertTrue(badKey.any { "kind: lens では使えないキー" in it }, badKey.toString())
    }

    @Test
    fun `レンズの定義は箱の絞り込みを持ち 絞り込みだけの定義も見え方として選べる`() {
        val lens = """
            id: open-lens
            name: 残りだけ
            kind: lens
            relations:
              - { name: uses, arrow: reverse }
            filters:
              - id: open-only
                label: 未解決だけ
                mode: show
                entityType: item
                where: [{ path: data.status, oneOf: [todo, doing] }]
                default: true
              - id: no-drafts
                mode: hide
                level: entity
                where: { path: data.draft, equals: true }
        """.trimIndent()
        val catalog = ViewDefinitions.load(listOf(DocumentId("_views/open-lens.yaml") to lens), schemas())
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val def = catalog.lensViews.single()
        assertTrue(def.selectable, "絞り込みを持つ定義は記法が無くても選ぶ定義")
        val (open, drafts) = def.filters
        assertEquals("未解決だけ", open.label)
        assertEquals(dev.toros.yde.semantic.LensFilterMode.SHOW, open.mode)
        assertEquals("item", open.entityType)
        assertTrue(open.default)
        assertEquals("no-drafts", drafts.label, "label を省くと id")
        assertEquals("entity", drafts.level)
        assertTrue(!drafts.default, "default の既定は入れない")
        // 誤り: 向きが無い・知らない向き・条件が無い・知らないキー・ID の重複・段の誤り
        fun errorsOf(text: String) = ViewDefinitions.load(listOf(DocumentId("_views/l.yaml") to text), schemas()).errors
        val head = "id: l\nkind: lens\nfilters:\n"
        assertTrue(errorsOf(head + "  - { id: a, where: { path: id, equals: x } }\n").any { "mode" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: only, where: { path: id, equals: x } }\n").any { "show / hide" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: hide }\n").any { "where" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: hide, where: { path: id, equals: x }, color: red }\n").any { "使えないキー" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: hide, where: { path: id, equals: x } }\n  - { id: a, mode: show, where: { path: id, equals: y } }\n")
            .any { "重複" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: hide, level: doc, where: { path: id, equals: x } }\n").any { "level" in it })
        assertTrue(errorsOf(head + "  - { id: a, mode: hide, where: { path: id, between: x } }\n").isNotEmpty(), "条件の書き方は変換の \$when と同じ")
    }

    @Test
    fun `絞り込みの例外 つながりで残す は線の関係名と相手の型と条件を持ち 関係名はレジストリでリントする`() {
        val lens = """
            id: l
            kind: lens
            filters:
              - id: hide-closed
                mode: hide
                where: { path: data.status, oneOf: [resolved, declined] }
                keepIfLinked:
                  via: [uses]
                  entityType: item
                  where: { not: { path: data.status, oneOf: [resolved, declined] } }
              - id: any-line
                mode: hide
                where: { path: data.draft, equals: true }
                keepIfLinked: { via: owns }
        """.trimIndent()
        val catalog = ViewDefinitions.load(listOf(DocumentId("_views/l.yaml") to lens), schemas())
        assertTrue(catalog.errors.isEmpty(), catalog.errors.toString())
        val (closed, anyLine) = catalog.lensViews.single().filters
        val keep = closed.keepIfLinked!!
        assertEquals(setOf("uses"), keep.via)
        assertEquals("item", keep.entityType)
        assertEquals(1, keep.conditions.size)
        assertEquals(setOf("owns"), anyLine.keepIfLinked!!.via, "via は 1 つなら文字でも書ける")
        assertTrue(anyLine.keepIfLinked!!.conditions.isEmpty())
        // 関係レジストリがあれば、via の関係名の宣言を見る
        val withRegistry = WorkspaceReader.load(InMemoryDocumentStore(mapOf(
            "_schemas/screen.yaml" to screenSchema,
            "_schemas/relations.yaml" to "id: relations\nkind: relations\nrelations:\n  - { name: uses, label: 利用する }\n",
        ))).schemas
        assertEquals(listOf("l.yaml: 絞り込み「any-line」の keepIfLinked.via の関係「owns」は関係レジストリに宣言されていません"),
            ViewDefinitions.load(listOf(DocumentId("_views/l.yaml") to lens), withRegistry).errors)
        // 誤り: 知らないキー・via の形
        val head = "id: l\nkind: lens\nfilters:\n  - id: a\n    mode: hide\n    where: { path: id, equals: x }\n"
        assertTrue(ViewDefinitions.load(listOf(DocumentId("_views/l.yaml") to head + "    keepIfLinked: { depth: 2 }\n"), schemas()).errors
            .any { "使えないキー" in it })
        assertTrue(ViewDefinitions.load(listOf(DocumentId("_views/l.yaml") to head + "    keepIfLinked: { via: [{ name: x }] }\n"), schemas()).errors
            .any { "via" in it })
    }
}
