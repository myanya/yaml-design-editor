package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.semantic.schema.DerivationDef
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 決定論的導出カーネル（SemanticDerivation。R16-02/03a）の契約テスト（Phase 6D）。
 * 完了条件: 導出値が入力座標（inputs）と規則座標（sourceDocument / sourcePath）の来歴を持ち、
 * 未解決がプレースホルダ単位で構造化されること。
 * 網羅はルート validate/ConsistencyTest・core/ViewsTest（シェル経由＝旧経路との一致）が担う。
 */
class SemanticDerivationTest {

    private val schema = SchemaTreeParser.parse(
        """
        id: tbl
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: name
              type: string
            - key: ddl
              type: string
        """.trimIndent(),
        fallbackId = "tbl",
    )

    private val tree = YamlIO.parse("id: T-1\nname: users\nddl: \"prefix-{name}\"")
    private val trees = mapOf("tables/a.yaml" to tree)

    private fun snapshot() = SemanticEngine.build(
        SemanticInput(1, listOf(SemanticDocument(DocumentId("tables/a.yaml"), tree, schema, "tbl"))),
    )

    private fun resolve(def: DerivationDef): SemanticDerivation.Result {
        val s = snapshot()
        return SemanticDerivation.resolve(
            def = def,
            startId = "T-1",
            occurrences = { id -> s.occurrences(id) },
            rel = { it.value },
            tree = { trees[it.value] },
        )
    }

    @Test
    fun `導出値は規則座標と入力座標の来歴を持つ`() {
        val result = resolve(
            DerivationDef(source = DerivationSelector(schemaId = "tbl"), path = parseYPath("ddl")),
        )
        assertEquals("prefix-users", result.value)
        assertEquals("prefix-{name}", result.raw)
        assertTrue(result.complete)
        assertEquals("tables/a.yaml", result.sourceDocument?.value, "規則座標（元値の文書）")
        assertEquals(listOf<Any>("ddl"), result.sourcePath)
        val input = result.inputs.single()
        assertEquals("name", input.placeholder)
        assertEquals("users", input.value)
        assertEquals("tables/a.yaml", input.document?.value, "入力座標（プレースホルダの解決元）")
        assertEquals(listOf<Any>("name"), input.path)
    }

    @Test
    fun `未解決プレースホルダは構造化されcompleteがfalseになる`() {
        val result = resolve(
            DerivationDef(source = DerivationSelector(schemaId = "tbl"), path = parseYPath("ddl"), map = emptyMap())
                .let { it.copy(path = parseYPath("ddl")) },
        )
        // ddl テンプレートに解決できないプレースホルダを含む文書で検証する
        val missingTree = YamlIO.parse("id: T-1\nddl: \"{missing}-x\"")
        val s = SemanticEngine.build(
            SemanticInput(1, listOf(SemanticDocument(DocumentId("tables/m.yaml"), missingTree, schema, "tbl"))),
        )
        val r = SemanticDerivation.resolve(
            def = DerivationDef(source = DerivationSelector(schemaId = "tbl"), path = parseYPath("ddl")),
            startId = "T-1",
            occurrences = { id -> s.occurrences(id) },
            rel = { it.value },
            tree = { mapOf("tables/m.yaml" to missingTree)[it.value] },
        )
        assertEquals("{missing}-x", r.value, "解決できないプレースホルダはそのまま残す（表示で気づける）")
        assertFalse(r.complete)
        val input = r.inputs.single()
        assertEquals("missing", input.placeholder)
        assertNull(input.value)
        assertNull(input.document, "未解決は座標なし")
        // 前段の result は complete=true（対比）
        assertTrue(result.complete)
    }

    @Test
    fun `元値がなくdefaultもなければ導出できないことが構造化される`() {
        val result = resolve(
            DerivationDef(source = DerivationSelector(schemaId = "other"), path = parseYPath("ddl")),
        )
        assertNull(result.value)
        assertNull(result.raw)
        assertTrue(result.complete)
        assertNull(result.sourceDocument, "source 出現なし＝規則座標なし")
    }
}
