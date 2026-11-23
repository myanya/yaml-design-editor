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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保有する要素（入れ子で書かれた要素。P40）と削除のリファクタリング（architecture/graph-query.md「エンティティ操作」）:
 * - 親を削除すると保有する要素も一緒に消える。消える要素と、それへの参照（壊れる参照）は注意事項に並び、計画は止めない
 * - 削除する要素の中に書かれた参照（子から親への参照など）は、そこごと消えるので削除を止めない
 * - 外から参照されている要素の削除も、壊れる参照を注意事項に並べて計画は止めない（UX-165。保有する要素への参照と同じ形）
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
    fun `外から参照されている要素の削除も 壊れる参照を注意事項に並べて計画は止めない`() {
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        // 参照も保有する要素も無い要素の削除は注意事項なし
        assertEquals(emptyList(), session.planRefactoring(RefactoringRequest.DeleteEntity("SI-2")).warnings)
        assertTrue(session.derived.semantic.issues.isEmpty(), session.derived.semantic.issues.toString())

        val plan = session.planRefactoring(RefactoringRequest.DeleteEntity("SI-3"))
        val warning = plan.warnings.single()
        assertTrue("「SI-3」への参照 1 件が壊れます" in warning && "usages/U-2.yaml の item" in warning, warning)
        // 続けると決めれば適用でき、参照は書き換えない（壊れた参照として検証に出る）。Undo 1 回で戻る
        assertTrue(session.applyRefactoring(plan).ok)
        assertFalse("SI-3" in session.derived.semantic.ids)
        assertTrue(session.derived.semantic.issues.any { "SI-3" in it.toString() }, session.derived.semantic.issues.toString())
        assertTrue(session.undo().ok)
        assertTrue("SI-3" in session.derived.semantic.ids)
        assertTrue(session.derived.semantic.issues.isEmpty(), session.derived.semantic.issues.toString())
    }

    @Test
    fun `親への参照と保有する要素への参照は 別々の注意事項に並ぶ`() {
        write("usages/U-3.yaml", "id: U-3\nitem: SCR-1")
        val session = WorkspaceSession(FileSystemDocumentStore(dir))
        val plan = session.planRefactoring(RefactoringRequest.DeleteEntity("SCR-1"))
        assertEquals(2, plan.warnings.size, plan.warnings.toString())
        assertTrue("「SCR-1」への参照 1 件が壊れます" in plan.warnings[0] && "usages/U-3.yaml" in plan.warnings[0], plan.warnings[0])
        assertTrue("SI-2" !in plan.warnings[0], "削除する要素の中に書かれた参照は数えない: ${plan.warnings[0]}")
        assertTrue("保有する要素「SI-1」" in plan.warnings[1], plan.warnings[1])
    }
}
