package core

import model.YNode
import model.YPath
import model.getAt
import model.parseYPath
import model.pretty
import yaml.YamlIO
import java.security.MessageDigest

/**
 * YPath を座標とする部分変更（パッチ）。
 * ルート（空パス）への操作は対象外（文書全体の置換はファイル書き込みで行う）。
 */
sealed class PatchOp {
    /** 値の置換または作成。マップは無ければキーを作成、配列は既存インデックスのみ。 */
    data class Set(val path: YPath, val value: YNode) : PatchOp()

    /** 挿入。配列は指定位置に挿入（末尾 = 現在の要素数）、マップは新規キーのみ（既存キーはエラー）。 */
    data class Insert(val path: YPath, val value: YNode) : PatchOp()

    /** 削除。存在しないパスはエラー。 */
    data class Delete(val path: YPath) : PatchOp()

    /** 移動。from を削除してから to へ挿入する（to は削除後の状態で評価される）。 */
    data class Move(val from: YPath, val to: YPath) : PatchOp()
}

object Patcher {

    /** 1 操作をツリーへ適用する（破壊的）。失敗時は日本語メッセージの IllegalStateException。 */
    fun apply(root: YNode, op: PatchOp) {
        when (op) {
            is PatchOp.Set -> {
                val (parent, seg) = locateParent(root, op.path)
                when (seg) {
                    is String -> asMap(parent, op.path).entries[seg] = op.value
                    is Int -> {
                        val seq = asSeq(parent, op.path)
                        if (seg !in seq.items.indices) error("インデックスが範囲外です: ${op.path.pretty()}（要素数 ${seq.items.size}）")
                        seq.items[seg] = op.value
                    }
                }
            }
            is PatchOp.Insert -> {
                val (parent, seg) = locateParent(root, op.path)
                when (seg) {
                    is String -> {
                        val map = asMap(parent, op.path)
                        if (seg in map.entries) error("既に存在するキーです: ${op.path.pretty()}")
                        map.entries[seg] = op.value
                    }
                    is Int -> {
                        val seq = asSeq(parent, op.path)
                        if (seg !in 0..seq.items.size) error("挿入位置が範囲外です: ${op.path.pretty()}（要素数 ${seq.items.size}）")
                        seq.items.add(seg, op.value)
                    }
                }
            }
            is PatchOp.Delete -> {
                val (parent, seg) = locateParent(root, op.path)
                when (seg) {
                    is String -> {
                        val map = asMap(parent, op.path)
                        if (seg !in map.entries) error("パスが存在しません: ${op.path.pretty()}")
                        map.entries.remove(seg)
                    }
                    is Int -> {
                        val seq = asSeq(parent, op.path)
                        if (seg !in seq.items.indices) error("パスが存在しません: ${op.path.pretty()}")
                        seq.items.removeAt(seg)
                    }
                }
            }
            is PatchOp.Move -> {
                val node = getAt(root, op.from) ?: error("移動元が存在しません: ${op.from.pretty()}")
                apply(root, PatchOp.Delete(op.from))
                apply(root, PatchOp.Insert(op.to, node))
            }
        }
    }

    private fun locateParent(root: YNode, path: YPath): Pair<YNode, Any> {
        require(path.isNotEmpty()) { "ルートへのパッチは対象外です（文書全体の置換は保存で行います）" }
        val parent = getAt(root, path.dropLast(1))
            ?: error("パスの親が存在しません: ${path.dropLast(1).pretty()}")
        return parent to path.last()
    }

    private fun asMap(parent: YNode, path: YPath): YNode.Map =
        parent as? YNode.Map ?: error("マップではありません: ${path.dropLast(1).pretty()}")

    private fun asSeq(parent: YNode, path: YPath): YNode.Seq =
        parent as? YNode.Seq ?: error("配列ではありません: ${path.dropLast(1).pretty()}")
}

/** 1 ファイルへの変更。base は変更の前提とするディスク内容のハッシュ（省略時は競合チェックなし）。 */
data class FileChange(
    val file: String,          // ワークスペース相対パス
    val baseHash: String?,     // contentHash() の値
    val ops: List<PatchOp>,
)

/** 複数ファイルにまたがる変更の束。 */
data class ChangeSet(val changes: List<FileChange>)

object ChangeSets {

    /** ディスク内容の競合検出に使うハッシュ（SHA-256 の16進表現）。 */
    fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 変更セットの YAML を解析する。
     * ```yaml
     * changes:
     *   - file: requirements/req-001.yaml
     *     base: <sha256>            # 省略可
     *     ops:
     *       - { op: set,    path: title,     value: 新しいタイトル }
     *       - { op: insert, path: tags[0],   value: 重要 }
     *       - { op: delete, path: obsolete }
     *       - { op: move,   from: items[2],  to: items[0] }
     * ```
     */
    fun parse(text: String): ChangeSet {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("変更セットのルートはobjectである必要があります")
        val changes = (raw["changes"] as? List<*> ?: error("changes リストが必要です")).mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("changes[$i]はobjectである必要があります")
            val file = m["file"]?.toString() ?: error("changes[$i].file が必要です")
            val ops = (m["ops"] as? List<*> ?: error("changes[$i].ops リストが必要です")).mapIndexed { j, opEntry ->
                parseOp(opEntry, "changes[$i].ops[$j]")
            }
            FileChange(file = file, baseHash = m["base"]?.toString(), ops = ops)
        }
        return ChangeSet(changes)
    }

    private fun parseOp(entry: Any?, where: String): PatchOp {
        val m = entry as? Map<*, *> ?: error("${where}はobjectである必要があります")
        fun path(key: String): YPath {
            val value = m[key]?.toString() ?: error("$where.$key が必要です")
            return parseYPath(value)
        }
        fun value(): YNode {
            if ("value" !in m.keys.map { it.toString() }) error("$where.value が必要です")
            return YamlIO.fromAny(m["value"])
        }
        return when (val op = m["op"]?.toString()) {
            "set" -> PatchOp.Set(path("path"), value())
            "insert" -> PatchOp.Insert(path("path"), value())
            "delete" -> PatchOp.Delete(path("path"))
            "move" -> PatchOp.Move(path("from"), path("to"))
            else -> error("$where.op は set / insert / delete / move のいずれかである必要があります: $op")
        }
    }
}
