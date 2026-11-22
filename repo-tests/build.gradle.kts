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
    testImplementation(project(":local-server"))
    testImplementation(project(":format-office"))
    testImplementation(project(":workspace-core"))
    testImplementation(project(":semantic-core"))
    testImplementation(project(":yaml-core"))
    // desktop の AppState の型（Compose の状態）をテストから読むため。版は desktop の compose プラグインと同じ
    testImplementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")   // ImportExportTest が Office の文書を組み立てる（本体は format-office）
}

tasks.withType<Test>().configureEach {
    // samples/・test-fixtures/・docs/ をリポジトリの根からの相対パスで読む（labs/ は読まない。規則テストの規則 21）
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
val preCommitTest = testPartition("preCommitTest", listOf(
    "**/SourceRulesTest*.class", "**/DocReferencesTest*.class", "**/DevtrackTest*.class", "**/SpecTest*.class",
))
preCommitTest.configure { description = "コミット前の規則・参照・devtrack・仕様の検査（全件実行でも共有）" }
tasks.withType<Test>().configureEach {
    inputs.files(
        repoDir.files("CLAUDE.md", "AGENTS.md", "settings.gradle.kts"),
        repoDir.dir("docs").asFileTree.matching { include("**/*.md") },
        // 開発管理と仕様（devtrack/）: DevtrackTest と SpecTest が、定義と文書と仕様の例をファイルとして読む
        repoDir.dir("devtrack").asFileTree,
        fileTree(rootDir) {
            include("*/src/**/*.kt", "*/build.gradle.kts", "*/README.md", "*/AGENTS.md", "src/**")
            exclude(".*/**", "build/**", "scratchpad/**", "labs/**", "samples/**", "local/**")
        },
    ).withPropertyName("repoSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
