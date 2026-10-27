package dev.toros.yde.agent

import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.app.core.ChangeSetResult
import dev.toros.yde.app.core.FileChangeResult
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 巻き戻し可能な適用（R3-04）。
 * 適用前の原文を実行記録（_runs）へバックアップしてから変更セットを適用する。
 * 無人実行の「Undo保証」の実体: revert() で適用前の状態へ機械的に戻せる。
 */
object AutoApply {

    data class RevertResult(val ok: Boolean, val messages: List<String>)

    /** バックアップの永続化に失敗したら適用しない。途中失敗の記録も復旧用に残す。 */
    fun applyWithBackup(ws: Workspace, changeSet: ChangeSet, runFile: Path?, requireValid: Boolean): ChangeSetResult {
        fun failure(message: String) = ChangeSetResult(false, false, changeSet.changes.map {
            FileChangeResult(it.file, false, message, emptyList())
        })
        if (runFile == null) return failure("バックアップ先の実行記録がないため、自動適用を中止しました")
        val before = try { changeSet.changes.map { change ->
            val file = dev.toros.yde.workspace.WorkspacePaths.resolve(ws.root, change.file, allowLinks = false)
            require(file.toAbsolutePath().normalize() != runFile.toAbsolutePath().normalize()) { "実行記録自体は変更できません" }
            val existed = Files.isRegularFile(file)
            linkedMapOf<String, Any?>("file" to change.file, "existed" to existed,
                "before" to if (existed) Files.readString(file) else null)
        }.also { RunLog.appendAutoApply(runFile, it, "prepared") }
        } catch (e: Exception) {
            return failure("バックアップを保存できないため、自動適用を中止しました: ${e.message}")
        }
        val result = try { ws.apply(changeSet, requireValid = requireValid) } catch (e: Exception) {
            return failure("適用中に失敗しました。バックアップを確認してください: ${e.message}")
        }
        if (result.ok) {
            try {
                val entries = before.map { entry ->
                    val afterText = Files.readString(ws.resolve(entry.getValue("file") as String))
                    entry + ("afterHash" to ChangeSets.contentHash(afterText))
                }
                RunLog.appendAutoApply(runFile, entries, "applied")
            } catch (e: Exception) {
                return result.copy(ok = false, results = result.results.map {
                    it.copy(message = "原本は適用済みですが完了記録を保存できません。バックアップを確認してください: ${e.message}")
                })
            }
        }
        return result
    }

    /**
     * 実行記録のバックアップから適用前の状態へ戻す。
     * 適用後にファイルが更に変更されている場合（afterHash 不一致）は競合として拒否する（force で上書き）。
     */
    fun revert(ws: Workspace, runFile: Path, force: Boolean = false): RevertResult {
        val raw = runCatching { YamlIO.parseToRaw(Files.readString(runFile)) as? Map<*, *> }.getOrNull()
            ?: return RevertResult(false, listOf("実行記録を読めません: $runFile"))
        val backup = ((raw["autoApply"] as? Map<*, *>)?.get("backup") as? List<*>)
            ?: return RevertResult(false, listOf("この実行記録には自動適用のバックアップがありません"))
        if ((raw["autoApply"] as? Map<*, *>)?.get("state") == "prepared" && !force) {
            return RevertResult(false, listOf("適用完了を確認できないバックアップです。原本と記録を確認し、復旧する場合は --force を指定してください"))
        }

        data class Entry(val rel: String, val existed: Boolean, val before: String?, val afterHash: String?)
        val entries = backup.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Entry(
                rel = m["file"]?.toString() ?: return@mapNotNull null,
                existed = (m["existed"] as? Boolean) ?: true,
                before = m["before"]?.toString(),
                afterHash = m["afterHash"]?.toString(),
            )
        }
        if (entries.isEmpty()) return RevertResult(false, listOf("バックアップが空です"))

        // 競合チェック: 適用後の状態から更に変わっていないか
        val conflicts = entries.mapNotNull { e ->
            val file = ws.resolve(e.rel)
            val current = if (Files.isRegularFile(file)) Files.readString(file) else null
            when {
                current == null -> "適用後のファイルが存在しません: ${e.rel}"
                e.afterHash != null && ChangeSets.contentHash(current) != e.afterHash ->
                    "適用後に変更されています: ${e.rel}"
                else -> null
            }
        }
        if (conflicts.isNotEmpty() && !force) {
            return RevertResult(false, conflicts + "（--force で強制的に戻せます）")
        }

        val messages = mutableListOf<String>()
        entries.forEach { e ->
            val file = ws.resolve(e.rel)
            if (e.existed && e.before != null) {
                Files.createDirectories(file.parent)
                Files.writeString(file, e.before)
                messages += "復元しました: ${e.rel}"
            } else {
                Files.deleteIfExists(file)
                messages += "削除しました（適用で新規作成されたファイル）: ${e.rel}"
            }
        }
        ws.refresh()
        RunLog.appendReverted(runFile)
        return RevertResult(true, messages)
    }
}
