package dev.toros.yde.cli

import dev.toros.yde.server.client.LocalApiMethod
import dev.toros.yde.server.client.LocalApiRequest
import dev.toros.yde.yaml.YamlIO

internal const val CONNECT_USAGE = """接続モード（同じ PC の API を明示的に利用）:
  connect [--port N] [--token-env NAME] [--format yaml|json|text] <命令>
  workspace / documents / document <文書> / search <語> / entity <ID>
  views / view <ID> / validation [--document 文書] / actions
    読取共通: --revision 版
    一覧: --offset N --limit N（documents / search / views / view / validation / actions）
    view: --date yyyy-MM-dd
  operation plan <操作名> [--target IDまたは文書] [--param 名前=YAML値]...
  operation commit <計画ID> --request-id 要求ID
  operation result <要求ID>
  work begin --mode proposal|direct --title 件名
  work edit <作業ID> <変更セット.yaml または ->
  work validate|submit|plan|discard|result <作業ID>
  work commit <作業ID> --plan-id 計画ID

ポートの既定は 18765。トークンは環境変数 YDE_API_TOKEN（--token-env で名前変更）から取得。
proposal は提出後にデスクトップで承認、direct は plan → commit で直接確定。
計画・確定・再送は明示操作。API が OFF・無権限・通信断でも独立書込へ切り替えない。
出力は API 応答全体（既定 YAML）。終了コード: 0 受付成功 / 1 入力・通信・HTTP 拒否 /
2 検証エラー・確定不可・却下 / 5 保存失敗・未確定。0 でも提案提出は保存完了を意味しない。
"""

internal data class ConnectedCommand(
    val port: Int,
    val tokenEnv: String,
    val format: OutputFormat,
    val request: LocalApiRequest,
    val changesFile: String? = null,
)

/** 引数を既存 API の要求へ写すだけ。編集・検証・権限・適用の判断はサーバへ委譲する。 */
internal fun connectedCommand(args: List<String>): ConnectedCommand {
    val options = ConnectOptions(args)
    val port = options.take("port")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..65535 }
        ?: error("ポート番号は 1〜65535 で指定してください") } ?: 18765
    val tokenEnv = options.take("token-env") ?: "YDE_API_TOKEN"
    require(tokenEnv.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) { "トークンの環境変数名が不正です" }
    val format = when (options.take("format")) {
        null, "yaml" -> OutputFormat.YAML
        "json" -> OutputFormat.JSON
        "text" -> OutputFormat.TEXT
        else -> error("--format は yaml / json / text のいずれかです")
    }
    val words = options.words
    fun count(n: Int) { require(words.size == n) { "命令の引数の数を確認してください（connect help）" } }
    fun id(index: Int): String = words.getOrNull(index)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,180}")) }
        ?: error("ID の形式を確認してください")
    var changesFile: String? = null
    val request = when (val command = words.firstOrNull()) {
        "workspace", "documents", "document", "search", "entity", "views", "view", "validation", "actions" -> {
            val required = mapOf("document" to "document", "search" to "q", "entity" to "id", "view" to "id")
            count(if (command in required) 2 else 1)
            val query = linkedMapOf<String, String>()
            required[command]?.let { query[it] = words[1] }
            options.take("revision")?.let { query["revision"] = it }
            if (command in setOf("documents", "search", "views", "view", "validation", "actions")) {
                for (key in listOf("offset", "limit")) options.take(key)?.let {
                    require(it.matches(Regex("[0-9]{1,5}"))) { "ページ指定は 5 桁以内の非負の整数です" }
                    query[key] = it
                }
            }
            if (command == "validation") options.take("document")?.let { query["document"] = it }
            if (command == "view") options.take("date")?.let {
                require(runCatching { java.time.LocalDate.parse(it) }.isSuccess) { "日付は yyyy-MM-dd で指定してください" }
                query["date"] = it
            }
            LocalApiRequest(LocalApiMethod.GET, listOf(command), query)
        }
        "operation" -> when (words.getOrNull(1)) {
            "plan" -> {
                count(3)
                val body = linkedMapOf<String, Any?>("name" to words[2])
                options.take("target")?.let { body["target"] = it }
                val params = linkedMapOf<String, Any?>()
                for (param in options.parameters()) {
                    val key = param.substringBefore('=')
                    require('=' in param && key.isNotBlank() && key !in params) { "--param は重複しない 名前=YAML値 で指定してください" }
                    params[key] = connectYaml(param.substringAfter('='))
                }
                body["params"] = params
                post(listOf("operations", "plan"), body)
            }
            "commit" -> {
                count(3)
                val requestId = options.required("request-id")
                require(requestId.matches(Regex("[A-Za-z0-9_-]{1,180}"))) { "要求 ID は英数字・_・- の 180 文字以内で指定してください" }
                post(listOf("operations", "commit"), mapOf("planId" to id(2), "requestId" to requestId))
            }
            "result" -> { count(3); LocalApiRequest(LocalApiMethod.GET, listOf("operations", id(2))) }
            else -> error("operation は plan / commit / result のいずれかです")
        }
        "work" -> when (val verb = words.getOrNull(1)) {
            "begin" -> {
                count(2)
                val mode = options.required("mode")
                require(mode in setOf("proposal", "direct")) { "--mode は proposal / direct のいずれかです" }
                post(listOf("works"), mapOf("mode" to mode, "title" to options.required("title")))
            }
            "edit" -> {
                count(4); changesFile = words[3]
                post(listOf("works", id(2), "changes"))
            }
            "validate", "submit", "plan", "discard" -> { count(3); post(listOf("works", id(2), verb)) }
            "commit" -> { count(3); post(listOf("works", id(2), verb), mapOf("planId" to options.required("plan-id"))) }
            "result" -> { count(3); LocalApiRequest(LocalApiMethod.GET, listOf("works", id(2))) }
            else -> error("work は begin / edit / validate / submit / plan / commit / discard / result のいずれかです")
        }
        else -> error("接続モードの命令を確認してください（connect help）")
    }
    options.finish()
    return ConnectedCommand(port, tokenEnv, format, request, changesFile)
}

private fun post(path: List<String>, body: Map<String, Any?> = emptyMap()) = LocalApiRequest(LocalApiMethod.POST, path, body = body)

internal fun connectYaml(text: String): Any? = try { YamlIO.parseToRaw(text) }
    catch (_: Exception) { error("入力 YAML を読み取れませんでした。構文・重複キー・入れ子を確認してください") }

private class ConnectOptions(args: List<String>) {
    val words = mutableListOf<String>()
    private val values = linkedMapOf<String, MutableList<String>>()
    init {
        var i = 0
        while (i < args.size) {
            val value = args[i++]
            if (!value.startsWith("--")) { words += value; continue }
            val key = value.removePrefix("--")
            val next = args.getOrNull(i++)?.takeUnless { it.startsWith("--") || it.isBlank() }
                ?: error("オプションの値を指定してください（connect help）")
            val list = values.getOrPut(key) { mutableListOf() }
            require(list.isEmpty() || key == "param") { "同じオプションを重複指定できません" }
            list += next
        }
    }
    fun take(name: String): String? = values.remove(name)?.single()
    fun required(name: String): String = take(name) ?: error("--$name を指定してください")
    fun parameters(): List<String> = values.remove("param") ?: emptyList()
    fun finish() { require(values.isEmpty()) { "この命令で使えないオプションがあります（connect help）" } }
}
