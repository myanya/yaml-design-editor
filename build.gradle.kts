import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "dev.toros"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.poi:poi-ooxml:5.3.0")   // Excel(xlsx)のインポート・エクスポート（skill機構）
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

// ヘッドレスCLI（UIなしでカタログ・検索・パッチ・検証を実行する）
// 例: ./gradlew runCli --args="catalog sample-workspace" -q
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "ヘッドレスCLIを実行する（core.CliKt）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("core.CliKt")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "yaml-design-editor"
            packageVersion = "1.0.0"
        }
    }
}
