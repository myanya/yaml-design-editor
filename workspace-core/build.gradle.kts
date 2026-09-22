// workspace-core: 文書集合の版・更新・永続化・履歴・トランザクション・派生状態の鮮度管理
// ファイルI/O（Path）はこの層のI/O境界に閉じる（ADR-0002）
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = providers.gradleProperty("appVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    api(project(":yaml-core"))
    api(project(":semantic-core"))
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
    // ゴールデンテスト（RefactoringPlanGoldenTest）の期待値更新スイッチ: ./gradlew :workspace-core:test -DupdateGolden=true
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}
