package dev.toros.yde.workspace

import dev.toros.yde.semantic.SemanticAutoValues
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.yaml.patch.Patcher
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YDocument
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.YamlIO
import java.time.LocalDateTime

data class DocumentChangeResult(val file: String, val applied: Boolean, val message: String?, val issues: List<CheckIssue>)

data class ChangeSetApplication(
    val ok: Boolean,
    val dryRun: Boolean,
    val results: List<DocumentChangeResult>,
    val persistence: Map<DocumentId, ChangePersistence> = emptyMap(),
) {
    val errorIssueCount: Int get() = results.sumOf { r -> r.issues.count { it.severity == Severity.ERROR } }
}

/** 計画・検証・保存の単一経路。既存の適用は編集用セッションへ、外部確定は独立したセッションへ書く。 */
object ChangeSetApplier {
    fun prepare(base: WorkspaceSession, changeSet: ChangeSet, now: LocalDateTime = LocalDateTime.now(), includeAffected: Boolean = true): PreparedChangeSet {
        val changes = mutableListOf<PreparedDocumentChange>()
        val failures = linkedMapOf<String, String>()
        val seen = mutableSetOf<DocumentId>()
        changeSet.changes.forEach { change ->
            runCatching {
                val id = DocumentId(normalize(change.file))
                require(seen.add(id)) { "変更対象が重複しています: ${id.value}" }
                val before = base.storedText(id)
                val root = if (change.create) {
                    require(before == null) { "競合: 既に同名のファイルが存在します: ${id.value}" }
                    (change.content ?: error("createにはcontentが必要です")).deepCopy()
                } else {
                    require(before != null) { "ファイルが存在しません: ${id.value}" }
                    require(change.baseHash == null || ChangeSets.contentHash(before) == change.baseHash) { "競合: ディスク内容が変更の前提（base）から変わっています" }
                    YamlIO.parse(before).also { tree -> change.ops.forEach { Patcher.apply(tree, ChangeSets.copyOp(it)) } }
                }
                val doc = YDocument(id, listOf(root))
                val sid = if (base.layout.isReserved(id.value) && base.layout.zoneOf(id.value) == null) null
                    else WorkspaceReader.assignSchemaId(id.value, doc, base.schemas, base.layout)
                base.schemas.firstOrNull { it.id == sid }?.tree?.let { tree ->
                    SemanticAutoValues.stamp(tree, before?.let(YamlIO::parse), root, now)
                }
                changes += PreparedDocumentChange(id, before, YamlIO.dump(root))
            }.onFailure { failures[change.file] = it.message ?: "計画に失敗しました" }
        }
        val overlays = changes.associate { it.id to YDocument(it.id, listOf(YamlIO.parse(it.after))) }
        return PreparedChangeSet(changes.toList(), ChangeSetValidation.validate(base, overlays, includeAffected), failures.toMap())
    }

    /** 既存のデスクトップ・CLI の互換窓口。検証の対象も従来どおり変更文書。 */
    fun apply(base: WorkspaceSession, changeSet: ChangeSet, dryRun: Boolean = false, requireValid: Boolean = false,
              target: WorkspaceSession = base, now: LocalDateTime = LocalDateTime.now()): ChangeSetApplication =
        applyPrepared(target, prepare(base, changeSet, now, includeAffected = false), dryRun, requireValid)

    /** 自動値を再計算せず、確認した候補を保存する。部分保存でも文書ごとの実結果を返す。 */
    fun applyPrepared(target: WorkspaceSession, prepared: PreparedChangeSet, dryRun: Boolean = false, requireValid: Boolean = true): ChangeSetApplication {
        val failures = prepared.failures.toMutableMap()
        val persistence = linkedMapOf<DocumentId, ChangePersistence>()
        val eligible = failures.isEmpty() && (!requireValid || prepared.valid)
        if (!dryRun && eligible && prepared.documents.isNotEmpty()) {
            prepared.documents.forEach { change ->
                runCatching { require(target.storedText(change.id) == change.before && target.expectedStoreText(change.id) == change.before) { "競合: 確認後にディスク内容が変わりました" } }
                    .onFailure { failures[change.id.value] = it.message ?: "競合の確認に失敗しました" }
            }
            if (failures.isEmpty()) {
                val operations = prepared.documents.map { c ->
                    val doc = YDocument(c.id, listOf(YamlIO.parse(c.after)))
                    if (c.before == null) WorkspaceOperation.CreateDocument(c.id, doc) else WorkspaceOperation.ReplaceDocument(c.id, doc)
                }
                val applied = target.apply(WorkspaceTransaction(target.version, ChangeIntent("変更セットの適用（${operations.size} ファイル）"), operations))
                if (!applied.ok) prepared.documents.forEach { failures[it.id.value] = applied.message ?: "適用できません" }
                else {
                    val saved = target.save(prepared.documents.map { it.id }.toSet())
                    saved.failures.forEach { (id, message) -> failures[id.value] = message }
                    prepared.documents.forEach { c ->
                        persistence[c.id] = runCatching {
                            val actual = target.storedText(c.id)
                            when {
                                c.id in saved.failures -> if (actual == c.before) ChangePersistence.NOT_WRITTEN else ChangePersistence.UNKNOWN
                                actual != c.after -> ChangePersistence.UNKNOWN
                                c.before == c.after -> ChangePersistence.UNCHANGED
                                else -> ChangePersistence.WRITTEN
                            }
                        }.getOrDefault(ChangePersistence.UNKNOWN)
                    }
                }
            }
        }
        prepared.documents.forEach { persistence.putIfAbsent(it.id, ChangePersistence.NOT_WRITTEN) }
        val results = linkedMapOf<String, DocumentChangeResult>()
        (prepared.documents.map { it.id } + prepared.issues.keys).distinct().forEach { id ->
            val state = persistence[id]
            results[id.value] = DocumentChangeResult(id.value,
                !dryRun && state in setOf(ChangePersistence.WRITTEN, ChangePersistence.UNCHANGED),
                failures[id.value] ?: if (state == ChangePersistence.UNKNOWN) "保存結果を確認できません" else null,
                prepared.issues[id].orEmpty())
        }
        failures.forEach { (file, message) -> results[file] = results[file]?.copy(message = message)
            ?: DocumentChangeResult(file, false, message, emptyList()) }
        return ChangeSetApplication(eligible && failures.isEmpty() && (dryRun || persistence.values.all {
            it == ChangePersistence.WRITTEN || it == ChangePersistence.UNCHANGED
        }), dryRun, results.values.toList(), persistence)
    }

    private fun normalize(rel: String): String {
        val segments = ArrayList<String>()
        val text = rel.replace('\\', '/')
        require(!text.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(text)) { "ワークスペース外のパスは扱えません: $rel" }
        for (segment in text.split('/')) when (segment) {
            "", "." -> Unit
            ".." -> require(segments.isNotEmpty()) { "ワークスペース外のパスは扱えません: $rel" }.also { segments.removeAt(segments.size - 1) }
            else -> segments += segment
        }
        require(segments.isNotEmpty()) { "ワークスペース外のパスは扱えません: $rel" }
        return segments.joinToString("/")
    }
}
