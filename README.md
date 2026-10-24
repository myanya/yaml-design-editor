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

Windows ZIP 版は、ZIP をすべて展開し `yaml-design-editor.exe` を起動します。Java の追加インストールは不要です。
同梱の `examples/todos` を作業用フォルダへコピーして開くと試せます。起動・更新の案内は ZIP 内の `README.txt` を参照してください。
以下はソースから起動する開発者向けの手順です。

要件: JDK 21（`./gradlew` を動かす JDK は 17 以上で構いません。ビルドと実行に使う JDK 21 は無ければ Gradle が自動で取得します）。Gradle Wrapper を同梱しています。初回ビルドは等幅フォント Cica（約 23 MB）を GitHub から取得するためネットワーク接続が要ります（取得物は Gradle のユーザーホームに保存され、2 回目からは不要）。

```bash
./gradlew run                                    # スタート画面から開く
./gradlew run --args="path/to/workspace"         # ワークスペースを指定して開く
./gradlew hotRun                                 # 開発時: Compose Hot Reload 付きで起動（コードを保存すると画面に反映）
./gradlew runCli -q --args="help"                # ヘッドレス CLI のコマンド一覧
./gradlew runCli -q --args="init my-ws --template ledger"   # テンプレートからワークスペースを作る
./gradlew packageDistributionForCurrentOS        # 配布物（msi / dmg / deb）
```

- `hotRun` は JetBrains Runtime 21 を Gradle が自動で取得して使います（初回は時間がかかります）。起動中の画面を AI エージェントから操作する MCP サーバ（スクリーンショット・クリック・入力・再読込）は `.mcp.json` に Windows 向けの設定があり、mac / Linux では `cmd /c gradlew.bat` を `./gradlew` に読み替えてください
- `samples/`: 公開用のサンプル。[todos](samples/todos/README.md)は操作できるTODO管理、[todos-sysdev-tablemodel](samples/todos-sysdev-tablemodel/README.md)はその概念・論理・物理モデルとSQL生成の検討
- `labs/`: 開発用の実験場。試作はここで作り、ある程度良いものができたら `samples/` へ置き直す。`labs/sysdev-template` は、会議室予約システムを題材に設計の機械的な検証を試せるワークスペース。`labs/kakeibo` は家計簿を題材に集計と残高の検査を試す試作

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/manual/](docs/manual/README.md) | 利用者マニュアル（画面・スキーマ定義・検証・ビュー・ID・AI・CLI・既知の制約） |
| [tutorial.md](tutorial.md) | チュートリアル（タスク管理を題材に、文書の作成からスキーマ・検証まで） |
| [docs/FEATURES.md](docs/FEATURES.md) | 機能一覧と実装状況 |
| [docs/architecture/](docs/architecture/README.md) | 設計（主題ごとに 1 ファイル。部品・流れ・規律・拡張ポイント） |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約 |
| [docs/releasing.md](docs/releasing.md) | Windows ZIP の作成・検査・公開手順 |
| [チェンジログ](docs/archive/changelog.md) | リリースごとの追加・変更・修正 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想とロードマップ |
| [CLAUDE.md](CLAUDE.md) | AI コーディングエージェント向けの作業ガイド |

## バージョン

利用者に見せる外部バージョン（メジャー.マイナー.パッチ）と、開発管理用の内部バージョン（yyyymmdd.ビルド番号）を分けて持ちます。スタート画面と `./gradlew runCli -q --args="version"` で確認できます。

## ライセンス

MIT License（[LICENSE](LICENSE)）。Copyright (c) 2026 myanya, toros

第三者ライブラリ・フォント・Java ランタイムには各々のライセンスが適用されます。[同梱物の案内](THIRD-PARTY-NOTICES.txt)を参照してください。
配布ビルドは解決済み依存の表示とソース取得先を生成し、アプリの資源ディレクトリ内 `licenses/` へ同梱します。
配布前は `./gradlew test createDistributable --no-parallel` を実行し、生成した表示と同梱ランタイムでの起動を確認してください。
Windows では `./gradlew verifyWindowsDistribution --no-parallel` で、同梱ランタイムによる通信クライアント生成・Office 往復・グラフ配置・表示同梱を検査できます（外部 API への接続なし）。
0.1 の利用条件と既知の制約は[利用者マニュアル](docs/manual/limits.md)に記載しています。
