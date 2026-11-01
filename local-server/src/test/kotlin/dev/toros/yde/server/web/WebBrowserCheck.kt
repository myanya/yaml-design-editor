package dev.toros.yde.server.web

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Playwright を同梱資源の実 HTTP 配信へ接続する任意検査。コードは子プロセスの環境だけへ渡す。 */
object WebBrowserCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        for (scenario in listOf("connection", "orders", "todos")) check(args.single(), scenario)
    }

    private suspend fun check(node: String, scenario: String) {
        val fixture = WebHttpFixture()
        try {
            fixture.start(if (scenario == "connection") null else Path.of("samples", scenario))
            val code = fixture.access.webLogins.issue(fixture.register().id)
            val web = Path.of("web-ui").toAbsolutePath()
            val builder = ProcessBuilder(node, web.resolve("node_modules/@playwright/test/cli.js").toString(), "test", "$scenario.spec.ts")
                .directory(web.toFile()).inheritIO()
            builder.environment()["YDE_WEB_URL"] = fixture.url
            builder.environment()["YDE_WEB_CODE"] = code.value
            builder.environment()["YDE_WEB_SCENARIO"] = scenario
            builder.environment()["PLAYWRIGHT_BROWSERS_PATH"] = web.resolve("build/browsers").toString()
            val process = builder.start()
            try {
                val finished = withContext(Dispatchers.IO) { process.waitFor(120, TimeUnit.SECONDS) }
                check(finished && process.exitValue() == 0) { "ブラウザ検査に失敗しました: $scenario" }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) } }
            }
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}
