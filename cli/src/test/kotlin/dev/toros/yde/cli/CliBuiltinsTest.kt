package dev.toros.yde.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 組込みスキーマ注入（C-4・RC-07。意図的差分 D-14）: cli の validate でも `_schemas` に
 * メタスキーマが適用され、定義の不備が構造エラーになる（アプリと同じ検証面）。
 */
class CliBuiltinsTest {

    private val dir: Path = Files.createTempDirectory("cli-builtins")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `メタスキーマの注入で_schemasの定義不備が構造エラーになる`() {
        Files.createDirectories(dir.resolve("_schemas"))
        Files.writeString(
            dir.resolve("_schemas/bad.yaml"),
            "id: bad\nmatch: \"docs/**/*.yaml\"\nroot:\n  type: nosuch\n",
        )
        val lines = mutableListOf<String>()
        val exit = workspaceValidate(dir.toString(), emptyList(), lines::add)
        assertEquals(2, exit, "メタスキーマ違反は構造エラー（exit 2）になる:\n" + lines.joinToString("\n"))
        assertTrue(lines.any { it.contains("_schemas/bad.yaml") && it.contains("構造") })
    }

    @Test
    fun `定義に問題がなければ従来どおりエラーなしで通る`() {
        Files.createDirectories(dir.resolve("_schemas"))
        Files.writeString(
            dir.resolve("_schemas/ok.yaml"),
            "id: ok\nmatch: \"docs/**/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: id, type: id }\n",
        )
        Files.createDirectories(dir.resolve("docs"))
        Files.writeString(dir.resolve("docs/a.yaml"), "id: DOC-A\n")
        val exit = workspaceValidate(dir.toString(), emptyList()) { }
        assertEquals(0, exit)
    }
}
