// desktop: 入口の 1 つ（Compose の画面。ADR-0013）。画面・画面の状態・セッションの Compose への写し・拡張の登録の一覧（desktop/ext）。
// 配布物（同梱ランタイム・フォント・内蔵ヘルプ・ライセンス）と Windows の ZIP リリースもここで作る。
// Compose を使うので共通の設定（yde.kotlin-module）は当てない（Compose の版と JUnit 4 が絡む）
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm")   // 版は buildSrc/build.gradle.kts（convention plugin と同じ Kotlin。ここに版を書くと Gradle が拒む）
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"   // buildSrc の Kotlin と同じ版にそろえる
    id("org.jetbrains.compose") version "1.12.1"
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

group = "dev.toros"
// 外部バージョン（利用者に見せる。メジャー.マイナー.パッチ）。gradle.properties の appVersion をリリース時に手で上げる
version = providers.gradleProperty("appVersion").get()

// 内部バージョン（yyyymmdd.ビルド番号）とコミットの算出は buildSrc の GitInfo（app-core の build-info.properties と共用）
fun git(vararg args: String): String? = GitInfo.git(rootDir, *args)
val buildVersion: String = GitInfo.buildVersion(rootDir, providers.gradleProperty("buildVersion").orNull)

// リポジトリの根。マニュアル・ライセンス・配布の案内・サンプル・検査スクリプトはここから読む
val repo: Directory = rootProject.layout.projectDirectory

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // 応用層（app-core・agent）。カーネルの 3 つにも直接依存してよい（ADR-0013。共通の語彙＝文書 ID・YNode・スキーマの宣言を使うため）
    implementation(project(":app-core"))
    implementation(project(":agent"))
    // 拡張（入出力の形式）。登録は desktop/ext/DesktopExtensions.kt の一覧
    implementation(project(":format-text"))
    implementation(project(":format-xlsx"))
    implementation(project(":format-docx"))
    implementation(project(":workspace-core"))
    implementation(project(":semantic-core"))
    implementation(project(":yaml-core"))

    implementation(compose.desktop.currentOs)
    // Material3 は CMP 1.9 から本体と別の版（別名 compose.material3 は非推奨）。安定版を直接指定する
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    // Material Icons（拡張）は 1.7.3 で凍結された配布物（更新されない。移行先は Material Symbols＝BACKLOG）
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    // 外部の UI ライブラリは ui/ の 1 ファイルで包んで使う（差し替えられるように。CONVENTIONS「Compose UI の書き方」）
    implementation("io.github.vinceglb:filekit-dialogs-compose:0.16.0")        // OS ネイティブのファイル・フォルダ選択（ui/common/FilePickers.kt）
    implementation("sh.calvin.reorderable:reorderable:3.1.0")                   // ドラッグで並べ替え（ui/common/Reorder.kt）
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")   // Markdown の描画（ui/common/MarkdownView.kt）
    implementation("com.patrykandpatrick.vico:compose-m3:3.3.1")              // グラフ（ui/view/Charts.kt）
    // 節点と辺の配置（ELK＝Eclipse Layout Kernel。ui/graph/GraphLayouts.kt に閉じる。応力配置は alg.force に同梱・spore は重なり除去）
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
        implementation("org.apache.logging.log4j:log4j-api:2.25.5") {
            because("POI の推移依存も非有限値の JSON 化の修正版へ揃える")
        }
    }
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // Compose の UI テスト（runComposeUiTest。画面無しで描いて semantics で確かめる。R47-50）。版は compose プラグインと同じ
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.1")
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")   // DistributionSmoke が Office の文書を組み立てる（本体は format-xlsx / format-docx）
}

kotlin {
    jvmToolchain(21)   // 外部ライブラリ（Markdown の描画）が Java 21 の class を要求する。無ければ Gradle（foojay）が自動で取得する
}

tasks.withType<Test>().configureEach {
    // ./gradlew test --tests '…' で絞ったとき、一致するテストが無いモジュールを失敗にしない（各モジュールの build.gradle.kts も同じ）
    filter.isFailOnNoMatchingTests = false
    // テストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む（ほかのモジュールと同じ作業ディレクトリ）
    workingDir = rootDir
}

// 等幅フォント Cica の同梱（ui/common/Common.kt の AppFonts.Mono）。TTF は git に入れない（.gitignore の *.ttf）ので、
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

