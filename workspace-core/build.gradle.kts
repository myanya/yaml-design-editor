// workspace-core: 文書集合の版・更新・永続化・履歴・トランザクション・派生状態の鮮度管理
// ファイルI/O（Path）はこの層のI/O境界に閉じる（ADR-0002）
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
    api(project(":semantic-core"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(21)   // 外部ライブラリ（Markdown の描画）が Java 21 の class を要求する。無ければ Gradle（foojay）が自動で取得する
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 共通fixture（test-fixtures/）の場所。テストは読み取り専用で使う（docs/CONVENTIONS.md §8）
    systemProperty("fixtures.root", rootDir.resolve("test-fixtures").absolutePath)
    // ゴールデンテスト（RefactoringPlanGoldenTest）の期待値更新スイッチ: ./gradlew :workspace-core:test -DupdateGolden=true
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}

// ワークスペースのテンプレート: リポジトリ直下の templates/ を資源 templates/ に写す（アプリと CLI の「新しいワークスペースを作る」が読む）。
// 一覧は index.txt と各テンプレートの files.txt（資源はディレクトリを列挙できないため、ビルド時に作る）
val templatesDir = layout.buildDirectory.dir("generated/templates")
val copyTemplates by tasks.registering(Sync::class) {
    description = "ワークスペースのテンプレート（templates/）を資源に入れる"
    val outDir = templatesDir.map { it.dir("templates") }
    from(rootProject.layout.projectDirectory.dir("templates")) { exclude("README.md") }
    into(outDir)
    doLast {
        val dir = outDir.get().asFile
        val ids = dir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()
        dir.resolve("index.txt").writeText(ids.joinToString("\n"))
        ids.forEach { id ->
            val base = dir.resolve(id)
            val names = base.walkTopDown().filter { it.isFile && it.name != "files.txt" }
                .map { base.toPath().relativize(it.toPath()).toString().replace('\\', '/') }.sorted().toList()
            base.resolve("files.txt").writeText(names.joinToString("\n"))
        }
    }
}
sourceSets["main"].resources.srcDir(templatesDir)
tasks.named("processResources") { dependsOn(copyTemplates) }
