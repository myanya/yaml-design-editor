package dev.toros.yde.app.core

/** フロー原本やビュー定義へ書き込まない、利用者の表示設定。 */
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
