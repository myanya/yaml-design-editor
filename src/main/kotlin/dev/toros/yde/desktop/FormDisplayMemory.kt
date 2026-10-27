package dev.toros.yde.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.toros.yde.yaml.model.DocumentId

/** 表示した事実と明示指定を分離する。YAML・編集履歴には書き込まない。 */
class FormDisplayMemory {
    internal val specifiedModes = mutableStateMapOf<DocumentId, FormDisplayMode>()
    var fixed by mutableStateOf(false)
        private set
    var currentMode by mutableStateOf(FormDisplayMode.SELECTED)
        private set

    fun specified(document: DocumentId): FormDisplayMode? = specifiedModes[document]

    /** 描画前にも読める決定規則。読むだけでは未指定のファイルに指定を作らない。 */
    fun modeFor(document: DocumentId): FormDisplayMode =
        if (fixed) currentMode else specified(document) ?: currentMode

    /** フォームを実際に表示したときだけ、次のファイルへ引き継ぐ方式を更新する。 */
    fun show(document: DocumentId) {
        currentMode = modeFor(document)
    }

    /** 方式ボタン・ツリーの×。同じ方式を選び直した場合も明示指定になる。 */
    fun choose(document: DocumentId, mode: FormDisplayMode) {
        specifiedModes[document] = mode
        currentMode = mode
    }

    /** 指定パネル。固定中はファイルの指定だけを変え、現在の表示は変えない。 */
    fun setSpecified(document: DocumentId, mode: FormDisplayMode?) {
        show(document)
        if (mode == null) specifiedModes.remove(document) else specifiedModes[document] = mode
        if (!fixed && mode != null) currentMode = mode
    }

    fun toggleFixed(document: DocumentId) {
        show(document)
        fixed = !fixed
        show(document)
    }

    fun clear() {
        specifiedModes.clear()
        fixed = false
        currentMode = FormDisplayMode.SELECTED
    }
}
