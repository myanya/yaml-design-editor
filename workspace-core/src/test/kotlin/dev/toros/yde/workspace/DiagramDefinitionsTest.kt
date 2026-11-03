package dev.toros.yde.workspace

import dev.toros.yde.semantic.*
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.*

class DiagramDefinitionsTest {
    private val source = DocumentId(".yde/views/person.yaml")
    private fun definition(text: String) = DiagramDefinitions.parse(YamlIO.parseToRaw(text.trimIndent()) as Map<*, *>, source)
    private fun binding(inputs: String = "{title: {\$copy: name}}") = DiagramBinding(
        "person", TransformDef.ofTemplate("inputs", YamlIO.parseToRaw(inputs), "inputs"),
    )

    @Test
    fun `同梱と利用者の記法を同じ契約で読み入力の違反を表示する`() {
        val def = definition("""
            id: person
            target: node
            input:
              type: object
              fields: [{key: title, type: string, required: true}]
            build:
              kind: box
              children: [{kind: text, text: {${'$'}copy: title}}]
        """)
        val input = SemanticTransform.TemplatePlace(YamlIO.parse("name: 花子"), DocumentId("people/a.yaml"), listOf("people", 0))
        val result = assertNotNull(DiagramNotations.resolve(listOf(def), listOf(binding()), input, source, "node"))
        assertTrue(result.notices.isEmpty())
        assertEquals("花子", result.node!!.children.single().text)
        assertEquals(DiagramLocation(DocumentId("people/a.yaml"), listOf("people", 0, "name")), result.location(listOf("children", 0, "text")))
        val missing = DiagramNotations.resolve(listOf(def), listOf(binding()), SemanticTransform.TemplatePlace(YamlIO.parse("{}"), null, emptyList()), source, "node")!!
        assertNull(missing.node)
        assertTrue(missing.notices.isNotEmpty())
        assertTrue(DiagramDefinitions.builtins.map { it.id }.containsAll(listOf("plain-node", "plain-link", "directed-link")))
    }

    @Test
    fun `別文書の画像と配列の子の来歴を二段の変換でも保つ`() {
        val def = definition("""
            id: person
            target: node
            build:
              kind: box
              children:
                - {kind: image, source: {${'$'}copy: portrait}}
                - kind: group
                  children:
                    ${'$'}each: attrs
                    ${'$'}build: {kind: text, text: {${'$'}copy: name}}
        """)
        val input = YamlIO.parse("data: {portrait: ./face.png, attrs: [{name: id}, {name: title}]}")
        val before = YamlIO.dumpPlain(input)
        val place = DiagramInputPlace(input, listOf(
            TransformOrigin(listOf("data", "portrait"), DocumentId("images/person.yaml"), listOf("portrait")),
            TransformOrigin(listOf("data", "attrs"), DocumentId("classes/a.yaml"), listOf("attributes")),
        ))
        val result = DiagramNotations.resolve(listOf(def), listOf(binding("{portrait: {\$copy: data.portrait}, attrs: {\$copy: data.attrs}}")), place, source, "node")!!
        assertEquals(DiagramLocation(DocumentId("images/person.yaml"), listOf("portrait")), result.location(listOf("children", 0, "source")))
        assertEquals(DiagramLocation(DocumentId("classes/a.yaml"), listOf("attributes", 1, "name")), result.location(listOf("children", 1, "children", 1, "text")))
        assertEquals(before, YamlIO.dumpPlain(input))
        // 合成したdata全体を一度に写しても、子ごとの文書を失わない。
        val whole = DiagramNotations.resolve(listOf(def), listOf(binding("{\$copy: data}")), place, source, "node")!!
        assertEquals(result.location(listOf("children", 0, "source")), whole.location(listOf("children", 0, "source")))
        assertEquals(result.location(listOf("children", 1, "children", 1, "text")), whole.location(listOf("children", 1, "children", 1, "text")))
    }

