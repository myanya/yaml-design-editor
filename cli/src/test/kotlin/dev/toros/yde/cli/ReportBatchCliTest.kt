package dev.toros.yde.cli

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ReportBatchCliTest {
    private val root = Files.createTempDirectory("batch-cli")
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `一括レポートのファイルを読み結果を返す`() {
        Files.createDirectories(root.resolve("_reports"))
        Files.writeString(root.resolve("_reports/item.yaml"), "id: item\nparams: [value]\nbuild: {value: ${'$'}value}\ntemplate: item.txt\noutput: 'out/{value}.txt'\n")
        Files.writeString(root.resolve("_reports/item.txt"), "値 {value}")
        val requests = root.resolve("requests.json")
        Files.writeString(requests, """{"outputs":[{"report":"item","params":{"value":"日本語"}},{"report":"item","params":{"value":2}}]}""")
        val result = runCli(arrayOf("report", root.toString(), "--batch", requests.toString(), "--format", "json"))
        assertTrue("\"outputs\"" in result && "out/日本語.txt" in result, result)
        assertEquals("値 日本語", Files.readString(root.resolve("out/日本語.txt")))
        assertEquals("値 2", Files.readString(root.resolve("out/2.txt")))
        for (option in listOf(listOf("--print"), listOf("--out", "x.txt"), listOf("--param", "value=x"))) {
            assertFails { runCli((listOf("report", root.toString(), "--batch", requests.toString()) + option).toTypedArray()) }
        }
        assertFails { runCli(arrayOf("report", root.toString(), "item", "--batch", requests.toString())) }
    }
}
