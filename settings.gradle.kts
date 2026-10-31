// JDK の自動取得（Compose Hot Reload の hotRun が使う JetBrains Runtime 21 を Gradle が自分で用意する。README.md「クイックスタート」）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yaml-design-editor"

// モジュール構成（docs/architecture/README.md「モジュールと依存方向」）。
// 依存方向: yaml-core ← semantic-core ← workspace-core ← app-core ← agent ← desktop（アプリ）。cli は app-core・agent・拡張まで。拡張（format-office）は app-core まで（ADR-0013・ADR-0015）。
// repo-tests はリポジトリ全体のテストだけ（main は無い）で、全モジュールに依存してよい。root はソースを置かない
// ローカル接続は app-core ← local-server ← desktop / cli（ADR-0016）。Ktor・MCP を app-core に入れない
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli",
    ":desktop",
    ":app-core",
    ":local-server",
    ":agent",
    ":format-office",
    ":repo-tests",
)
