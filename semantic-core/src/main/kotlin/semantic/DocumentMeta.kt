package semantic

import model.YNode
import model.scalarText

/**
 * ルート予約メタデータ `_document` の読み取り（3層コア移行 Phase 6C で IdIndex companion から移動）。
 * ルート側 IdIndex の companion（documentIdOf / documentNameOf / documentDescriptionOf）はここへ委譲する。
 */
object DocumentMeta {

    /** 文書の論理ID（`_document.id` ＞ fallback＝相対パス）。 */
    fun idOf(root: YNode, fallback: String): String {
        val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
        return scalarText(metadata?.entries?.get("id")) ?: fallback
    }

    /** ルートの予約メタデータに宣言された表示用の文書名。 */
    fun nameOf(root: YNode): String? {
        val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
        return scalarText(metadata?.entries?.get("name"))?.takeIf { it.isNotBlank() }
    }

    /** ルートの予約メタデータに宣言された、文書全体の概要。 */
    fun descriptionOf(root: YNode): String? {
        val metadata = (root as? YNode.Map)?.entries?.get("_document") as? YNode.Map
        return scalarText(metadata?.entries?.get("description"))?.takeIf { it.isNotBlank() }
    }
}
