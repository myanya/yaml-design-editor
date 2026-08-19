package semantic

import edit.YamlOperation
import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.FieldType
import schema.entityFor

/**
 * 意味的リファクタリングの計画生成（R21-02。3層コア移行 Phase 9 で core/EntityOps.kt から移動）。
 * rename / merge / delete を「文書ごとの型付き操作列」へ束ねる**生成器**で、適用はしない
 * （計画書§4.2: plan は semantic-core・apply は workspace-core）。
 * 生成規則・拒否条件・警告は EntityOps と同一。座標は DocumentId × YPath（原本座標）。
 */

/** リファクタリング要求（計画書§4.2）。 */
sealed interface RefactoringRequest {
    data class RenameEntity(val oldId: String, val newId: String) : RefactoringRequest

    data class DeleteEntity(val id: String) : RefactoringRequest

    /** deleteOccurrences = false は from の出現を into の詳細化として残す（現行 merge の RETAIN／DELETE）。 */
    data class MergeEntity(
        val sourceId: String,
        val targetId: String,
        val deleteOccurrences: Boolean = false,
    ) : RefactoringRequest

    /**
     * 文書の移動・改名（R21-03）。パスID（`_document.id` 未宣言）の文書参照を書き換える計画を生成し、
     * 物理移動（moves）と参照書き換えを1つのトランザクションで適用する（旧 DocMove の
     * 「移動成功→書き換え失敗」の継ぎ目を解消）。存在・衝突の前提検査は上位層（session）が行う。
     */
    data class MoveDocument(val from: DocumentId, val to: DocumentId) : RefactoringRequest
}

/** 1文書分の計画（原本座標つきの型付き操作列）。 */
data class PlannedDocumentChange(
    val document: DocumentId,
    val operations: List<YamlOperation>,
)

/** リファクタリング計画。sourceVersion が現在の version と一致するときだけ適用できる（stale 拒否）。 */
data class RefactoringPlan(
    val sourceVersion: Long,
    val request: RefactoringRequest,
    val changes: List<PlannedDocumentChange>,
    /** 適用を止めない注意事項（idPattern 逸脱・型の不一致など）。 */
    val warnings: List<String>,
    /** 人間向けサマリ。 */
    val summary: List<String>,
    /** 文書の物理移動（from → to。MoveDocument のみ非空。適用は changes より先）。 */
    val moves: List<Pair<DocumentId, DocumentId>> = emptyList(),
)

object SemanticRefactor {

