package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * フローを含むリファクタリングの計画の期待値（golden）。元は workspace-core の RefactoringPlanGoldenTest の 1 本で、
 * flow を差し込み口を通る種類にした（ADR-0014）ので、FlowViewKind を渡したセッションでここに移した。期待値のファイルも同じ中身のまま移した。
 * 計画の文の組み立て（planText・opText）は RefactoringPlanGoldenTest と同じ形。
 */
class FlowRefactoringGoldenTest {

    private fun nodeText(node: YNode): String = YamlIO.dumpPlain(node).trimEnd().replace("\n", "⏎")

    private fun opText(op: YamlOperation): String = when (op) {
        is YamlOperation.SetScalar -> "SetScalar ${op.path.pretty()} = ${op.value}（${op.value?.let { it::class.simpleName } ?: "null"}）"
        is YamlOperation.Replace -> "Replace ${op.path.pretty()} = ${nodeText(op.value)}"
        is YamlOperation.Insert -> "Insert ${op.path.pretty()} = ${nodeText(op.value)}"
        is YamlOperation.Remove -> "Remove ${op.path.pretty()}"
        is YamlOperation.Move -> "Move ${op.from.pretty()} -> ${op.to.pretty()}"
        is YamlOperation.RenameKey -> "RenameKey ${op.path.pretty()} -> ${op.newName}"
        is YamlOperation.ReorderKeys -> "ReorderKeys ${op.path.pretty()} ${op.order}"
    }

    private fun planText(plan: RefactoringPlan): String = buildString {
        plan.moves.forEach { (from, to) -> appendLine("  move: ${from.value} -> ${to.value}") }
        plan.changes.forEach { change ->
            appendLine("  change: ${change.document.value}")
            change.operations.forEach { appendLine("    ${opText(it)}") }
        }
        plan.warnings.forEach { appendLine("  warning: $it") }
        plan.summary.forEach { appendLine("  summary: $it") }
    }

    @Test
    fun `フローの追随と投影の要確認箇所を含む計画が期待値と一致する`() {
        val requests = listOf(
            RefactoringRequest.RenameEntity("diagram", "renamed"),
            RefactoringRequest.RenameEntity("ACT-1", "ACT-9"),
            RefactoringRequest.RenameField("process", "activities.title", "name"),
            RefactoringRequest.RenameField("process", "activities", "steps"),
            RefactoringRequest.RenameRelation("next", "follows"),
            RefactoringRequest.RenameSchemaId("process", "procedure"),
            RefactoringRequest.MoveDocument(DocumentId("flows/a.yaml"), DocumentId("elsewhere/a.yaml")),
        )
        val actual = buildString {
            for (request in requests) {
                appendLine("### $request")
                append(planText(FlowRefactoringFixture.session(extraRule = true).planRefactoring(request)))
                appendLine()
            }
        }
        assertGolden("flow-refactoring-plans.golden.txt", actual.replace("\r\n", "\n"))
    }

    private fun assertGolden(resource: String, actual: String) {
        if (System.getProperty("updateGolden") == "true") {
            val target = Path.of("app-core/src/test/resources", resource)   // app-core のテストはリポジトリの根で動く
            Files.createDirectories(target.parent)
            Files.writeString(target, actual)
            return
        }
        val expected = javaClass.classLoader.getResourceAsStream(resource)
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
            ?: error("期待値がありません: src/test/resources/$resource（-DupdateGolden=true で生成してください）")
        assertEquals(expected, actual, "リファクタリング計画が期待値と異なります（意図した変更なら -DupdateGolden=true で更新）")
    }
}
