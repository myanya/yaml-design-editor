package dev.toros.yde.app.view.flow

/** フロー原本やビュー定義へ書き込まない、利用者の表示設定（画面が読むので公開する）。 */
enum class FlowLayoutMode(val label: String) {
    GROUPED("グループ"),
    SWIMLANES("スイムレーン"),
    PLAIN("レーンなし"),
}

/**
 * 自動配置の調整（既定はどれも選ばない）。効くのは自動配置と「再配置」で、保存済みの位置は動かさない。
 * key は配置の記録を分ける選択条件に入れる名前で、変えると記録が別になる。
 */
enum class FlowLayoutOption(val key: String, val label: String, val description: String) {
    COMPACT("compact", "間隔を詰める", "箱どうしと線の通り道の余白を小さくする"),
    NO_LABEL_SEATS("no-label-seats", "ラベルの席を取らない", "線のラベルの場所を配置で確保せず、配置の後で線の近くへ置く（狭い所では折り返す）"),
    WRAP("wrap", "長い一本道を折り返す", "流れの向きに長く続く図を、縦横の比が近くなるよう折り返す"),
}

data class FlowDisplayOptions(
    val mode: FlowLayoutMode = FlowLayoutMode.GROUPED,
    val direction: String? = null,
    val options: Set<FlowLayoutOption> = emptySet(),
    /** 線の中央のラベル（条件）を隠す。描き方だけを変え、配置と位置の記録には効かない。 */
    val hideLabels: Boolean = false,
) {
    fun directionOrDefault(defined: String) = direction ?: defined
}
