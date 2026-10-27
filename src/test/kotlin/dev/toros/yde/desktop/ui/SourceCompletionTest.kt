package dev.toros.yde.desktop.ui

import dev.toros.yde.desktop.AppState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ctrl+Space の候補: キー名はスキーマ、enum は値、ID は索引、file はワークスペースのファイルから。 */
class SourceCompletionTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-completion")
        write("_schemas/item-list.yaml", """
            id: item-list
            match: list.yaml
            entity: { type: item, idPattern: "IT-*", defines: true }
            root:
              type: object
              fields:
                - { key: title, type: string, label: 題名 }
                - { key: status, type: enum, values: [draft, fixed] }
                - { key: spec, type: file, extensions: [md] }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id }
                      - { key: name, type: string, required: true }
                      - { key: parent, type: ref, idPatterns: ["IT-*"] }
        """)
        write("list.yaml", """
            title: 一覧
            items:
              - id: IT-1
                name: one
              - id: IT-2
                name: two
                parent: IT-1
        """)
        write("notes/spec.md", "# spec\n")
    }

    @AfterTest
    fun tearDown() { dir.toFile().deleteRecursively() }

    private fun write(rel: String, content: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent() + "\n")
    }

    private fun candidatesAt(text: String, caret: Int): List<Candidate> {
        val state = AppState().also { it.openWorkspace(dir) }
        state.openFile(dir.resolve("list.yaml"))
        val doc = state.openDoc!!
        val files = SourceCompletion.listWorkspaceFiles(dir)
        return SourceCompletion.candidates(state, doc, SourceContext.at(text, caret), files)
    }

    @Test
    fun `key candidates come from the schema and skip keys already present`() {
        val text = "title: 一覧\nitems:\n  - id: IT-1\n    name: one\ns"
        val c = candidatesAt(text, text.length)
        assertEquals(listOf("status", "spec"), c.map { it.label })
        assertEquals("status: ", c[0].insert)
        // 木（最後に解析できた内容）に既にあるキー（id・name）は出ない
        val inItem = "title: 一覧\nitems:\n  - id: IT-1\n    name: one\n    "
        val item = candidatesAt(inItem, inItem.length)
        assertEquals(listOf("parent"), item.map { it.label })
        // 新しい要素（木に無い）では全部の子が出て、必須が分かる
        val newItem = "title: 一覧\nitems:\n  - id: IT-1\n    name: one\n  - id: IT-2\n    name: two\n    parent: IT-1\n  - "
        val fresh = candidatesAt(newItem, newItem.length)
        assertEquals(listOf("id", "name", "parent"), fresh.map { it.label })
        assertTrue(fresh.first { it.label == "name" }.detail!!.contains("必須"))
    }

    @Test
    fun `value candidates follow the field type`() {
        // 打ちかけの語を含む候補を、頭が一致するものから
        val enumText = "title: 一覧\nstatus: f"
        assertEquals(listOf("fixed", "draft"), candidatesAt(enumText, enumText.length).map { it.label })
        val refText = "title: 一覧\nitems:\n  - id: IT-3\n    parent: IT"
        assertEquals(listOf("IT-1", "IT-2"), candidatesAt(refText, refText.length).map { it.label })
        val fileText = "title: 一覧\nspec: "
        assertEquals(listOf("notes/spec.md"), candidatesAt(fileText, fileText.length).map { it.label })
        val stringText = "title: "
        assertTrue(candidatesAt(stringText, stringText.length).isEmpty())
    }
}
