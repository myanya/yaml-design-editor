# 仕様書

ユーザ管理システムの仕様書を読む・作成するための入口です。
業務仕様と設計内容の原本は各YAMLに置き、本書は文書体系と参照先を案内します。

## 文書体系

概要で提供範囲を把握し、ルールで判断の根拠を確認します。
サブドメインで業務領域を、コンテキストでモデルの境界を整理し、連携と業務フローを具体化します。

| 種類・作成ガイド | 役割 | 原本への入口 |
|---|---|---|
| [概要](10_overview/README.md) | 目的・提供範囲・システム全体像 | [システム概要](10_overview/overview.yaml) |
| [ルール](20_rules/README.md) | 業務・システムの判断基準 | [ルール一覧](20_rules/0000-rule-lists.yaml) |
| [サブドメイン](30_subdomains/README.md) | 業務領域と戦略的な分類 | [サブドメイン一覧](30_subdomains/0000-subdomain-lists.yaml) |
| [コンテキスト](35_bounded_contexts/README.md) | 用語・モデル・管理責任の境界 | [コンテキスト一覧](35_bounded_contexts/0000-bounded-context-lists.yaml) |
| [コンテキスト間の連携](37_context_maps/README.md) | 境界間で受け渡す情報と責任 | [連携一覧](37_context_maps/0000-context-map-lists.yaml) |
| [業務フロー](40_business_flows/README.md) | 複数の境界を横断する業務の流れ | [業務フロー一覧](40_business_flows/0000-business-flow-lists.yaml) |

詳細ユースケースは `50_usecases/` で具体化する対象です。画面・API・エンティティ定義とあわせて、作成状況・未決事項は [TODO](../TODO.md) で管理します。
ルールから業務フロー・詳細ユースケースへ展開し、具体化で分かった不足を関連ルールに戻して検討します。

## 作成・更新時の参照先

1. [共通記述規約](WRITING.md)で、配置・命名・ID・参照・状態の扱いを確認します。
2. 上表の作成ガイドで、対象文書の項目と記述例を確認します。
3. [操作・検証ガイド](../OPERATIONS.md)で、YDEの操作・ビュー・検証方法を確認します。

進捗と再開位置は [TODO](../TODO.md)、ワークスペース全体の目的と作業方針は [README](../README.md) を参照してください。
