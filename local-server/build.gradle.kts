// 通信を応用層から隔離する組込みモジュール。起動と設定の所有者は desktop。
plugins { id("yde.kotlin-module") }

dependencies {
    implementation(project(":app-core"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.cio)
    implementation(libs.mcp.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mcp.client)
}

dependencyLocking { lockAllConfigurations() }

tasks.processResources {
    dependsOn(":web-ui:bundle")
    from(rootProject.layout.projectDirectory.dir("web-ui/build/dist")) { into("web-ui") }
}

// 公開サンプルは一時ディレクトリへ写して契約試験に使う。
tasks.withType<Test>().configureEach { workingDir = rootDir }

// Inspector は開発時の任意検査だけで使い、製品や通常の test には要求しない。
tasks.register<JavaExec>("verifyMcpInspector") {
    description = "取得済みの公式 Inspector CLI から一時ワークスペースへ実接続する"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.toros.yde.server.mcp.McpInspectorCheck")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    workingDir = rootDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    doFirst {
        val cli = providers.gradleProperty("inspectorCli").orNull
            ?: error("-PinspectorCli=取得したInspectorのclients/launcher/build/index.jsを指定してください")
        args(rootProject.file(cli).absolutePath, providers.gradleProperty("inspectorNode").getOrElse("node"))
    }
}

tasks.register<JavaExec>("verifyWebBrowser") {
    description = "同梱 Web 画面のログイン・再読込・ログアウトを実ブラウザで検査する"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.toros.yde.server.web.WebBrowserCheck")
    workingDir = rootDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    doFirst { args(providers.gradleProperty("webNode").getOrElse("node")) }
}
