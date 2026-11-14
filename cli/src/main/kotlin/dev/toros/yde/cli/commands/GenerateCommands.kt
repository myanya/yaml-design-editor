package dev.toros.yde.cli.commands

import dev.toros.yde.cli.CliResult
import dev.toros.yde.cli.asData
import dev.toros.yde.cli.issueMap
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.workspace.BusinessModel
import dev.toros.yde.workspace.ChangeSetValidation
import dev.toros.yde.workspace.Generators
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.YamlIO

/**
 * 生成の命令: generate（業務モデルから文書を作る。生成の定義は `.yde/transforms/`）。計画と業務モデルの展開はカーネル
 * （workspace.Generation・BusinessModel）にあり、ここは引数を写し、結果を YAML へ写すだけ。
 * - `generate <ws> [<定義ID>…]` 計画だけ（作る・作り直す・消す・変わらない文書と、作る文書の検証）
 * - `--apply` 書く（作る文書に検証エラーがあれば書かない。`--allow-errors` で書く）
 * - `--check` 生成物が最新でなければ失敗（終了コード 1。台帳の検査・CI 用）
 * - `--model` 業務モデルを展開した形（生成の入力）を出す
 */
internal fun generateCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val session = ws.mirror
    if ("--model" in rest) return@run asData(YamlIO.toAny(BusinessModel.of(session)))
    val names = rest.drop(1).filter { !it.startsWith("--") }.toSet()
    val plan = Generators.plan(session, names)
    // 作る・作り直す文書を重ねて検証する（適用と同じ検証。生成物の書き方の誤りは、ここで分かる）
    val overlays = plan.outputs.filter { it.document in plan.created || it.document in plan.updated }
        .associate { it.document to DefaultYamlCodec.parse(it.document, it.text) }
    val issues = if (overlays.isEmpty()) emptyMap() else ChangeSetValidation.validate(session, overlays, includeAffected = true)
    val errorCount = issues.values.sumOf { list -> list.count { it.severity.name == "ERROR" } }
    if ("--check" in rest && !plan.upToDate) {
        error(
            "生成物が最新ではありません（作る ${plan.created.size}・作り直す ${plan.updated.size}・消す ${plan.deleted.size}・誤り ${plan.errors.size}）。" +
                "generate ${rest.first()} --apply で作り直します" + plan.errors.joinToString("") { "\n- $it" },
        )
    }
    val apply = "--apply" in rest
    var applied = false
    var message: String? = null
    if (apply) {
        when {
            plan.errors.isNotEmpty() -> message = "生成の定義に誤りがあるので書きません"
            errorCount > 0 && "--allow-errors" !in rest -> message = "作る文書に検証エラーがあるので書きません（--allow-errors で書きます）"
            plan.transaction.operations.isEmpty() -> message = "生成物は最新です"
            else -> {
                val result = session.apply(plan.transaction)
                if (!result.ok) message = result.message
                else {
                    val saved = session.save()
                    applied = saved.ok
                    if (!saved.ok) message = "保存に失敗しました: ${saved.failures}"
                }
            }
        }
    }
    asData(
        linkedMapOf<String, Any?>(
            "upToDate" to plan.upToDate,
            "created" to plan.created.map { it.value },
            "updated" to plan.updated.map { it.value },
            "deleted" to plan.deleted.map { it.value },
            "unchanged" to plan.unchanged.size,
            "errors" to plan.errors,
            "issues" to issues.filterValues { it.isNotEmpty() }.map { (doc, list) -> linkedMapOf("file" to doc.value, "issues" to list.map { issueMap(it) }) },
            "applied" to applied,
            "message" to message,
        ).filterValues { it != null },
    )
}
