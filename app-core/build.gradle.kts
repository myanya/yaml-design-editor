// app-core: 応用層の中核（画面無し。ADR-0013）。ワークスペースを開き・読み・検証し・変える窓口（core.Workspace）と、
// 開いている文書の集合（session.DocumentSession）・索引とスキーマの窓口・読み出し・入出力変換。
// Compose・java.util.prefs・AI・POI に依存しない（依存はここの dependencies で止める。入口＝root と cli が上に載る）
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    api(project(":workspace-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")   // skill/Tables.kt の JSON の読み書き（直列化プラグインは使わない）
}

tasks.withType<Test>().configureEach {
    // root から移したテストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む。root のテストと同じ作業ディレクトリにそろえる
    workingDir = rootDir
}
