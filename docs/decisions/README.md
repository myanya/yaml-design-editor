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
| [ADR-0011](0011-file-arrangement-history.md) | 一般ファイルの配置変更と文書修正を単一履歴で扱う | 承認済み |
| [ADR-0012](0012-save-conflict-check.md) | 保存直前に既知のディスク内容と比較する | 承認済み |
| [ADR-0013](0013-application-layer-and-extensions.md) | 画面を持たない応用層（app-core）と入口・拡張のモジュール構成 | 承認済み（一部を ADR-0014・ADR-0015・ADR-0016 で上書き） |
| [ADR-0014](0014-view-kinds-in-kernel.md) | ビューの種類の差し込み口はカーネルに置き、拡張へ出すのは chart と flow にする | 承認済み（一部を ADR-0015 で上書き） |
| [ADR-0015](0015-builtin-kinds-and-office-extension.md) | chart・flow と文字の形式は app-core の組込みにし、拡張は Office の形式だけにする | 承認済み |
| [ADR-0016](0016-local-connections-and-shared-host.md) | ローカル接続を組込みサーバへ集約し、編集状態を入口間で共有する | 承認済み（一部を ADR-0017・ADR-0019 で上書き） |
| [ADR-0017](0017-external-commit-and-desktop-history.md) | 外部の確定とデスクトップの取り消しを分ける | 承認済み |
| [ADR-0018](0018-connection-read-scopes.md) | 接続権限の全体閲覧と指定文書を分ける | 承認済み（一部を ADR-0019 で上書き） |
| [ADR-0019](0019-external-work-commit-routes.md) | 外部作業の提案と直接確定を分ける | 承認済み |
| [ADR-0020](0020-diagram-notation-definitions.md) | 図の記法を共有するYAML定義にする | 承認済み |
| [ADR-0021](0021-workspace-diagram-positions.md) | 図の手動位置をワークスペースの表示状態に分離する | 承認済み（一部を ADR-0022・ADR-0026 で上書き） |
| [ADR-0022](0022-stable-diagram-positions.md) | 図の現在位置を保持し、再配置を明示操作にする | 承認済み（一部を ADR-0028 で上書き） |
| [ADR-0023](0023-diagram-grid-editing.md) | グリッド編集補助を図の表示状態として保存する | 承認済み |
| [ADR-0024](0024-diagram-ports.md) | ポートを図の表示モデルに導入する | 承認済み |
| [ADR-0025](0025-flow-query-sources.md) | フローの入力をクエリ変換で合成する | 採用 |
| [ADR-0026](0026-diagram-state-writes-without-document-lock.md) | 図の配置の保存は文書の排他を取らず、外部変更の確認も起こさない | 承認済み |
| [ADR-0027](0027-lens-definition-selection.md) | レンズの定義に箱の絞り込みを持たせる | 承認済み |
| [ADR-0028](0028-flow-edge-vias.md) | フロー図の線の経由点（ラベルの位置）を表示状態に記録する | 承認済み |
| [ADR-0029](0029-lifecycle-drives-state.md) | エンティティの状態は Lifecycle を正にし、操作と業務の流れを Lifecycle から導く | 承認済み |
| [ADR-0030](0030-lifecycle-state-refinement.md) | Lifecycle の State の中の流れは、同じ ID の詳細化として別に書く | 承認済み |
| [ADR-0031](0031-definitions-index-and-generation.md) | 業務モデルの定義を索引に載せ、設計書は業務モデルから生成する | 承認済み |
| [ADR-0032](0032-definition-scopes.md) | 定義体のスコープを定義し、生成は業務モデルのスコープだけを読む | 承認済み |
| [ADR-0033](0033-views-in-definition-index.md) | ビューを定義の索引に載せ、業務モデルの項にダッシュボードからたどれる範囲を持たせる | 承認済み |
| [ADR-0034](0034-stage-terms.md) | 設計書は段の項から作り、横ぐしの方針と補正は段の規則・補正として項に重ねる | 承認済み |
| [ADR-0035](0035-design-items-corrections-views.md) | 設計の原本は設計項目一覧に持ち、個別の直しは補正一覧に書いて、設計書はビューで見る | 承認済み |
| [ADR-0036](0036-detail-items-and-logical-types.md) | 詳細設計の項目一覧は詳細設計にだけ出るものを持ち、論理型は基本設計の設計項目、実装の型は型対応表で決める（区分は基本設計項目台帳・業務モデルのどの形もテーブルにする） | 承認済み |
| [ADR-0037](0037-sql-default-literals.md) | SQL の初期値の出力方法は型対応表で決める | 承認済み（テーブル設計書全体はレビュー継続中） |
| [ADR-0038](0038-effective-design-items.md) | 項目固有の制限と補正後の値をビュー・後段の生成で共有する | 足場固めを実装（設計書のレビュー継続中） |
| [ADR-0039](0039-finalized-design-items.md) | 導出・補正と指定・追加を合成し、確定項目一覧をビューと後段で共有する | 方針・属性・保存構造を確認済み（候補合成を変換へ接続、全体の確定生成はこれから） |
| [ADR-0040](0040-vocabulary-spec-in-devtrack.md) | 定義に書ける語の仕様を devtrack に持ち、今の実装の記述としてテストで実装に結ぶ | 承認済み |
| [ADR-0041](0041-design-layout-and-independent-app.md) | 設計を段階ごとに配置し、生成アプリを独立したビルドにする | 方針合意（配置移行とアプリ生成は未着手） |
| [ADR-0042](0042-system-models-and-views.md) | 業務モデルから 3 つの変換でソースコードまで作り、定義書と設計書を各段のモデルのビューにする | 方針合意（体系の移行は未着手） |

関連文書:

- [意図的差分一覧](../archive/intentional-diffs.md) — 3層コア移行で意図して変えた挙動の記録（移行完了により退役。archive）
- [3層コア移行の計画](../archive/three-layer-core-migration-plan.md)・[移行台帳](../archive/migration-ledger.md) — 完了した移行の記録（archive）
