// semantic-core: 文書集合の意味的解釈・意味グラフ・意味的検証・リファクタリング計画
// ファイルI/O禁止（java.nio.file.Path への依存を持たない。ADR-0002）
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
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
