package semantic

import model.DocumentId
import schema.EntityDecl
import schema.RelationDef
import schema.StructuralSchemaParser
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 増分更新の oracle テスト（Phase 11・PF-001）: どの変更でも
 * `SemanticEngine.update` の結果が同じ文書集合の `SemanticEngine.build`（全再構築＝正の基準）と
 * 完全一致することを固定する。
 */
class SemanticIncrementTest {

    private val schema = StructuralSchemaParser.parse(
        """
        id: item
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: title
              type: string
            - key: link
              type: ref
              relation: uses
        """.trimIndent(),
        fallbackId = "item",
    )

    private val entities = listOf(EntityDecl(type = "item", idPattern = "IT-*", defines = true))

    private val declarations = SemanticDeclarations(
        relations = mapOf(
            "uses" to RelationDef(name = "uses", source = listOf("item"), target = listOf("item"), acyclic = true),
        ),
    )

    private fun doc(name: String, yaml: String) = SemanticDocument(
        id = DocumentId(name),
        root = YamlIO.parse(yaml.trimIndent()),
        schema = schema,
        schemaId = "item",
        entities = entities,
    )

    private fun buildAll(version: Long, texts: Map<String, String>): SemanticSnapshot =
        SemanticEngine.build(
            SemanticInput(version, texts.map { (name, yaml) -> doc(name, yaml) }, declarations)
        )

    private fun assertSameSnapshot(oracle: SemanticSnapshot, actual: SemanticSnapshot, label: String) {
        assertEquals(oracle.sourceVersion, actual.sourceVersion, label)
        assertEquals(oracle.ids, actual.ids, label)
        assertEquals(oracle.refs, actual.refs, label)
        assertEquals(oracle.documentIds, actual.documentIds, label)
        assertEquals(oracle.issues, actual.issues, label)
        assertEquals(oracle.graph.entities, actual.graph.entities, label)
        assertEquals(oracle.graph.documents, actual.graph.documents, label)
        assertEquals(oracle.scans, actual.scans, label)
    }

    @Test
    fun `増分更新は編集追加削除のどれでも全再構築と一致する`() {
        val texts = linkedMapOf(
            "items/a.yaml" to "id: IT-001\ntitle: A",
            "items/b.yaml" to "id: IT-002\ntitle: B\nlink: IT-001",
            "items/c.yaml" to "id: IT-003\ntitle: C\nlink: IT-002",
        )
        var version = 1L
        var snapshot = buildAll(version, texts)
        assertTrue(snapshot.issues.isEmpty(), "初期状態は問題なし: ${snapshot.issues}")

        fun step(label: String, changed: Set<String> = emptySet(), removed: Set<String> = emptySet()) {
            version += 1
            snapshot = SemanticEngine.update(
                snapshot,
                SemanticDelta(
                    workspaceVersion = version,
                    changed = changed.map { doc(it, texts.getValue(it)) },
                    removed = removed.map { DocumentId(it) }.toSet(),
                    declarations = declarations,
                ),
            )
            assertSameSnapshot(buildAll(version, texts), snapshot, label)
        }

        // 編集: 参照先を存在しないIDへ（未解決 ERROR が現れる）
        texts["items/b.yaml"] = "id: IT-002\ntitle: B\nlink: IT-404"
        step("編集（未解決参照の発生）", changed = setOf("items/b.yaml"))
        assertTrue(snapshot.issues.any { "IT-404" in it.message }, "${snapshot.issues}")

        // 追加: 新しい文書
        texts["items/d.yaml"] = "id: IT-004\ntitle: D\nlink: IT-003"
        step("追加", changed = setOf("items/d.yaml"))

        // 削除: 参照されていない文書を除く
        texts.remove("items/a.yaml")
        step("削除", removed = setOf("items/a.yaml"))

        // 編集: 自己参照（acyclic 宣言の循環 ERROR が現れる＝グラフ整合検証も増分経路で一致）
        texts["items/b.yaml"] = "id: IT-002\ntitle: B\nlink: IT-002"
        step("編集（循環の発生）", changed = setOf("items/b.yaml"))
        assertTrue(snapshot.issues.any { "循環" in it.message }, "${snapshot.issues}")

        // 編集で復旧
        texts["items/b.yaml"] = "id: IT-002\ntitle: B\nlink: IT-003"
        step("編集（復旧）", changed = setOf("items/b.yaml"))
    }

    @Test
    fun `空の差分でもsourceVersionが進み内容は変わらない`() {
        val texts = mapOf("items/a.yaml" to "id: IT-001\ntitle: A")
        val first = buildAll(5, texts)
        val updated = SemanticEngine.update(first, SemanticDelta(workspaceVersion = 9, changed = emptyList(), declarations = declarations))
        assertSameSnapshot(buildAll(9, texts), updated, "空差分")
        assertEquals(9, updated.sourceVersion)
    }
}
