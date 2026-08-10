package core

import index.IdEntry
import index.RefEntry
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.FieldType
import schema.entityFor
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エンティティ操作（R21-02。ARCHITECTURE §4.31）: rename / merge / delete を
 * 1つの変更セット（baseハッシュ付き）へ束ねる**生成器**。新しい書込経路は作らず、
 * 適用は既存の Workspace.apply（オール・オア・ナッシング・オーバーレイ検証）に委ねる。
 * 入口は CLI `entity` とコマンドパレット（確認ダイアログでプレビュー→適用）。
 */
object EntityOps {

    /** 生成した変更セットと、適用を止めない注意事項（idPattern 逸脱など）・人間向けサマリ。 */
    data class Plan(
        val changeSet: ChangeSet,
        val warnings: List<String>,
        val summary: List<String>,
    )

    /** merge での from 出現の扱い: into のIDへ書き換えて詳細化として残す（RETAIN）／要素ごと削除（DELETE）。 */
    enum class MergeMode { RETAIN, DELETE }

    /**
     * 改名: 全出現のIDフィールド値＋全参照（スカラー値／属性付き参照の to）を newId へ書き換える。
     * newId が既存IDと衝突する場合は生成段階で拒否する（統合は merge を使う）。
     */
    fun rename(ws: Workspace, oldId: String, newId: String): Plan {
        require(newId.isNotBlank()) { "改名先のIDを指定してください" }
        require(newId != oldId) { "改名先が現在のIDと同じです: $oldId" }
        val occurrences = ws.index.occurrences(oldId)
        require(occurrences.isNotEmpty()) { "ID「$oldId」の出現が見つかりません" }
        require(!ws.index.entries.containsKey(newId)) { "改名先のID「$newId」は既に存在します（統合は merge を使ってください）" }

        val refs = entityRefsTo(ws, oldId)
        val warnings = idPatternWarnings(ws, occurrences, newId)
        val files = FileOpsBuilder(ws)
        occurrences.forEach { e ->
            idFieldPaths(ws, e).forEach { path -> files.at(e.file).ops += PatchOp.Set(path, YamlIO.fromAny(newId)) }
        }
        refs.forEach { r -> files.at(r.file).ops += PatchOp.Set(r.path, YamlIO.fromAny(newId)) }
        return Plan(
            changeSet = files.toChangeSet(),
            warnings = warnings,
            summary = listOf(
                "ID「$oldId」→「$newId」",
                "出現 ${occurrences.size} 件（${occurrences.map { it.file }.distinct().size} ファイル）のIDフィールドを書き換え",
                "参照 ${refs.size} 件を書き換え",
            ),
        )
    }

    /**
     * 統合: fromId への全参照を intoId へ張り替える。from の出現は mode に従い
     * into のIDへ書き換えて詳細化として残す（RETAIN）か、要素ごと削除する（DELETE）。
     */
    fun merge(ws: Workspace, fromId: String, intoId: String, mode: MergeMode): Plan {
        require(fromId != intoId) { "統合元と統合先が同じIDです: $fromId" }
        val occurrences = ws.index.occurrences(fromId)
        require(occurrences.isNotEmpty()) { "ID「$fromId」の出現が見つかりません" }
        require(ws.index.entries.containsKey(intoId)) { "統合先のID「$intoId」が存在しません（改名は rename を使ってください）" }

        val warnings = mutableListOf<String>()
        val fromType = ws.index.graph.entities[fromId]?.entityType
        val intoType = ws.index.graph.entities[intoId]?.entityType
        if (fromType != null && intoType != null && fromType != intoType) {
            warnings += "エンティティ型が異なります（$fromId: $fromType ／ $intoId: $intoType）"
        }
        if (mode == MergeMode.DELETE) {
            occurrences.firstOrNull { it.path.isEmpty() }?.let { e ->
                error("出現が文書ルートのため要素削除できません（詳細化として残す方を選ぶか、ファイル削除は手動で行ってください）: ${ws.relPath(e.file)}")
            }
        }

        val deletedRoots: List<Pair<Path, YPath>> =
            if (mode == MergeMode.DELETE) occurrences.map { it.file to it.path } else emptyList()
        // 削除される出現サブツリー配下の参照は要素ごと消えるため書き換え対象から除く
        val refs = entityRefsTo(ws, fromId).filterNot { r ->
            deletedRoots.any { (f, p) -> r.file == f && r.path.size >= p.size && r.path.subList(0, p.size) == p }
        }
        val files = FileOpsBuilder(ws)
        refs.forEach { r -> files.at(r.file).ops += PatchOp.Set(r.path, YamlIO.fromAny(intoId)) }
        when (mode) {
            MergeMode.RETAIN -> occurrences.forEach { e ->
                idFieldPaths(ws, e).forEach { path -> files.at(e.file).ops += PatchOp.Set(path, YamlIO.fromAny(intoId)) }
            }
            // 削除は Set の後ろへ・パス降順（後方の要素から消してインデックスのずれを避ける）
            MergeMode.DELETE -> occurrences.sortedWith(pathDescending).forEach { e ->
                files.at(e.file).ops += PatchOp.Delete(e.path)
            }
        }
        return Plan(
            changeSet = files.toChangeSet(),
            warnings = warnings + (if (mode == MergeMode.RETAIN) idPatternWarnings(ws, occurrences, intoId) else emptyList()),
            summary = listOf(
                "ID「$fromId」を「$intoId」へ統合",
                "参照 ${refs.size} 件を張り替え",
                if (mode == MergeMode.RETAIN) "出現 ${occurrences.size} 件を「$intoId」の詳細化として残す"
                else "出現 ${occurrences.size} 件を削除",
            ),
        )
    }

