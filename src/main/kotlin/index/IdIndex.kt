package index

import model.YNode
import model.YPath
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.RefScope
import schema.WorkspaceLayout
import schema.effectiveFields
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/** ワークスペース内で定義された ID 1 件。path は ID を持つ要素（マップ）の位置。 */
data class IdEntry(
    val id: String,
    val file: Path,
    val path: YPath,
    val title: String?,   // 同じ要素の title / name（表示用）
    val schemaId: String?,
    val documentId: String,
)

/** ID への参照 1 件。path は参照値（スカラー）の位置。 */
data class RefEntry(
    val targetId: String,
    val file: Path,
    val path: YPath,
    val viaKey: String,   // 参照が書かれていたキー名（表示用）
    val idPatterns: List<String> = emptyList(),
    val scope: RefScope = RefScope(),
    val relation: String? = null,       // スキーマで宣言された関係種別（type: ref の relation）
    val sourceSchemaId: String? = null, // 参照元文書のスキーマID（カバレッジ判定に使う）
)

fun idMatchesPattern(id: String, pattern: String): Boolean {
    val regex = buildString {
        append('^')
        pattern.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
        append('$')
    }
    return runCatching { Regex(regex).matches(id) }.getOrDefault(false)
}

fun IdEntry.isAllowedBy(ref: RefEntry): Boolean =
    (ref.idPatterns.isEmpty() || ref.idPatterns.any { idMatchesPattern(id, it) }) &&
        (ref.scope.schemaIds.isEmpty() || schemaId in ref.scope.schemaIds) &&
        (ref.scope.documentIds.isEmpty() || documentId in ref.scope.documentIds)

/**
 * 1 ドキュメントから ID 定義と参照を抽出するスキャナ。
 * - スキーマがある場合: type: id / type: ref のフィールドを正として扱う
 * - スキーマがない場合: 命名規約ヒューリスティクスで検出する
 * - スキーマ外のサブツリー: 未知項目の境界で走査を止める
 *   - ID:  キー名 "id"
 *   - 参照: キー名 "ref" / "*_ref"、および "refs" / "*_refs" / "depends_on" / "relates_to" 配下のスカラー配列
 */
object DocScanner {

    // 命名規約の実体は schema.RefNaming（スキーマ推測 R11-01 と共有。ARCHITECTURE §4.23）
    fun isGenericRefKey(k: String): Boolean = schema.RefNaming.isRefKey(k)

    fun isGenericRefListKey(k: String): Boolean = schema.RefNaming.isRefListKey(k)

