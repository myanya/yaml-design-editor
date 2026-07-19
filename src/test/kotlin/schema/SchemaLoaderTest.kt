package schema

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaLoaderTest {
    @Test
    fun `スキーマ全体の説明を読み込める`() {
        val schema = SchemaLoader.parseSchemaText(
            """
            id: requirement
            name: 要求
            description: |
              要求事項を管理するスキーマです。
              要求の状態と関連を記録します。
            match: "requirements/**/*.yaml"
            root:
              type: object
              fields: []
            """.trimIndent(),
            Path.of("_schemas/requirement.yaml"),
        )

        assertEquals("要求事項を管理するスキーマです。\n要求の状態と関連を記録します。\n", schema.description)
    }
}
