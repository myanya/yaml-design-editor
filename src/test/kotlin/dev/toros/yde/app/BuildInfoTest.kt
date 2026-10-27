package dev.toros.yde.app

import dev.toros.yde.app.cli.runCli
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** バージョン情報（build-info.properties。ビルド時に生成）が読めて、外部・内部の2層になっていること。 */
class BuildInfoTest {
    @Test
    fun `外部バージョンは gradle_properties の appVersion と一致し、内部バージョンは yyyymmdd_ビルド番号の形`() {
        val expected = Path.of("gradle.properties").takeIf { Files.exists(it) }?.let { p ->
            Files.readAllLines(p).firstOrNull { it.startsWith("appVersion=") }?.substringAfter("=")?.trim()
        }
        if (expected != null) assertEquals(expected, BuildInfo.appVersion)
        assertTrue(Regex("""\d{8}\.\d+(-dirty)?""").matches(BuildInfo.buildVersion), "内部バージョン: ${BuildInfo.buildVersion}")
        assertTrue(BuildInfo.commit.isNotBlank())
        assertEquals("v${BuildInfo.appVersion}（build ${BuildInfo.buildVersion}）", BuildInfo.display)
    }

    @Test
    fun `CLI の version は3つの値を YAML で返す`() {
        val out = runCli(arrayOf("version"))
        assertTrue(out.startsWith("appVersion: ${BuildInfo.appVersion}\n"), out)
        assertTrue("buildVersion: ${BuildInfo.buildVersion}\n" in out, out)
        assertTrue("commit: ${BuildInfo.commit}\n" in out, out)
    }
}
