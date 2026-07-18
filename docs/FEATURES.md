# 機能一覧と実装状況

本書はプロジェクトの全機能を管理する台帳です。機能の追加・変更・削除を行ったら**必ず本書を更新**してください。

- 利用方法・仕様の詳細は [README.md](../README.md)、実装構造は [ARCHITECTURE.md](ARCHITECTURE.md) を参照（本書には重複記載しない）。
- 中長期の将来構想・新機能計画は [ROADMAP.md](ROADMAP.md) で管理する。本書の §9 は**現行実装の制約**のみを扱う。
- **状態**: ✅ 実装済み ／ 🚧 一部実装 ／ 📋 未実装（計画のみ）
- **テスト**: `単体` = src/test 配下に単体テストあり ／ `手動` = 手動確認のみ

## 1. ワークスペース・ファイル管理

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| W-01 | ワークスペース選択（起動引数／ダイアログ／`sample-workspace` 自動オープン） | ✅ | `Main.kt`, `AppState.openWorkspace` | 手動 |
| W-02 | ファイルツリー表示（展開／折りたたみ、未保存●表示） | ✅ | `ui/FileTreePane.kt` | 手動 |
| W-03 | ファイル／ディレクトリの作成・改名・削除（未保存保護、削除確認、新規YAMLはスキーマ初期値で生成） | ✅ | `AppState.createYaml` ほか, `ui/FileTreePane.kt` | 手動 |
| W-04 | 外部変更検出（約1秒ポーリング、未編集は自動再読込、未保存は競合バナーで選択） | ✅ | `AppState.pollExternalChanges`, `Main.kt` | 手動 |
| W-05 | 最近開いたファイル履歴（12件、ワークスペース単位で Preferences に永続化） | ✅ | `AppState.recentFiles` | 手動 |
| W-06 | UIレイアウト永続化（左右ペイン幅・開閉、テーマ選択） | ✅ | `Main.kt`（`uiPreferences`） | 手動 |
| W-07 | 未保存確認（アプリ終了時・ワークスペース切替時） | ✅ | `Main.kt` | 手動 |

## 2. 文書編集

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| E-01 | スキーマ駆動フォーム（全12型のエディタ、必須マーク、説明表示、折りたたみ） | ✅ | `ui/FormView.kt` | 手動 |
| E-02 | 汎用ツリーエディタ（スキーマなし文書、スキーマ外キーの汎用行表示） | ✅ | `ui/FormView.kt`（`GenericEditor`） | 手動 |
| E-03 | ソース編集（行番号、文書内検索、「変更を反映」、パースエラーの行・列表示） | ✅ | `ui/SourcePane.kt` | 手動 |
| E-04 | 差分表示（保存済み vs 編集中、LCSによる行整列、空行差の弱調表示） | ✅ | `ui/DiffPane.kt` | 単体（`DiffPaneTest`） |
| E-05 | Undo/Redo（正規化テキストのスナップショット履歴、最大100件） | ✅ | `app/OpenDocument` | 手動 |
| E-06 | 保存／すべて保存（Ctrl+S / Ctrl+Shift+S、ソース編集中は反映→保存） | ✅ | `AppState.save*`, `ui/SourcePane.kt` | 手動 |
| E-07 | 文書メタデータ `_document`（id / name / description）の編集・ヘッダー表示 | ✅ | `ui/FormView.kt`（`DocumentMetadataEditor`）, `index/IdIndex.kt` | 手動 |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集をメモリ保持） | ✅ | `AppState.docCache` | 手動 |

