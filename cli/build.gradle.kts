// cli: 3層コアの簡易CLI（パースと表示のみ。業務ロジックはcore側に置く）
plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("cli.MainKt")
}

// 相対パス引数をリポジトリルート基準で解決する（計画書§14の実行例に合わせる）
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    // CLI出力はUTF-8固定（JDK 17のWindows既定はMS932。機械可読出力を文字化けさせない）
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    standardInput = System.`in`  // shell（対話モード）用
}

group = "dev.toros"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":workspace-core"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 共通fixture（test-fixtures/）の場所。テストは読み取り専用で使う（docs/CONVENTIONS.md §8）
    systemProperty("fixtures.root", rootDir.resolve("test-fixtures").absolutePath)
}
