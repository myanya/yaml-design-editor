package core

import model.DocumentId
import model.YDocument
import model.YPath
import workspace.FileSystemDocumentStore
import workspace.WorkspaceSession
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 旧経路（core.Workspace＋IdIndex／overlayIndex）と新経路（WorkspaceSession＋derivedWith）の
 * 読み取り一致テスト（移行後リファクタリング Phase A-1・RA-01）。
 * AppState がセッションを併設し読み経路を切り替えていく前提として、
 * ディスク状態と未保存オーバーレイの両方で走査結果（ID・参照）が一致することを固定する。
 * 既知の非対象: derive スキーマ・メタスキーマ適用（C-2 の残課題。ここでは使わない）。
 */
class SessionReadParityTest {

    private lateinit var ws: Path

    @BeforeTest
    fun setup() {
        ws = createTempDirectory("yde-session-parity")
        Files.createDirectories(ws.resolve("_schemas"))
        Files.createDirectories(ws.resolve("items"))
        Files.writeString(
            ws.resolve("_schemas/item.yaml"),
            """
            id: fx-item
            match: "items/**/*.yaml"
            entity:
              type: item
              idPattern: "IT-*"
              defines: true
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: title
                  type: string
                  required: true
                - key: link
                  type: ref
            """.trimIndent(),
        )
        Files.writeString(ws.resolve("items/a.yaml"), "id: IT-001\ntitle: A\n")
        Files.writeString(ws.resolve("items/b.yaml"), "id: IT-002\ntitle: B\nlink: IT-001\n")
    }

