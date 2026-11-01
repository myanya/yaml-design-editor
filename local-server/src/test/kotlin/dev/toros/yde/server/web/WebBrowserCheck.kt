package dev.toros.yde.server.web

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Playwright を同梱資源の実 HTTP 配信へ接続する任意検査。コードは子プロセスの環境だけへ渡す。 */
object WebBrowserCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val fixture = WebHttpFixture()
        try {
            fixture.start()
            val code = fixture.access.webLogins.issue(fixture.register().id)
            val web = Path.of("web-ui").toAbsolutePath()
            val builder = ProcessBuilder(args.single(), web.resolve("node_modules/@playwright/test/cli.js").toString(), "test")
                .directory(web.toFile()).inheritIO()
            builder.environment()["YDE_WEB_URL"] = fixture.url
            builder.environment()["YDE_WEB_CODE"] = code.value
            builder.environment()["PLAYWRIGHT_BROWSERS_PATH"] = web.resolve("build/browsers").toString()
            val process = builder.start()
            try {
                val finished = withContext(Dispatchers.IO) { process.waitFor(90, TimeUnit.SECONDS) }
                check(finished && process.exitValue() == 0) { "ブラウザ検査に失敗しました" }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) } }
            }
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}
