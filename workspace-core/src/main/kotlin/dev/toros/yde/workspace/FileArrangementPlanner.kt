package dev.toros.yde.workspace

import dev.toros.yde.semantic.PathRewrite
import dev.toros.yde.semantic.RefactoringRequest
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.edit.EditBatch
import dev.toros.yde.yaml.edit.YamlEdit
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.*
import java.nio.file.Files
import java.nio.file.Path

/** 配置変更の計画。ファイル操作の入口は UI・CLI ともこの計画を使える。 */
object FileArrangementPlanner {
    fun plan(session: WorkspaceSession, root: Path, moves: List<PathMove>,
             updateDefinitions: Boolean = true, updatePermissions: Boolean = false,
             deleting: Boolean = false, createDirectory: String? = null): FileArrangementPlan {
        require(moves.isNotEmpty() || createDirectory != null) { "対象を選択してください" }
        val version = session.version
        val warnings = mutableListOf<String>()
        val mapping = moves.map { move ->
            require(move.from != move.to) { "同じ場所へは移動できません" }
            val from = WorkspacePaths.resolve(root, move.from, allowLinks = false)
            val to = WorkspacePaths.resolve(root, move.to, allowLinks = false)
            require(Files.exists(from)) { "移動元がありません: ${move.from}" }
            require(!Files.exists(to)) { "移動先が既に存在します: ${move.to}" }
            require(!to.startsWith(from)) { "自分の配下へは移動できません" }
            if (!deleting && session.layout.zoneOf(move.from) != session.layout.zoneOf(move.to))
                warnings += "${move.from} → ${move.to}: 定義領域の種類が変わります。配置で決まる役割は自動移設できません"
            if (!deleting && session.layout.inLedger(move.from) != session.layout.inLedger(move.to))
                warnings += "${move.from} → ${move.to}: 台帳対象の範囲が変わります（範囲の追随を採用した場合はその修正後の範囲に従います）"
            require(move.from != session.layout.configDir && move.from != "${session.layout.configDir}/workspace.yaml") {
                "ワークスペースの構成自体は移動できません"
            }
            require(move.from != ".yde-trash" && !move.from.startsWith(".yde-trash/") && !move.from.startsWith(".yde-history")) { "復元用の領域は操作できません" }
            PathRewrite.Move(move.from, move.to, Files.isDirectory(from))
        }
        require(moves.map { it.to }.distinct().size == moves.size) { "移動先が重複しています" }
        for (a in mapping) for (b in mapping) if (a != b) {
            require(!b.from.startsWith(a.from + "/") && !b.to.startsWith(a.to + "/")) { "親子の場所を同時には移動できません" }
            require(!b.to.startsWith(a.from + "/") && b.to != a.from) { "移動元と移動先が重複しています" }
        }
        val documents = session.snapshot.documents
        val knownFiles = (documents.keys.map { it.value } + if (deleting) emptyList() else movedFiles(root, moves)).distinct()
        val moved = documents.keys.filter { PathRewrite.relocated(it.value, mapping) != it.value }
        val operations = mutableListOf<WorkspaceOperation>()
        val edits = linkedMapOf<Pair<DocumentId, YPath>, PathEdit>()
        val texts = mutableListOf<FileTextEdit>()
        fun add(document: DocumentId, edit: PathRewrite.Edit) {
            val next = PathEdit(document, edit.path, edit.before, edit.after, edit.permission)
            val key = document to edit.path
            require(edits[key]?.after?.let { it == next.after } != false) { "同じ設定に複数の修正候補があります: ${document.value}" }
            edits[key] = next
        }
        if (deleting) {
            moved.forEach { operations += WorkspaceOperation.DeleteDocument(it) }
            warnings += "削除したファイルは .yde-trash に退避します。Undo で復元できます（参照は削除しません）"
        } else {
            moved.forEach { id ->
                val destination = DocumentId(PathRewrite.relocated(id.value, mapping))
                operations += WorkspaceOperation.MoveDocument(id, destination)
                // 既存の文書参照の意味解決を使い、複数移動でも座標を最後に一度だけ写す。
                val plan = dev.toros.yde.semantic.SemanticRefactor.plan(session.derived.semantic, emptyMap(),
                    RefactoringRequest.MoveDocument(id, destination)) { document -> documents[document]?.document?.documents?.firstOrNull() }
                warnings += plan.warnings
                plan.changes.forEach { change ->
                    val source = if (change.document == destination) id else change.document
                    change.operations.filterIsInstance<YamlOperation.SetScalar>().forEach { op ->
                        val node = documents[source]?.document?.documents?.firstOrNull()
                        val before = node?.let { scalarText(getAt(it, op.path)) } ?: return@forEach
                        add(source, PathRewrite.Edit(op.path, before, op.value.toString()))
                    }
                }
            }
            for ((id, doc) in documents) {
                val node = doc.document.documents.firstOrNull() ?: continue
                val schema = session.schemas.firstOrNull { it.id == doc.schemaId }
                schema?.let { PathRewrite.files(it.tree, node, id, mapping).forEach { edit -> add(id, edit) } }
                if (session.layout.isReserved(id.value)) {
                    val proposed = PathRewrite.definition(node, mapping, knownFiles)
                    warnings += proposed.warnings.map { "${id.value}: $it" }
                    for (edit in proposed.edits) {
                        if (updateDefinitions && (!edit.permission || updatePermissions)) add(id, edit)
                        else warnings += "要確認 ${id.value}:${edit.path.pretty()} ${edit.before} → ${edit.after}"
                    }
                }
            }
            session.layout.configDir?.let { config ->
                val id = DocumentId("$config/workspace.yaml")
                val before = session.storedText(id)
                if (before != null) {
                    val document = DefaultYamlCodec.parse(id, before)
                    val node = document.documents.firstOrNull()
                    if (node != null) {
                        val proposed = PathRewrite.definition(node, mapping, knownFiles)
                        val selected = proposed.edits.filter { updateDefinitions && (!it.permission || updatePermissions) }
                        proposed.edits.filterNot { it in selected }.forEach { warnings += "要確認 ${id.value}:${it.path.pretty()} ${it.before} → ${it.after}" }
                        if (selected.isNotEmpty()) {
                            YamlEdit.applyBatch(node, EditBatch(selected.map { YamlOperation.SetScalar(it.path, it.after) }))
                            texts += FileTextEdit(id, before, DefaultYamlCodec.dump(document))
                            selected.forEach { add(id, it) }
                        }
                    }
                }
            }
            // Markdown の通常のインラインリンク。参照形式・HTML・コード内の言及は警告に留める。
            markdownFiles(root).forEach { file ->
                    val id = DocumentId(root.relativize(file).toString().replace('\\', '/'))
                    if (Files.size(file) > 2 * 1024 * 1024) { warnings += "${id.value}: 大きな Markdown のリンクは未検査です"; return@forEach }
                    val before = runCatching { Files.readString(file) }.getOrNull() ?: return@forEach
                    val destination = DocumentId(PathRewrite.relocated(id.value, mapping))
                    val link = Regex("(!?\\[[^\\]\\r\\n]*]\\()([^\\s)]+)([^)]*\\))")
                    var fenced = false
                    val normalized = before.lines().joinToString("\n") { line ->
                        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fenced = !fenced; line }
                        else if (fenced || '`' in line) line
                        else link.replace(line) { match ->
                            val value = match.groupValues[2]
                            val raw = value.substringBefore('#').substringBefore('?')
                            if (raw.isBlank() || ':' in raw || raw.startsWith('/')) match.value
                            else {
                                val target = dev.toros.yde.semantic.schema.FilePath.collapse(id.value.substringBeforeLast('/', "") + "/" + raw)
                                if (target == null) match.value else {
                                    val movedTarget = PathRewrite.relocated(target, mapping)
                                    if (movedTarget == target && destination == id) match.value else
                                        match.groupValues[1] + PathRewrite.relative(destination.value, movedTarget) + value.removePrefix(raw) + match.groupValues[3]
                                }
                            }
                        }
                    }
                    val after = if ("\r\n" in before) normalized.replace("\n", "\r\n") else normalized
                    if (after != before) texts += FileTextEdit(id, before, after, destination)
                    if (mapping.any { before.contains(it.from) }) warnings += "${id.value}: Markdown の参照形式・HTML・コード内のパスは手動確認してください"
                }
        }
        // 移動対象外にまで新しく適用されるパターンを知らせる。将来の対象範囲も差分で確認できる。
        edits.values.filter { it.path.lastOrNull() in setOf("match", "fileGlob", "allowedFiles") }.forEach { edit ->
            val before = runCatching { globToRegex(edit.before) }.getOrNull()
            val after = runCatching { globToRegex(edit.after) }.getOrNull()
            val added = knownFiles.filter { PathRewrite.relocated(it, mapping) == it && before?.matches(it) != true && after?.matches(it) == true }
            if (added.isNotEmpty()) warnings += "${edit.document.value}: 移動対象外も新しく一致します: ${added.take(8).joinToString()}"
        }
        edits.values.filter { it.document in documents }.groupBy { it.document }.forEach { (id, changes) ->
            operations += WorkspaceOperation.EditDocument(DocumentId(PathRewrite.relocated(id.value, mapping)),
                changes.map { YamlOperation.SetScalar(it.path, it.after) })
        }
        val touched = moved.toSet() + edits.values.map { it.document }
        require(touched.filter { it in documents }.none(session::isDirty)) { "対象または参照元に未保存の変更があります。先に保存してください" }
        require(session.version == version) { "確認中に文書が変わりました。再確認してください" }
        val transaction = WorkspaceTransaction(version,
            ChangeIntent(if (createDirectory != null) "フォルダー作成: $createDirectory" else if (deleting) "削除: ${moves.joinToString { it.from }}" else "移動: ${moves.joinToString { "${it.from} → ${it.to}" }}"), operations)
        val preliminary = FileArrangementPlan(root, transaction, moves, edits.values.toList(), warnings.distinct(), emptyMap(), createDirectory, texts)
        val locations = moves.flatMap { listOf(it.from, it.to) } + preliminary.documentIds.map { it.value } + texts.flatMap { listOf(it.document.value, it.destination.value) } + listOfNotNull(createDirectory)
        val signature = FileArrangementJournal.signature(root, locations)
        require(session.externalChanges(touched.filter { it in documents }).isEmpty() &&
            texts.all { session.storedText(it.document) == it.before }) {
            "対象または参照元が外部で変更されています。再読込してから再確認してください"
        }
        return preliminary.copy(signature = signature)
    }

    private fun markdownFiles(root: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir != root && (dir.fileName.toString().startsWith(".") ||
                    runCatching { WorkspacePaths.resolve(root, root.relativize(dir).toString(), allowLinks = false) }.isFailure))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile && !file.fileName.toString().startsWith(".") && file.fileName.toString().endsWith(".md", true) &&
                    runCatching { WorkspacePaths.resolve(root, root.relativize(file).toString(), allowLinks = false) }.isSuccess) result.add(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return result
    }

    /** 一般ファイルの部分移動も glob に追随させる。本文は読まない。 */
    private fun movedFiles(root: Path, moves: List<PathMove>): List<String> {
        val result = mutableListOf<String>()
        moves.forEach { move -> Files.walkFileTree(WorkspacePaths.resolve(root, move.from, allowLinks = false),
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    WorkspacePaths.resolve(root, root.relativize(dir).toString(), allowLinks = false)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) result.add(root.relativize(file).toString().replace('\\', '/'))
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            }) }
        return result
    }
}
