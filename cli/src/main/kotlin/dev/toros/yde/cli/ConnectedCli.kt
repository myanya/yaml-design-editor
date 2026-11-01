package dev.toros.yde.cli

import dev.toros.yde.server.client.LocalApiClient
import dev.toros.yde.server.client.LocalApiConnection
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** HTTP が成功しても保存・検証が成功とは限らない。元の結果は stdout に残せる形で返す。 */
internal class ConnectedResultFailure(val output: String, val exitCode: Int) : RuntimeException(
    if (exitCode == 2) "検証エラー・確定不可・却下の結果です。応答を確認してください"
    else "保存成功を確認できません。応答と同じ ID の結果を確認し、新しい作業として再実行しないでください",
)

internal fun runConnectedCli(
    args: List<String>,
    environment: (String) -> String? = System::getenv,
    connection: (Int, String) -> LocalApiConnection = { port, token -> LocalApiClient(port, token) },
    readChanges: (String) -> String = ::readConnectedChanges,
): String {
    if (args.isEmpty() || args == listOf("help") || args == listOf("--help")) return CONNECT_USAGE
    val command = connectedCommand(args)
    val token = environment(command.tokenEnv)?.takeIf { it.isNotBlank() }
        ?: error("API トークンの環境変数を設定してください（既定 YDE_API_TOKEN、--token-env で名前変更）")
    val request = command.changesFile?.let { file ->
        val raw = connectYaml(readChanges(file))
        require(raw is Map<*, *> && raw.keys.all { it is String }) { "変更セットは文字列キーのマップで指定してください" }
        @Suppress("UNCHECKED_CAST")
        command.request.copy(body = raw as Map<String, Any?>)
    } ?: command.request
    val response = connection(command.port, token).use { client -> runBlocking { client.execute(request) } }
    val output = render(CliResult.Data(response), command.format)
    val code = connectedExitCode(response)
    if (code != 0) throw ConnectedResultFailure(output, code)
    return output
}

/** API の判定だけを終了コードへ写す。検証や保存規則は CLI で計算しない。 */
internal fun connectedExitCode(response: Map<String, Any?>): Int {
    val data = response["data"] as? Map<*, *> ?: error("API の応答形式を確認できませんでした")
    if (data["state"] in setOf("failed", "partial", "unknown", "reconciled", "applying", "received")) return 5
    if (data["state"] == "rejected" || data["valid"] == false || data["canCommit"] == false) return 2
    // 読取 validation は返されたページの指摘だけを扱う。全件取得や追加の検証はしない。
    val items = data["items"] as? List<*> ?: return 0
    if (items.any { item -> ((item as? Map<*, *>)?.get("issues") as? List<*>)?.any {
        (it as? Map<*, *>)?.get("severity") == "error"
    } == true }) return 2
    return 0
}

private fun readConnectedChanges(file: String): String {
    val limit = 2 * 1024 * 1024
    val bytes = try {
        if (file == "-") System.`in`.readNBytes(limit + 1)
        else Files.newInputStream(Path.of(file)).use { it.readNBytes(limit + 1) }
    } catch (_: Exception) { error("変更セットの入力を読み取れませんでした") }
    require(bytes.size <= limit) { "変更セットは 2 MiB 以内で指定してください" }
    return try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { error("変更セットは UTF-8 で指定してください") }
}
