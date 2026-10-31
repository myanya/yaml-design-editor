package dev.toros.yde.app.tools

import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity

enum class SubmissionPolicy { ERROR_FREE, NO_NEW_ERRORS, NONE }

/** 提出の検証ゲート。定義ローダの追加検査は WorkingCopy の差し込み口を通る。 */
object SubmissionChecks {
    fun rejection(copy: WorkingCopy, policy: SubmissionPolicy): String? = when (policy) {
        SubmissionPolicy.NONE -> null
        SubmissionPolicy.ERROR_FREE -> rejection(copy.validateAll())
        SubmissionPolicy.NO_NEW_ERRORS -> rejection(copy.newErrors(), newOnly = true)
    }

    fun rejection(issues: Map<String, List<CheckIssue>>, newOnly: Boolean = false): String? {
        val errors = issues.flatMap { (file, items) -> items.filter { it.severity == Severity.ERROR }
            .map { "$file :: ${it.path.pretty()}: ${it.message}" } }
        if (errors.isEmpty()) return null
        val header = if (newOnly) "提出は拒否されました。この変更で新しく増えた検証エラーを解消してください" +
            "（${errors.size} 件。適用前からある既存エラーは提出を妨げません）:\n"
        else "提出は拒否されました。検証エラーを解消してください（${errors.size} 件）:\n"
        return header + errors.take(20).joinToString("\n") { "- $it" }
    }
}
