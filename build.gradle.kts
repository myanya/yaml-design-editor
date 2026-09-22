import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "dev.toros"
// 外部バージョン（利用者に見せる。メジャー.マイナー.パッチ）。gradle.properties の appVersion をリリース時に手で上げる
version = providers.gradleProperty("appVersion").get()

// 内部バージョン（開発管理用。yyyymmdd.ビルド番号）。既定は git から算出する:
// HEAD のコミット日（yyyymmdd）と、その日の何番目のコミットかをビルド番号にする（同じコミットからは同じ値になる）。
// 未コミットの変更を含むビルドには "-dirty" を付ける。-PbuildVersion=20260923.7 のように手動で指定もできる。
fun git(vararg args: String): String? = runCatching {
    val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
}.getOrNull()

val buildVersion: String = providers.gradleProperty("buildVersion").orNull ?: run {
    val day = git("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
    if (day == null) {
        LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".0"
    } else {
        val n = git("rev-list", "--count", "HEAD", "--since=$day 00:00") ?: "0"
        val dirty = if (git("status", "--porcelain")?.isNotEmpty() == true) "-dirty" else ""
        day.replace("-", "") + "." + n + dirty
    }
}
val gitCommit: String = git("rev-parse", "--short", "HEAD") ?: "unknown"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // 3層コア（移行中はアプリから3coreすべてへ直接依存してよい。ADR-0001/0002）
    implementation(project(":workspace-core"))
    implementation(project(":semantic-core"))
    implementation(project(":yaml-core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")   // Excel(xlsx)のインポート・エクスポート（skill機構）
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

// バージョン情報を資源（build-info.properties）として jar に入れる。アプリは app/BuildInfo.kt で読む
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo by tasks.registering {
    description = "バージョン情報（build-info.properties）を生成する"
    val app = version.toString()
    val build = buildVersion
    val commit = gitCommit
    inputs.property("appVersion", app)
    inputs.property("buildVersion", build)
    inputs.property("commit", commit)
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("build-info.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("appVersion=$app\nbuildVersion=$build\ncommit=$commit\n")
    }
}
sourceSets["main"].resources.srcDir(buildInfoDir)
tasks.named("processResources") { dependsOn(generateBuildInfo) }

// ヘッドレスCLI（UIなしでカタログ・検索・パッチ・検証を実行する）
// 例: ./gradlew runCli --args="catalog samples/sample-workspace" -q
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "ヘッドレスCLIを実行する（dev.toros.yde.app.core.CliKt）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.toros.yde.app.core.CliKt")
}

compose.desktop {
    application {
        mainClass = "dev.toros.yde.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "yaml-design-editor"
            // 配布物のバージョンは外部バージョン（msi・deb）。
            packageVersion = version.toString()
            // macOS（dmg）はプラグインの制約でメジャーが 1 以上でないと構成できない（Windows でのビルドも止まる）。
            // 外部バージョンが 1.0.0 に達するまでは仮の値を使う。1.0.0 以上になれば自動的に外部バージョンそのものになる
            macOS {
                packageVersion = version.toString().takeIf { (it.substringBefore('.').toIntOrNull() ?: 0) > 0 } ?: "1.0.0"
            }
        }
    }
}