    fun scan(root: YNode?, file: Path, schemaDef: SchemaDef?, documentId: String = file.toString()): Pair<List<IdEntry>, List<RefEntry>> {
        val ids = mutableListOf<IdEntry>()
        val refs = mutableListOf<RefEntry>()
        if (root == null) return ids to refs

        fun walk(node: YNode, path: YPath, def: FieldDef?, keyLabel: String?) {
            val effective = schemaDef?.resolve(def) ?: def
            // スキーマ上 ref と定義された位置のスカラー値は参照として記録
            if (effective?.type == FieldType.REF && node is YNode.Scalar) {
                scalarText(node)?.let {
                    refs += RefEntry(
                        it, file, path, keyLabel ?: "ref", effective.idPatterns, effective.scope,
                        relation = effective.relation, sourceSchemaId = schemaDef?.id,
                    )
                }
                return
            }
            when (node) {
                is YNode.Scalar -> Unit
                is YNode.Seq -> {
                    val itemDef = if (effective?.type == FieldType.ARRAY) effective.item else null
                    node.items.forEachIndexed { i, child ->
                        walk(child, path + i, itemDef, keyLabel)
                    }
                }
                is YNode.Map -> {
                    // object の実効フィールド（enum 値に応じた case フィールドを含む）
                    val objectFields = if (schemaDef != null && effective?.type == FieldType.OBJECT) {
                        schemaDef.effectiveFields(effective.fields, node).fields
                    } else null
                    // --- ID の検出 ---
                    val idKeys = LinkedHashSet<String>()
                    objectFields?.filter { (schemaDef?.resolve(it) ?: it).type == FieldType.ID }?.forEach { it.key?.let(idKeys::add) }
                    // スキーマ適用中は、明示的に type: id とされた項目だけを索引化する。
                    if (schemaDef == null) idKeys.add("id")
                    for (k in idKeys) {
                        val idText = scalarText(node.entries[k]) ?: continue
                        // タイトルは表示解決（R10-01）に一元化する: スキーマの role 指定 ＞ 予約語（title/name/label）
                        val title = schema.Display.titleOf(node, schemaDef, effective)
                        ids += IdEntry(idText, file, path, title, schemaDef?.id, documentId)
                        break
                    }
                    // --- 子要素の走査と参照検出 ---
                    for ((k, child) in node.entries) {
                        // ルートの予約メタデータは業務要素の ID／参照として索引化しない。
                        if (path.isEmpty() && k == "_document") continue
                        val childDef = when {
                            objectFields != null -> objectFields.firstOrNull { it.key == k }
                            effective?.type == FieldType.MAP -> effective.value
                            else -> null
                        }
                        val childPath = path + k
                        // スキーマ外サブツリーは未知項目の境界で止め、ID・参照を索引化しない。
                        if (schemaDef != null && childDef == null) continue
                        if (childDef == null) {
                            // スキーマなし文書ではヒューリスティクスで参照を検出する。
                            if (isGenericRefKey(k) && child is YNode.Scalar) {
                                scalarText(child)?.let { refs += RefEntry(it, file, childPath, k) }
                            } else if (isGenericRefListKey(k) && child is YNode.Seq) {
                                child.items.forEachIndexed { i, c ->
                                    if (c is YNode.Scalar) {
                                        scalarText(c)?.let { refs += RefEntry(it, file, childPath + i, k) }
                                    }
                                }
                            }
                        }
                        walk(child, childPath, childDef, k)
                    }
                }
            }
        }

        walk(root, emptyList(), schemaDef?.root, null)
        return ids to refs
    }
}

/**
 * ワークスペース全体の ID 索引。
 * ディスク上のファイルを対象とするため、未保存の編集は「保存」または「再読込」まで反映されない。
 * ファイル単位の走査結果を保持し、変更ファイルだけの差し替え（増分更新。R8-02）ができる。
 */
class IdIndex {

    private class FileScan(val ids: List<IdEntry>, val refs: List<RefEntry>)

    /** ファイル -> 走査結果。集約（entries / refs）はここから導出する。 */
    private val byFile = HashMap<Path, FileScan>()

    /** 論理 id -> 文書ごとの出現一覧 */
    var entries: Map<String, List<IdEntry>> = emptyMap()
        private set

    /** ワークスペース内の全参照 */
    var refs: List<RefEntry> = emptyList()
        private set

