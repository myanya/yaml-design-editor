package dev.toros.yde.app.core

import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspaceSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保有する要素（入れ子で書かれた要素。P40）と削除のリファクタリング（architecture/graph-query.md「エンティティ操作」）:
 * - 親を削除すると保有する要素も一緒に消える。消える要素と、それへの参照（壊れる参照）は注意事項に並び、計画は止めない
 * - 削除する要素の中に書かれた参照（子から親への参照など）は、そこごと消えるので削除を止めない
 * - 外から参照されている要素の削除は今までどおり拒否する
 */
class HoldingDeleteTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-holding-delete")
        write(
            "_schemas/screens.yaml",
            """
            id: screens
            match: "screens.yaml"
            entity:
              - { type: screen, idPattern: "SCR-*", defines: true }
              - { type: screen-item, idPattern: "SI-*", defines: true }
            root:
              type: object
              fields:
                - key: screens
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - key: items
                        type: array
                        relation: has-item
                        item:
                          type: object
                          fields:
                            - { key: id, type: id, required: true }
                            - { key: back, type: ref }
            """,
        )
        write(
            "_schemas/usage.yaml",
            """
            id: usage
            match: "usages/*.yaml"
            entity: { type: usage, idPattern: "U-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: item, type: ref }
            """,
        )
        write(
            "screens.yaml",
            """
            screens:
              - id: SCR-1
                items:
                  - id: SI-1
                  - id: SI-2
                    back: SCR-1
              - id: SCR-2
                items:
                  - id: SI-3
            """,
        )
        write("usages/U-1.yaml", "id: U-1\nitem: SI-1")
        write("usages/U-2.yaml", "id: U-2\nitem: SI-3")
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

    @Test
    fun `親を削除すると保有する要素も消え 壊れる参照を注意事項に並べて計画は止めない`() {
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        // SI-2 から SCR-1 への参照は削除する要素の中に書かれているので、削除を止めない
        val plan = session.planRefactoring(RefactoringRequest.DeleteEntity("SCR-1"))
        assertTrue(plan.summary.any { "保有する要素 2 件" in it }, plan.summary.toString())
        val warning = plan.warnings.single()
        assertTrue("SI-1" in warning && "usages/U-1.yaml" in warning, warning)
        assertTrue("SI-2" !in warning, "外から参照されていない要素は注意事項に出ない: $warning")
        assertTrue(session.applyRefactoring(plan).ok)
        assertTrue(session.save().ok)
        val text = Files.readString(dir.resolve("screens.yaml"))
        assertFalse("SCR-1" in text || "SI-1" in text, text)
        assertTrue("SCR-2" in text && "SI-3" in text, text)
    }

    @Test
    fun `外から参照されている要素の削除は今までどおり拒否する`() {
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        val e = assertFailsWith<IllegalStateException> {
            session.planRefactoring(RefactoringRequest.DeleteEntity("SI-3"))
        }
        assertTrue("usages/U-2.yaml" in e.message.orEmpty(), e.message)
        // 保有する要素の無い要素の削除は注意事項なし
        val plan = session.planRefactoring(RefactoringRequest.DeleteEntity("SI-2"))
        assertEquals(emptyList(), plan.warnings)
    }
}