    @Test
    fun `不正な部品と競合した適用は原本を変えず診断する`() {
        val def = definition("id: person\ntarget: node\nbuild: {kind: script}")
        val place = SemanticTransform.TemplatePlace(YamlIO.parse("name: A"), null, emptyList())
        val invalid = DiagramNotations.resolve(listOf(def), listOf(binding()), place, source, "node")!!
        assertNull(invalid.node)
        assertEquals(listOf("build", "kind"), invalid.notices.single().location.path)
        val conflict = DiagramNotations.resolve(listOf(def), listOf(binding(), binding()), place, source, "node")!!
        assertTrue(conflict.notices.single().message.contains("複数"))
        assertFailsWith<IllegalArgumentException> {
            DiagramDefinitions.appearance(YamlIO.parseToRaw("nodes: [{notation: a}, {notation: b}]"))
        }
    }

    @Test fun `UMLの区画は原本の配列へ戻り複数の来歴を一つへ決めない`() {
        val input = YamlIO.parse("title: 注文\nattributes: ['+ id: String', '- total: Number']\noperations: ['+ submit(): void']")
        val bind = DiagramBinding("uml-class", TransformDef.ofTemplate("input", mapOf("${'$'}copy" to "."), "input"))
        val rendered = DiagramNotations.resolve(DiagramDefinitions.builtins, listOf(bind), SemanticTransform.TemplatePlace(input, source, emptyList()), source, "node")!!
        assertTrue(rendered.notices.isEmpty(), rendered.notices.toString())
        assertEquals("- total: Number", rendered.node!!.children[2].children[1].text)
        assertEquals(DiagramLocation(source, listOf("attributes", 1)), rendered.location(listOf("children", 2, "children", 1, "text")))
        val ambiguous = rendered.copy(origins = listOf(
            TransformOrigin(emptyList(), source, listOf("first")), TransformOrigin(emptyList(), DocumentId("other.yaml"), listOf("second"))))
        assertNull(ambiguous.location(emptyList()))
        assertEquals(2, ambiguous.locations(emptyList()).size)
        val copied = SemanticTransform.runAt(bind.inputs, DiagramInputPlace(input, ambiguous.origins))
        assertEquals(2, copied.origins.size)
    }

    @Test fun `記法の条件と繰り返しは既存の変換で評価し原本を変更しない`() {
        val def = definition("""
            id: conditional
            target: node
            build:
              kind: box
              children:
                - {kind: text, text: {${'$'}copy: title}}
                - ${'$'}when: {path: urgent, equals: true}
                  ${'$'}then: {kind: text, text: 要確認, tone: warning}
        """)
        val bind = DiagramBinding(def.id, TransformDef.ofTemplate("input", mapOf("${'$'}copy" to "."), "input"))
        for (urgent in listOf(false, true)) {
            val tree = YamlIO.parse("title: 注文\nurgent: $urgent")
            val before = YamlIO.dumpPlain(tree)
            val result = DiagramNotations.resolve(listOf(def), listOf(bind), SemanticTransform.TemplatePlace(tree, source, emptyList()), source, "node")!!
            assertTrue(result.notices.isEmpty(), result.notices.toString())
            assertEquals(if (urgent) 2 else 1, result.node!!.children.size)
            assertEquals(before, YamlIO.dumpPlain(tree))
        }
        assertTrue(DiagramDefinitions.builtins.single { it.id == "uml-class" }.definitionYaml.contains("attributes"))
    }

    @Test
    fun `カタログは記法の重複と対象違いを診断し従来の矢印指定も読める`() {
        val loaded = ViewDefinitions.load(listOf(
            source to "id: lens\nkind: lens\nrelations: [{name: child, arrow: reverse}]\nappearance: {nodes: [{notation: directed-link}]}",
            DocumentId(".yde/views/duplicate.yaml") to "id: plain-node\nkind: diagram-notation\ntarget: node\nbuild: {kind: text, text: x}",
        ))
        assertTrue(loaded.errors.any { "重複" in it })
        assertTrue(loaded.errors.any { "node 用ではありません" in it })
        assertEquals(setOf("child"), loaded.lensViews.single().reversedRelations)
    }
}
