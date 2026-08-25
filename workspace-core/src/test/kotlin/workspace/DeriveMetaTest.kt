package workspace

import edit.YamlOperation
import model.DocumentId
import model.parseYPath
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 派生スキーマ（derive。R11-03）とメタスキーマ適用の reader / session 対応（移行後リファクタリング C-2。
 * WS-002 v1 の残の解消）。展開コアは yaml-core の SchemaDerivation（root DerivedSchemas と共有）。
 */
class DeriveMetaTest {

    private val template = """
        id: table-template
        derive:
          from: "defs/*.yaml"
          each: tables
          id: "tbl-{name}"
          name: "{name} テーブル"
          match: "data/{name}/*.yaml"
          fields:
            source: columns
            key: name
            required: required
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
    """.trimIndent()

    private val tablesDoc = """
        tables:
          - name: user
            columns:
              - name: code
                kind: identifier
              - name: email
                required: true
              - name: uses
                kind: reference
    """.trimIndent()

    private fun store() = InMemoryDocumentStore(
        mapOf(
            "_schemas/table.yaml" to template,
            "defs/tables.yaml" to tablesDoc,
            "data/user/u1.yaml" to "code: U-1\nemail: a@example.com\n",
            "data/user/u2.yaml" to "code: U-2\nuses: U-1\n",  // email 必須欠落
        )
    )

    @Test
    fun `deriveスキーマがreaderで展開され割当・構造検証・走査に効く`() {
        val ws = WorkspaceReader.load(store())
        val derived = ws.schemas.firstOrNull { it.id == "tbl-user" }
        assertTrue(derived != null && !derived.isTemplate, "導出スキーマが展開される: ${ws.schemas.map { it.id }}")
        assertTrue(ws.schemas.first { it.id == "table-template" }.isTemplate, "テンプレートは文書にマッチしない")
        assertEquals("tbl-user", ws.snapshot.documents.getValue(DocumentId("data/user/u1.yaml")).schemaId)
        assertTrue(
            ws.derived.structuralIssues.getValue(DocumentId("data/user/u2.yaml")).any { "email" in it.message },
            "導出フィールドの必須が構造検証に効く: ${ws.derived.structuralIssues}",
        )
        // 意味走査: code が ID・uses が ref として走査される（導出スキーマの型写像が効く）
        assertTrue("U-1" in ws.derived.semantic.ids, "${ws.derived.semantic.ids.keys}")
        assertEquals(1, ws.derived.semantic.backlinksTo(setOf("U-1")).size)
    }

    @Test
    fun `導出元文書の編集はスキーマ再構築を経て派生に反映されundoで戻る`() {
        val s = WorkspaceSession(store()) { Instant.EPOCH }
        assertTrue(s.derived.structuralIssues.getValue(DocumentId("data/user/u2.yaml")).any { "email" in it.message })
        // 列 email の required を外す → u2 の必須欠落が消える（導出元の編集＝スキーマ変更と同じ扱い）
        val r = s.apply(
            WorkspaceTransaction(
                s.version, ChangeIntent("required解除"),
                listOf(
                    WorkspaceOperation.EditDocument(
                        DocumentId("defs/tables.yaml"),
                        listOf(YamlOperation.SetScalar(parseYPath("tables[0].columns[1].required"), false)),
                    )
                ),
            )
        )
        assertTrue(r.ok, r.message ?: "")
        assertTrue(s.derived.structuralIssues.getValue(DocumentId("data/user/u2.yaml")).none { "email" in it.message })
        // oracle: 全再構築と一致
        val full = s.fullDerived()
        assertEquals(full.structuralIssues, s.derived.structuralIssues)
        assertEquals(full.semantic.ids, s.derived.semantic.ids)
        assertEquals(full.semantic.issues, s.derived.semantic.issues)
        assertTrue(s.undo().ok)
        assertTrue(
            s.derived.structuralIssues.getValue(DocumentId("data/user/u2.yaml")).any { "email" in it.message },
            "undo で導出スキーマも対称に戻る",
        )
    }

    @Test
    fun `noteStoreChangesは導出元文書の変更を全再読込へ降格する`() {
        val st = store()
        val s = WorkspaceSession(st) { Instant.EPOCH }
        st.write(DocumentId("defs/tables.yaml"), tablesDoc.replace("required: true", "required: false"))
        assertTrue(s.noteStoreChanges(listOf(DocumentId("defs/tables.yaml"))).ok)
        assertTrue(
            s.derived.structuralIssues.getValue(DocumentId("data/user/u2.yaml")).none { "email" in it.message },
            "導出元の外部変更でスキーマが作り直される: ${s.derived.structuralIssues}",
        )
    }

    @Test
    fun `隠しパスの文書は文書集合から除外される`() {
        // root WorkspaceScan と同じ規則（RA-02 で一致を明示）: 隠しディレクトリ・隠しファイルは対象外
        val ws = WorkspaceReader.load(
            InMemoryDocumentStore(
                mapOf(
                    "notes/a.yaml" to "id: N-1\ntitle: A\n",
                    ".trash/old.yaml" to "id: N-9\ntitle: 削除済み\n",
                    "notes/.draft.yaml" to "id: N-8\ntitle: 下書き\n",
                )
            )
        )
        assertEquals(
            listOf("notes/a.yaml"),
            ws.snapshot.documents.keys.map { it.value },
            "隠しディレクトリ・隠しファイルは文書集合に載らない",
        )
        assertTrue("N-9" !in ws.derived.semantic.ids && "N-8" !in ws.derived.semantic.ids)
    }

    @Test
    fun `メタスキーマが_schemas文書へ適用され構造検証が走る`() {
        val builtins = WorkspaceReader.parseSchemas(
            listOf(
                DocumentId("(builtin)/schema.yaml") to """
                    id: ${WorkspaceReader.META_SCHEMA_ID}
                    match: "_schemas/**/*.{yaml,yml}"
                    root:
                      type: object
                      fields:
                        - key: id
                          type: string
                          required: true
                """.trimIndent(),
                DocumentId("(builtin)/relations.yaml") to """
                    id: ${WorkspaceReader.RELATIONS_SCHEMA_ID}
                    match: "_schemas/**/*.{yaml,yml}"
                    root:
                      type: object
                      fields:
                        - key: kind
                          type: string
                          required: true
                """.trimIndent(),
            )
        ).schemas
        val ws = WorkspaceReader.load(
            InMemoryDocumentStore(
                mapOf(
                    "_schemas/noid.yaml" to "match: \"x/*.yaml\"\nroot:\n  type: object\n",  // id 欠落
                    "_schemas/rel.yaml" to "kind: relations\nrelations: []\n",
                    "notes/a.yaml" to "title: hello\n",
                )
            ),
            builtins = builtins,
        )
        assertEquals(
            WorkspaceReader.META_SCHEMA_ID,
            ws.snapshot.documents.getValue(DocumentId("_schemas/noid.yaml")).schemaId,
            "schemas ゾーンにはメタスキーマだけが適用される",
        )
        assertEquals(
            WorkspaceReader.RELATIONS_SCHEMA_ID,
            ws.snapshot.documents.getValue(DocumentId("_schemas/rel.yaml")).schemaId,
            "kind: relations の文書には関係レジストリのメタスキーマ",
        )
        assertTrue(
            ws.derived.structuralIssues.getValue(DocumentId("_schemas/noid.yaml")).any { "id" in it.message },
            "メタスキーマの必須検証が効く: ${ws.derived.structuralIssues}",
        )
    }
}
