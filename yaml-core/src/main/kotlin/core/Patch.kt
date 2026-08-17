package core

import model.YNode
import model.YPath
import model.getAt
import model.pretty

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

    /**
     * マップキーの改名（R25-01）。値・キー順・コメントを保ったままキー名だけを変える
     * （Delete＋Insert では末尾へ移動してしまうため一級の操作にする。R8-05 の改名と同じ意味論）。
     */
    data class RenameKey(val path: YPath, val newName: String) : PatchOp()

    /**
     * マップキーの並べ替え（R25-02）。order にあるキーをその順で先頭に置き、残りは元の相対順を保つ
     * （値・コメントは保持）。文書の「スキーマ順へ整列」に使う。path が空のときはルートのマップを並べ替える
     * （ルート置換ではなく順序の入替だけなので、ルート操作禁止の対象外）。
     */
    data class ReorderKeys(val path: YPath, val order: List<String>) : PatchOp()
}

object Patcher {

    /** 1 操作をツリーへ適用する（破壊的）。失敗時は日本語メッセージの IllegalStateException。 */
    fun apply(root: YNode, op: PatchOp) {
        when (op) {
            is PatchOp.Set -> {
                val (parent, seg) = locateParent(root, op.path)
                when (seg) {
                    is String -> {
                        val map = asMap(parent, op.path)
                        op.value.inheritMetaFrom(map.entries[seg])  // 置換してもコメント・表記スタイルを保持する（R8-01）
                        map.entries[seg] = op.value
                    }
                    is Int -> {
                        val seq = asSeq(parent, op.path)
                        if (seg !in seq.items.indices) error("インデックスが範囲外です: ${op.path.pretty()}（要素数 ${seq.items.size}）")
                        op.value.inheritMetaFrom(seq.items[seg])
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
            is PatchOp.RenameKey -> {
                val (parent, seg) = locateParent(root, op.path)
                val map = asMap(parent, op.path)
                val key = seg as? String ?: error("キー改名はマップのキーにのみ使えます: ${op.path.pretty()}")
                if (op.newName.isBlank()) error("改名先のキーが空です: ${op.path.pretty()}")
                if (key !in map.entries) error("パスが存在しません: ${op.path.pretty()}")
                if (op.newName == key) error("改名先が現在のキーと同じです: ${op.path.pretty()}")
                if (op.newName in map.entries) error("既に存在するキーです: ${op.path.dropLast(1).pretty()} の ${op.newName}")
                val entries = map.entries.toList()
                map.entries.clear()
                for ((k, v) in entries) map.entries[if (k == key) op.newName else k] = v
            }
            is PatchOp.ReorderKeys -> {
                val map = (if (op.path.isEmpty()) root else getAt(root, op.path)) as? YNode.Map
                    ?: error("マップではありません: ${op.path.pretty()}")
                val entries = map.entries.toList()
                val head = op.order.filter { it in map.entries }
                map.entries.clear()
                head.forEach { k -> map.entries[k] = entries.first { it.first == k }.second }
                for ((k, v) in entries) if (k !in map.entries) map.entries[k] = v
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
