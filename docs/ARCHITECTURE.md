# アーキテクチャ（クラス設計と設計方針）

本書は実装の構造と、変更時に守るべき設計方針を定義します。構造や方針に影響する変更を行ったら**必ず本書を更新**してください。機能の有無・実装状況は [FEATURES.md](FEATURES.md)、利用者向けの仕様は [README.md](../README.md) を参照。

## 1. 技術スタック

- Kotlin (JVM 17) + Compose for Desktop + Material 3
- YAML入出力: SnakeYAML
- AI連携: Java 11 HttpClient + kotlinx-serialization-json（外部SDK不使用）
- 永続化: `java.util.prefs.Preferences`（UIレイアウト・テーマ・履歴・AIモデル設定）
- ビルド: Gradle（Wrapper 同梱）、テスト: kotlin.test

## 2. パッケージ構成と依存方向

```
src/main/kotlin/
├── model/     YNode: YAMLの可変ツリー表現、YPath          （依存なし）
├── yaml/      YamlIO: SnakeYAML との相互変換               （→ model）
├── schema/    スキーマモデル・ローダ・メタ検証・glob       （→ model, yaml）
├── index/     ID索引・参照スキャナ                          （→ model, schema, yaml）
├── validate/  スキーマ検証・参照解決・重複検出              （→ model, schema, index, app）
├── ai/        AI生成: JSON Schema変換・プロバイダクライアント（→ model, schema, index）
├── app/       AppState / OpenDocument: アプリ状態・ファイル操作（→ model, yaml, schema, index）
├── ui/        Composeビュー一式                             （→ 上記すべて）
└── Main.kt    エントリポイント・ウィンドウ・ツールバー・パレット（→ app, ui, index）
```

**依存ルール**: `model` → `yaml` → `schema` → `index` → `app` → `ui` の一方向。逆流させない。

- `model` / `yaml` / `schema` / `index` / `validate` / `ai` は **Compose 非依存**（純粋ロジック）。単体テストはこの層に書く。
- `app` は Compose の `mutableStateOf` のみに依存（UI部品には依存しない）。
- `ai` はネットワークを伴うが、リクエスト構築・レスポンス解析は純粋関数として分離しテスト可能にしている。

## 3. 主要クラスと責務

| クラス | ファイル | 責務 |
|---|---|---|
| `YNode` (Scalar/Seq/Map) | model/YNode.kt | YAMLの可変ツリー。`YPath`（String=キー, Int=索引）で位置を表現 |
| `YamlIO` | yaml/YamlIO.kt | テキスト⇔`YNode` 変換。表層情報を保持しない正規化ダンプ（ブロック・インデント2） |
| `FieldType` / `FieldDef` / `SchemaDef` | schema/Schema.kt | スキーマモデル。`resolve()` が `$ref` を属性合成しつつ解決 |
| `SchemaLoader` | schema/Schema.kt | `_schemas/` の読込・パース・スキーマID重複検出 |
| `validateDefinition` / `generateSample` | schema/Schema.kt | メタスキーマ検証・サンプルデータ生成（拡張関数） |
| `globToRegex` / `idMatchesPattern` | schema / index | glob→正規表現変換（パス用と ID 用で対応構文が異なる点に注意） |
| `DocScanner` | index/IdIndex.kt | 1文書からのID・参照抽出。スキーマ有無で検出規則を切替、未知キー境界で走査停止 |
| `IdIndex` | index/IdIndex.kt | ワークスペース全体の `id -> 出現一覧` 索引と全参照。**ディスク基準**（未保存編集は反映されない） |
| `Validator` | validate/Validator.kt | 開いている文書の検証。スキーマ適合＋参照解決＋文書内ID重複 |
| `OpenDocument` | app/AppState.kt | 開いている1文書。ツリー・履歴（Undo/Redo）・dirty・外部競合の管理 |
| `AppState` | app/AppState.kt | ワークスペース・文書キャッシュ・索引・ナビゲーション・ファイル操作・一括更新・外部変更検出 |
| `AiGenerator` ほか | ai/ | スキーマ→JSON Schema 変換、REF候補の enum 制約、プロバイダ振り分け |
| `OpenAiGenerator` / `AnthropicGenerator` | ai/ | 各API（Responses / Messages）のリクエスト構築とレスポンス解析 |
| `FormView` / `FormCtx` | ui/FormView.kt | スキーマ駆動フォームと汎用エディタ（中核UI）。`FormCtx` が編集操作と参照解決を仲介 |
| `Slot` (Map/Seq/Root) | ui/Common.kt | 「値の置き場所」の抽象。後述 |
| `AiGenerationHost` | ui/AiGenerationDialog.kt | AI生成ダイアログの状態機械（入力→生成→プレビュー→適用）と提案合成 |
| `SchemaEditor` | ui/SchemaEditor.kt | `_schemas` 用の構造化エディタ（設計・プレビュー・適用対象） |
| その他 ui | FileTreePane / SourcePane / DiffPane / SidePanel / WorkspaceViews / Common | 各ペイン。[FEATURES.md](FEATURES.md) の対応表を参照 |

## 4. 設計方針（変更時に守ること）

### 4.1 ツリーが単一の正（single source of truth）

