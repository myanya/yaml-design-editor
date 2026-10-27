// JDK の自動取得（Compose Hot Reload の hotRun が使う JetBrains Runtime 21 を Gradle が自分で用意する。README.md「クイックスタート」）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yaml-design-editor"

// モジュール構成（docs/architecture/README.md「モジュールと依存方向」）。
// 依存方向: yaml-core ← semantic-core ← workspace-core ← app-core ← agent ← ルート（アプリ）。cli は app-core・agent・拡張まで。拡張（format-*）は app-core まで（ADR-0013）
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli",
    ":app-core",
    ":agent",
    ":format-text",
    ":format-xlsx",
    ":format-docx",
)
