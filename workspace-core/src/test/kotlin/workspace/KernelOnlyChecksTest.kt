package workspace

import model.DocumentId
import validate.CodedChecks
import validate.IssueDelivery
import validate.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * root にだけあった点検と検査が、カーネルだけで動くこと（P27 段D の D-a。意図的差分 D-33）:
 * スキーマの定義の点検・宣言のない関係名の点検（スキーマの読み込みエラー）と、ID のパターンからの逸脱・重複所有・
 * テンプレートの健全性（文書へ配る指摘）。アプリ側と結果が一致することは、root の KernelCheckLoadingTest が確かめる。
 */
class KernelOnlyChecksTest {

    private val relations = """
        id: relations
        kind: relations
        relations:
          - { name: typed-as, label: 値型 }
    """.trimIndent()

    private fun concepts(extraFields: String = "") = """
        id: concepts
        name: 概念
        match: "concepts.yaml"
        entity:
          - { type: vtype, idPattern: "VT-*", defines: true }
          - { type: field, idPattern: "FLD-*", defines: true }
        root:
          type: object
          fields:
            - key: types
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: maxLength, type: int }
            - key: fields
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: vtype, type: ref, idPattern: "VT-*", relation: typed-as }
    """.trimIndent() + extraFields

    private val mapping = """
        id: mapping
        name: 型写像
        match: "mapping.yaml"
        root:
          type: object
          fields:
            - key: mappings
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: to, type: string }
    """.trimIndent()

    private val impl = """
        id: impl
        name: 実装設計
        match: "impl.yaml"
        consistency:
          - description: 実装型は型写像から導出した値と一致すること
            idPattern: "FLD-*"
            path: type
            expected:
              lookup: { schemaId: concepts, via: vtype }
              source: { schemaId: mapping }
              path: to
              values:
                - schemaId: concepts
        root:
          type: object
          fields:
            - key: rows
              type: array
              item:
                type: object
                fields:
                  - { key: id, type: id, required: true }
                  - { key: type, type: string }
    """.trimIndent()

    private fun screenSchema(id: String, dir: String, entity: String) = """
        id: $id
        name: 画面（$id）
        match: "$dir/*.yaml"
        entity: $entity
        root:
          type: object
          fields:
            - { key: id, type: id, required: true }
            - { key: layout, type: string }
            - { key: memo, type: string }
    """.trimIndent()

    private fun load(files: Map<String, String>) = WorkspaceReader.load(InMemoryDocumentStore(files))

    @Test
    fun `スキーマの定義の点検と 宣言のない関係名の点検は スキーマの読み込みエラーになる`() {
        val ws = load(
            mapOf(
                "_schemas/relations.yaml" to relations,
                "_schemas/concepts.yaml" to concepts(
                    "\n          - { key: owner, type: ref, relation: owned-by }" +
                        "\n          - { key: note, type: int, maxLength: 5 }",
                ),
            )
        )
        assertEquals(
            listOf(
                "concepts.yaml [root.fields.item.note]: minLength / maxLength は string / text 型でのみ使用できます",
                "concepts.yaml: 関係「owned-by」は関係レジストリに宣言されていません",
            ),
            ws.schemaErrors,
        )
        // 関係レジストリが無ければ、関係名の点検はしない（宣言のないワークスペースは挙動不変）
        val withoutRegistry = load(mapOf("_schemas/concepts.yaml" to concepts("\n          - { key: owner, type: ref, relation: owned-by }")))
        assertTrue(withoutRegistry.schemaErrors.isEmpty(), withoutRegistry.schemaErrors.toString())
    }

    @Test
    fun `点検の窓口は 1件のスキーマにも使える`() {
        val ws = load(mapOf("_schemas/concepts.yaml" to concepts("\n          - { key: kind, type: string, cases: { a: [] } }")))
        val schema = ws.schemas.single { it.id == "concepts" }
        assertEquals(listOf("casesはenum型でのみ使用できます"), SchemaDefinitionChecks.issuesOf(schema).map { it.message })
        assertEquals(listOf("typed-as"), SchemaDefinitionChecks.undeclaredRelations(schema, emptyMap()))
    }

    @Test
    fun `ID のパターンからの逸脱 重複所有 テンプレートの健全性は 文書へ配る指摘になる`() {
        val ws = load(
            mapOf(
                "_schemas/relations.yaml" to relations,
                "_schemas/concepts.yaml" to concepts(),
                "_schemas/mapping.yaml" to mapping,
                "_schemas/impl.yaml" to impl,
                "_schemas/design.yaml" to screenSchema("design", "details-design", "{ type: screen, idPattern: \"SCR-*\", aspect: 設計, owns: [layout, memo] }"),
                "_schemas/memo.yaml" to screenSchema("memo", "details-memo", "{ type: screen, idPattern: \"SCR-*\", defines: true }"),
                "concepts.yaml" to "types:\n  - id: VT-TITLE\n    maxLength: 100\n  - id: BAD-1\nfields:\n  - id: FLD-TITLE\n    vtype: VT-TITLE\n",
                "mapping.yaml" to "mappings:\n  - id: VT-TITLE\n    to: varchar({maxlen})\n  - id: VT-OK\n    to: varchar({id})\n",
                "impl.yaml" to "rows:\n  - id: FLD-TITLE\n    type: varchar(100)\n",
                "details-design/scr-001.yaml" to "id: SCR-001\nlayout: 2列\nmemo: 設計のメモ\n",
                "details-memo/scr-001.yaml" to "id: SCR-001\nmemo: 補足メモ\n",
            )
        )
        assertTrue(ws.schemaErrors.isEmpty(), ws.schemaErrors.toString())
        val delivered = WorkspaceCheckRunner.deliverAll(ws)
        fun issuesOf(rel: String) = delivered[DocumentId(rel)]?.issues.orEmpty()

        val pattern = issuesOf("concepts.yaml").single()
        assertEquals(Severity.WARNING to "ID「BAD-1」はエンティティ型の idPattern（VT-*, FLD-*）のいずれにも一致しません", pattern.severity to pattern.message)
        assertEquals(listOf<Any>("types", 1), pattern.path)
        assertEquals(Triple("(code):entity#0", "id-pattern", "BAD-1"), Triple(pattern.source.rule?.key, pattern.source.kind, pattern.source.subjectId))
        assertTrue(pattern.source.coded)

        // 重複所有は、双方の出現の文書に出る（少なくとも一方が明示の owns を持つ組だけ）
        for ((rel, other) in listOf("details-design/scr-001.yaml" to "details-memo/scr-001.yaml", "details-memo/scr-001.yaml" to "details-design/scr-001.yaml")) {
            val issue = issuesOf(rel).single()
            assertEquals(
                "エンティティ「SCR-001」のフィールド（layout, memo）が $other の出現でも所有されています（重複所有。片側の owns を絞ってください）",
                issue.message,
            )
            assertEquals("duplicate-ownership", issue.source.kind)
        }

        // テンプレートの健全性は、テンプレートが書かれた場所への指摘（解決できる穴には出ない）
        val template = issuesOf("mapping.yaml").single()
        assertEquals("プレースホルダ {maxlen} を導出の入力（値型定義等）から解決できません — 実装型は型写像から導出した値と一致すること", template.message)
        assertEquals(listOf<Any>("mappings", 0, "to"), template.path)
        assertEquals(CodedChecks.TEMPLATE to IssueDelivery.VALUE, template.source.rule?.at to template.source.delivery)
    }
}
