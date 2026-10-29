package dev.toros.yde.desktop.ui.form

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.source.SourceStructure

/** 確認を開いた時点の配列要素。再読込や並べ替え後に同じ行番号を削除しない。 */
internal class ArrayDeletion(private val document: OpenDocument, private val array: YNode.Seq, val index: Int) {
    private val root = document.root
    val path = root?.let { SourceStructure.pathOfNode(it, array) }
    private val version = document.version
    private val sourceOrder = document.sourceInputOrder
    private val sourceEpoch = document.sourceEpoch
    private val item = array.items.getOrNull(index)

    fun problem(current: OpenDocument, readOnly: Boolean): String? = when {
        readOnly || current.draftBroken -> "閲覧専用のため削除できません。"
        current.hasPendingSourceInput -> "ソースの入力を反映してから、削除し直してください。"
        current !== document || current.root !== root || current.version != version ||
            current.sourceInputOrder != sourceOrder || current.sourceEpoch != sourceEpoch ||
            path == null || root == null || getAt(root, path) !== array ||
            item == null || array.items.getOrNull(index) !== item ->
            "確認中に文書または対象が変わりました。閉じて、削除する要素を選び直してください。"
        else -> null
    }

    /** 書込と履歴は既存のフォーム編集経路へ渡す。確認の作成・取消では変更しない。 */
    fun confirm(
        current: OpenDocument,
        readOnly: Boolean,
        afterDelete: () -> Unit = {},
        markEdited: () -> Unit = { current.markEdited() },
    ): Boolean {
        if (problem(current, readOnly) != null) return false
        array.items.removeAt(index)
        afterDelete()
        markEdited()
        return true
    }
}