## 3. スキーマ

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| S-01 | スキーマ読込（`_schemas/*.yaml`、`match` glob 先勝ち、id 重複検出） | ✅ | `schema/Schema.kt`（`SchemaLoader`） | 手動 |
| S-02 | 型システム12種（string/text/int/number/boolean/enum/date/id/ref/array/map/object） | ✅ | `schema/Schema.kt`（`FieldType`） | 手動 |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`、属性のオーバーライド合成） | ✅ | `SchemaDef.resolve` | 手動 |
| S-04 | メタスキーマ検証（必須情報、キー重複、子定義不足、enum選択肢、未解決 `$ref`） | ✅ | `SchemaDef.validateDefinition` | 手動 |
| S-05 | スキーマ設計UI（設計／プレビュー／適用対象タブ、競合スキーマ表示） | ✅ | `ui/SchemaEditor.kt` | 手動 |
| S-06 | サンプルYAML生成（プレビュー・クリップボードコピー・新規ファイル初期値） | ✅ | `SchemaDef.generateSample` | 手動 |
| S-07 | スキーマ外キーの検証ポリシー（`unknownFields: allow / warning / error`、未知サブツリーは走査境界） | ✅ | `validate/Validator.kt`, `index/IdIndex.kt` | 手動 |
| S-08 | 参照の関係種別（`type: ref` の `relation`。表示・カバレッジ判定・`$ref`合成に対応） | ✅ | `schema/Schema.kt`, `index/IdIndex.kt`, `ui/SchemaEditor.kt` | 単体（`WorkspaceCliTest`） |
| S-09 | カバレッジルール定義（スキーマの `coverage`: 対象IDパターン・参照元スキーマ・関係・必要数・重大度） | ✅ | `schema/Schema.kt`（`CoverageRule`） | 単体（`WorkspaceCliTest`） |

## 4. 検証

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| V-01 | スキーマ適合検証（必須／型不一致／enum選択肢外／date形式警告） | ✅ | `validate/Validator.kt` | 手動 |
| V-02 | 参照解決検証（未解決エラー、パターン・スコープ対象外の区別、未保存IDの考慮） | ✅ | `validate/Validator.kt` | 手動 |
| V-03 | ID重複検出（同一文書内のみエラー。文書間の同一IDは段階的詳細化として許容） | ✅ | `validate/Validator.kt` | 手動 |
| V-04 | 検証パネル（クリックで該当箇所へジャンプ、スキーマ定義エラーも合流表示） | ✅ | `ui/SidePanel.kt` | 手動 |
| V-05 | カバレッジ検証（「被参照必須」等のトレーサビリティ充足を機械判定。索引＝保存済み状態基準） | ✅ | `validate/Validator.kt`（`coverageStatuses`） | 単体（`WorkspaceCliTest`） |

## 5. ID・参照（リンク）

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| I-01 | ID索引（ディスク基準。`type: id` ＋ スキーマなし文書のキー名 `id` を索引化、documentId 管理） | ✅ | `index/IdIndex.kt`（`DocScanner`） | 手動 |
| I-02 | 参照認識（`type: ref` ＋ 命名規約ヒューリスティクス `ref`/`*_ref`/`refs`/`depends_on` 等） | ✅ | `index/IdIndex.kt`（`DocScanner`） | 手動 |
| I-03 | リンクジャンプ（→ボタン、ハイライト＋自動スクロール、複数出現時は選択ダイアログ） | ✅ | `AppState.navigateToId`, `ui/Common.kt`（`Highlightable`） | 手動 |
| I-04 | 参照候補の補完（`idPatterns` / `scope.schemaIds` / `scope.documentIds` で絞り込み） | ✅ | `ui/FormView.kt`（`RefEditor`）, `index/IdIndex.kt` | 手動 |
| I-05 | バックリンク（被参照一覧、クリックで参照元へジャンプ） | ✅ | `IdIndex.backlinksTo`, `ui/SidePanel.kt` | 手動 |
| I-06 | ID検索（ツールバー。ID・タイトル部分一致） | ✅ | `Main.kt`（`IdSearchBox`） | 手動 |
| I-07 | ID横ぐしビュー（同一論理IDの全出現・要素からの参照・被参照・カバレッジ充足を1画面に集約） | ✅ | `ui/IdCrossView.kt`, `AppState.openCrossView` | 手動 |

## 6. ワークスペース横断ビュー

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別、文字列／種別／状態で絞り込み） | ✅ | `ui/WorkspaceViews.kt`, `AppState.workspaceRecords` | 手動 |
| X-02 | 一括編集（ルートの `status` / `owner`。確認ダイアログ、未保存文書があれば拒否） | ✅ | `AppState.bulkUpdate` | 手動 |
| X-03 | 参照グラフ（円形配置の簡易描画＋参照一覧。40ノード／500参照に制限） | ✅ | `ui/WorkspaceViews.kt` | 手動 |
| X-04 | コマンドパレット（Ctrl+K。ビュー切替・保存・ファイル検索・ID移動） | ✅ | `Main.kt`（`CommandPalette`） | 手動 |

## 7. AIによる作成支援

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| A-01 | フィールド単位の生成（ラベル横 ✨ アイコン） | ✅ | `ui/AiGenerationDialog.kt`, `ui/FormView.kt` | 手動 |
| A-02 | 配列への候補追加／全体置き換えの選択 | ✅ | `ui/AiGenerationDialog.kt`（`combineProposal`） | 単体（`AiProposalTest`） |
| A-03 | 文書全体の生成（`_document` メタデータは保持） | ✅ | `ui/AiGenerationDialog.kt`（`replaceInClone`） | 単体（`AiProposalTest`） |
| A-04 | スキーマ→JSON Schema 変換による Structured Outputs（`map` 型を含む場合はプロンプト指示にフォールバック） | ✅ | `ai/AiGenerator.kt`（`fieldToJsonSchema`, `containsMap`） | 単体（`OpenAiGeneratorTest`） |
| A-05 | REF候補の enum 制約（`idPatterns`/`scope` で絞り込み、200件超は省略） | ✅ | `ai/AiGenerator.kt`（`refCandidateIds`） | 単体（`AiGeneratorTest`） |
| A-06 | 適用前の差分プレビューと適用後検証（エラー・警告数）、Undo可能な適用 | ✅ | `ui/AiGenerationDialog.kt` | 手動 |
| A-07 | プロバイダ切替（OpenAI Responses API / Anthropic Messages API）、モデル設定の永続化、APIキーはメモリのみ | ✅ | `ai/OpenAiGenerator.kt`, `ai/AnthropicGenerator.kt`, `AiSessionSettings` | 単体（レスポンス解析） |
| A-08 | 生成のキャンセル、拒否・トークン上限のエラーハンドリング | ✅ | `ui/AiGenerationDialog.kt`, `ai/*Generator.kt` | 単体（一部） |

## 8. コアAPI・CLI（エージェント基盤）

UIに依存しないヘッドレス操作の層（[ROADMAP.md](ROADMAP.md) P1 の成果）。CLI の使い方は README §13 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| C-01 | ヘッドレスワークスペースAPI（開く・読む・保存・検証・相対パス解決の脱出防止） | ✅ | `core/Workspace.kt` | 単体（`WorkspaceCliTest`） |
| C-02 | YPathパッチ（set / insert / delete / move）と YPath 文字列パース | ✅ | `core/Patch.kt`（`Patcher`）, `model/YNode.kt`（`parseYPath`） | 単体（`PatchTest`, `YPathTest`） |
| C-03 | 変更セット（複数ファイルのパッチ束。baseハッシュ競合検出、全件成功時のみ書込、`--require-valid` ゲート、ドライラン） | ✅ | `core/Patch.kt`（`ChangeSets`）, `core/Workspace.kt`（`apply`） | 単体（`WorkspaceCliTest`） |
| C-04 | 段階的開示API（カタログ→骨子→ノード全文の3段階、文字数予算付き文脈バンドル） | ✅ | `core/Disclosure.kt` | 単体（`WorkspaceCliTest`） |
| C-05 | 構造検索API（本文・キーの部分一致＋スキーマ／ファイルglob／パスglob条件、参照検索・未解決参照検索） | ✅ | `core/Search.kt` | 単体（`WorkspaceCliTest`） |
| C-06 | CLI（catalog / outline / read / bundle / search / validate / apply / hash。出力はUTF-8のYAML） | ✅ | `core/Cli.kt`, `build.gradle.kts`（`runCli`） | 単体（`WorkspaceCliTest` 一気通貫） |

## 9. 未実装・既知の制約

利用者向けの注意事項でもあるため、変更時は README の「既知の制約」節との整合を保つこと。

| ID | 内容 | 状態 | 備考 |
|---|---|---|---|
| L-01 | YAMLコメント・アンカー・スタイルの保持 | 📋 | SnakeYAML でブロックスタイル・インデント2に正規化される。対応するなら `YamlIO` を Node API ベースへ差し替え（[ARCHITECTURE.md](ARCHITECTURE.md) 拡張ポイント参照） |
| L-02 | 複数ドキュメント（`---` 区切り）YAML | 📋 | 先頭ドキュメントのみ対象 |
| L-03 | ソースエディタの構文ハイライト・置換 | 📋 | 行番号・検索・エラー位置表示までは実装済み |
| L-04 | 文字単位の差分表示 | 📋 | 行単位のLCS整列まで実装済み |
| L-05 | 汎用ビューでのキー名変更 | 📋 | 現状は「削除→追加」で代替 |
| L-06 | 参照グラフの本格的なレイアウト・全件表示 | 🚧 | 描画40ノード・一覧500参照に制限した簡易実装 |
| L-07 | 一括編集の対象キー拡張 | 🚧 | ルートの `status` / `owner` のみ |
| L-08 | ファイル監視のOSネイティブ化（WatchService等） | 📋 | 現状は1秒間隔のタイムスタンプポーリング |
| L-09 | フォーム系UI・AppState の自動テスト | 🚧 | 単体テストは ai／diff／AI提案合成／core（パッチ・開示・検索・CLI）／カバレッジ検証。Compose UI と AppState は手動確認 |
| L-10 | AppState の core.Workspace への全面委譲 | 🚧 | 走査・検証・パッチは共通実装を共有済み。文書キャッシュ・外部同期の統合は P2（ステージング導入）で扱う |
