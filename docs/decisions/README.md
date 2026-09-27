# 設計決定記録（ADR）

アーキテクチャ決定の台帳。
決定を変更する場合は、既存ADRを書き換えず、新しいADRで上書きを宣言する。

| ID | タイトル | 状態 |
|---|---|---|
| [ADR-0001](0001-in-repo-multimodule.md) | in-repoマルチモジュール方式 | 承認済み |
| [ADR-0002](0002-dependency-direction.md) | 3層の依存方向 | 承認済み |
| [ADR-0003](0003-ynode-mutable-cow.md) | YNodeは構文木・可変のまま・copy-on-write規律 | 承認済み |
| [ADR-0004](0004-semantic-graph-derived.md) | 意味グラフは派生物・semantic-coreはI/Oなし | 承認済み |
| [ADR-0005](0005-operations-and-transactions.md) | すべての変更をoperation/transactionで表す | 承認済み |
| [ADR-0006](0006-undo-workspace-ownership.md) | Undo/Redo・履歴はworkspace-core単一所有 | 承認済み |
| [ADR-0007](0007-single-version-counter.md) | バージョンは単一のLong連番 | 承認済み |
| [ADR-0008](0008-presentation-semantics-direction.md) | 表記と意味の方向規則 | 承認済み |
| [ADR-0009](0009-core-calculus.md) | YDE Core Calculus を最上位の設計方針とする | 承認済み |
| [ADR-0010](0010-expression-values-are-terms.md) | 式の値は項とし、集合から値への入口を all に限る | 承認済み |

関連文書:

- [意図的差分一覧](../archive/intentional-diffs.md) — 3層コア移行で意図して変えた挙動の記録（移行完了により退役。archive）
- [3層コア移行の計画](../archive/three-layer-core-migration-plan.md)・[移行台帳](../archive/migration-ledger.md) — 完了した移行の記録（archive）
