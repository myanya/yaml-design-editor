rootProject.name = "yaml-design-editor"

// 3層コア移行（docs/THREE-LAYER-CORE-MIGRATION-PLAN.md）のモジュール。
// 依存方向: yaml-core ← semantic-core ← workspace-core ← cli / ルート（アプリ）
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli",
)
