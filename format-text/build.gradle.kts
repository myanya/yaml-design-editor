// format-text: 文字の形式（csv・json・markdown・html）の表の読み書き。拡張の 1 つで、公開するのは FormatCodec の実装だけ（ADR-0013）。
// 依存は app-core（とカーネル）まで。他の拡張・agent・入口に依存しない
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    implementation(project(":app-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")   // json の読み書き（直列化プラグインは使わない）
}
