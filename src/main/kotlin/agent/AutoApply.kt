package agent

import core.ChangeSet
import core.ChangeSetResult
import core.ChangeSets
import core.Workspace
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * 巻き戻し可能な適用（R3-04）。
 * 適用前の原文を実行記録（_runs）へバックアップしてから変更セットを適用する。
 * 無人実行の「Undo保証」の実体: revert() で適用前の状態へ機械的に戻せる。
 */
object AutoApply {

    data class RevertResult(val ok: Boolean, val messages: List<String>)

    /** バックアップを実行記録に残してから適用する。適用失敗時は何も記録しない（原本も変わらない）。 */
    fun applyWithBackup(ws: Workspace, changeSet: ChangeSet, runFile: Path?, requireValid: Boolean): ChangeSetResult {
        val before = changeSet.changes.map { change ->
            val file = ws.resolve(change.file)
            val existed = Files.isRegularFile(file)
            Triple(change.file, existed, if (existed) Files.readString(file) else null)
        }
        val result = ws.apply(changeSet, requireValid = requireValid)
        if (result.ok && runFile != null) {
            val entries = before.map { (rel, existed, text) ->
                val afterText = runCatching { Files.readString(ws.resolve(rel)) }.getOrDefault("")
                linkedMapOf<String, Any?>(
                    "file" to rel,
                    "existed" to existed,
                    "before" to text,
                    "afterHash" to ChangeSets.contentHash(afterText),
                )
            }
            RunLog.appendAutoApply(runFile, entries)
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
