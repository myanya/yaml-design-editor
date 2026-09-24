rootProject.name = "yaml-design-editor"

// モジュール構成（docs/ARCHITECTURE.md「パッケージ構成と依存方向」）。
// 依存方向: yaml-core ← semantic-core ← workspace-core ← cli / ルート（アプリ）
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli",
)
