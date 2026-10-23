package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class PathMove(val from: String, val to: String)
data class PathEdit(val document: DocumentId, val path: dev.toros.yde.yaml.model.YPath, val before: String, val after: String, val permission: Boolean = false)
data class FileTextEdit(val document: DocumentId, val before: String, val after: String, val destination: DocumentId = document)

/** YAML の変更計画と、一般ファイル・フォルダーの物理移動を束ねる。履歴はセッションの一件。 */
data class FileArrangementPlan(
    val root: Path,
    val transaction: WorkspaceTransaction,
    val moves: List<PathMove>,
    val edits: List<PathEdit>,
    val warnings: List<String>,
    val signature: Map<String, String>,
    val createDirectory: String? = null,
    val texts: List<FileTextEdit> = emptyList(),
) {
    val documentIds: Set<DocumentId> get() = transaction.operations.flatMap { op -> when (op) {
        is WorkspaceOperation.EditDocument -> listOf(op.documentId)
        is WorkspaceOperation.CreateDocument -> listOf(op.documentId)
        is WorkspaceOperation.DeleteDocument -> listOf(op.documentId)
        is WorkspaceOperation.ReplaceDocument -> listOf(op.documentId)
        is WorkspaceOperation.MoveDocument -> listOf(op.from, op.to)
    } }.toSet()
}

/**
 * 物理操作の前に競合を検査。YAML の書込は呼出元の session.save に委譲する。
 * 処理失敗時は移動を逆に辿り、書込前の文面を復元する。電源断を跨ぐ原子性は保証しない。
 */
internal class FileArrangementJournal(val plan: FileArrangementPlan) {
    private var expected = plan.signature
    private fun path(rel: String) = WorkspacePaths.resolve(plan.root, rel, allowLinks = false)
    private fun locations() = (plan.moves.flatMap { listOf(it.from, it.to) } +
        plan.documentIds.map { it.value } + plan.texts.flatMap { listOf(it.document.value, it.destination.value) } + listOfNotNull(plan.createDirectory)).distinct()

    fun run(reverse: Boolean, save: () -> Unit) {
        require(signature(plan.root, locations()) == expected) { "確認後にファイルが変わりました。再確認してください（上書きしません）" }
        val backups = Files.createTempDirectory("yde-file-transaction-")
        val backupIds = (plan.documentIds + plan.texts.flatMap { listOf(it.document, it.destination) }).toList()
        val originals = backupIds.associate { id ->
            val source = path(id.value)
            id.value to if (Files.isRegularFile(source)) {
                val copy = backups.resolve("${backupIds.indexOf(id)}")
                Files.copy(source, copy); copy
            } else null
        }
        val moved = mutableListOf<Pair<Path, Path>>()
        val created = mutableListOf<Path>()
        var removedDirectory: Path? = null
        var recovered = true
        try {
            plan.createDirectory?.let { rel ->
                val dir = path(rel)
                if (reverse) { Files.delete(dir); removedDirectory = dir }
                else { Files.createDirectory(dir); created.add(dir) }
            }
            val moves = if (reverse) plan.moves.asReversed().map { PathMove(it.to, it.from) } else plan.moves
            for (move in moves) {
                val from = path(move.from); val to = path(move.to)
                require(Files.exists(from, NOFOLLOW_LINKS)) { "移動元がありません: ${move.from}" }
                require(!Files.exists(to, NOFOLLOW_LINKS)) { "移動先が既に存在します: ${move.to}" }
                val parents = mutableListOf<Path>()
                var parent = to.parent
                while (!Files.exists(parent)) { parents.add(parent); parent = parent.parent }
                parents.asReversed().forEach { Files.createDirectory(it); created.add(it) }
                Files.move(from, to); moved.add(from to to)
            }
            plan.texts.forEach { text -> FileSystemDocumentStore(plan.root).write(if (reverse) text.document else text.destination, if (reverse) text.before else text.after) }
            save()
            expected = signature(plan.root, locations())
        } catch (failure: Exception) {
            try {
                moved.asReversed().forEach { (from, to) -> Files.move(to, from) }
                originals.forEach { (rel, copy) ->
                    val target = path(rel)
                    if (copy == null) Files.deleteIfExists(target)
                    else { Files.createDirectories(target.parent); Files.copy(copy, target, REPLACE_EXISTING) }
                }
                removedDirectory?.let { Files.createDirectory(it) }
                created.asReversed().forEach { Files.deleteIfExists(it) }
            } catch (rollback: Exception) {
                recovered = false
                throw IllegalStateException("${failure.message}。復元にも失敗しました: ${rollback.message}。文面の控え: $backups", failure)
            }
            throw failure
        } finally {
            if (recovered) {
                Files.newDirectoryStream(backups).use { it.forEach(Files::delete) }
                Files.delete(backups)
            }
        }
    }

    companion object {
        /** 内容ハッシュにより、更新時刻が同じ外部編集も Undo の上書きから守る。 */
        fun signature(root: Path, locations: Collection<String>): Map<String, String> {
            val result = sortedMapOf<String, String>()
            val realRoot = root.toRealPath()
            for (rel in locations.distinct()) {
                val p = WorkspacePaths.resolve(root, rel, allowLinks = false)
                if (!Files.exists(p, NOFOLLOW_LINKS)) { result[rel] = "absent"; continue }
                Files.walk(p).use { paths -> paths.forEach { entry ->
                    require(!Files.isSymbolicLink(entry)) { "リンクを含む場所は移動できません: $entry" }
                    val key = root.relativize(entry).toString().replace('\\', '/')
                    require(entry.toRealPath() == realRoot.resolve(root.relativize(entry))) { "リンクを含む場所は操作できません: $key" }
                    result[key] = if (Files.isDirectory(entry, NOFOLLOW_LINKS)) "directory" else {
                        val digest = MessageDigest.getInstance("SHA-256")
                        Files.newInputStream(entry).use { input ->
                            val bytes = ByteArray(65536)
                            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                } }
            }
            return result
        }
    }
}
