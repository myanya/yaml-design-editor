# YAML Design Editor

Kotlin + Compose for Desktop 製の、スキーマ駆動の YAML エディタです。要求・設計・課題などの設計情報を YAML 文書として管理し、ワークスペースに置いたスキーマ（`_schemas/*.yaml`）からフォームを自動生成して編集します。文書をまたぐ ID の索引と参照の解決、設計の機械的な検証、AI による作成支援まで、ひとつのツールで行えます。

## 特徴

- **スキーマ駆動のフォーム編集**: フォーム／ソース／差分の3モード。スキーマの無い文書も汎用のツリーで編集できる
- **ID と参照の索引**: リンクジャンプ・補完・未解決の検出・バックリンク・同じ ID の出現をまとめて見るエンティティページ
- **設計の機械的な検証**: スキーマ検証に加え、関係の多重度・詳細文書の存在・パターン制約・網羅マトリクスを YAML の定義体で宣言する
- **ビュー**: 複数の文書を横断する一覧・マトリクス・文書ビューを射影として定義し、その場で原本を編集する
- **AI による作成支援とエージェント実行**: OpenAI / Anthropic 連携。提案は変更セットとして人間が承認してから原本へ書く
- **ヘッドレス CLI**: GUI なしで探索・検索・パッチ・検証・エージェント実行・入出力変換を行う

## クイックスタート

要件: JDK 17 以上。Gradle Wrapper を同梱しています。

```bash
./gradlew run                                    # スタート画面から開く
./gradlew run --args="path/to/workspace"         # ワークスペースを指定して開く
./gradlew runCli -q --args="help"                # ヘッドレス CLI のコマンド一覧
./gradlew runCli -q --args="init my-ws --template ledger"   # テンプレートからワークスペースを作る
./gradlew packageDistributionForCurrentOS        # 配布物（msi / dmg / deb）
```

- `samples/`: 公開用のサンプル（テーマ別に整備中）
- `labs/`: 開発用の実験場。`labs/sysdev-template` は、会議室予約システムを題材に設計の機械的な検証を試せるワークスペース

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/MANUAL.md](docs/MANUAL.md) | 利用者マニュアル（画面・操作・スキーマ定義リファレンス・検証・CLI・既知の制約） |
| [tutorial.md](tutorial.md) | チュートリアル（タスク管理を題材に、文書の作成からスキーマ・検証まで） |
| [docs/FEATURES.md](docs/FEATURES.md) | 機能一覧と実装状況 |
| [docs/architecture/](docs/architecture/README.md) | 設計（主題ごとに 1 ファイル。部品・流れ・規律・拡張ポイント） |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想とロードマップ |
| [CLAUDE.md](CLAUDE.md) | AI コーディングエージェント向けの作業ガイド |

## バージョン

利用者に見せる外部バージョン（メジャー.マイナー.パッチ）と、開発管理用の内部バージョン（yyyymmdd.ビルド番号）を分けて持ちます。スタート画面と `./gradlew runCli -q --args="version"` で確認できます。

## ライセンス

MIT License（[LICENSE](LICENSE)）。Copyright (c) 2026 myanya, toros
