package edit

import core.PatchOp
import core.Patcher
import model.YNode
import model.YPath
import model.deepCopy
import model.getAt
import model.pretty

/**
 * 型付き編集操作（3層コア移行 Phase 4・YM-007b。計画書§4.1）。
 * 座標は既存のパッチ語彙（PatchOp / ChangeSets）と同じ「対象そのものを指す YPath」。
 * 適用は破壊的で、対象は copy-on-write 規律（ADR-0003）で複製済みの候補木であること。
 * 各操作は適用と同時に逆操作列を返す。逆操作のスタックを積むのは workspace-core の
 * 履歴だけであり、yaml-core は機構のみ提供する（ADR-0006）。
 */
sealed interface YamlOperation {

    /**
     * スカラー値の設定（無ければ作成）。value の値域はスカラーの閉集合
     * （String / Boolean / Int / Long / Double / null）で、Kotlin の型が正（§4.1 スカラー値と表記の契約）。
     * 既存ノードを置換した場合、styleHint・コメントは継承される（R8-01）。
     */
    data class SetScalar(val path: YPath, val value: Any?) : YamlOperation

    /** ノードの置換または作成。表記の明示制御（styleHint 付き Scalar 等）はこちらを使う。 */
    data class Replace(val path: YPath, val value: YNode) : YamlOperation

    /** 挿入。配列は指定位置へ挿入（末尾 = 要素数）、マップは新規キーのみ（既存キーはエラー）。 */
    data class Insert(val path: YPath, val value: YNode) : YamlOperation

    /** 削除。存在しないパスはエラー。 */
    data class Remove(val path: YPath) : YamlOperation

    /** 移動。from を削除してから to へ挿入する（to は削除後の状態で評価される）。 */
    data class Move(val from: YPath, val to: YPath) : YamlOperation

    /** マップキーの改名（値・キー順・コメントを保持）。 */
    data class RenameKey(val path: YPath, val newName: String) : YamlOperation

    /** マップキーの並べ替え（order のキーを先頭へ、残りは相対順を維持）。 */
    data class ReorderKeys(val path: YPath, val order: List<String>) : YamlOperation
}

/** 複数操作の一括適用単位（1回のフォーム操作・1文書分の変更など。計画書§5）。 */
data class EditBatch(val operations: List<YamlOperation>)

/**
 * 操作の適用器。実装は Patcher（core/Patch.kt）機構の上の薄い層で、
 * 逆操作の生成と「失敗時に文書を部分変更のまま残さない」ことを契約に加える。
 */
object YamlEdit {

    /**
     * 1操作を適用し、逆操作列（順に適用すると元へ戻る）を返す。
     * 逆操作が保持する旧ノードは deepCopy 済みの所有コピー（ADR-0003）。
     * 失敗時は文書を変更せず例外を投げる（IllegalArgumentException / IllegalStateException）。
     * マップ中間からの Remove / Move の逆操作には、キー順復元の ReorderKeys が付く
     * （Insert はマップ末尾へ入るため、順序まで戻すのに必要）。
     */
    fun apply(root: YNode, op: YamlOperation): List<YamlOperation> = when (op) {
        is YamlOperation.SetScalar -> {
            requireScalarValue(op.value)
            applySet(root, op.path, YNode.Scalar(op.value))
        }
        is YamlOperation.Replace -> applySet(root, op.path, op.value)
        is YamlOperation.Insert -> {
            Patcher.apply(root, PatchOp.Insert(op.path, op.value))
            listOf(YamlOperation.Remove(op.path))
        }
        is YamlOperation.Remove -> {
            val old = getAt(root, op.path) ?: error("パスが存在しません: ${op.path.pretty()}")
            val oldCopy = old.deepCopy()
            val orderRestore = mapOrderRestore(root, op.path)
            Patcher.apply(root, PatchOp.Delete(op.path))
            listOf(YamlOperation.Insert(op.path, oldCopy)) + orderRestore
        }
        is YamlOperation.Move -> {
            val node = getAt(root, op.from) ?: error("移動元が存在しません: ${op.from.pretty()}")
            val orderRestore = mapOrderRestore(root, op.from)
            Patcher.apply(root, PatchOp.Delete(op.from))
            try {
                Patcher.apply(root, PatchOp.Insert(op.to, node))
            } catch (e: Exception) {
                // 挿入に失敗したら削除を巻き戻す（部分変更を残さない）
                Patcher.apply(root, PatchOp.Insert(op.from, node))
                orderRestore.forEach { apply(root, it) }
                throw e
            }
            listOf(YamlOperation.Move(op.to, op.from)) + orderRestore
        }
        is YamlOperation.RenameKey -> {
            Patcher.apply(root, PatchOp.RenameKey(op.path, op.newName))
            listOf(YamlOperation.RenameKey(op.path.dropLast(1) + op.newName, op.path.last() as String))
        }
        is YamlOperation.ReorderKeys -> {
            val map = (if (op.path.isEmpty()) root else getAt(root, op.path)) as? YNode.Map
                ?: error("マップではありません: ${op.path.pretty()}")
            val fullOrder = map.entries.keys.toList()
            Patcher.apply(root, PatchOp.ReorderKeys(op.path, op.order))
            listOf(YamlOperation.ReorderKeys(op.path, fullOrder))
        }
    }

    /**
     * EditBatch の一括適用。全操作成功で「順に適用すると元へ戻る」逆操作列を返す。
     * 途中で失敗した場合は適用済み分を巻き戻してから例外を投げる（文書を部分変更のまま残さない）。
     */
    fun applyBatch(root: YNode, batch: EditBatch): List<YamlOperation> {
        val inverses = mutableListOf<YamlOperation>()
        for (op in batch.operations) {
            try {
                inverses.addAll(0, apply(root, op))
            } catch (e: Exception) {
                inverses.forEach { inv -> apply(root, inv) }
                throw e
            }
        }
        return inverses
    }

    /** SetScalar の値域検査（§4.1: スカラーの閉集合。Kotlin の型が正）。 */
    private fun requireScalarValue(value: Any?) {
        val ok = value == null || value is String || value is Boolean ||
            value is Int || value is Long || value is Double
        require(ok) {
            "SetScalar の値は String / Boolean / Int / Long / Double / null のいずれかである必要があります: ${value!!::class.simpleName}"
        }
    }

    /** Set / Replace 共通: 既存置換は旧ノードの所有コピーで Replace、新規作成は Remove が逆操作。 */
    private fun applySet(root: YNode, path: YPath, value: YNode): List<YamlOperation> {
        val old = getAt(root, path)
        val inverse = if (old != null) {
            listOf(YamlOperation.Replace(path, old.deepCopy()))
        } else {
            listOf(YamlOperation.Remove(path))
        }
        Patcher.apply(root, PatchOp.Set(path, value))
        return inverse
    }

    /**
     * マップ中間からの削除・移動を戻すときのキー順復元。親がマップで対象がキーのときだけ、
     * 削除前の全キー順（対象を含む）を ReorderKeys として返す。
     */
    private fun mapOrderRestore(root: YNode, path: YPath): List<YamlOperation> {
        if (path.lastOrNull() !is String) return emptyList()
        val parentPath = path.dropLast(1)
        val parent = (if (parentPath.isEmpty()) root else getAt(root, parentPath)) as? YNode.Map ?: return emptyList()
        return listOf(YamlOperation.ReorderKeys(parentPath, parent.entries.keys.toList()))
    }
}
