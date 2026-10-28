// view-chart: ビューの種類 chart（表を図に）。拡張の 1 つで、公開するのは ViewKind の実装と、描画が読む定義と結果の型（ADR-0014）。
// 依存は app-core（とカーネル）まで。他の拡張・agent・入口に依存しない
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    implementation(project(":app-core"))
}
