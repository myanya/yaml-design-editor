// agent: LLM との会話・アクティビティの実行・ステージングと承認待ち・自動実行・評価と指標・文書の生成支援（ADR-0013）。
// 書き込みはステージング → 変更セット → app-core の適用だけ。Compose・java.util.prefs・POI に依存しない
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    api(project(":app-core"))
    implementation(libs.kotlinx.serialization.json)   // LLM の要求と応答の JSON（直列化プラグインは使わない）
    implementation(libs.kotlinx.coroutines.core)     // 自動実行と評価の並行処理（desktop の Compose と同じ版）
}

tasks.withType<Test>().configureEach {
    // テストは samples/ や test-fixtures/ をリポジトリの根からの相対パスで読む。ほかのモジュールのテストと同じ作業ディレクトリにそろえる
    workingDir = rootDir
}
