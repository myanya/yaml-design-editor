// cli: 1 つの CLI（ADR-0013。R47-41）。カーネルの命令（yaml… / workspace…）と応用の命令（catalog・view・validate・agent・import …）。
// 引数の解釈と表示だけを持ち、業務ロジックは app-core・agent・カーネルに置く。登録する拡張は CliExtensions.kt の一覧
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
    application
}

application {
    mainClass.set("dev.toros.yde.cli.MainKt")
}

// ./gradlew runCli -q --args="…"（root から打つ。作業ディレクトリはリポジトリの根＝相対パスの引数が文書の例のとおりに解決する）
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "ヘッドレス CLI を実行する（dev.toros.yde.cli.MainKt）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.toros.yde.cli.MainKt")
    workingDir = rootDir
    // CLI 出力は UTF-8 固定（Windows の既定は MS932。機械可読出力を文字化けさせない）
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    standardInput = System.`in`  // workspace shell（対話モード）用
}

// ./gradlew devtrackDocs: 開発管理（devtrack/）の YAML から docs/BACKLOG.md と docs/archive/backlog-closed.md を作り直す（devtrack/README.md「運用」）。
// レポートの出力先はワークスペースの中に限られるので、CLI の report --print の文字を UTF-8 のまま受け、成功したときだけ置き換える。
// 最新かどうかは repo-tests の DevtrackTest が見る
val devtrackReports = mapOf("backlog-md" to "docs/BACKLOG.md", "backlog-closed-md" to "docs/archive/backlog-closed.md")
val devtrackDocTasks = devtrackReports.map { (report, target) ->
    val staged = layout.buildDirectory.file("devtrack/$report.md")
    val targetFile = rootDir.resolve(target)
    tasks.register<JavaExec>("devtrackDoc-$report") {
        group = "documentation"
        description = "devtrack のレポート $report を $target へ書く"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("dev.toros.yde.cli.MainKt")
        workingDir = rootDir
        jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        args("report", "devtrack", report, "--print")
        outputs.upToDateWhen { false }
        var stream: java.io.OutputStream? = null
        doFirst {
            val file = staged.get().asFile
            file.parentFile.mkdirs()
            stream = file.outputStream().also { standardOutput = it }
        }
        doLast {
            stream?.close()
            val text = staged.get().asFile.readText(Charsets.UTF_8)
            check("<!-- 埋まらなかった穴:" !in text) { "レポート $report に埋まらなかった穴があります（${staged.get().asFile}）" }
            targetFile.writeText(text, Charsets.UTF_8)
            logger.lifecycle("$target を作り直しました")
        }
    }
}
tasks.register("devtrackDocs") {
    group = "documentation"
    description = "devtrack の YAML から docs/BACKLOG.md と docs/archive/backlog-closed.md を作り直す"
    dependsOn(devtrackDocTasks)
}

// :cli:run は runCli と同じ（application プラグインの run）。ルートで ./gradlew run（GUI）と打ったときに走らないよう、:cli:run と明示したときだけ有効
tasks.named<JavaExec>("run") {
    enabled = gradle.startParameter.taskNames.any { it.endsWith("cli:run") }
    workingDir = rootDir
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    standardInput = System.`in`
}

dependencies {
    implementation(project(":app-core"))
    implementation(project(":local-server"))  // 明示的な connect の通信だけ。待受は起動しない
    implementation(project(":agent"))
    // 拡張（Excel・Word の形式）。登録は CliExtensions.kt の一覧
    implementation(project(":format-office"))
    implementation(libs.kotlinx.coroutines.core)   // agent / watch / eval の runBlocking（agent と同じ版）
}

tasks.withType<Test>().configureEach {
    // 応用の命令のテストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む
    workingDir = rootDir
}
