# 開発規約

本書はコードとドキュメントの書き方の規約を定義します。設計上の方針（依存方向・状態管理・安全性の原則）は [ARCHITECTURE.md](ARCHITECTURE.md) を参照。規約を変更したら本書を更新してください。

## 1. 言語・コードスタイル

- Kotlin 公式コードスタイル（`kotlin.code.style=official`）。インデント4スペース、1行はおおむね120桁まで。
- KDoc・コード内コメント・コミットメッセージ・UI文言・エラーメッセージは**日本語**で書く（識別子は英語）。
- コメントは「何をするか」の逐語訳ではなく、制約・理由・注意点を書く（例: 「索引はディスク基準のため未保存編集は反映されない」）。
- パッケージごとの配置は [ARCHITECTURE.md](ARCHITECTURE.md) §2 の依存ルールに従う。`model`〜`ai` の純粋ロジック層に Compose を import しない。

## 2. Compose UI の書き方

- 画面単位で `ui/` に1ファイル。ファイル内の大区分は `// ================ 見出し` 形式のセクションコメントで区切る。
- `YNode` ツリーを表示するコンポーザブルは、先頭で `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する（読み忘れると更新されない）。
- **`Dispatchers.Main` は使わない**（本プロジェクトの classpath には Main dispatcher の提供モジュールがなく、実行時例外になる）。バックグラウンド処理からUIへ反映するときは、スナップショット状態（`mutableStateOf` / `mutableStateListOf`）を直接更新する——スナップショット状態はスレッド安全で、変更は次フレームの再コンポーズに反映される。
- ツリーの変更は必ず `ctx.markEdited()`（または `doc.markEdited()`）とセットで行う。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape` を使う。生の `Color(0x...)` や Material 既定色をビューに直接書かない（テーマ切替が壊れるため）。
- 文字サイズの目安: 見出し 13–16sp、本文 12–13sp、補足 10–11sp。
- 破壊的操作（削除・破棄・一括変更）は `AlertDialog` で確認し、確定ボタンの文言に `Palette.ErrorRed` を使う。

## 3. エラー処理・検証

- ユーザー操作に対する失敗は例外を漏らさず `Result` / `runCatching` で受け、`state.status` へ日本語メッセージで反映する。
- 事前条件は `require(...) { "利用者に見せられる日本語メッセージ" }`。
- 検証結果は `Issue(severity, message, path)` に統一し、必ず `YPath` を紐付けてジャンプ可能にする。

## 4. テスト

- テストは `src/test/kotlin` にプロダクトと同じパッケージで置き、`kotlin.test` を使う。
- テスト名はバッククォートの文と日本語コメントで意図を明示する（例: `` fun `idPatterns filter candidates`() ``）。
- UIコンポーザブルは自動テスト対象外。**テストしたいロジックはコンポーザブルから純粋関数へ切り出し**、`internal` にしてテストする（例: `calculateDiff`, `combineProposal`, `parseResponse`）。
- ネットワークは叩かない。APIクライアントはレスポンス解析関数（`parseResponse(statusCode, body)`）を単体テストする。
- 実行: `./gradlew test`（コミット前に通すこと）。

## 5. Git

- コミットメッセージは日本語で「何を・なぜ」を要約する（既存履歴のスタイルに合わせる）。
- 論理的に独立した変更は分けてコミットする。
- `sample-workspace/` と `gradle/` は `.gitignore` 対象（履歴に含めない）。

## 6. ドキュメント更新規約（重要）

ドキュメントは以下の4つに役割分担しており、**同じ内容を複数に書かない**。迷ったら役割で判断し、他方にはリンクを張る。

| ドキュメント | 役割 | 更新するタイミング |
|---|---|---|
| [README.md](../README.md) | 利用者マニュアル（ビルド・操作方法・スキーマ定義リファレンス・制約） | 利用者から見える仕様・操作・UIが変わったとき |
| [docs/FEATURES.md](FEATURES.md) | 機能台帳（一覧・実装状況・テスト有無・未実装） | 機能の追加・変更・削除、テスト追加、制約の解消 |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | クラス設計・設計方針・拡張ポイント | パッケージ／クラスの追加・移動、設計方針・データフローの変更 |
| [docs/ROADMAP.md](ROADMAP.md) | 将来構想・フェーズ別ロードマップ | 方向性・優先度の変更、ロードマップ項目（Rx-xx）の着手・完了（完了時は FEATURES.md へ移す） |
| [docs/CONVENTIONS.md](CONVENTIONS.md)（本書） | 書き方の規約 | 規約自体を変えるとき |

作業のたびに次を確認する:

1. **着手前**: 対象領域の FEATURES.md の該当行と ARCHITECTURE.md の該当節を読む。
2. **実装後**: 上の表に従って影響するドキュメントを更新する（機能追加なら FEATURES.md に行を追加し、README に利用方法を書く）。
3. コード内の行番号・件数など変わりやすい値はドキュメントに書かない（クラス名・ファイル名で参照する）。
