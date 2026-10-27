// app-core: 応用層の中核（画面無し。ADR-0013）。ワークスペースを開き・読み・検証し・変える窓口（core.Workspace）と、
// 開いている文書の集合（session.DocumentSession）・索引とスキーマの窓口・読み出し・入出力変換。
// Compose・java.util.prefs・AI・POI に依存しない（依存はここの dependencies で止める。入口＝root と cli が上に載る）
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = providers.gradleProperty("appVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    api(project(":workspace-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")   // skill/Tables.kt の JSON の読み書き（直列化プラグインは使わない）
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // root から移したテストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む。root のテストと同じ作業ディレクトリにそろえる
    workingDir = rootDir
    // ルートから ./gradlew test --tests '…' で絞ったとき、このモジュールに一致するテストが無くても失敗にしない
    filter.isFailOnNoMatchingTests = false
    // 共通fixture（test-fixtures/）の場所。テストは読み取り専用で使う（docs/CONVENTIONS.md §8）
    systemProperty("fixtures.root", rootDir.resolve("test-fixtures").absolutePath)
}
