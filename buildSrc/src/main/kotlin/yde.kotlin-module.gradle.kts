// Compose を使わないモジュール（カーネル・app-core・agent・cli・拡張・repo-tests）の共通設定。
// モジュール側は `plugins { id("yde.kotlin-module") }` と dependencies、固有の設定（資源の生成・作業ディレクトリなど）だけを書く。
// Compose を使うモジュール（desktop。将来の view-*-ui）は Compose の版と JUnit 4 が絡むので別に書く。
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = providers.gradleProperty("appVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(21)   // 外部ライブラリ（Markdown の描画）が Java 21 の class を要求する。無ければ Gradle（foojay）が自動で取得する
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // ルートから ./gradlew test --tests '…' で絞ったとき、このモジュールに一致するテストが無くても失敗にしない
    filter.isFailOnNoMatchingTests = false
    // 共通fixture（test-fixtures/）の場所。テストは読み取り専用で使う（docs/CONVENTIONS.md「モジュールの規約」）
    systemProperty("fixtures.root", rootDir.resolve("test-fixtures").absolutePath)
}
