package dev.toros.yde.view.flow

/** フロー原本やビュー定義へ書き込まない、利用者の表示設定（画面が読むので公開する）。 */
enum class FlowLayoutMode(val label: String) {
    GROUPED("グループ"),
    SWIMLANES("スイムレーン"),
    PLAIN("レーンなし"),
}

data class FlowDisplayOptions(
    val mode: FlowLayoutMode = FlowLayoutMode.GROUPED,
    val direction: String? = null,
) {
    fun directionOrDefault(defined: String) = direction ?: defined
}
