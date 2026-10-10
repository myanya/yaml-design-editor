# コンテキスト間の連携

コンテキスト間で受け渡す情報と責任を定義します。
各CMAP文書は一つの境界間の連携を扱い、その集合をコンテキストマップとして表示します。

## 文書の形式

[一覧](0000-context-map-lists.yaml)にID・タイトル・概要を置き、詳細文書に連携を記述します。
共通の分担・ID・状態の扱いは [共通記述規約](../WRITING.md) に従います。

| 詳細の項目 | 内容 |
|---|---|
| `id`・`status` | 連携のIDと草案／確定 |
| `context_a`・`context_b` | 連携の当事者となる二つの境界 |
| `purpose` | 連携の目的 |
| `information_flow` | 提供・利用する情報と方向 |
| `ownership` | 情報・変更の管理責任 |
| `model_translation` | 境界間での意味の対応 |
| `collaboration` | チーム間の協調・上流下流の関係 |
| `integration` | 技術的な連携・翻訳の方式 |
| `timing_and_failure` | 同期・遅延・失敗時の約束 |
| `rule_refs` | 根拠の個別ルール |

スキーマは [context-map-list.yaml](../../.yde/schemas/context-map-list.yaml) と [context-map-detail.yaml](../../.yde/schemas/context-map-detail.yaml) です。
必須項目はスキーマに従い、任意項目の未決定の内容は省略します。未解決の論点は [TODO](../../TODO.md) で管理します。
作成済みの連携が全連携を網羅しているとは限りません。

## 関係の記述

コンテキスト同士の連携はCMAPを原本とし、当事者の文書に同じ内容を二重入力しません。
CMAPからBCTXへの参照を `connects-context` として扱い、[domain-relations.yaml](../../.yde/schemas/domain-relations.yaml) に関係を定義します。
境界A・Bの順序や、この参照の方向は、情報の方向や上流下流を表しません。

DDDの関係パターンは、チーム間の協調関係と技術的な連携方式を分けます。
例えばCustomer/Supplier、公開ホストサービス、公開言語、腐敗防止層は併用し得るため、単一の排他的な分類にしません。
上流・下流はモデル変更の影響や契約の主導関係で定め、API呼び出しやデータ転送の向きとは別に記録します。対等な協調関係も表せるようにします。

サブドメインの担当範囲は [コンテキストの作成ガイド](../35_bounded_contexts/README.md) に従って記録します。
連携一覧とグラフレンズの使い方は [操作・検証ガイド](../../OPERATIONS.md) を参照してください。
