package index

import model.YNode
import model.YPath
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.RefScope
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

    fun isGenericRefKey(k: String): Boolean =
        k == "ref" || k.endsWith("_ref")

    fun isGenericRefListKey(k: String): Boolean =
        k == "refs" || k.endsWith("_refs") || k == "depends_on" || k == "relates_to"

    fun scan(root: YNode?, file: Path, schemaDef: SchemaDef?, documentId: String = file.toString()): Pair<List<IdEntry>, List<RefEntry>> {
        val ids = mutableListOf<IdEntry>()
        val refs = mutableListOf<RefEntry>()
        if (root == null) return ids to refs

        fun walk(node: YNode, path: YPath, def: FieldDef?, keyLabel: String?) {
            val effective = schemaDef?.resolve(def) ?: def
            // スキーマ上 ref と定義された位置のスカラー値は参照として記録
            if (effective?.type == FieldType.REF && node is YNode.Scalar) {
                scalarText(node)?.let {
                    refs += RefEntry(it, file, path, keyLabel ?: "ref", effective.idPatterns, effective.scope)
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
                    // --- ID の検出 ---
                    val idKeys = LinkedHashSet<String>()
                    if (effective?.type == FieldType.OBJECT) {
                        effective.fields.filter { (schemaDef?.resolve(it) ?: it).type == FieldType.ID }.forEach { it.key?.let(idKeys::add) }
                    }
                    // スキーマ適用中は、明示的に type: id とされた項目だけを索引化する。
                    if (schemaDef == null) idKeys.add("id")
                    for (k in idKeys) {
                        val idText = scalarText(node.entries[k]) ?: continue
                        val title = scalarText(node.entries["title"]) ?: scalarText(node.entries["name"])
                        ids += IdEntry(idText, file, path, title, schemaDef?.id, documentId)
                        break
                    }
                    // --- 子要素の走査と参照検出 ---
                    for ((k, child) in node.entries) {
                        // ルートの予約メタデータは業務要素の ID／参照として索引化しない。
                        if (path.isEmpty() && k == "_document") continue
                        val childDef = when (effective?.type) {
                            FieldType.OBJECT -> effective.fields.firstOrNull { it.key == k }
                            FieldType.MAP -> effective.value
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
 */
class IdIndex {

    /** 論理 id -> 文書ごとの出現一覧 */
    var entries: Map<String, List<IdEntry>> = emptyMap()
        private set

    /** ワークスペース内の全参照 */
    var refs: List<RefEntry> = emptyList()
        private set

    fun rebuild(workspace: Path, files: List<Path>, schemas: List<SchemaDef>) {
        val ids = mutableMapOf<String, MutableList<IdEntry>>()
        val allRefs = mutableListOf<RefEntry>()
        for (f in files) {
            val rel = relPathOf(workspace, f)
            if (rel.startsWith(schema.SchemaLoader.SCHEMA_DIR + "/")) continue
            val root = runCatching { YamlIO.parse(Files.readString(f)) }.getOrNull() ?: continue
            val schemaDef = schemas.firstOrNull { it.matches(rel) }
            val documentId = documentIdOf(root, rel)
            val (docIds, docRefs) = DocScanner.scan(root, f, schemaDef, documentId)
            for (e in docIds) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            allRefs += docRefs
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
