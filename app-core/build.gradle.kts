// app-core: 応用層の中核（画面無し。ADR-0013）。ワークスペースを開き・読み・検証し・変える窓口（core.Workspace）と、
// 開いている文書の集合（session.DocumentSession）・索引とスキーマの窓口・読み出し・入出力変換。
// Compose・java.util.prefs・AI・POI に依存しない（依存はここの dependencies で止める。入口＝desktop と cli が上に載る）
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    api(project(":workspace-core"))
    api(libs.kotlinx.coroutines.core) // service/ の実行順と通知（画面・通信には依存しない）
    implementation(libs.kotlinx.serialization.json)   // tools/ のツールの引数と JSON Schema（直列化プラグインは使わない）
}

tasks.withType<Test>().configureEach {
    // テストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む。ほかのモジュールのテストと同じ作業ディレクトリにそろえる
    workingDir = rootDir
}

// バージョン情報を資源（build-info.properties）として jar に入れる。読むのは core/BuildInfo.kt（desktop と cli が使う）。
// 内部バージョン（yyyymmdd.ビルド番号）とコミットは buildSrc の GitInfo が git から算出する（-PbuildVersion=… で手動指定もできる）
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo by tasks.registering {
    description = "バージョン情報（build-info.properties）を生成する"
    val app = version.toString()
    val build = GitInfo.buildVersion(rootDir, providers.gradleProperty("buildVersion").orNull)
    val commit = GitInfo.commit(rootDir)
    val outDir = buildInfoDir  // 実行時の処理からスクリプトの値を直接参照しない（構成キャッシュの再利用で復元できないため）
    inputs.property("appVersion", app)
    inputs.property("buildVersion", build)
    inputs.property("commit", commit)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("build-info.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("appVersion=$app\nbuildVersion=$build\ncommit=$commit\n")
    }
}
sourceSets["main"].resources.srcDir(buildInfoDir)
tasks.named("processResources") { dependsOn(generateBuildInfo) }
