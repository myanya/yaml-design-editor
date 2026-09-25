rootProject.name = "yaml-design-editor"

// モジュール構成（docs/architecture/README.md「モジュールと依存方向」）。
// 依存方向: yaml-core ← semantic-core ← workspace-core ← cli / ルート（アプリ）
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli",
)
