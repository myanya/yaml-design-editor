package edit

import model.YNode
import schema.StructuralSchemaParser
import validate.Severity
import validate.StructuralValidator
import yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 計画書§9 Scenario A（単文書編集）の統合テスト（Phase 12）:
 * parse → スキーマ適用 → スカラー変更 → 構造検証 → 逆操作で復元 → 再適用 → round-trip 確認、
 * の一巡が yaml-core だけで成立することを固定する。
 */
class ScenarioTest {

    @Test
    fun `シナリオA 単文書編集の一巡が成立する`() {
        // 1. parse（コメント付き原文）。復元の比較基準は正規形（parse直後のdump＝EditBufferと同じ基準）
        val text = "id: IT-001\ntitle: 旧タイトル  # メモ\n"
        val root = YamlIO.parse(text)
        val canonical = YamlIO.dump(root)

        // 2. スキーマを適用する（構造スキーマの解決）
        val schema = StructuralSchemaParser.parse(
            """
            id: item
            root:
              type: object
              fields:
                - key: id
                  type: id
                - key: title
                  type: string
                  required: true
            """.trimIndent(),
            fallbackId = "item",
        )
        assertTrue(StructuralValidator.validate(root, schema).none { it.severity == Severity.ERROR })

        // 3. スカラーを変更する（逆操作列を受け取る）
        val inverse = YamlEdit.apply(root, YamlOperation.SetScalar(listOf("title"), "新タイトル"))

        // 4. 構造検証する（必須 title は満たされたまま）
        assertTrue(StructuralValidator.validate(root, schema).none { it.severity == Severity.ERROR })
        assertEquals("新タイトル", ((root as YNode.Map).entries["title"] as YNode.Scalar).value)

        // 5. inverse operation で元へ戻す（コメント・表記ごと正規形一致）
        inverse.forEach { YamlEdit.apply(root, it) }
        assertEquals(canonical, YamlIO.dump(root))

        // 6. もう一度適用する
        YamlEdit.apply(root, YamlOperation.SetScalar(listOf("title"), "新タイトル"))

        // 7. dump して round-trip を確認する（コメントは保持される）
        val dumped = YamlIO.dump(root)
        assertTrue("title: 新タイトル" in dumped && "# メモ" in dumped, dumped)
        assertEquals(dumped, YamlIO.dump(YamlIO.parse(dumped)), "round-trip 一致")
    }
}