    /** 削除: 入次数が 0 でなければ参照元一覧つきで拒否する（黙って壊さない）。出現要素を削除する。 */
    fun delete(ws: Workspace, id: String): Plan {
        val occurrences = ws.index.occurrences(id)
        require(occurrences.isNotEmpty()) { "ID「$id」の出現が見つかりません" }
        val inbound = entityRefsTo(ws, id)
        if (inbound.isNotEmpty()) {
            val shown = inbound.take(5).joinToString("、") { r ->
                "${ws.relPath(r.file)} の ${r.path.pretty()}"
            } + (if (inbound.size > 5) " 他${inbound.size - 5}件" else "")
            error("ID「$id」への参照が ${inbound.size} 件あるため削除できません（先に参照を編集するか merge を使ってください）: $shown")
        }
        occurrences.firstOrNull { it.path.isEmpty() }?.let { e ->
            error("出現が文書ルートのため要素削除できません（ファイルの削除は手動で行ってください）: ${ws.relPath(e.file)}")
        }
        val files = FileOpsBuilder(ws)
        occurrences.sortedWith(pathDescending).forEach { e -> files.at(e.file).ops += PatchOp.Delete(e.path) }
        return Plan(
            changeSet = files.toChangeSet(),
            warnings = emptyList(),
            summary = listOf("ID「$id」の出現 ${occurrences.size} 件（${occurrences.map { it.file }.distinct().size} ファイル）を削除"),
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 指定IDへのエンティティ参照（文書参照 R21-03 は対象外＝別名前空間）。 */
    private fun entityRefsTo(ws: Workspace, id: String): List<RefEntry> =
        ws.index.graph.backlinksTo(setOf(id)).filter { it.targetKind == schema.RefTarget.ENTITY }

    /** 出現要素のIDフィールド（値が対象IDのもの）のパス。スキーマの type: id を正、スキーマなしは id キー。 */
    private fun idFieldPaths(ws: Workspace, e: IdEntry): List<YPath> {
        val root = runCatching { YamlIO.parseFirst(Files.readString(e.file)) }.getOrNull() ?: return emptyList()
        val element = getAt(root, e.path) as? YNode.Map ?: return emptyList()
        val schemaDef = ws.schemaFor(e.file)
        return element.entries.mapNotNull { (k, v) ->
            if (scalarText(v) != e.id) return@mapNotNull null
            val isIdField = if (schemaDef == null) k == "id"
            else Views.fieldAt(schemaDef, root, e.path + k)?.type == FieldType.ID
            if (isIdField) e.path + k else null
        }
    }

    /** 改名・統合先のIDがエンティティ型の idPattern から逸脱する場合の警告（適用は止めない）。 */
    private fun idPatternWarnings(ws: Workspace, occurrences: List<IdEntry>, newId: String): List<String> =
        occurrences.mapNotNull { e ->
            val decl = ws.schemaFor(e.file)?.entityFor(e.id) ?: return@mapNotNull null
            if (decl.idPattern != null && !decl.matchesId(newId)) {
                "エンティティ型「${decl.type}」の idPattern「${decl.idPattern}」に一致しません: $newId"
            } else null
        }.distinct()

    /** 同一ファイル内の削除を後方から行うためのパス降順（数値セグメントは数値比較）。 */
    private val pathDescending = Comparator<IdEntry> { a, b ->
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

    /** ファイル単位の ops を集め、baseハッシュ付きの変更セットへ組む。 */
    private class FileOpsBuilder(private val ws: Workspace) {
        private val byFile = LinkedHashMap<Path, MutableList<PatchOp>>()

        fun at(file: Path): FileOpsHandle = FileOpsHandle(byFile.getOrPut(file) { mutableListOf() })

        class FileOpsHandle(val ops: MutableList<PatchOp>)

        fun toChangeSet(): ChangeSet = ChangeSet(
            byFile.entries.sortedBy { it.key.toString() }.map { (file, ops) ->
                val text = Files.readString(file)
                // Set を先・Delete を後に安定整列する（削除によるインデックスずれを Set に波及させない）
                val ordered = ops.filter { it !is PatchOp.Delete } + ops.filterIsInstance<PatchOp.Delete>()
                FileChange(
                    file = ws.relPath(file),
                    baseHash = ChangeSets.contentHash(text),
                    ops = ordered,
                )
            },
        )
    }
}