- `OpenDocument.root: YNode` が唯一の編集対象。フォームはツリーを**直接ミューテート**し、`markEdited()`（= `version` インクリメント）で再コンポーズを駆動する。
- `YNode` は意図的に「Composeの`State`ではない単純な可変オブジェクト」。UI側は `doc.version` / `state.indexVersion` を読むことで再コンポーズを購読する。新しいビューを書くときはこの購読を忘れないこと。
- ソース編集は例外的にテキストを持つが、「変更を反映」でパースしてツリーに合流させる。保存は常に `canonicalText()`（ツリーの正規化ダンプ）を書き出す。

### 4.2 履歴・dirty 判定は正規化テキストで行う

Undo/Redo は `YamlIO.dump` したスナップショット文字列の履歴（最大100件）。dirty は保存時スナップショットとの文字列比較。ツリーの構造比較は実装しない。

### 4.3 Slot 抽象

マップのキー（`MapSlot`）・配列要素（`SeqSlot`）・文書ルート（`RootSlot`）を `Slot`（get/set/remove）で統一し、スキーマフォームと汎用エディタがエディタ部品を共有する。新しい「値の置き場所」（例: `_document` メタデータ）が必要なら `Slot` 実装を追加する。

### 4.4 YPath ベースのナビゲーション

要素位置は `YPath = List<Any>` で統一。検証結果・ハイライト・ジャンプ・AI生成対象はすべて YPath で紐付く。`state.highlightPath` に設定すると `Highlightable` がスクロール＋一時ハイライトする。

### 4.5 索引はディスク基準

`IdIndex` は保存済みファイルから再構築される（開く・再読込・保存・外部変更・一括編集のタイミング）。未保存編集中の参照解決は、`DocScanner.scan` を編集中ツリーへ都度実行した「ローカルID」を索引に**重ねて**判定する（`FormCtx.resolvable`, `Validator`）。この二層構造を崩さないこと。

### 4.6 ID・参照のセマンティクス

- 同一IDの複数文書出現は「同一論理要素の段階的詳細化」として正当。エラーは同一文書内の重複のみ。
- 参照の解決可否は `idPatterns` × `scope.schemaIds` × `scope.documentIds` のAND（各配列内はOR）。候補表示と検証で同じ判定（`IdEntry.isAllowedBy`）を使う。判定ロジックを追加する場合も候補・検証・AI制約（`refCandidateIds`）の三箇所で一貫させる。

### 4.7 スキーマの `$ref` 解決

`SchemaDef.resolve()` は参照先定義に呼び出し側の `key`/`label`/`required` 等を合成して返す。フィールドを使う側は**必ず** `schema.resolve(field)` を通す（`FormView`・`Validator`・`DocScanner`・`ai` すべて同じ流儀）。

### 4.8 安全性の原則

- 破壊的操作（削除・一括編集・ワークスペース切替・終了）は、未保存文書があれば拒否または確認ダイアログを挟む。
- 外部変更は dirty でなければ自動反映、dirty なら競合バナーで利用者に選択させる。無断上書きはしない。
- AI提案は必ずプレビュー→明示的な適用、適用は Undo 可能、自動保存しない。APIキーはディスクへ保存しない。

### 4.9 AI生成の構造保証

生成値の型保証は「対象フィールドのスキーマ→JSON Schema 変換＋Structured Outputs」で行い、プロンプトに依存しない。ただし `map` 型（additionalProperties がスキーマ）を含む場合のみ構造化出力を無効化してプロンプト指示にフォールバックする。生成結果は `value` プロパティに包んだレスポンススキーマで受け、`parseGeneratedValue` で `YNode` 化する。

### 4.10 エラー処理

- ファイルI/O・パースは `runCatching` で受け、ユーザー操作の結果は `Result` を返して呼び出し側（UI）が `state.status` に反映する。
- 入力検証の失敗は `require(...) { "日本語メッセージ" }`、内部異常は `error("日本語メッセージ")`。メッセージはそのままUIに表示される前提で書く。

## 5. 主要データフロー

- **編集**: フォーム操作 → `Slot.set` / ツリー直接変更 → `markEdited()` → `version`++ → 再コンポーズ＋履歴追加
- **保存**: `canonicalText()` をファイルへ書き出し → スキーマファイルなら再読込 → `rebuildIndex()` → `indexVersion`++
- **ジャンプ**: `navigateToId` → 索引の出現検索（複数なら選択ダイアログ）→ `openFile(file, focus)` → `highlightPath` 設定 → `Highlightable` がスクロール
- **AI生成**: ✨/ボタン → `AppState.requestAi(field, path)` → `AiGenerationHost` が現ツリーを複製して入力構築 → プロバイダ呼び出し → `combineProposal`/`replaceInClone` で適用後ツリーを合成 → 差分プレビュー＋`Validator` で事前検証 → 適用（`doc.root` 差し替え）

## 6. 拡張ポイント

- **フィールド型の追加**: `FieldType` に enum を追加し、`Validator.checkField`・`FormView.ValueEditor`・`ai.fieldToJsonSchema`・`SchemaDef.generateSample`/`defaultNode` に分岐を追加する。
- **コメント保持**: `YamlIO` を SnakeYAML の Node API（`LoaderOptions.setProcessComments(true)`）ベースに差し替える。ツリー変換層が `YamlIO` に隔離されているため影響範囲は限定的。
- **横断ビューの追加**: データソースとして `AppState.workspaceRecords()` と `IdIndex` を利用し、`Main.kt` の `workspaceView` 分岐に追加する。
- **AIプロバイダの追加**: `AiProvider` に enum を追加し、`AiGenerator.generate` の分岐と専用クライアント（`generate` + `parseResponse` を持つ object）を実装する。
