// repo-tests: リポジトリ全体のテスト（main は無い）。規則テスト（rules/。ソースの規則・モジュールの形・文書の参照）と、
// 入口（desktop・cli）や拡張の一覧をまたぐテストを置く。全モジュールに依存してよいのはこの箱だけ（テストだけ）。
// 1 つのモジュールの依存で書けて、試す対象もそのモジュールのテストは、そのモジュールに置く
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

repositories {
    google()   // desktop の Compose の推移依存を解決する
}

dependencies {
    testImplementation(project(":desktop"))
    testImplementation(project(":cli"))
    testImplementation(project(":agent"))
    testImplementation(project(":app-core"))
    testImplementation(project(":format-office"))
    testImplementation(project(":workspace-core"))
    testImplementation(project(":semantic-core"))
    testImplementation(project(":yaml-core"))
    // desktop の AppState の型（Compose の状態）をテストから読むため。版は desktop の compose プラグインと同じ
    testImplementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")   // ImportExportTest が Office の文書を組み立てる（本体は format-office）
}

tasks.withType<Test>().configureEach {
    // samples/・labs/・test-fixtures/・docs/ をリポジトリの根からの相対パスで読む
    workingDir = rootDir
}

tasks.register<JavaExec>("fileTreeBenchmark") {
    description = "1000件と10万件の実ファイルで一覧と検索を計測する（ワークスペースの窓口と画面側の開き方）"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.toros.yde.app.core.FileTreeBenchmark")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// 規則テストと文書の参照のテストは、全モジュールのソースと文書をファイルとして読む（クラスパスには入らない）。
// 入力に宣言しないと、文書やコメントだけを変えたときに UP-TO-DATE で走らない（コミット前のフックが見逃す）
val repoDir = rootProject.layout.projectDirectory
tasks.named<Test>("test") {
    inputs.files(
        repoDir.files("CLAUDE.md", "AGENTS.md", "settings.gradle.kts"),
        repoDir.dir("docs").asFileTree.matching { include("**/*.md") },
        fileTree(rootDir) {
            include("*/src/**/*.kt", "*/build.gradle.kts", "*/README.md", "*/AGENTS.md", "src/**")
            exclude(".*/**", "build/**", "scratchpad/**", "labs/**", "samples/**", "local/**")
        },
    ).withPropertyName("repoSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
