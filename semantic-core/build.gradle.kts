// semantic-core: 文書集合の意味的解釈・意味グラフ・意味的検証・リファクタリング計画
// ファイルI/O禁止（java.nio.file.Path への依存を持たない。ADR-0002）
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    api(project(":yaml-core"))
}

