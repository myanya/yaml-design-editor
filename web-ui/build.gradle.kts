import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.options.Option

plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("24.21.0")
    npmVersion.set("11.19.0")
    download.set(true)
    npmInstallCommand.set("ci")
}

// ルートの test --tests は JVM の選択条件。Web は小さい独立スイートを常に検査する。
abstract class WebTestTask : NpmTask() {
    @Option(option = "tests", description = "JVM テストの絞込み。Web は全件を検査する")
    fun acceptJvmTestFilter(patterns: List<String>) { }
}

val webInputs = files("package.json", "package-lock.json", ".npmrc", "tsconfig.json", "vite.config.ts",
    "index.html", rootProject.file("local-server/src/main/resources/web-api/openapi.json"), fileTree("src"), fileTree("scripts"))

val generateTypes by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "generate"))
    inputs.files("package.json", "package-lock.json", rootProject.file("local-server/src/main/resources/web-api/openapi.json"))
    outputs.file(layout.buildDirectory.file("generated/web-api.d.ts"))
}

val bundle by tasks.registering(NpmTask::class) {
    dependsOn(generateTypes)
    npmCommand.set(listOf("run", "build"))
    inputs.files(webInputs)
    outputs.dir(layout.buildDirectory.dir("dist"))
}
tasks.register<WebTestTask>("test") {
    dependsOn(generateTypes)
    npmCommand.set(listOf("test"))
    inputs.files(webInputs)
}
tasks.assemble { dependsOn(bundle) }
tasks.check { dependsOn("test") }
