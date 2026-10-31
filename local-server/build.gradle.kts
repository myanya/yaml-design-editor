// 通信を応用層から隔離する組込みモジュール。起動と設定の所有者は desktop。
plugins { id("yde.kotlin-module") }

dependencies {
    implementation(project(":app-core"))
    implementation(libs.ktor.server.cio)
    implementation(libs.mcp.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.mcp.client)
}

dependencyLocking { lockAllConfigurations() }