    @AfterTest
    fun cleanup() {
        Files.walk(ws).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** 旧経路の走査結果を（相対パス, ID, パス）の組へ写す。 */
    private fun oldEntries(idx: index.IdIndex, root: Path): Set<Triple<String, String, YPath>> =
        idx.entries.values.flatten()
            .map { Triple(index.IdIndex.relPathOf(root, it.file), it.id, it.path) }.toSet()

    private fun oldRefs(idx: index.IdIndex, root: Path): Set<Triple<String, String, YPath>> =
        idx.refs.map { Triple(index.IdIndex.relPathOf(root, it.file), it.targetId, it.path) }.toSet()

    /** 新経路の走査結果を同じ座標系（相対パス, ID, パス）へ写す。 */
    private fun newEntries(derived: workspace.WorkspaceDerivedState): Set<Triple<String, String, YPath>> =
        derived.semantic.ids.values.flatten()
            .map { Triple(it.document.value, it.id, it.path) }.toSet()

    private fun newRefs(derived: workspace.WorkspaceDerivedState): Set<Triple<String, String, YPath>> =
        derived.semantic.refs.map { Triple(it.document.value, it.targetId, it.path) }.toSet()

    @Test
    fun `ディスク状態のID・参照の走査結果が旧経路と一致する`() {
        val cw = Workspace.open(ws)
        val s = WorkspaceSession(FileSystemDocumentStore(ws))
        assertEquals(oldEntries(cw.index, cw.root), newEntries(s.derived), "ID出現の集合")
        assertEquals(oldRefs(cw.index, cw.root), newRefs(s.derived), "参照の集合")
        assertEquals(
            cw.index.entries["IT-001"]?.firstOrNull()?.entityType,
            s.derived.semantic.ids["IT-001"]?.firstOrNull()?.entityType,
            "entity 宣言の解決（型）も一致",
        )
    }

    @Test
    fun `未保存オーバーレイを重ねた走査結果が旧経路のoverlayIndexと一致する`() {
        val cw = Workspace.open(ws)
        val s = WorkspaceSession(FileSystemDocumentStore(ws))
        // 編集中: b の参照を未解決へ変え、未保存の新規文書 e（IT-777）を重ねる
        val editedB = YamlIO.parse("id: IT-002\ntitle: B\nlink: IT-999\n")
        val newE = YamlIO.parse("id: IT-777\ntitle: E\n")

        val oldOverlay = cw.overlayIndex(
            mapOf(ws.resolve("items/b.yaml") to editedB, ws.resolve("items/e.yaml") to newE)
        )
        val newOverlay = s.derivedWith(
            mapOf(
                DocumentId("items/b.yaml") to YDocument(DocumentId("items/b.yaml"), listOf(editedB)),
                DocumentId("items/e.yaml") to YDocument(DocumentId("items/e.yaml"), listOf(newE)),
            )
        )
        assertEquals(oldEntries(oldOverlay, cw.root), newEntries(newOverlay), "オーバーレイ後のID出現の集合")
        assertEquals(oldRefs(oldOverlay, cw.root), newRefs(newOverlay), "オーバーレイ後の参照の集合")
        assertTrue("IT-777" in newOverlay.semantic.ids, "未保存の新IDが載る")
        assertTrue(newOverlay.semantic.issues.any { "IT-999" in it.message }, "未解決参照が指摘される")
        // 二層規律: どちらの経路もディスク側（正）は変わらない
        assertTrue("IT-777" !in cw.index.entries, "旧経路のディスク索引は不変")
        assertTrue("IT-777" !in s.derived.semantic.ids, "新経路のセッションworkingは不変")
    }

    @Test
    fun `deriveスキーマの適用（走査規則）が新経路でも一致する`() {
        // 導出元文書（テーブル定義）から派生スキーマを作るワークスペース（R11-03 / C-2）
        Files.createDirectories(ws.resolve("defs"))
        Files.createDirectories(ws.resolve("data/user"))
        Files.writeString(
            ws.resolve("_schemas/table.yaml"),
            """
            id: table-template
            derive:
              from: "defs/*.yaml"
              each: tables
              id: "tbl-{name}"
              match: "data/{name}/*.yaml"
              fields:
                source: columns
                key: name
                type:
                  path: kind
                  map:
                    identifier: id
                    reference: ref
                  default: string
            root:
              type: object
              fields:
                - key: memo
                  type: string
            """.trimIndent(),
        )
        Files.writeString(
            ws.resolve("defs/tables.yaml"),
            "tables:\n  - name: user\n    columns:\n      - name: code\n        kind: identifier\n      - name: uses\n        kind: reference\n",
        )
        // code / uses は命名規約ヒューリスティクスに掛からない名前＝導出スキーマの型宣言だけが走査根拠
        Files.writeString(ws.resolve("data/user/u1.yaml"), "code: U-1\n")
        Files.writeString(ws.resolve("data/user/u2.yaml"), "code: U-2\nuses: U-1\n")

        val cw = Workspace.open(ws)
        val s = WorkspaceSession(FileSystemDocumentStore(ws))
        assertEquals(oldEntries(cw.index, cw.root), newEntries(s.derived), "導出スキーマ配下のID出現が一致")
        assertEquals(oldRefs(cw.index, cw.root), newRefs(s.derived), "導出スキーマ配下の参照が一致")
        assertTrue("U-1" in s.derived.semantic.ids, "導出フィールド（code: id 型）が走査に効く")
        assertEquals("tbl-user", s.derived.semantic.ids["U-1"]?.single()?.schemaId, "導出スキーマが割当てられる")
    }

    @Test
    fun `derivedIdIndexファサードがoverlayIndexと完全一致する`() {
        val cw = Workspace.open(ws)
        val s = WorkspaceSession(
            FileSystemDocumentStore(ws),
            builtins = schema.BuiltinSchemas.kernelSchemas(cw.layout),
        )
        // ディスク状態: エントリ（並び含む）・参照・被参照が一致
        val diskFacade = index.derivedIdIndex(s.derived.semantic.scans, cw.root)
        assertEquals(cw.index.entries, diskFacade.entries, "entries（出現の並び含む）")
        assertEquals(cw.index.refs, diskFacade.refs, "refs（並び含む）")

        // 未保存オーバーレイ: 旧経路の overlayIndex と一致
        val editedB = YamlIO.parse("id: IT-002\ntitle: B\nlink: IT-999\n")
        val old = cw.overlayIndex(mapOf(ws.resolve("items/b.yaml") to editedB))
        val derived = s.derivedWith(
            mapOf(DocumentId("items/b.yaml") to YDocument(DocumentId("items/b.yaml"), listOf(editedB)))
        )
        val facade = index.derivedIdIndex(derived.semantic.scans, cw.root)
        assertEquals(old.entries, facade.entries, "オーバーレイ後の entries")
        assertEquals(old.refs, facade.refs, "オーバーレイ後の refs")
        assertEquals(old.backlinksTo(setOf("IT-001")), facade.backlinksTo(setOf("IT-001")), "グラフ導出（被参照）")
        assertEquals(
            old.resolve("IT-002")?.file, facade.resolve("IT-002")?.file,
            "代表出現の解決（ジャンプ先）",
        )
    }

    @Test
    fun `メタスキーマ（実組込の注入）が_schemas文書へ適用される`() {
        val cw = Workspace.open(ws)
        val s = WorkspaceSession(
            FileSystemDocumentStore(ws),
            builtins = schema.BuiltinSchemas.kernelSchemas(cw.layout),
        )
        assertEquals(
            schema.BuiltinSchemas.META_SCHEMA_ID,
            s.snapshot.documents.getValue(DocumentId("_schemas/item.yaml")).schemaId,
            "`_schemas` 配下にはメタスキーマだけが適用される（一般スキーマの match は及ばない）",
        )
        assertTrue(
            s.derived.structuralIssues.getValue(DocumentId("_schemas/item.yaml"))
                .none { it.severity == validate.Severity.ERROR },
            "妥当なスキーマ定義は実メタスキーマ検証を ERROR なしで通る: ${s.derived.structuralIssues[DocumentId("_schemas/item.yaml")]}",
        )
    }
}
