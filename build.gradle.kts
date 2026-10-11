import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.1"
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
    // Material3 は CMP 1.9 から本体と別の版（別名 compose.material3 は非推奨）。安定版を直接指定する
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    // Material Icons（拡張）は 1.7.3 で凍結された配布物（更新されない。移行先は Material Symbols＝BACKLOG）
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    // 外部の UI ライブラリは ui/ の 1 ファイルで包んで使う（差し替えられるように。CONVENTIONS「Compose UI の書き方」）
    implementation("io.github.vinceglb:filekit-dialogs-compose:0.16.0")        // OS ネイティブのファイル・フォルダ選択（ui/FilePickers.kt）
    implementation("sh.calvin.reorderable:reorderable:3.1.0")                   // ドラッグで並べ替え（ui/Reorder.kt）
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")   // Markdown の描画（ui/MarkdownView.kt）
    implementation("com.patrykandpatrick.vico:compose-m3:3.3.1")              // グラフ（ui/Charts.kt）
    // 節点と辺の配置（ELK＝Eclipse Layout Kernel。ui/GraphLayouts.kt に閉じる。応力配置は alg.force に同梱・spore は重なり除去）
    val elk = "0.12.0"
    implementation("org.eclipse.elk:org.eclipse.elk.core:$elk")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:$elk")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.force:$elk")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.radial:$elk")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.spore:$elk")
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.36.0")   // ELK の POM に書かれていない実行時依存（配置の目録を作るときに要る）
    constraints {
        // ELK は Guava を「15.0 以上」の幅で要求する。ビルドのたびに版が動かないよう固定する
        implementation("com.google.guava:guava") { version { strictly("33.7.2-jre") } }
    }
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")   // Excel(xlsx)のインポート・エクスポート（skill機構）
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)   // 外部ライブラリ（Markdown の描画）が Java 21 の class を要求する。無ければ Gradle（foojay）が自動で取得する
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

// 等幅フォント Cica の同梱（ui/Common.kt の AppFonts.Mono）。TTF は git に入れない（.gitignore の *.ttf）ので、
// ビルド時に配布物を取得して検証し、資源（fonts/cica/）へ入れる。取得した zip は Gradle のユーザーホームに置き、clean でも消えない
val cicaVersion = "5.0.3"
val cicaZipUrl = "https://github.com/miiton/Cica/releases/download/v$cicaVersion/Cica_v$cicaVersion.zip"
val cicaSha256 = "cbd1bcf1f3fd1ddbffe444369c76e42529add8538b25aeb75ab682d398b0506f"
val cicaFiles = listOf("Cica-Regular.ttf", "Cica-Bold.ttf", "LICENSE.txt", "COPYRIGHT.txt")
val fontsDir = layout.buildDirectory.dir("generated/fonts")
val downloadFonts by tasks.registering {
    description = "等幅フォント Cica を取得して資源（fonts/cica/）に入れる"
    val cache = File(gradle.gradleUserHomeDir, "caches/yaml-design-editor/Cica_v$cicaVersion.zip")
    val url = cicaZipUrl
    val sha = cicaSha256
    val files = cicaFiles
    val outDir = fontsDir.map { it.dir("fonts/cica").asFile }
    inputs.property("url", url)
    inputs.property("sha256", sha)
    inputs.property("files", files)
    outputs.dir(fontsDir)
    doLast {
        fun sha256(f: File): String =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!cache.exists() || sha256(cache) != sha) {
            cache.parentFile.mkdirs()
            runCatching {
                URI(url).toURL().openStream().use { input -> cache.outputStream().use { input.copyTo(it) } }
            }.getOrElse { throw GradleException("フォント Cica の取得に失敗しました（$url）。ネットワーク接続を確認してください", it) }
            val actual = sha256(cache)
            if (actual != sha) {
                cache.delete()
                throw GradleException("フォント Cica の検証に失敗しました（sha256 が一致しません: $actual）")
            }
        }
        val out = outDir.get()
        out.deleteRecursively()
        out.mkdirs()
        ZipInputStream(cache.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in files) out.resolve(name).outputStream().use { zin.copyTo(it) }
            }
        }
        val missing = files.filter { !out.resolve(it).exists() }
        if (missing.isNotEmpty()) throw GradleException("フォント Cica の配布物に $missing がありません")
    }
}
sourceSets["main"].resources.srcDir(fontsDir)
tasks.named("processResources") { dependsOn(downloadFonts) }

// 内蔵ヘルプ: 利用者マニュアル（docs/manual/*.md）をそのまま資源 help/ に入れる（アプリは app/Help.kt で読む）。
// 一覧は index.txt（資源はディレクトリを列挙できないため、ビルド時に作る）
val helpDir = layout.buildDirectory.dir("generated/help")
val copyHelp by tasks.registering(Copy::class) {
    description = "利用者マニュアル（docs/manual/*.md）を内蔵ヘルプの資源に入れる"
    from(layout.projectDirectory.dir("docs/manual")) { include("*.md") }
    into(helpDir.map { it.dir("help") })
    doLast {
        val dir = helpDir.get().dir("help").asFile
        val names = dir.listFiles { f -> f.extension == "md" }.orEmpty().map { it.name }.sorted()
        dir.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
sourceSets["main"].resources.srcDir(helpDir)
tasks.named("processResources") { dependsOn(copyHelp) }

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
        // run と packageDistributionForCurrentOS が使う JDK を toolchain（21）に合わせる。
        // 既定は Gradle を動かしている JDK で、それが 17 だと Java 21 向けのライブラリが読めずに起動に失敗する
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
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
