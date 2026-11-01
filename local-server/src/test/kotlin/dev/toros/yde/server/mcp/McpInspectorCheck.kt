package dev.toros.yde.server.mcp

import dev.toros.yde.server.LocalConnectionSettings
import dev.toros.yde.server.protocol.externalJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Inspector 自体を子プロセスとして使う任意検査。資格情報は使い捨ての設定へ書き、引数やログへ出さない。 */
object McpInspectorCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && Files.isRegularFile(Path.of(args[0]))) { "取得済みの Inspector CLI を指定してください" }
        val fixture = McpHttpFixture()
        try {
            fixture.start()
            val registration = fixture.register()
            var token = fixture.access.issueBearer(registration.id)
            val config = fixture.root.resolve("inspector.json")
            val output = fixture.root.resolve("inspector-out.json")
            val errors = fixture.root.resolve("inspector-err.txt")
            val runner = fixture.root.resolve("inspector-runner.mjs")
            val argumentsFile = fixture.root.resolve("inspector-args.json")
            requireNotNull(javaClass.getResourceAsStream("/mcp/inspector-runner.mjs")).use { Files.copy(it, runner) }
            suspend fun invoke(vararg command: String, successful: Boolean = true): JsonObject {
                val settings = mapOf("mcpServers" to mapOf("yde-test" to mapOf("type" to "streamable-http", "url" to fixture.url,
                    "headers" to mapOf("Authorization" to "Bearer ${token.value}"),
                    "connectionTimeout" to 10_000, "requestTimeout" to 10_000)))
                Files.writeString(config, externalJson(settings).toString())
                Files.writeString(argumentsFile, externalJson(listOf("--cli", "--config", config.toString(), "--server", "yde-test",
                    "--stored-auth-only", "--quiet", "--format", "json") + command).toString())
                val builder = ProcessBuilder(args[1], runner.toString(), args[0], argumentsFile.toString())
                    .redirectOutput(output.toFile()).redirectError(errors.toFile())
                builder.environment()["MCP_STORAGE_DIR"] = fixture.root.resolve("inspector-state").toString()
                builder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
                builder.environment()["no_proxy"] = "127.0.0.1,localhost"
                val process = builder.start()
                try {
                    val completed = withContext(Dispatchers.IO) { process.waitFor(25, TimeUnit.SECONDS) }
                    check(completed) { "Inspector が時間内に終了しませんでした" }
                    val stderr = Files.readString(errors).replace(token.value, "[非表示]")
                    if (!successful) {
                        check(process.exitValue() != 0) { "停止中の MCP が成功しました" }
                        return JsonObject(emptyMap())
                    }
                    check(process.exitValue() == 0) {
                        val stdout = Files.readString(output).replace(token.value, "[非表示]")
                        "Inspector 終了コード ${process.exitValue()}: ${stderr.take(1_000)} ${stdout.take(2_000)}"
                    }
                    return Json.parseToJsonElement(Files.readString(output)).jsonObject.getValue("result").jsonObject
                } finally {
                    if (process.isAlive) { process.destroyForcibly(); withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) } }
                    Files.deleteIfExists(config)
                }
            }
            suspend fun call(name: String, values: Map<String, Any?> = emptyMap()): JsonObject {
                val command = mutableListOf("--method", "tools/call", "--tool-name", name)
                command += listOf("--tool-args-json", externalJson(values).toString())
                val result = invoke(*command.toTypedArray())
                assertFalse(result["isError"]?.jsonPrimitive?.booleanOrNull == true, result.toString())
                return result.getValue("structuredContent").jsonObject.getValue("data").jsonObject
            }
            assertEquals(17, invoke("--method", "tools/list").getValue("tools").jsonArray.size)
            call("list_documents")
            val work = call("begin_work", mapOf("title" to "Inspectorの変更", "mode" to "direct")).text("id")
            call("edit_work", mapOf("workId" to work, "changes" to changes("Inspectorで確定")))
            call("validate_work", mapOf("workId" to work))
            val plan = call("plan_work", mapOf("workId" to work)).text("planId")
            val result = call("commit_work", mapOf("workId" to work, "planId" to plan))
            assertEquals(result, call("work_result", mapOf("workId" to work)))
            assertTrue(Files.readString(fixture.root.resolve("a.yaml")).contains("Inspectorで確定"))
            println("Inspector: 初期化・一覧・参照・編集・検証・計画・確定・再接続後の結果照会 成功")

            // API の待受だけを残し、MCP OFF が既存の外部クライアントにも効くことを確認する。
            fixture.configure(LocalConnectionSettings(apiEnabled = true, mcpEnabled = true))
            fixture.configure(LocalConnectionSettings(apiEnabled = true))
            invoke("--method", "tools/list", successful = false)
            fixture.configure(LocalConnectionSettings(mcpEnabled = true))
            token = fixture.access.issueBearer(registration.id)
            assertEquals(result, call("commit_work", mapOf("workId" to work, "planId" to plan)))
            println("Inspector: MCP停止時の拒否・再発行と再接続・同じ確定の再送 成功")
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}
