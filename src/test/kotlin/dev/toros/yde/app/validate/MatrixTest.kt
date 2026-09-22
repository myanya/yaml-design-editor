package dev.toros.yde.app.validate

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 網羅マトリクス検証（R28-03。ARCHITECTURE §4.32）のテスト。
 * 「行×列の全組合せに証人（witness）が存在すること」——不足セルの列挙・行の定義出現への報告・
 * 明示的除外（証人の excluded: true 行）による充足・母集団外／列空の判定保留を検証する。
 */
class MatrixTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("yde-matrix")
        write(
            "_schemas/process.yaml",
            """
            id: process
            match: "procs/*.yaml"
            entity: { type: process, idPattern: "PROC-*", defines: true }
            matrix:
              - id: proc-vp-matrix
                description: 各処理は全試験観点のテスト項目を持つこと
                rows: { entity: process }
                columns: { entity: viewpoint }
                witness:
                  node: { entity: item }
                  row: { relation: verifies }
                  column: { relation: viewpoint }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/viewpoint.yaml",
            """
            id: viewpoint
            match: "vps.yaml"
            entity: { type: viewpoint, idPattern: "VP-*", defines: true }
            root:
              type: object
              fields:
                - key: viewpoints
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
            """,
        )
        write(
            "_schemas/item.yaml",
            """
            id: item
            match: "items.yaml"
            entity: { type: item, idPattern: "TI-*", defines: true }
            root:
              type: object
              fields:
                - key: items
                  type: array
                  item:
                    type: object
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: target, type: ref, relation: verifies, scope: { schemaIds: [process] } }
                      - { key: viewpoint, type: ref, relation: viewpoint, scope: { schemaIds: [viewpoint] } }
                      - { key: excluded, type: boolean }
            """,
        )
        write("vps.yaml", "viewpoints:\n- { id: VP-1, name: 正常系 }\n- { id: VP-2, name: 境界値 }")
        write("procs/PROC-1.yaml", "id: PROC-1\nname: 登録処理")
        write("items.yaml", "items:\n- { id: TI-1, target: PROC-1, viewpoint: VP-1 }")
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
    fun `不足セルは行の定義出現に列タイトルつきで列挙される`() {
        val ws = Workspace.open(dir)
        val issue = ws.validate("procs/PROC-1.yaml").single { "網羅マトリクス" in it.message }
        assertEquals(Severity.WARNING, issue.severity)
        assertTrue("PROC-1" in issue.message, issue.message)
        assertTrue("1 件" in issue.message && "VP-2（境界値）" in issue.message, issue.message)
        assertTrue("全試験観点のテスト項目" in issue.message, issue.message)
        // 列側・証人側の文書には報告しない（行の定義出現のみ）
        assertTrue(ws.validate("vps.yaml").none { "網羅マトリクス" in it.message })
        assertTrue(ws.validate("items.yaml").none { "網羅マトリクス" in it.message })
    }

    @Test
    fun `全セルに証人があると指摘は消える`() {
        write("items.yaml", "items:\n- { id: TI-1, target: PROC-1, viewpoint: VP-1 }\n- { id: TI-2, target: PROC-1, viewpoint: VP-2 }")
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("procs/PROC-1.yaml").none { "網羅マトリクス" in it.message })
    }

    @Test
    fun `excludedの証人でもセルは充足する（明示的除外）`() {
        write(
            "items.yaml",
            "items:\n- { id: TI-1, target: PROC-1, viewpoint: VP-1 }\n- { id: TI-2, target: PROC-1, viewpoint: VP-2, excluded: true }",
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("procs/PROC-1.yaml").none { "網羅マトリクス" in it.message })
    }

    @Test
    fun `列の母集団が空なら判定しない`() {
        write("vps.yaml", "viewpoints: []")
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("procs/PROC-1.yaml").none { "網羅マトリクス" in it.message })
    }

    @Test
    fun `行の母集団外のエンティティには黙る`() {
        // rows を idPattern で絞る → 合わない行は判定されない
        write(
            "_schemas/process.yaml",
            """
            id: process
            match: "procs/*.yaml"
            entity: { type: process, idPattern: "PROC-*", defines: true }
            matrix:
              - id: proc-vp-matrix
                rows: { entity: process, id: "PROC-9*" }
                columns: { entity: viewpoint }
                witness:
                  node: { entity: item }
                  row: { relation: verifies }
                  column: { relation: viewpoint }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - { key: name, type: string, role: title }
            """,
        )
        val ws = Workspace.open(dir)
        assertTrue(ws.validate("procs/PROC-1.yaml").none { "網羅マトリクス" in it.message })
    }

    @Test
    fun `詳細化出現の文書には定義出現への報告が案内される`() {
        // R28-05a（UX-96）の配送: 報告先は行の定義出現のみ・同じIDの詳細化出現には INFO の案内
        write(
            "_schemas/process-detail.yaml",
            """
            id: process-detail
            match: "details/*.yaml"
            entity: { type: process, idPattern: "PROC-*" }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
            """,
        )
        write("details/PROC-1.yaml", "id: PROC-1")
        val ws = Workspace.open(dir)
        val notice = ws.validate("details/PROC-1.yaml").single { "網羅マトリクス" in it.message }
        assertEquals(Severity.INFO, notice.severity)
        assertTrue("報告先: 定義出現 procs/PROC-1.yaml" in notice.message, notice.message)
        assertTrue("1 件" in notice.message && "VP-2（境界値）" in notice.message, notice.message)
        assertTrue("全試験観点のテスト項目" in notice.message, notice.message)
        assertEquals(listOf("PROC-1", "VP-2"), notice.relatedIds)
        // 報告先（定義出現）の指摘は従来どおり1件
        assertEquals(Severity.WARNING, ws.validate("procs/PROC-1.yaml").single { "網羅マトリクス" in it.message }.severity)
    }

    @Test
    fun `複数の不足セルは件数と一覧で報告される`() {
        write("vps.yaml", "viewpoints:\n- { id: VP-1, name: 正常系 }\n- { id: VP-2, name: 境界値 }\n- { id: VP-3, name: 排他 }")
        write("items.yaml", "items: []")
        val ws = Workspace.open(dir)
        val issue = ws.validate("procs/PROC-1.yaml").single { "網羅マトリクス" in it.message }
        assertTrue("3 件" in issue.message, issue.message)
        assertTrue("VP-1（正常系）" in issue.message && "VP-3（排他）" in issue.message, issue.message)
    }
}
