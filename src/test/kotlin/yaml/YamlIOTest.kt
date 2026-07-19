package yaml

import kotlin.test.Test
import kotlin.test.assertEquals

class YamlIOTest {

    @Test
    fun `block sequences are indented below their parent property`() {
        val node = YamlIO.parse(
            """
            title: sample
            items:
              - id: first
                tags:
                  - one
                  - two
              - id: second
            """.trimIndent()
        )

        val expected = "title: sample\n" +
            "items:\n" +
            "  - id: first\n" +
            "    tags:\n" +
            "      - one\n" +
            "      - two\n" +
            "  - id: second\n"

        assertEquals(expected, YamlIO.dump(node))
    }
}