    /** 計画を生成する（snapshot・文書は読み取り専用。workspace は変化しない）。 */
    fun plan(
        snapshot: SemanticSnapshot,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan = when (request) {
        is RefactoringRequest.RenameEntity -> rename(snapshot, schemas, request, tree)
        is RefactoringRequest.MergeEntity -> merge(snapshot, schemas, request, tree)
        is RefactoringRequest.DeleteEntity -> delete(snapshot, request)
        is RefactoringRequest.MoveDocument -> moveDocument(snapshot, request)
    }

    /** 文書移動: パスIDの文書参照の書き換え＋物理移動を1計画にする（DocMove.plan と同じ規則）。 */
    private fun moveDocument(
        snapshot: SemanticSnapshot,
        request: RefactoringRequest.MoveDocument,
    ): RefactoringPlan {
        val (from, to) = request
        require(from != to) { "移動元と移動先が同じです: ${from.value}" }
        val oldRel = from.value
        val newRel = to.value
        val notes = mutableListOf<String>()

        // _document.id 宣言済みなら documentId は移動で変わらない＝文書参照の書き換えは不要
        val documentId = snapshot.graph.documents.values.firstOrNull { it.document == from }?.documentId ?: oldRel
        val idDeclared = documentId != oldRel
        if (idDeclared) {
            notes += "この文書は _document.id（$documentId）を宣言しているため、文書参照は移動後も安定です（書き換え不要）"
        }

        // パスIDへの文書参照を書き換える（移動対象ファイル内の参照は移動後のパスで扱う）
        val refs = if (idDeclared) emptyList() else snapshot.refs.filter {
            it.targetKind == schema.RefTarget.DOCUMENT && it.targetId == oldRel
        }
        val builder = DocOpsBuilder()
        refs.forEach { r ->
            val target = if (r.document == from) to else r.document
            builder.at(target) += YamlOperation.SetScalar(r.path, newRel)
        }
        if (refs.isNotEmpty()) {
            notes += "パスIDの文書参照 ${refs.size} 件を「$newRel」へ書き換えます"
        }
        return RefactoringPlan(
            sourceVersion = snapshot.sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf("文書「$oldRel」→「$newRel」") + notes,
            moves = listOf(from to to),
        )
    }

    /**
     * 改名: 全出現のIDフィールド値＋全参照（スカラー値／属性付き参照の to）を newId へ書き換える。
     * newId が既存IDと衝突する場合は生成段階で拒否する（統合は merge を使う）。
     */
    private fun rename(
        snapshot: SemanticSnapshot,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.RenameEntity,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (oldId, newId) = request
        require(newId.isNotBlank()) { "改名先のIDを指定してください" }
        require(newId != oldId) { "改名先が現在のIDと同じです: $oldId" }
        val occurrences = snapshot.occurrences(oldId)
        require(occurrences.isNotEmpty()) { "ID「$oldId」の出現が見つかりません" }
        require(newId !in snapshot.ids) { "改名先のID「$newId」は既に存在します（統合は merge を使ってください）" }

        val refs = snapshot.backlinksTo(setOf(oldId))
        val warnings = idPatternWarnings(schemas, occurrences, newId)
        val builder = DocOpsBuilder()
        occurrences.forEach { e ->
            idFieldPaths(schemas, e, tree).forEach { path ->
                builder.at(e.document) += YamlOperation.SetScalar(path, newId)
            }
        }
        refs.forEach { r -> builder.at(r.document) += YamlOperation.SetScalar(r.path, newId) }
        return RefactoringPlan(
            sourceVersion = snapshot.sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = warnings,
            summary = listOf(
                "ID「$oldId」→「$newId」",
                "出現 ${occurrences.size} 件（${occurrences.map { it.document }.distinct().size} 文書）のIDフィールドを書き換え",
                "参照 ${refs.size} 件を書き換え",
            ),
        )
    }

    /**
     * 統合: fromId への全参照を intoId へ張り替える。from の出現は deleteOccurrences に従い
     * into のIDへ書き換えて詳細化として残すか、要素ごと削除する。
     */
    private fun merge(
        snapshot: SemanticSnapshot,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.MergeEntity,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (fromId, intoId, deleteOccurrences) = request
        require(fromId != intoId) { "統合元と統合先が同じIDです: $fromId" }
        val occurrences = snapshot.occurrences(fromId)
        require(occurrences.isNotEmpty()) { "ID「$fromId」の出現が見つかりません" }
        require(intoId in snapshot.ids) { "統合先のID「$intoId」が存在しません（改名は rename を使ってください）" }

        val warnings = mutableListOf<String>()
        val fromType = snapshot.graph.entities[fromId]?.entityType
        val intoType = snapshot.graph.entities[intoId]?.entityType
        if (fromType != null && intoType != null && fromType != intoType) {
            warnings += "エンティティ型が異なります（$fromId: $fromType ／ $intoId: $intoType）"
        }
        if (deleteOccurrences) {
            occurrences.firstOrNull { it.path.isEmpty() }?.let { e ->
                error("出現が文書ルートのため要素削除できません（詳細化として残す方を選ぶか、ファイル削除は手動で行ってください）: ${e.document.value}")
            }
        }

        val deletedRoots: List<Pair<DocumentId, YPath>> =
            if (deleteOccurrences) occurrences.map { it.document to it.path } else emptyList()
        // 削除される出現サブツリー配下の参照は要素ごと消えるため書き換え対象から除く
        val refs = snapshot.backlinksTo(setOf(fromId)).filterNot { r ->
            deletedRoots.any { (d, p) -> r.document == d && r.path.size >= p.size && r.path.subList(0, p.size) == p }
        }
        val builder = DocOpsBuilder()
        refs.forEach { r -> builder.at(r.document) += YamlOperation.SetScalar(r.path, intoId) }
        if (deleteOccurrences) {
            // 削除は Set の後ろへ・パス降順（後方の要素から消してインデックスのずれを避ける）
            occurrences.sortedWith(pathDescending).forEach { e ->
                builder.at(e.document) += YamlOperation.Remove(e.path)
            }
        } else {
            occurrences.forEach { e ->
                idFieldPaths(schemas, e, tree).forEach { path ->
                    builder.at(e.document) += YamlOperation.SetScalar(path, intoId)
                }
            }
        }
        return RefactoringPlan(
            sourceVersion = snapshot.sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = warnings + (if (!deleteOccurrences) idPatternWarnings(schemas, occurrences, intoId) else emptyList()),
            summary = listOf(
                "ID「$fromId」を「$intoId」へ統合",
                "参照 ${refs.size} 件を張り替え",
                if (!deleteOccurrences) "出現 ${occurrences.size} 件を「$intoId」の詳細化として残す"
                else "出現 ${occurrences.size} 件を削除",
            ),
        )
    }

    /** 削除: 入次数が 0 でなければ参照元一覧つきで拒否する（黙って壊さない）。出現要素を削除する。 */
    private fun delete(snapshot: SemanticSnapshot, request: RefactoringRequest.DeleteEntity): RefactoringPlan {
        val id = request.id
        val occurrences = snapshot.occurrences(id)
        require(occurrences.isNotEmpty()) { "ID「$id」の出現が見つかりません" }
        val inbound = snapshot.backlinksTo(setOf(id))
        if (inbound.isNotEmpty()) {
            val shown = inbound.take(5).joinToString("、") { r ->
                "${r.document.value} の ${r.path.pretty()}"
            } + (if (inbound.size > 5) " 他${inbound.size - 5}件" else "")
            error("ID「$id」への参照が ${inbound.size} 件あるため削除できません（先に参照を編集するか merge を使ってください）: $shown")
        }
        occurrences.firstOrNull { it.path.isEmpty() }?.let { e ->
            error("出現が文書ルートのため要素削除できません（ファイルの削除は手動で行ってください）: ${e.document.value}")
        }
        val builder = DocOpsBuilder()
        occurrences.sortedWith(pathDescending).forEach { e -> builder.at(e.document) += YamlOperation.Remove(e.path) }
        return RefactoringPlan(
            sourceVersion = snapshot.sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf("ID「$id」の出現 ${occurrences.size} 件（${occurrences.map { it.document }.distinct().size} 文書）を削除"),
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 出現要素のIDフィールド（値が対象IDのもの）のパス。スキーマの type: id を正、スキーマなしは id キー。 */
    private fun idFieldPaths(
        schemas: Map<String, SchemaSemantics>,
        e: ScannedId,
        tree: (DocumentId) -> YNode?,
    ): List<YPath> {
        val root = tree(e.document) ?: return emptyList()
        val element = getAt(root, e.path) as? YNode.Map ?: return emptyList()
        val structural = e.schemaId?.let { schemas[it]?.structural }
        return element.entries.mapNotNull { (k, v) ->
            if (scalarText(v) != e.id) return@mapNotNull null
            val isIdField = if (structural == null) k == "id"
            else structural.fieldAt(root, e.path + k)?.type == FieldType.ID
            if (isIdField) e.path + k else null
        }
    }

    /** 改名・統合先のIDがエンティティ型の idPattern から逸脱する場合の警告（適用は止めない）。 */
    private fun idPatternWarnings(
        schemas: Map<String, SchemaSemantics>,
        occurrences: List<ScannedId>,
        newId: String,
    ): List<String> = occurrences.mapNotNull { e ->
        val decl = e.schemaId?.let { schemas[it] }?.let { entityFor(it.entities, e.id) } ?: return@mapNotNull null
        if (decl.idPattern != null && !decl.matchesId(newId)) {
            "エンティティ型「${decl.type}」の idPattern「${decl.idPattern}」に一致しません: $newId"
        } else null
    }.distinct()

    /** 同一文書内の削除を後方から行うためのパス降順（数値セグメントは数値比較）。 */
    private val pathDescending = Comparator<ScannedId> { a, b ->
        val x = a.path
        val y = b.path
        for (i in 0 until minOf(x.size, y.size)) {
            val sx = x[i]
            val sy = y[i]
            val c = when {
                sx is Int && sy is Int -> sx.compareTo(sy)
                else -> sx.toString().compareTo(sy.toString())
            }
            if (c != 0) return@Comparator -c
        }
        -(x.size.compareTo(y.size))
    }

    /** 文書単位の操作を集める（文書ID順・Set 先／Remove 後の安定整列＝EntityOps と同じ規則）。 */
    private class DocOpsBuilder {
        private val byDoc = LinkedHashMap<DocumentId, MutableList<YamlOperation>>()

        fun at(document: DocumentId): MutableList<YamlOperation> = byDoc.getOrPut(document) { mutableListOf() }

        fun toChanges(): List<PlannedDocumentChange> =
            byDoc.entries.sortedBy { it.key.value }.map { (document, ops) ->
                val ordered = ops.filter { it !is YamlOperation.Remove } + ops.filterIsInstance<YamlOperation.Remove>()
                PlannedDocumentChange(document, ordered)
            }
    }
}
