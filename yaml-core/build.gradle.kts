// yaml-core: 1つのYAML文書の構文・スキーマ適合・編集操作（3層コアの最下層）
// SnakeYAML依存はこのモジュールだけに置き、公開APIへSnakeYAMLの型を出さない。
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = providers.gradleProperty("appVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
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
    // 共通fixture（test-fixtures/）の場所。テストは読み取り専用で使う（docs/CONVENTIONS.md §8）
    systemProperty("fixtures.root", rootDir.resolve("test-fixtures").absolutePath)
}
