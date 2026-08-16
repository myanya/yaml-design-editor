# 設計決定記録（ADR）

3層コア移行（[../THREE-LAYER-CORE-MIGRATION-PLAN.md](../THREE-LAYER-CORE-MIGRATION-PLAN.md)）に関する決定の台帳。
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

関連文書:

- [意図的差分一覧](INTENTIONAL-DIFFS.md) — 既存挙動から意図して変える点。比較テストで「移植ミス」と区別するための台帳
- [移行台帳](../MIGRATION-LEDGER.md) — テスト・機能単位の移行状態
