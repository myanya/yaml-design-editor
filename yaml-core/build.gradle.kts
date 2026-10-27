// yaml-core: 1つのYAML文書の構文・スキーマ適合・編集操作（3層コアの最下層）
// SnakeYAML依存はこのモジュールだけに置き、公開APIへSnakeYAMLの型を出さない。
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
}

