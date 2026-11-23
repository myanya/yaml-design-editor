package dev.toros.yde.desktop.ui.form

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pathOfNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.desktop.OpenDocument

/** 確認を開いた時点の map のキー。再読込やキー名の変更の後に、同じ名前の別の値を削除しない（配列の `ArrayDeletion` と同じ決まり）。 */
internal class MapKeyDeletion(private val document: OpenDocument, private val map: YNode.Map, val key: String) {
    private val root = document.root
    val path = root?.let { pathOfNode(it, map) }
    private val version = document.version
    private val sourceOrder = document.sourceInputOrder
    private val sourceEpoch = document.sourceEpoch
    private val value = map.entries[key]

    fun problem(current: OpenDocument, readOnly: Boolean): String? = when {
        readOnly || current.draftBroken -> "閲覧専用のため削除できません。"
        current.hasPendingSourceInput -> "ソースの入力を反映してから、削除し直してください。"
        current !== document || current.root !== root || current.version != version ||
            current.sourceInputOrder != sourceOrder || current.sourceEpoch != sourceEpoch ||
            path == null || root == null || getAt(root, path) !== map ||
            value == null || map.entries[key] !== value ->
            "確認中に文書または対象が変わりました。閉じて、削除するキーを選び直してください。"
        else -> null
    }

    /** 書込と履歴は既存のフォーム編集経路へ渡す。確認の作成・取消では変更しない。 */
    fun confirm(
        current: OpenDocument,
        readOnly: Boolean,
        markEdited: () -> Unit = { current.markEdited() },
    ): Boolean {
        if (problem(current, readOnly) != null) return false
        map.entries.remove(key)
        markEdited()
        return true
    }
}
