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

// :cli:run は runCli と同じ（application プラグインの run）。ルートで ./gradlew run（GUI）と打ったときに走らないよう、:cli:run と明示したときだけ有効
tasks.named<JavaExec>("run") {
    enabled = gradle.startParameter.taskNames.any { it.endsWith("cli:run") }
    workingDir = rootDir
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    standardInput = System.`in`
}

dependencies {
    implementation(project(":app-core"))
    implementation(project(":agent"))
    // 拡張（Excel・Word の形式）。登録は CliExtensions.kt の一覧
    implementation(project(":format-office"))
    implementation(libs.kotlinx.coroutines.core)   // agent / watch / eval の runBlocking（agent と同じ版）
}

tasks.withType<Test>().configureEach {
    // 応用の命令のテストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む
    workingDir = rootDir
}
