package dev.toros.yde.app.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 小さな入力で一括実行の契約を検査する。実アプリ全出力との一致は TodosApplicationTest が守る。 */
class ReportBatchTest {
    private val root = Files.createTempDirectory("report-batch")
    init {
        Files.createDirectories(root.resolve("_reports"))
        Files.writeString(root.resolve("_reports/greeting.yaml"), """
            id: greeting
            params: [name]
            build: { name: ${'$'}name }
            template: greeting.txt
            output: 'output/{name}.txt'
        """.trimIndent())
        Files.writeString(root.resolve("_reports/greeting.txt"), "こんにちは {name}")
    }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `複数の引数と出力先を単独実行と同じ内容で保存する`() {
        val requests = ReportBatch.parse("""{"outputs":[{"report":"greeting","params":{"name":"一"}},{"report":"greeting","params":{"name":"二"},"destination":"other.txt"}]}""")
        val workspace = Workspace.open(root)
        val results = ReportBatch.write(workspace, requests)
        val loaded = Reports.load(root).report("greeting")!!
        val views = Views.load(root, workspace.schemas)
        assertEquals(listOf("output/一.txt", "other.txt"), results.map { it.written })
        results.zip(requests).forEach { (result, request) ->
            assertEquals(Reports.run(loaded, request.params, views, workspace.index, workspace::treeOf, workspace::schemaFor).text, Files.readString(result.file))
        }
    }

    @Test fun `後続の不明なレポートや不足した引数では先行分も保存しない`() {
        for (bad in listOf(ReportBatch.Request("missing"), ReportBatch.Request("greeting"))) {
            assertFails { ReportBatch.write(Workspace.open(root), listOf(good(), bad)) }
            assertFalse(Files.exists(root.resolve("output")))
        }
    }

    @Test fun `出力の重複と範囲外と原本への保存を拒否し先行分も保存しない`() {
        for (destination in listOf("output/一.txt", "output/./一.txt", "output/一.txt/child.txt", "../outside.txt", "_reports/changed.txt", "source.yaml")) {
            assertFails(destination) { ReportBatch.write(Workspace.open(root), listOf(good(), good().copy(destination = destination))) }
            assertFalse(Files.exists(root.resolve("output")), destination)
        }
    }

    @Test fun `埋まらない穴と壊れた定義と出力先なしでは保存しない`() {
        val template = root.resolve("_reports/greeting.txt")
        Files.writeString(template, "{unknown}")
        assertFails { ReportBatch.write(Workspace.open(root), listOf(good())) }
        Files.writeString(template, "{#each unclosed}")
        assertFails { ReportBatch.write(Workspace.open(root), listOf(good())) }
        Files.writeString(template, "{name}")
        val definition = root.resolve("_reports/greeting.yaml")
        Files.writeString(definition, Files.readString(definition).substringBefore("output:"))
        assertFails { ReportBatch.write(Workspace.open(root), listOf(good())) }
        assertFalse(Files.exists(root.resolve("output")))
    }

    @Test fun `実行リストの値の型を維持し誤記は拒否する`() {
        val parsed = ReportBatch.parse("outputs: [{report: greeting, params: {name: false, count: 2, text: '002', values: [a, b]}}]")
        assertEquals(mapOf("name" to false, "count" to 2, "text" to "002", "values" to listOf("a", "b")), parsed.single().params)
        for (text in listOf("[]", "outputs: {}", "outputs: [x]", "outputs: [{report: ''}]", "outputs: [{report: greeting, params: []}]", "outputs: [{report: greeting, destination: 2}]", "outputs: [{report: greeting, unknown: x}]")) {
            assertFails(text) { ReportBatch.parse(text) }
        }
        assertTrue(ReportBatch.parse("outputs: []").isEmpty())
    }

    private fun good() = ReportBatch.Request("greeting", mapOf("name" to "一"))
}