// 内蔵ヘルプ: 利用者マニュアル（docs/manual/*.md）をそのまま資源 help/ に入れる（アプリは desktop/Help.kt で読む）。
// 一覧は index.txt（資源はディレクトリを列挙できないため、ビルド時に作る）
val helpDir = layout.buildDirectory.dir("generated/help")
val copyHelp by tasks.registering(Copy::class) {
    description = "利用者マニュアル（docs/manual/*.md）を内蔵ヘルプの資源に入れる"
    val outDir = helpDir.map { it.dir("help") }  // 実行時の処理からスクリプトの値を直接参照しない（構成キャッシュの再利用で復元できないため）
    from(repo.dir("docs/manual")) { include("*.md") }
    into(outDir)
    doLast {
        val dir = outDir.get().asFile
        val names = dir.listFiles { f -> f.extension == "md" }.orEmpty().map { it.name }.sorted()
        dir.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
sourceSets["main"].resources.srcDir(helpDir)
tasks.named("processResources") { dependsOn(copyHelp) }

// ソース公開時の LICENSE を、本体 JAR にも引き継ぐ。
tasks.named<ProcessResources>("processResources") {
    from(repo.file("LICENSE")) { into("META-INF") }
}

licenseReport {
    projects = arrayOf(project)
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf(com.github.jk1.license.render.TextReportRenderer(), com.github.jk1.license.render.JsonReportRenderer())
}

val distributionResources = layout.buildDirectory.dir("generated/distribution-resources")
val prepareDistributionLicenses by tasks.registering(Sync::class) {
    description = "配布物へ本体・依存・フォントのライセンスとソース取得案内を同梱する"
    dependsOn("generateLicenseReport", downloadFonts)
    from(repo.file("LICENSE"), repo.file("THIRD-PARTY-NOTICES.txt"))
    from(repo.dir("licenses/upstream")) { into("upstream") }
    from(layout.buildDirectory.dir("reports/dependency-license")) { into("dependencies") }
    from(fontsDir.map { it.dir("fonts/cica") }) { include("LICENSE.txt", "COPYRIGHT.txt"); into("cica") }
    into(distributionResources.map { it.dir("common/licenses") })
}

// 対象版を明記したソース取得先。JAR 内の著作権・NOTICE・about 等も元のまま保持する。
val runtimeCoordinates = configurations.named("runtimeClasspath").map { config ->
    config.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        .filter { it.group != "dev.toros" }.map { "${it.group}:${it.name}:${it.version}" }.distinct().sorted()
}
val sourceIndex = layout.buildDirectory.file("generated/source-index/SOURCES.txt")
val generateSourceIndex by tasks.registering {
    val coordinates = runtimeCoordinates
    val output = sourceIndex
    inputs.property("coordinates", coordinates)
    outputs.file(output)
    doLast {
        val lines = coordinates.get().map { id ->
            val (group, name, version) = id.split(':')
            val repository = if (group.startsWith("androidx.")) "https://dl.google.com/dl/android/maven2" else "https://repo.maven.apache.org/maven2"
            "$id\n$repository/${group.replace('.', '/')}/$name/$version/$name-$version-sources.jar"
        }
        output.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n\n") + "\n") }
    }
}
prepareDistributionLicenses { dependsOn(generateSourceIndex); from(sourceIndex) }
tasks.matching { it.name in setOf("prepareAppResources", "prepareReleaseAppResources", "createDistributable", "createReleaseDistributable") }.configureEach {
    dependsOn(prepareDistributionLicenses)
    inputs.dir(distributionResources)
}

tasks.register<Exec>("verifyWindowsDistribution") {
    description = "Windows 配布ランタイムで HTTP・Office・グラフ・同梱表示を検査する"
    group = "verification"
    dependsOn("testClasses", "createDistributable")
    val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        repo.file("scripts/test-distribution.ps1").asFile.absolutePath,
        "-JdkHome", jdk.get().metadata.installationPath.asFile.absolutePath)
}

// ZIP 配布は通常ビルドを使い、公開前に展開後のランタイムと版の一致も検査する。
val distributionPreview = providers.gradleProperty("distributionPreview").map { it.toBoolean() }.getOrElse(false)
val windowsZipName = "yaml-design-editor-$version-windows-x64" + if (distributionPreview) "-preview" else ""
val windowsReleaseDir = layout.buildDirectory.dir("release/$windowsZipName")
val windowsImage = layout.buildDirectory.dir("compose/binaries/main/app/yaml-design-editor")
val windowsReleaseInfo = layout.buildDirectory.dir("generated/windows-release")
val releaseCommit = git("rev-parse", "HEAD") ?: "unknown"
val releaseStatus = git("status", "--porcelain")
val checkWindowsRelease by tasks.registering {
    doLast {
        require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "Windows x64 の JDK でビルドしてください"
        }
        require(releaseCommit.matches(Regex("[0-9a-f]{40}")) && releaseStatus != null) { "Git のコミット情報を取得できません" }
        require(distributionPreview || (releaseStatus.isEmpty() && !buildVersion.endsWith("-dirty"))) {
            "公開用 ZIP は変更をコミットしてから作成してください。検証用は -PdistributionPreview=true を指定します"
        }
        // 再生成が途中で失敗したとき、以前の合格結果を新しい ZIP に添えない。
        windowsReleaseDir.get().asFile.resolve("SHA256SUMS.txt").delete()
        windowsReleaseDir.get().asFile.resolve("RELEASE-NOTES.md").delete()
    }
}
val prepareWindowsReleaseInfo by tasks.registering {
    dependsOn(checkWindowsRelease)
    val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    inputs.properties(mapOf("appVersion" to version, "buildVersion" to buildVersion, "commit" to releaseCommit,
        "dirty" to !releaseStatus.isNullOrEmpty(), "preview" to distributionPreview))
    inputs.file(jdk.map { it.metadata.installationPath.file("release") })
    outputs.dir(windowsReleaseInfo)
    doLast {
        val out = windowsReleaseInfo.get().asFile.apply { mkdirs() }
        out.resolve("BUILD-INFO.txt").writeText(
            "appVersion=$version\nbuildVersion=$buildVersion\ncommit=$releaseCommit\n" +
                "dirty=${!releaseStatus.isNullOrEmpty()}\npreview=$distributionPreview\nplatform=windows-x64\n")
        // 同梱 JDK の供給元・対象版も残す。ローカルの絶対パスは記録しない。
        jdk.get().metadata.installationPath.file("release").asFile.copyTo(out.resolve("RUNTIME-RELEASE.txt"), overwrite = true)
    }
}
val packageWindowsZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "起動案内と TODO サンプルを含む Windows x64 ZIP を作成する"
    dependsOn("verifyWindowsDistribution", prepareWindowsReleaseInfo)
    archiveFileName.set("$windowsZipName.zip")
    destinationDirectory.set(windowsReleaseDir)
    // 展開後に固定の過去日時が表示されないよう、配布元の更新日時を保持する。
    isPreserveFileTimestamps = true
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into("yaml-design-editor") {
        from(windowsImage)
        from(windowsReleaseInfo)
        from(repo.file("distribution/windows/README.txt"))
        into("examples/todos") {
            from(repo.dir("samples/todos")) {
                include("README.md", ".yde/workspace.yaml", ".yde/schemas/*.yaml", ".yde/views/*.yaml",
                    "todos/*.yaml", "todos/details/*.yaml")
            }
        }
    }
}
val verifyWindowsZip by tasks.registering(Exec::class) {
    group = "verification"
    description = "配布 ZIP を展開し、内容の一致と同梱ランタイムを検査する"
    dependsOn(packageWindowsZip)
    val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        repo.file("scripts/test-windows-zip.ps1").asFile.absolutePath,
        "-ArchivePath", packageWindowsZip.get().archiveFile.get().asFile.absolutePath,
        "-JdkHome", jdk.get().metadata.installationPath.asFile.absolutePath,
        "-AppVersion", version.toString(), "-BuildVersion", buildVersion, "-Commit", releaseCommit)
}
tasks.register("windowsRelease") {
    group = "distribution"
    description = "全テスト・ZIP 検査後にチェックサムと GitHub Releases 用の説明を生成する"
    dependsOn(verifyWindowsZip, "test", ":yaml-core:test", ":semantic-core:test", ":workspace-core:test", ":cli:test", ":app-core:test", ":agent:test", ":format-text:test", ":format-xlsx:test", ":format-docx:test", ":repo-tests:test")
    val releaseNotes = repo.file("distribution/windows/release-notes.md")
    inputs.file(releaseNotes)
    doLast {
        require(distributionPreview || (git("rev-parse", "HEAD") == releaseCommit && git("status", "--porcelain") == "")) {
            "ビルド中にソースが変更されています。コミットを確定して再実行してください"
        }
        val zip = packageWindowsZip.get().archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        zip.resolveSibling("SHA256SUMS.txt").writeText(digest.digest().joinToString("") { "%02x".format(it) } + "  ${zip.name}\n")
        zip.resolveSibling("RELEASE-NOTES.md").writeText(releaseNotes.asFile.readText()
            .replace("@VERSION@", version.toString()).replace("@ZIP@", zip.name)
            .replace("@COMMIT@", releaseCommit).replace("@BUILD@", buildVersion)
            .replace("@MODE@", if (distributionPreview) "開発中の検証用プレビュー" else "Windows ZIP 配布"))
        logger.lifecycle("配布成果物: ${zip.parentFile}")
    }
}

compose.desktop {
    application {
        mainClass = "dev.toros.yde.desktop.MainKt"
        // run と packageDistributionForCurrentOS が使う JDK を toolchain（21）に合わせる。
        // 既定は Gradle を動かしている JDK で、それが 17 だと Java 21 向けのライブラリが読めずに起動に失敗する
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            modules("java.instrument", "java.net.http", "java.prefs", "java.security.jgss", "java.xml.crypto", "jdk.security.auth", "jdk.unsupported")
            appResourcesRootDir.set(distributionResources)
            licenseFile.set(repo.file("LICENSE"))
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
