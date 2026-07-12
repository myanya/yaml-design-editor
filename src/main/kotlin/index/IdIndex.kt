package index

import model.YNode
import model.YPath
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/** ワークスペース内で定義された ID 1 件。path は ID を持つ要素（マップ）の位置。 */
data class IdEntry(
    val id: String,
    val file: Path,
    val path: YPath,
    val title: String?,   // 同じ要素の title / name（表示用）
    val kind: String?,    // 定義元ファイルに適用されたスキーマ名
)

/** ID への参照 1 件。path は参照値（スカラー）の位置。 */
data class RefEntry(
    val targetId: String,
    val file: Path,
    val path: YPath,
    val viaKey: String,   // 参照が書かれていたキー名（表示用）
)

/**
 * 1 ドキュメントから ID 定義と参照を抽出するスキャナ。
 * - スキーマがある場合: type: id / type: ref のフィールドを正として扱う
 * - スキーマがない場合（またはスキーマ外のキー）: 命名規約ヒューリスティクスで検出する
 *   - ID:  キー名 "id"
 *   - 参照: キー名 "ref" / "*_ref"、および "refs" / "*_refs" / "depends_on" / "relates_to" 配下のスカラー配列
 */
object DocScanner {

    fun isGenericRefKey(k: String): Boolean =
        k == "ref" || k.endsWith("_ref")

    fun isGenericRefListKey(k: String): Boolean =
        k == "refs" || k.endsWith("_refs") || k == "depends_on" || k == "relates_to"

    fun scan(root: YNode?, file: Path, schemaDef: SchemaDef?): Pair<List<IdEntry>, List<RefEntry>> {
        val ids = mutableListOf<IdEntry>()
        val refs = mutableListOf<RefEntry>()
        if (root == null) return ids to refs

        fun walk(node: YNode, path: YPath, def: FieldDef?, keyLabel: String?) {
            val effective = schemaDef?.resolve(def) ?: def
            // スキーマ上 ref と定義された位置のスカラー値は参照として記録
            if (effective?.type == FieldType.REF && node is YNode.Scalar) {
                scalarText(node)?.let { refs += RefEntry(it, file, path, keyLabel ?: "ref") }
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
                    idKeys.add("id")
                    for (k in idKeys) {
                        val idText = scalarText(node.entries[k]) ?: continue
                        val title = scalarText(node.entries["title"]) ?: scalarText(node.entries["name"])
                        ids += IdEntry(idText, file, path, title, schemaDef?.name)
                        break
                    }
                    // --- 子要素の走査と参照検出 ---
                    for ((k, child) in node.entries) {
                        val childDef = when (effective?.type) {
                            FieldType.OBJECT -> effective.fields.firstOrNull { it.key == k }
                            FieldType.MAP -> effective.value
                            else -> null
                        }
                        val childPath = path + k
                        if (childDef == null) {
                            // ヒューリスティクスによる参照検出（スキーマ外のキーのみ対象）
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

    /** id -> 定義一覧（重複定義があり得るため List） */
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
            val (docIds, docRefs) = DocScanner.scan(root, f, schemaDef)
            for (e in docIds) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            allRefs += docRefs
        }
        entries = ids
        refs = allRefs
    }

    fun resolve(id: String): IdEntry? = entries[id]?.firstOrNull()

    fun isDuplicated(id: String): Boolean = (entries[id]?.size ?: 0) > 1

    /** 指定 ID 群を参照しているエントリ（被参照＝バックリンク）。 */
    fun backlinksTo(idSet: Set<String>): List<RefEntry> =
        refs.filter { it.targetId in idSet }

    companion object {
        fun relPathOf(workspace: Path, file: Path): String =
            workspace.relativize(file).toString().replace('\\', '/')
    }
}
