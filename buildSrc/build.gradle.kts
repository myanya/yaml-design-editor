// 共通の Gradle 設定（convention plugin）の置き場。モジュールの build.gradle.kts は `id("yde.kotlin-module")` を当てて、
// 自分の依存と固有の設定だけを書く（ADR-0013「機械で守るもの」・docs/CONVENTIONS.md「モジュールの規約」）。
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Kotlin の版はここが正。desktop/build.gradle.kts の compose 用プラグイン（org.jetbrains.kotlin.plugin.compose）も同じ版にそろえる
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
}
