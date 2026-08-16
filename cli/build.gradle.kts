// cli: 3層コアの簡易CLI（パースと表示のみ。業務ロジックはcore側に置く）
plugins {
    kotlin("jvm")
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
}
