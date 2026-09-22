package index

import core.Workspace
import schema.QuerySpec
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * クエリの意味の整理（P27・意図的差分 D-27。BACKLOG UX-108・UX-106）:
 * - 参照を書いた要素は、向きによらず「その参照を含む最も内側の ID を持つ要素」（出ていく向きと入ってくる向きが対になる）
 * - 入れ子を辿りたいときは、入れ子の線（edge の contains）を使う
 * - via は、参照が書かれた場所の末尾のキーの並びと比べる
 */
class QueryNestingTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-nesting")
        write(
            "_schemas/screen.yaml",
            """
            id: screen
            match: "screens/*.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*", defines: true }
              - { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
                - key: uses
                  type: array
                  item: { type: ref, relation: uses }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: source, type: ref, relation: source }
                      - key: link
                        type: object
                        fields:
                          - key: target
                            type: object
                            fields:
                              - { key: ref, type: ref, relation: link }
                      - key: other
                        type: object
                        fields:
                          - { key: ref, type: ref, relation: link }
            """,
        )
        write(
            "_schemas/attr.yaml",
            """
            id: attr
            match: "attrs/*.yaml"
            entity:
              - { type: attr, idPattern: "EA-*", defines: true }
              - { type: usecase, idPattern: "UC-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        write(
            "screens/SCR-1.yaml",
            """
            id: SCR-1
            name: 画面1
            uses: [UC-1]
            items:
              - id: SI-1
                source: EA-1
                link: { target: { ref: EA-2 } }
                other: { ref: EA-3 }
              - id: SI-2
                source: EA-2
            """,
        )
        listOf("EA-1", "EA-2", "EA-3", "UC-1").forEach { write("attrs/$it.yaml", "id: $it") }
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

    private fun spec(yaml: String): QuerySpec = QuerySpec.parse(YamlIO.parseToRaw(yaml.trimIndent()) as Map<*, *>, "spec")

    private fun ids(ws: Workspace, yaml: String, bind: String): List<String> =
        Query.run(ws.index, spec(yaml), ws::treeOf).rows.map { it.bindings.getValue(bind).id }

    @Test
    fun `出ていく向きは その要素が直接書いた参照だけを辿り 入ってくる向きと対になる`() {
        val ws = Workspace.open(dir)
        // 画面（外側の要素）から source を辿っても、入れ子の画面項目が書いた参照は画面の参照ではない
        assertEquals(
            emptyList(),
            ids(ws, "match: [{node: {id: SCR-1, bind: a}}, {edge: {relation: source}}, {node: {entity: attr, bind: t}}]", "t"),
        )
        // 画面が直接書いた参照は辿れる
        assertEquals(
            listOf("UC-1"),
            ids(ws, "match: [{node: {id: SCR-1, bind: a}}, {edge: {relation: uses}}, {node: {bind: t}}]", "t"),
        )
        // 画面項目から出る向きと、属性へ入ってくる向きは、同じ組を返す
        assertEquals(
            listOf("EA-1"),
            ids(ws, "match: [{node: {id: SI-1, bind: a}}, {edge: {relation: source}}, {node: {bind: t}}]", "t"),
        )
        assertEquals(
            listOf("SI-1"),
            ids(ws, "match: [{node: {id: EA-1, bind: t}}, {edge: {relation: source, dir: in}}, {node: {bind: a}}]", "a"),
        )
    }

    @Test
    fun `入れ子の線で 外側の要素と内側の要素と文書を行き来できる`() {
        val ws = Workspace.open(dir)
        assertEquals(
            listOf("SI-1", "SI-2"),
            ids(ws, "match: [{node: {id: SCR-1, bind: a}}, {edge: {contains: true}}, {node: {bind: c}}]", "c"),
        )
        assertEquals(
            listOf("SCR-1"),
            ids(ws, "match: [{node: {id: SI-2, bind: c}}, {edge: {contains: true, dir: in}}, {node: {bind: a}}]", "a"),
        )
        // 文書からは、いちばん外側で書かれた要素だけ（入れ子の画面項目は含まない）
        assertEquals(
            listOf("SCR-1"),
            ids(
                ws,
                "match: [{node: {kind: document, id: \"screens/*\", bind: d}}, {edge: {contains: true}}, {node: {bind: e}}]",
                "e",
            ),
        )
        assertEquals(
            listOf("screens/SCR-1.yaml"),
            ids(
                ws,
                "match: [{node: {id: SCR-1, bind: e}}, {edge: {contains: true, dir: in}}, {node: {kind: document, bind: d}}]",
                "d",
            ),
        )
    }

    @Test
    fun `入れ子の線を何段でも辿ってから参照を辿れば 配下のどの要素が書いた参照でも辿れる`() {
        val ws = Workspace.open(dir)
        // 画面そのもの（0段）と、入れ子の画面項目が書いた source・link のすべて
        assertEquals(
            listOf("EA-1", "EA-2", "EA-3", "UC-1"),
            ids(
                ws,
                """
                match:
                  - node: {id: SCR-1, bind: a}
                  - edge: {contains: true, repeat: {min: 0}}
                  - node: {kind: entity}
                  - edge: {}
                  - node: {bind: t}
                """,
                "t",
            ).distinct().sorted(),
        )
    }

    @Test
    fun `via は参照が書かれた場所の末尾のキーの並びと比べる`() {
        val ws = Workspace.open(dir)
        fun targets(via: String) =
            ids(ws, "match: [{node: {id: SI-1, bind: a}}, {edge: {via: \"$via\"}}, {node: {bind: t}}]", "t")
        // 1語はキー名（今までどおり）: link.target.ref と other.ref の両方
        assertEquals(listOf("EA-2", "EA-3"), targets("ref"))
        // 場所を書けば、その場所に書かれた参照だけ
        assertEquals(listOf("EA-2"), targets("target.ref"))
        assertEquals(listOf("EA-2"), targets("link.target.ref"))
        assertEquals(listOf("EA-3"), targets("other.ref"))
        assertEquals(emptyList(), targets("nowhere.ref"))
    }

    @Test
    fun `参照の本数も その要素が直接書いた参照だけを数える`() {
        write(
            "_schemas/lib.yaml",
            """
            id: lib
            kind: library
            constraints:
              - id: screen-writes-no-source
                match:
                  - node: { entity: screen, bind: a }
                check:
                  - eq: [{ refCount: { of: a, dir: out, relation: source } }, 0]
                level: error
              - id: item-writes-one-source
                match:
                  - node: { entity: screen-item, bind: a }
                check:
                  - eq: [{ refCount: { of: a, dir: out, relation: source } }, 1]
                level: error
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val errors = ws.validate("screens/SCR-1.yaml").filter { it.severity == validate.Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `入れ子の線に 参照の指定は書けない`() {
        val e = assertFailsWith<IllegalStateException> {
            spec("match: [{node: {bind: a}}, {edge: {contains: true, relation: uses}}, {node: {bind: b}}]")
        }
        assertTrue("contains" in e.message.orEmpty(), e.message)
    }
}
