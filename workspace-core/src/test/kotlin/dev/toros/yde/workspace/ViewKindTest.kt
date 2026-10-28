package dev.toros.yde.workspace

import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.ExtensionViewResult
import dev.toros.yde.semantic.ViewContext
import dev.toros.yde.semantic.VirtualViewDefinition
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ビューの種類の差し込み口（ADR-0014）がカーネルだけで動くこと: 登録した種類だけを読み、定義の一式を見た点検を種類に任せ、
 * 文書ビューの区画に埋め込め、メタスキーマに断片が合成される。登録していない種類は使える種類を並べた誤り。
 * 本物の種類（chart）のテストは拡張 view-chart の ChartViewKindTest。
 */
class ViewKindTest {

    /** 試験用の種類「note」: 文字 1 つ（text）を持ち、相手（target）が一意な定義であることを点検する。 */
    private data class NoteDef(
        override val id: String, override val name: String, override val description: String?, override val source: DocumentId,
        val text: String, val target: String?,
    ) : ExtensionViewDef {
        override val viewKind = "note"
        override val kindLabel = "メモ"
        override fun listing(): Map<String, Any?> = mapOf("id" to id, "kind" to viewKind)
        override fun listingText(): String = "メモ $text"
        override fun resolve(context: ViewContext): ExtensionViewResult = error("この試験では解決しない")
    }

    private object NoteKind : ViewKind {
        override val id = "note"
        override val label = "メモ"
        override val sectionKey = "note"
        override val schemaFragment = """
            kind: { value: note, label: メモ }
            fields:
              - { key: text, label: 文（note）, type: string }
              - { key: target, label: 相手（note）, type: string }
            section:
              key: note
              type: object
              fields:
                - { key: view, type: string, required: true }
        """.trimIndent()

        override fun parse(raw: Map<*, *>, source: DocumentId): ExtensionViewDef {
            val id = raw["id"]?.toString() ?: error("id が必要です")
            return NoteDef(id, id, null, source, raw["text"]?.toString() ?: error("text が必要です"), raw["target"]?.toString())
        }

        override fun lint(def: ExtensionViewDef, all: List<VirtualViewDefinition>, schemas: List<WorkspaceSchema>): List<String> {
            val target = (def as NoteDef).target ?: return emptyList()
            return if (all.count { it.id == target } == 1) emptyList() else listOf("メモ「${def.id}」の相手「$target」がありません")
        }
    }

    private fun doc(name: String) = DocumentId("_views/$name.yaml")

    @Test
    fun `登録した種類だけを読み 点検は種類に任せ 文書ビューの区画に埋め込める`() {
        val sources = listOf(
            doc("a") to "id: a\nkind: note\ntext: こんにちは\ntarget: nosuch\n",
            doc("d") to "id: d\nkind: document\nmatch: \"x/*.yaml\"\nsections:\n  - { label: メモ, note: { view: a } }\n",
        )
        val catalog = ViewDefinitions.load(sources, kinds = listOf(NoteKind))
        assertEquals(listOf("a"), catalog.extensionViews.map { it.id })
        assertEquals("メモ こんにちは", catalog.extensionView("a")!!.listingText())
        assertEquals(listOf("a.yaml: メモ「a」の相手「nosuch」がありません"), catalog.errors)
        assertEquals("note", catalog.docView("d")!!.sections.single().embed?.kind)
        // 登録していなければ、使える種類を並べた誤り（文書ビューの区画のキーも知らない）
        val bare = ViewDefinitions.load(sources)
        assertTrue(bare.extensionViews.isEmpty())
        assertTrue(bare.errors.any { it == "a.yaml: kind は grid / document / matrix / lens / dashboard のいずれかである必要があります: note" }, bare.errors.toString())
        assertTrue(bare.errors.any { "d.yaml" in it && "fields / backlinks のいずれか一方" in it }, bare.errors.toString())
    }

    @Test
    fun `メタスキーマに kind の選択肢と項目と区画の項目が合成され 名前がぶつかれば誤り`() {
        val view = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY, listOf(NoteKind)).single { it.id == "builtin-view" }
        val root = view.tree.root.fields
        assertTrue("note" in root.single { it.key == "kind" }.values)
        assertTrue(root.any { it.key == "text" } && root.any { it.key == "target" })
        val section = root.single { it.key == "sections" }.item!!.fields
        assertTrue(section.any { it.key == "note" })
        // 中核の項目（name）と同じ名前は足せない
        val clash = object : ViewKind by NoteKind {
            override val schemaFragment = "kind: { value: note, label: メモ }\nfields:\n  - { key: name, type: string }\n"
        }
        val e = assertFailsWith<IllegalArgumentException> { ViewSchemaFragments.compose(BuiltinSchemaResources.text("view"), listOf(clash)) }
        assertTrue("項目「name」はルートにすでにあります" in e.message.orEmpty(), e.message)
        // 断片の kind.value は種類の ID と同じ
        val wrong = object : ViewKind by NoteKind {
            override val schemaFragment = "kind: { value: memo }\n"
        }
        assertFailsWith<IllegalArgumentException> { ViewSchemaFragments.compose(BuiltinSchemaResources.text("view"), listOf(wrong)) }
        // 合成した原文はそのまま読める YAML
        assertTrue(YamlIO.parseToRaw(BuiltinSchemaResources.viewText(listOf(NoteKind))) is Map<*, *>)
    }
}
