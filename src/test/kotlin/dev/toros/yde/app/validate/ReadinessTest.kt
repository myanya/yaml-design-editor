package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 準備完了レポート（R28-05b。ARCHITECTURE §4.32）のテスト。
 * 検証指摘のエンティティへの帰属（最も内側のID出現）・準備完了判定（エラー0）・
 * 文書レベル（unassigned）の別掲・フェーズ解決と型／フェーズ別集計を検証する。
 */
class ReadinessTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-readiness")
        write(
            "_schemas/term.yaml",
            """
            id: term
            match: "terms.yaml"
            entity: { type: term, idPattern: "T-*", defines: true }
            phases:
              order: [基本設計, 詳細設計]
            constraints:
              - id: term-used
                description: 用語は1箇所以上から参照されること
                match:
                  - node: { entity: term, bind: t }
                  - edge: { dir: in }
                  - node: { schema: doc }
                expect: exists
                level: error
            root:
              type: object
              fields:
                - key: phase
                  type: enum
                  values: [基本設計, 詳細設計]
                  default: 基本設計
                - key: terms
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: title, type: string, role: title }
            """,
        )
        write(
            "_schemas/doc.yaml",
            """
            id: doc
            match: "docs/*.yaml"
            entity:
              - { type: doc, idPattern: "D-*", defines: true }
              - { type: item, idPattern: "IT-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: uses, type: ref, scope: { schemaIds: [term] } }
            """,
        )
        write("terms.yaml", "phase: 詳細設計\nterms:\n- { id: T-1, title: 参照される用語 }\n- { id: T-2, title: 未参照用語 }")
        write("docs/a.yaml", "id: D-A\nitems:\n- { id: IT-1, uses: T-1 }")
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
    fun `エラーはエンティティに帰属し準備完了はエラー0で判定される`() {
        val report = Readiness.report(Workspace.open(dir))
        assertEquals(listOf("D-A", "IT-1", "T-1", "T-2"), report.entities.map { it.id })
        val t2 = report.entities.single { it.id == "T-2" }
        assertEquals(1, t2.errors)
        assertTrue(!t2.ready)
        assertTrue(report.entities.filter { it.id != "T-2" }.all { it.ready })
    }

    @Test
    fun `指摘は最も内側のID出現のエンティティに帰属する`() {
        // 項目の参照切れは項目（IT-1）の指摘であり、容れ物の文書エンティティ（D-A）は責めない
        write("docs/a.yaml", "id: D-A\nitems:\n- { id: IT-1, uses: T-9 }")
        write("terms.yaml", "phase: 詳細設計\nterms:\n- { id: T-1, title: 参照される用語 }")
        val report = Readiness.report(Workspace.open(dir))
        val it1 = report.entities.single { it.id == "IT-1" }
        assertTrue(it1.errors >= 1 && !it1.ready)
        assertTrue(report.entities.single { it.id == "D-A" }.ready)
    }

    @Test
    fun `エンティティ外の指摘は文書レベルとして別掲される`() {
        write(
            "_schemas/plain.yaml",
            """
            id: plain
            match: "plain.yaml"
            root:
              type: object
              fields:
                - { key: title, type: string, required: true }
            """,
        )
        write("plain.yaml", "note: タイトルなし")
        val report = Readiness.report(Workspace.open(dir))
        val row = report.unassigned.single { it.file == "plain.yaml" }
        assertTrue(row.errors >= 1, row.toString())
        // エンティティ側の一覧には plain.yaml の指摘は混ざらない
        assertTrue(report.entities.none { it.file == "plain.yaml" })
    }

    @Test
    fun `フェーズが解決され型とフェーズで集計される`() {
        val report = Readiness.report(Workspace.open(dir))
        assertEquals("詳細設計", report.entities.single { it.id == "T-1" }.phase)
        val byTerm = report.byType.single { it.key == "term" }
        assertEquals(2, byTerm.total)
        assertEquals(1, byTerm.ready)
        val byPhase = report.byPhase.single { it.key == "詳細設計" }
        assertEquals(2, byPhase.total)
        assertEquals(1, byPhase.ready)
    }
}
