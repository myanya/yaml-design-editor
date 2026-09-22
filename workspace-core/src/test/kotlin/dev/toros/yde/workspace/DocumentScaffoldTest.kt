package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.SchemaScaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新しい文書のひな型と、スキーマからの初期値・サンプルの生成（P27 段D の D-c で root から移した）。
 * アプリ側の細かなケースは、root の ViewsTest・SchemaLoaderTest が、今までどおり保証する（同じ部品を通る）。
 */
class DocumentScaffoldTest {

    private val screen = """
        id: screen-detail
        match: "screens/detail/*.yaml"
        root:
          type: object
          fields:
            - { key: code, type: id, required: true }
            - { key: layout, type: enum, values: [single-column, two-column], required: true, default: single-column }
            - { key: title, type: string, required: true }
            - { key: memo, type: text }
            - key: kind
              type: enum
              required: true
              values: [form, list]
              cases:
                list:
                  - { key: pageSize, type: int, required: true }
            - key: items
              type: array
              item: { type: string }
    """.trimIndent()

    private fun schemas() = WorkspaceReader.load(InMemoryDocumentStore(mapOf("_schemas/screen.yaml" to screen))).schemas

    @Test
    fun `ひな型は 作り先に合うスキーマの必須の項目と ID を持つ`() {
        val scaffold = DocumentScaffolds.scaffold("screens/detail/{id}.yaml", "SCR-NEW", schemas())
        assertEquals("screens/detail/SCR-NEW.yaml", scaffold.rel)
        val map = scaffold.root as YNode.Map
        // ID は、ルート直下の ID の項目（ここでは code）へ入る。default を尊重する。任意の項目は作らない
        assertEquals(listOf("code", "layout", "title", "kind"), map.entries.keys.toList())
        assertEquals("SCR-NEW", scalarText(map.entries["code"]))
        assertEquals("single-column", scalarText(map.entries["layout"]))
        // 合うスキーマが無ければ、id だけの文書
        val plain = DocumentScaffolds.scaffold("notes/{id}.yaml", "N-1", schemas()).root as YNode.Map
        assertEquals(mapOf("id" to "N-1"), plain.entries.mapValues { scalarText(it.value) })
        // ファイル名にできない ID は拒否する
        assertFailsWith<IllegalArgumentException> { DocumentScaffolds.scaffold("screens/detail/{id}.yaml", "../escape", schemas()) }
    }

    @Test
    fun `詳細スキーマの match から 作り先のパターンを導く`() {
        assertEquals("details/{id}.yaml", DocumentScaffolds.detailPatternFromMatch("details/*.yaml"))
        assertEquals("a/b/{id}.yaml", DocumentScaffolds.detailPatternFromMatch("a/b/*.{yaml,yml}"))
        assertEquals("{id}.yml", DocumentScaffolds.detailPatternFromMatch("*.yml"))
        assertNull(DocumentScaffolds.detailPatternFromMatch("a/**/*.yaml"))
        assertNull(DocumentScaffolds.detailPatternFromMatch("a/x-*.yaml"))
    }

    @Test
    fun `サンプルは 任意の項目も含めて 型ごとの例の値を入れる`() {
        val tree = schemas().single { it.id == "screen-detail" }.tree
        val sample = SchemaScaffold.sample(tree) as YNode.Map
        assertEquals(listOf("code", "layout", "title", "memo", "kind", "items"), sample.entries.keys.toList())
        assertEquals("SAMPLE-001", scalarText(sample.entries["code"]))
        assertEquals("サンプル", scalarText(sample.entries["title"]))
        assertEquals("", (sample.entries["memo"] as YNode.Scalar).value)
        assertEquals("form", scalarText(sample.entries["kind"]))
        assertTrue((sample.entries["items"] as YNode.Seq).items.size == 1)
    }
}
