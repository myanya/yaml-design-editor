// yaml-core: 1つのYAML文書の構文・スキーマ適合・編集操作（3層コアの最下層）
// SnakeYAML依存はこのモジュールだけに置き、公開APIへSnakeYAMLの型を出さない。
plugins {
    kotlin("jvm")
}

group = "dev.toros"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
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
