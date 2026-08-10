package core

import index.IdIndex
import schema.RefTarget
import schema.globToRegex
import yaml.YamlIO
import java.nio.file.Files

/**
 * ファイル移動・改名の一級化（R21-03。ARCHITECTURE §4.31）。
 * 変更セットにファイル移動の語彙は増やさない——「パスIDの文書参照を書き換える変更セット」＋
 * 「物理移動」の合成として実行し、プレビューに影響（書き換え対象・スキーマ match の変化・
 * ビュー定義の file / fileGlob が外れる警告）を出す。入口は CLI `move` と W-03（ツリーの改名）。
 */
object DocMove {

    data class Plan(
        val oldRel: String,
        val newRel: String,
        /** パスID（`_document.id` 未宣言）の文書参照の書き換え。参照元ファイル単位・base ハッシュ付き。 */
        val changeSet: ChangeSet,
        /** 適用を止めない警告（スキーマ match の変化・ビュー定義の対象外れ）。 */
        val warnings: List<String>,
        /** 補足（`_document.id` 宣言済み＝参照は安定で書き換え不要、等）。 */
        val notes: List<String>,
    )

    /** 移動の影響を計画する（書き込みは行わない）。 */
    fun plan(ws: Workspace, oldRel: String, newRel: String): Plan {
        val oldFile = ws.resolve(oldRel)
        require(Files.isRegularFile(oldFile)) { "ファイルが存在しません: $oldRel" }
        val newFile = ws.resolve(newRel)
        require(!Files.exists(newFile)) { "移動先に同名のファイルが存在します: $newRel" }
        val oldNorm = ws.relPath(oldFile)
        val newNorm = ws.relPath(newFile)
        require(oldNorm != newNorm) { "移動元と移動先が同じです: $oldNorm" }

        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // _document.id 宣言済みなら documentId は移動で変わらない＝文書参照の書き換えは不要
        val root = runCatching { YamlIO.parseFirst(Files.readString(oldFile)) }.getOrNull()
        val documentId = root?.let { IdIndex.documentIdOf(it, oldNorm) } ?: oldNorm
        val idDeclared = documentId != oldNorm
        if (idDeclared) {
            notes += "この文書は _document.id（$documentId）を宣言しているため、文書参照は移動後も安定です（書き換え不要）"
        }

        // パスIDへの文書参照を書き換える（参照元ファイル単位。移動対象ファイル内の参照は移動後のパスで扱う）
        val refs = if (idDeclared) emptyList() else ws.index.refs.filter {
            it.targetKind == RefTarget.DOCUMENT && it.targetId == oldNorm
        }
        val changes = refs.groupBy { it.file }.entries.sortedBy { it.key.toString() }.map { (file, fileRefs) ->
            val sourceRel = ws.relPath(file)
            FileChange(
                file = if (sourceRel == oldNorm) newNorm else sourceRel,
                baseHash = ChangeSets.contentHash(Files.readString(file)),
                ops = fileRefs.map { PatchOp.Set(it.path, YamlIO.fromAny(newNorm)) },
            )
        }
        if (refs.isNotEmpty()) {
            notes += "パスIDの文書参照 ${refs.size} 件を「$newNorm」へ書き換えます"
        }

        // スキーマ match の当たりが変わる警告
        val oldSchema = ws.schemas.firstOrNull { it.matches(oldNorm) }
        val newSchema = ws.schemas.firstOrNull { it.matches(newNorm) }
        if (oldSchema?.id != newSchema?.id) {
            warnings += "スキーマの適用が変わります: ${oldSchema?.id ?: "（なし）"} → ${newSchema?.id ?: "（なし）"}"
        }

        // ビュー定義の file / fileGlob が外れる警告
        fun globMatches(glob: String?, rel: String): Boolean =
            glob != null && runCatching { globToRegex(glob).matches(rel) }.getOrDefault(false)
        fun leaves(glob: String?): Boolean = globMatches(glob, oldNorm) && !globMatches(glob, newNorm)
        val viewDefs = Views.load(ws.root, ws.schemas)
        for (view in viewDefs.views) {
            val hits = mutableListOf<String>()
            if (view.newRow?.file == oldNorm) hits += "newRow.file"
            if (view.columns.any { leaves(it.from.fileGlob) }) hits += "columns の fileGlob"
            if (leaves(view.orderBy?.fileGlob)) hits += "orderBy.fileGlob"
            if (leaves(view.detail?.source?.fileGlob)) hits += "detail.fileGlob"
            if (hits.isNotEmpty()) warnings += "ビュー「${view.id}」の ${hits.joinToString("・")} がこのファイルを対象にしなくなります"
        }
        for (docView in viewDefs.docViews) {
            if (leaves(docView.match)) warnings += "文書ビュー「${docView.id}」の match がこのファイルを対象にしなくなります"
        }
        return Plan(oldNorm, newNorm, ChangeSet(changes), warnings, notes)
    }

    /**
     * 移動を実行する: 物理移動 → 索引更新 → 参照書き換えの変更セット適用（生成済みプラン）。
     * 参照書き換えが1件でも失敗すれば変更セット全体は書き込まれない（オール・オア・ナッシング）が、
     * 物理移動自体は完了している（メッセージで案内する）。
     */
    fun execute(ws: Workspace, plan: Plan): ChangeSetResult? {
        val oldFile = ws.resolve(plan.oldRel)
        val newFile = ws.resolve(plan.newRel)
        require(Files.isRegularFile(oldFile)) { "ファイルが存在しません: ${plan.oldRel}" }
        require(!Files.exists(newFile)) { "移動先に同名のファイルが存在します: ${plan.newRel}" }
        Files.createDirectories(newFile.parent)
        Files.move(oldFile, newFile)
        ws.noteDiskChanges(changed = listOf(newFile), removed = listOf(oldFile))
        if (plan.changeSet.changes.isEmpty()) return null
        val result = ws.apply(plan.changeSet)
        require(result.ok) {
            val message = result.results.firstOrNull { it.message != null }?.let { "${it.file}: ${it.message}" }
            "ファイルは移動しましたが、参照の書き換えに失敗しました（${message ?: "不明"}）。参照は手動で修正してください"
        }
        return result
    }
}
