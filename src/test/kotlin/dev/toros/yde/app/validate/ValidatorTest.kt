package dev.toros.yde.app.validate

import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.app.schema.validateDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Validator まわりのメタスキーマ検証テスト。
 * cases 合成・属性付き参照など単一文書で完結する構造検証のケースは、
 * yaml-core の StructuralValidatorTest へ移設した（3層コア移行 YM-006）。
 */
class ValidatorTest {

    @Test
    fun `属性への入れ子refはメタスキーマ検証で不許可`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: bad
            name: 属性に入れ子ref
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: owner
                  type: ref
                  fields:
                    - key: delegate
                      type: ref
                    - key: to
                      type: string
            """.trimIndent(),
            Path.of("_schemas/bad.yaml"),
        )
        val issues = schema.validateDefinition().map { it.message }
        assertTrue(issues.any { "ref / id 型は使えません" in it }, issues.toString())
        assertTrue(issues.any { "予約キー" in it }, issues.toString())
    }
}
