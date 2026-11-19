package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeneratedValidationTest {
    private fun input(default: String) = YamlIO.fromAny(YamlIO.parseToRaw("""
        schemas:
          - id: design
            match: design.yaml
            root:
              type: object
              fields:
                - {key: id, type: id, required: true}
                - {key: version, type: int, required: true}
              constraints:
                - {id: nonnegative, check: [{gte: [{path: version}, 0]}], level: error, message: 版は零以上です}
        documents:
          design.yaml: {id: REC-1, version: $default}
    """.trimIndent()))

    @Test fun `組み立てた文書の構造と成立条件を検査して値を変えず返す`() {
        val data = input("0")
        val before = YamlIO.dumpPlain(data)
        val result = SchemaModels.validate(data)
        assertEquals(mapOf("design.yaml" to mapOf("id" to "REC-1", "version" to 0)), YamlIO.toAny(result))
        assertEquals(before, YamlIO.dumpPlain(data))
        assertFailsWith<IllegalArgumentException> { SchemaModels.validate(input("-1")) }
        assertFailsWith<IllegalArgumentException> { SchemaModels.validate(input("abc")) }
    }

    @Test fun `渡していない参照先とスキーマの無い文書と予約領域は拒否する`() {
        val definition = """
            schemas:
              - id: design
                match: '*.yaml'
                root:
                  type: object
                  fields: [{key: id, type: id}, {key: target, type: ref}]
            documents:
              a.yaml: {id: A, target: B}
        """.trimIndent()
        fun validate(text: String) = SchemaModels.validate(YamlIO.fromAny(YamlIO.parseToRaw(text)))
        assertTrue(assertFailsWith<IllegalArgumentException> { validate(definition) }.message!!.contains("B"))
        validate("$definition\n  b.yaml: {id: B}\n")
        assertFailsWith<IllegalArgumentException> { validate(definition.replace("a.yaml:", ".yde/a.yaml:")) }
        assertFailsWith<IllegalArgumentException> { validate(definition.replace("'*.yaml'", "'other.yaml'")) }
    }
}