    /** ディスクから読む既定のローダ（複数ドキュメントYAMLは先頭を索引化する。R8-05）。 */
    private val diskLoader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() }

    /**
     * 索引を全ファイルから再構築する。loader を差し替えると、ディスク以外（例: エージェントのステージング）の
     * 内容で索引を組める。schemas ゾーンと機械が書く実行記録 runs ゾーンは索引対象外（`layout.indexExcluded`）。
     */
    fun rebuild(
        workspace: Path,
        files: List<Path>,
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode? = diskLoader,
    ) {
        byFile.clear()
        for (f in files) {
            scanFile(workspace, f, schemas, layout, loader)?.let { byFile[f] = it }
        }
        derive()
    }

    /**
     * 未保存編集を重ねたオーバーレイ索引（UX-03）: この索引の走査結果を複製し、指定ファイルだけを
     * loader の内容（編集中ツリー）で走査し直した**新しい索引**を返す（この索引自体は変更しない）。
     * コストは走査結果の複製＋対象ファイル数に比例する。UIの「見たまま判定」（ARCHITECTURE §4.5）に使う。
     */
    fun overlay(
        workspace: Path,
        files: Collection<Path>,
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode?,
    ): IdIndex {
        val out = IdIndex()
        out.byFile.putAll(byFile)
        out.update(workspace, files, emptyList(), schemas, layout, loader)
        return out
    }

    /**
     * 変更・削除されたファイルの分だけ走査し直して索引を差し替える（R8-02: 増分更新）。
     * スキーマ自体が変わった場合は全ファイルの走査規則が変わるため、rebuild を使うこと。
     */
    fun update(
        workspace: Path,
        changed: Collection<Path>,
        removed: Collection<Path> = emptyList(),
        schemas: List<SchemaDef>,
        layout: WorkspaceLayout = WorkspaceLayout.LEGACY,
        loader: (Path) -> YNode? = diskLoader,
    ) {
        removed.forEach { byFile.remove(it) }
        changed.forEach { f ->
            val scan = scanFile(workspace, f, schemas, layout, loader)
            if (scan != null) byFile[f] = scan else byFile.remove(f)
        }
        derive()
    }

    /** 1 ファイルを走査する。索引対象外・読取不能なら null。 */
    private fun scanFile(workspace: Path, f: Path, schemas: List<SchemaDef>, layout: WorkspaceLayout, loader: (Path) -> YNode?): FileScan? {
        val rel = relPathOf(workspace, f)
        if (layout.indexExcluded(rel)) return null
        // 台帳外（ledger 宣言の対象外・隠しパス）は、増分更新（update）で個別に渡されても索引に載せない（R13-02）
        if (!layout.inLedger(rel) && layout.zoneOf(rel) == null) return null
        val root = loader(f) ?: return null
        val schemaDef = schemas.firstOrNull { it.matches(rel) }
        val documentId = documentIdOf(root, rel)
        val (docIds, docRefs) = DocScanner.scan(root, f, schemaDef, documentId)
        return FileScan(docIds, docRefs)
    }

    /** byFile から集約ビュー（entries / refs）を導出する。順序はパス順で安定させる。 */
    private fun derive() {
        val ids = mutableMapOf<String, MutableList<IdEntry>>()
        val allRefs = mutableListOf<RefEntry>()
        for (f in byFile.keys.sortedBy { it.toString() }) {
            val scan = byFile.getValue(f)
            for (e in scan.ids) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            allRefs += scan.refs
        }
        entries = ids
        refs = allRefs
    }

    fun occurrences(id: String): List<IdEntry> = entries[id].orEmpty()

    fun resolve(id: String): IdEntry? = occurrences(id).firstOrNull()

    fun resolve(ref: RefEntry): List<IdEntry> = occurrences(ref.targetId).filter { it.isAllowedBy(ref) }

    fun documentIdFor(file: Path, root: YNode): String =
        entries.values.asSequence().flatten().firstOrNull { it.file == file }?.documentId
            ?: documentIdOf(root, file.toString())

    /** 指定 ID 群を参照しているエントリ（被参照＝バックリンク）。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> =
        refs.filter { it.targetId in idSet }

    /**
     * 参照値の位置を含む、最も内側（パスが最長）の ID 出現＝参照を書いた要素。
     * バックリンクの表示解決（「どの要素からの参照か」を ID・タイトルで見せる。R10-01）に使う。
     */
    fun sourceEntryOf(ref: RefEntry): IdEntry? =
        entries.values.asSequence().flatten()
            .filter { it.file == ref.file && it.path.size <= ref.path.size && ref.path.subList(0, it.path.size) == it.path }
            .maxByOrNull { it.path.size }

    companion object {
        fun relPathOf(workspace: Path, file: Path): String =
            workspace.relativize(file).toString().replace('\\', '/')

        fun documentIdOf(root: YNode, fallback: String): String {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("id")) ?: fallback
        }

        /** ルートの予約メタデータに宣言された表示用の文書名。 */
        fun documentNameOf(root: YNode): String? {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("name"))?.takeIf { it.isNotBlank() }
        }

        /** ルートの予約メタデータに宣言された、文書全体の概要。 */
        fun documentDescriptionOf(root: YNode): String? {
            val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
            return scalarText(metadata?.entries?.get("description"))?.takeIf { it.isNotBlank() }
        }
    }
}
