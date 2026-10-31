package dev.toros.yde.server

/** 利用者のアプリ設定。未設定の機能は停止し、ワークスペースの内容からは有効にしない。 */
data class LocalConnectionSettings(
    val apiEnabled: Boolean = false,
    val mcpEnabled: Boolean = false,
    val webEnabled: Boolean = false,
    val port: Int = 18765,
) {
    init { require(port in 1..65535) { "ポート番号は 1〜65535 で指定してください" } }

    val requiresListener: Boolean get() = apiEnabled || mcpEnabled || webEnabled
}
