# CLAUDE.md

Kotlin + Compose for Desktop 製のスキーマ駆動YAMLエディタ。ワークスペース内のYAML文書を、`_schemas/*.yaml` のスキーマに基づくフォーム／ソース／差分で編集し、ID索引・参照解決・検証・AI生成支援を提供する。

## コマンド

```bash
./gradlew test                              # 単体テスト（コミット前に必ず実行）
./gradlew run                               # sample-workspace で起動
./gradlew run --args="/path/to/workspace"   # ワークスペース指定で起動
./gradlew runCli -q --args="help"           # ヘッドレスCLI（GUIなしで探索・検索・パッチ・検証。README §13）
./gradlew packageDistributionForCurrentOS   # 配布物作成
```

要件: JDK 17+。GUIアプリのため `run` は対話的に閉じる必要がある（検証はテスト・ビルド・`runCli` で行う）。

## ドキュメント体系（必ず参照・更新する）

| ドキュメント | 役割 |
|---|---|
| [README.md](README.md) | 利用者マニュアル（操作・スキーマ定義リファレンス・制約） |
| [docs/FEATURES.md](docs/FEATURES.md) | 機能台帳: 全機能の一覧・実装状況・テスト有無・未実装項目 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | クラス設計・設計方針・データフロー・拡張ポイント |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約（スタイル・Compose作法・テスト・ドキュメント更新規約） |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想（サンドボックス型AI実行環境化）とフェーズ別ロードマップ |

**作業のワークフロー:**

1. **着手前**: docs/FEATURES.md で対象機能の位置（実装ファイル・テスト有無）を確認し、docs/ARCHITECTURE.md の該当する設計方針（特に §4）を読む。
2. **実装中**: docs/CONVENTIONS.md に従う。重要: UI文言・コメント・エラーメッセージは日本語／純粋ロジック層に Compose を持ち込まない／ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、変更内容に応じてドキュメントを更新する（対応表は docs/CONVENTIONS.md §6）。機能を追加・変更したのにドキュメントが差分に含まれない場合は、更新漏れを疑うこと。`/sync-docs` スキルでチェックできる。

## 落とし穴（要注意）

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する。読み忘れると画面が更新されない。
- `IdIndex` はディスク基準。未保存編集の参照解決は `DocScanner.scan` によるローカルIDを重ねて判定する二層構造（docs/ARCHITECTURE.md §4.5）。
- スキーマの `FieldDef` を使う際は必ず `schema.resolve(field)` を通す（`$ref` 解決）。
- フィールド型を追加する場合の変更箇所は docs/ARCHITECTURE.md §6 に列挙されている（4箇所以上に分岐が必要）。
