package dev.toros.yde.app.core

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceCheckRunner
import dev.toros.yde.workspace.WorkspaceReader
import dev.toros.yde.workspace.WorkspaceSession
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * samples/todos-sysdev（todos を業務モデルにしたシステム開発の設計書体系）: 設計書の生成物が業務モデルの写しから作り直した
 * 内容と同じ（最新）で、ワークスペース全体の検証にエラーが無い（設計の対応の検査を含む）。業務モデルや生成の定義を直したら、
 * `./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直す。
 */
class TodosSysdevTest {
    private val root = Path.of("samples/todos-sysdev")

    @Test
    fun `生成物は最新`() {
        val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(root)))
        assertEquals(emptyList(), plan.errors)
        assertTrue(plan.upToDate, "生成物が最新ではありません（作る ${plan.created} ・作り直す ${plan.updated} ・消す ${plan.deleted}）。generate samples/todos-sysdev --apply で作り直します")
    }

    @Test
    fun `検証にエラーが無い`() {
        val ws = WorkspaceReader.load(FileSystemDocumentStore(root), builtins = dev.toros.yde.app.schema.BuiltinSchemas.kernelSchemas(dev.toros.yde.workspace.layout.WorkspaceLayout.of(root)))
        assertEquals(emptyList(), ws.schemaErrors)
        val errors = WorkspaceCheckRunner.validateAll(ws).flatMap { (doc, v) -> v.issues.filter { it.severity.name == "ERROR" }.map { "${doc.value}: ${it.message}" } }
        assertEquals(emptyList(), errors)
    }
}
