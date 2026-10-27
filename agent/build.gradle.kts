// agent: LLM との会話・アクティビティの実行・ステージングと承認待ち・自動実行・評価と指標・文書の生成支援（ADR-0013）。
// 書き込みはステージング → 変更セット → app-core の適用だけ。Compose・java.util.prefs・POI に依存しない
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = providers.gradleProperty("appVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    api(project(":app-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")   // LLM の要求と応答の JSON（直列化プラグインは使わない）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")     // 自動実行と評価の並行処理（root の Compose と同じ版）
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
