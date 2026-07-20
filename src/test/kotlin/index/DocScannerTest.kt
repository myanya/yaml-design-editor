package index

import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocScannerTest {

    private val schema = SchemaLoader.parseSchemaText(
        """
        id: screen
        name: 画面
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: type
              type: enum
              values: [radio, text]
              cases:
                radio:
                  - key: options
                    type: array
                    item:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: rel
                          type: ref
        """.trimIndent(),
        Path.of("_schemas/screen.yaml"),
    )

    @Test
    fun `アクティブなcase配下のIDと参照が索引化される`() {
        val root = YamlIO.parse(
            """
            type: radio
            options:
              - id: OPT-1
                rel: TARGET-1
            """.trimIndent()
        )
        val (ids, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), schema)
        assertEquals(listOf("OPT-1"), ids.map { it.id })
        assertEquals(listOf("TARGET-1"), refs.map { it.targetId })
    }

    @Test
    fun `非アクティブなcase配下はスキーマ境界として索引化されない`() {
        val root = YamlIO.parse(
            """
            type: text
            options:
              - id: OPT-1
                rel: TARGET-1
            """.trimIndent()
        )
        val (ids, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), schema)
        assertTrue(ids.isEmpty())
        assertTrue(refs.isEmpty())
    }
}
