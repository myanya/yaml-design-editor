package dev.toros.yde.workspace

import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.FilePolicyLevel
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.validate.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * file 型の値の検査（P37）: 書き方・拡張子・ワークスペースの外・存在と、`.yde/workspace.yaml` の `files:` による扱いの切替。
 * 存在はメモリのストア（キーがあれば存在）に聞く。
 */
class FileChecksTest {
    private val schema = """
        id: spec
        name: 仕様
        match: "specs/*.yaml"
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: diagram, type: file, extensions: [png, svg] }
            - { key: note, type: file }
    """.trimIndent()

    private fun workspace(doc: String, config: String? = null, extra: Map<String, String> = emptyMap()): LoadedWorkspace {
        val files = mutableMapOf(
            ".yde/schemas/spec.yaml" to schema,
            "specs/a.yaml" to doc,
            "specs/images/flow.png" to "(png)",
            "docs/memo.txt" to "(txt)",
        )
        if (config != null) files[".yde/workspace.yaml"] = config
        files += extra
        return WorkspaceReader.load(InMemoryDocumentStore(files))
    }

    private fun issues(ws: LoadedWorkspace) =
        WorkspaceCheckRunner.validateAll(ws)[DocumentId("specs/a.yaml")]?.issues.orEmpty().map { it.severity to it.message }

    @Test
    fun `workspace relative and document relative paths resolve and existing files pass`() {
        val found = issues(workspace("""
            id: S-1
            diagram: specs/images/flow.png
            note: ./images/../../docs/memo.txt
        """.trimIndent()))
        assertTrue(found.isEmpty(), found.toString())
    }

    @Test
    fun `missing file is an error by default and extension is checked`() {
        val found = issues(workspace("""
            id: S-1
            diagram: specs/images/missing.png
            note: specs/images/flow.png.bak
        """.trimIndent()))
        assertEquals(2, found.size, found.toString())
        assertTrue(found.any { it.first == Severity.ERROR && it.second.contains("ファイルがありません") && it.second.contains("missing.png") })
        assertTrue(found.any { it.first == Severity.ERROR && it.second.contains("ファイルがありません") && it.second.contains("flow.png.bak") })
    }

    @Test
    fun `wrong extension is an error`() {
        val found = issues(workspace("""
            id: S-1
            diagram: docs/memo.txt
        """.trimIndent()))
        assertEquals(listOf(Severity.ERROR), found.map { it.first })
        assertTrue(found.single().second.contains("png / svg"))
    }

    @Test
    fun `outside the workspace is a warning by default and configurable`() {
        val doc = """
            id: S-1
            note: C:/shared/memo.txt
        """.trimIndent()
        // 外にあって存在するファイル: 既定は「許して警告」だけ
        val warned = issues(workspace(doc, extra = mapOf("C:/shared/memo.txt" to "(txt)")))
        assertEquals(listOf(Severity.WARNING), warned.map { it.first }, warned.toString())
        assertTrue(warned.single().second.contains("ワークスペースの外"))
        // 外にあって存在しないファイル: 外の警告と、無いファイルの違反の両方
        val warnedAndMissing = issues(workspace(doc))
        assertEquals(listOf(Severity.WARNING, Severity.ERROR), warnedAndMissing.map { it.first }, warnedAndMissing.toString())

        val allowed = issues(workspace(doc, config = "files:\n  outside: allow\n  missing: allow\n"))
        assertTrue(allowed.isEmpty(), allowed.toString())

        val forbidden = issues(workspace(doc, config = "files:\n  outside: error\n"))
        assertEquals(listOf(Severity.ERROR), forbidden.map { it.first })

        val trusted = issues(workspace(doc, config = "files:\n  outside: error\n  allowedRoots: [C:/shared]\n  missing: allow\n"))
        assertTrue(trusted.isEmpty(), trusted.toString())
    }

    @Test
    fun `escaping the root counts as outside and malformed paths are errors`() {
        val found = issues(workspace("""
            id: S-1
            note: ../../elsewhere.txt
            diagram: specs/images/
        """.trimIndent(), config = "files:\n  outside: warning\n  missing: allow\n"))
        assertTrue(found.any { it.first == Severity.WARNING && it.second.contains("ワークスペースの外") })
        assertTrue(found.any { it.first == Severity.ERROR && it.second.contains("場所が読めません") })
    }

    @Test
    fun `policy parsing falls back to defaults for unknown values`() {
        val layout = WorkspaceLayout(configDir = ".yde", ledger = emptyList(),
            files = WorkspaceLayout.parseFilesText("files:\n  outside: nope\n  allowedRoots: 'D:\\x\\'\n"))
        assertEquals(FilePolicyLevel.WARNING, layout.files.outside)
        assertEquals(listOf("D:/x"), layout.files.allowedRoots)
        assertEquals(FilePolicy.DEFAULT.missing, layout.files.missing)
    }
}
