# 機能一覧と実装状況

本書はプロジェクトの全機能を管理する台帳です。機能の追加・変更・削除を行ったら**必ず本書を更新**してください。

- 利用方法・仕様の詳細は [README.md](../README.md)、実装構造は [ARCHITECTURE.md](ARCHITECTURE.md) を参照（本書には重複記載しない）。
- 中長期の将来構想・新機能計画は [ROADMAP.md](ROADMAP.md) で管理する。本書の §10 は**現行実装の制約**のみを扱う。
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
| E-01 | スキーマ駆動フォーム（全12型のエディタ、必須マーク（`requiredFrom` はフェーズ名つきで区別表示）、説明表示、折りたたみ） | ✅ | `ui/FormView.kt` | 手動 |
| E-02 | 汎用ツリーエディタ（スキーマなし文書、スキーマ外キーの汎用行表示） | ✅ | `ui/FormView.kt`（`GenericEditor`） | 手動 |
| E-03 | ソース編集（行番号、文書内検索、「変更を反映」、パースエラーの行・列表示） | ✅ | `ui/SourcePane.kt` | 手動 |
| E-04 | 差分表示（保存済み vs 編集中、LCSによる行整列、空行差の弱調表示） | ✅ | `ui/DiffPane.kt` | 単体（`DiffPaneTest`） |
| E-05 | Undo/Redo（正規化テキストのスナップショット履歴、最大100件） | ✅ | `app/OpenDocument` | 手動 |
| E-06 | 保存／すべて保存（Ctrl+S / Ctrl+Shift+S、ソース編集中は反映→保存。リストは親プロパティから一段下げて整形） | ✅ | `AppState.save*`, `yaml/YamlIO.kt`, `ui/SourcePane.kt` | 単体（`YamlIOTest`）＋手動 |
| E-07 | 文書メタデータ `_document`（id / name / description）の編集・ヘッダー表示 | ✅ | `ui/FormView.kt`（`DocumentMetadataEditor`）, `index/IdIndex.kt` | 手動 |
| E-08 | 文書キャッシュ（ファイル切替時も未保存編集をメモリ保持） | ✅ | `AppState.docCache` | 手動 |
| E-09 | 同一IDの他文書出現ストリップ（フォーム上部。文書ルートのIDが他文書にも出現する場合に相手側のスカラー情報を表示・ジャンプ。詳細文書に一覧側情報を重複記載しない運用を支える） | ✅ | `ui/FormView.kt`（`SameIdOccurrencesStrip`） | 手動 |

## 3. スキーマ

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| S-01 | スキーマ読込（`_schemas/*.yaml`、`match` glob 先勝ち、id 重複検出） | ✅ | `schema/Schema.kt`（`SchemaLoader`） | 手動 |
| S-02 | 型システム12種（string/text/int/number/boolean/enum/date/id/ref/array/map/object） | ✅ | `schema/Schema.kt`（`FieldType`） | 手動 |
| S-03 | 名前付き定義と再帰参照（`definitions` / `$ref`、属性のオーバーライド合成） | ✅ | `SchemaDef.resolve` | 手動 |
| S-04 | メタスキーマ検証（必須情報、キー重複、子定義不足、enum選択肢、`cases` の整合、未解決 `$ref`） | ✅ | `SchemaDef.validateDefinition` | 単体（`SchemaLoaderTest`） |
| S-05 | スキーマ設計UI（スキーマ全体の説明、設計／プレビュー／適用対象タブ、競合スキーマ表示、フィールドの `requiredFrom` 入力）。`phases` / `coverage` はスキーマ全体設定のためソース編集のみ（構造化UI未対応） | ✅ | `ui/SchemaEditor.kt` | 手動 |
| S-06 | サンプルYAML生成（プレビュー・クリップボードコピー・新規ファイル初期値） | ✅ | `SchemaDef.generateSample` | 手動 |
| S-07 | スキーマ外キーの検証ポリシー（`unknownFields: allow / warning / error`、未知サブツリーは走査境界） | ✅ | `validate/Validator.kt`, `index/IdIndex.kt` | 手動 |
| S-08 | 参照の関係種別（`type: ref` の `relation`。表示・カバレッジ判定・`$ref`合成に対応） | ✅ | `schema/Schema.kt`, `index/IdIndex.kt`, `ui/SchemaEditor.kt` | 単体（`WorkspaceCliTest`） |
| S-09 | カバレッジルール定義（スキーマの `coverage`: 対象IDパターン・参照元スキーマ・関係・必要数・重大度） | ✅ | `schema/Schema.kt`（`CoverageRule`） | 単体（`WorkspaceCliTest`） |
| S-10 | enum 値ごとの追加フィールド（`cases`: 判別共用体。case 由来enumの多段展開・フォーム追随・検証・索引・AI生成 `anyOf`・スキーマ設計UI対応） | ✅ | `schema/Schema.kt`（`effectiveFields`）, `validate/Validator.kt`, `index/IdIndex.kt`, `ui/FormView.kt`, `ui/SchemaEditor.kt`, `ai/AiGenerator.kt` | 単体（`SchemaLoaderTest` / `ValidatorTest` / `DocScannerTest` / `AiGeneratorTest`） |
| S-11 | フェーズ機構（ROADMAP P7）: `phases`（順序付きフェーズと申告キー）の宣言、フィールドの `requiredFrom`（累積必須）、カバレッジの `activeFrom`（判定保留） | ✅ | `schema/Schema.kt`（`PhasesDef`, `currentPhaseIndex`, `requiredAt`） | 単体（`PhaseTest`） |

## 4. 検証

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| V-01 | スキーマ適合検証（必須／型不一致／enum選択肢外／date形式警告／非アクティブなcaseキーの案内） | ✅ | `validate/Validator.kt` | 単体（`ValidatorTest`） |
| V-02 | 参照解決検証（未解決エラー、パターン・スコープ対象外の区別、未保存IDの考慮） | ✅ | `validate/Validator.kt` | 手動 |
| V-03 | ID重複検出（同一文書内のみエラー。文書間の同一IDは段階的詳細化として許容） | ✅ | `validate/Validator.kt` | 手動 |
| V-04 | 検証パネル（クリックで該当箇所へジャンプ、スキーマ定義エラーも合流表示、`phases` 宣言スキーマはフェーズ別記入進捗を表示） | ✅ | `ui/SidePanel.kt` | 手動 |
| V-05 | カバレッジ検証（「被参照必須」等のトレーサビリティ充足を機械判定。索引＝保存済み状態基準） | ✅ | `validate/Validator.kt`（`coverageStatuses`） | 単体（`WorkspaceCliTest`） |
| V-06 | フェーズ考慮の検証（ROADMAP P7）: `requiredFrom` は現在フェーズ以降のみ必須エラー化、`coverage.activeFrom` は定義文書のフェーズ到達まで判定保留（「フェーズ未到達」として区別表示）、`phaseProgress` でフェーズ別記入進捗を取得 | ✅ | `validate/Validator.kt`（`checkField`, `coverageStatuses`, `phaseProgress`） | 単体（`PhaseTest`） |

## 5. ID・参照（リンク）

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| I-01 | ID索引（ディスク基準。`type: id` ＋ スキーマなし文書のキー名 `id` を索引化、documentId 管理） | ✅ | `index/IdIndex.kt`（`DocScanner`） | 手動 |
| I-02 | 参照認識（`type: ref` ＋ 命名規約ヒューリスティクス `ref`/`*_ref`/`refs`/`depends_on` 等） | ✅ | `index/IdIndex.kt`（`DocScanner`） | 手動 |
| I-03 | リンクジャンプ（→ボタン、ハイライト＋自動スクロール、複数出現時は選択ダイアログ） | ✅ | `AppState.navigateToId`, `ui/Common.kt`（`Highlightable`） | 手動 |
| I-04 | 参照候補の補完（`idPatterns` / `scope.schemaIds` / `scope.documentIds` で絞り込み） | ✅ | `ui/FormView.kt`（`RefEditor`）, `index/IdIndex.kt` | 手動 |
| I-05 | バックリンク（被参照一覧、クリックで参照元へジャンプ） | ✅ | `IdIndex.backlinksTo`, `ui/SidePanel.kt` | 手動 |
| I-06 | ID検索（ツールバー。ID・タイトル部分一致） | ✅ | `Main.kt`（`IdSearchBox`） | 手動 |
| I-07 | ID横ぐしビュー（同一論理IDの全出現・要素からの参照・被参照・カバレッジ充足を1画面に集約。フェーズ未到達のカバレッジルールは区別表示。各出現は`_views`定義なしでその場編集できる埋め込みフォーム＝スキーマがあれば`ObjectFields`、なければ`GenericEditor`。ROADMAP R7-04） | ✅ | `ui/IdCrossView.kt`, `AppState.openCrossView` / `ensureDoc` | 手動 |

## 6. ワークスペース横断ビュー

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| X-01 | 一覧ビュー（ID・タイトル・状態・担当・種別、文字列／種別／状態で絞り込み） | ✅ | `ui/WorkspaceViews.kt`, `AppState.workspaceRecords` | 手動 |
| X-02 | 一括編集（ルートの `status` / `owner`。確認ダイアログ、未保存文書があれば拒否） | ✅ | `AppState.bulkUpdate` | 手動 |
| X-03 | 参照グラフ（円形配置の簡易描画＋参照一覧。40ノード／500参照に制限） | ✅ | `ui/WorkspaceViews.kt` | 手動 |
| X-04 | コマンドパレット（Ctrl+K。ビュー切替・保存・ファイル検索・ID移動） | ✅ | `Main.kt`（`CommandPalette`） | 手動 |
| X-05 | ビュー定義 `_views`（宣言的な射影: 行=論理IDパターン × 列=出現からの相対YPath。並び順・詳細文書・新規行の宣言、組込みスキーマでフォーム編集・検証、メタアクティビティの提案対象） | ✅ | `core/Views.kt`, `schema/BuiltinSchemas.kt` | 単体（`ViewsTest`） |
| X-06 | ビューグリッド（ビューを編集可能な一覧として表示。スカラーセルの直接編集・複合値はジャンプ・詳細文書の作成・新規行の追加・カバレッジ表示（フェーズ未到達のルールは対象外）・絞り込み） | ✅ | `ui/ViewGridPane.kt`, `AppState.ensureDoc` / `createViewDetail` / `addViewRow` | 単体（core経路は `ViewsTest`）＋手動 |

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

## 8. エージェント実行（アクティビティ）

マルチターンのツール使用ループによるエージェント実行（[ROADMAP.md](ROADMAP.md) P2〜P4 の成果）。利用方法は README §12〜13 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| AG-01 | エージェント定義 `_agents`（persona・観点・モデル設定・ツール許可。組込みスキーマでフォーム編集・検証可能） | ✅ | `agent/Definitions.kt`, `schema/BuiltinSchemas.kt` | 単体（`DefinitionsTest`） |
| AG-02 | アクティビティ定義 `_activities`（対象・指示・文脈レシピ・書込許可ファイル/パス・ツール許可・上限・提出ゲート） | ✅ | `agent/Definitions.kt`, `schema/BuiltinSchemas.kt` | 単体（`DefinitionsTest`） |
| AG-03 | ツール使用対応LLMクライアント（OpenAI Responses / Anthropic Messages の function calling。会話セッション抽象で差し替え可能） | ✅ | `ai/ToolChat.kt` | 単体（`ToolChatParseTest`） |
| AG-04 | エージェント実行カーネル（マルチターンループ、ターン・トークン・時間上限、終了手続きの強制、キャンセル、疑似セッション注入） | ✅ | `agent/Kernel.kt` | 単体（`KernelTest`） |
| AG-05 | エージェントツールセット（探索5・編集2・検証1・対話2・管理3の計13ツール。書込権限はツール側で強制） | ✅ | `agent/Tools.kt` | 単体（`KernelTest`） |
| AG-06 | ステージング（複数文書の作業オーバーレイ。影響ファイルを含む横断検証、ID改名の整合確認、未提出ドラフト保全） | ✅ | `agent/Staging.kt` | 単体（`StagingTest`） |
| AG-07 | 変更セットレビューUI（ファイル単位の部分採用、採用サブセットの再検証、左右差分、未保存文書の保護、適用） | ✅ | `ui/AgentPanel.kt` | 手動 |
| AG-08 | 実行中の人間との対話（`ask_human` によるブロッキング質問と回答、計画・メモの進捗表示、実行キャンセル） | ✅ | `agent/Kernel.kt`, `ui/AgentPanel.kt` | 単体（`KernelTest` 一部）＋手動 |
| AG-09 | 実行記録 `_runs`（ターン・ツール呼び出し・質問と回答・採否・トークン使用量をYAML保存。索引対象外） | ✅ | `agent/RunLog.kt` | 手動 |
| AG-10 | CLIからのアクティビティ実行（`agent` / `activities` コマンド。非対話実行では質問に「回答なし」を返す） | ✅ | `core/Cli.kt` | 手動（スモーク） |
| AG-11 | トリガ（`trigger`: manual / on-change（デバウンス付き） / schedule / on-invalid。予約ディレクトリと自己変更では発火しない） | ✅ | `agent/Automation.kt`（`tick`） | 単体（`AutomationTest`） |
| AG-12 | アクティビティ連鎖（`chain.onSuccess` / `onFailure`。深さ上限・循環検出。人間承認は採否決定後に発火） | ✅ | `agent/Automation.kt`（`chainNext`） | 単体（`AutomationTest`） |
| AG-13 | 無人リトライ（`retry.maxAttempts`。失敗理由と検証エラーをフィードバックし、同一失敗の繰り返しは収束打ち切り） | ✅ | `agent/Automation.kt`（`handleFailure`） | 単体（`AutomationTest`） |
| AG-14 | 自動承認 `approval: auto`（ゲート通過・スコープ内・非編集中のみ。条件を欠くと承認待ちへ降格。適用前バックアップ） | ✅ | `agent/Automation.kt`, `agent/AutoApply.kt` | 単体（`AutomationTest`） |
| AG-15 | 実行キューと停止（直列実行、常時停止＝キュー消去＋実行中キャンセル、承認待ち変更セットのファイル保存による耐久性） | ✅ | `agent/Automation.kt` | 単体（`AutomationTest`） |
| AG-16 | 自動適用の巻き戻し（実行記録のバックアップから復元。適用後の変更は競合として拒否、`--force` で上書き） | ✅ | `agent/AutoApply.kt`（`revert`）, CLI `revert` | 単体（`AutomationTest`） |
| AG-17 | サブエージェント委譲（`delegate` ツール。親のステージングを読める読み取り専用の相談、所見テキストを返す。深さ1） | ✅ | `agent/Kernel.kt`（`consult`）, `agent/Tools.kt` | 単体（`KernelTest`） |
| AG-18 | 自動実行パネルUI（監視ON/OFF・キュー・承認待ちレビュー・履歴・ログ）と CLI `watch`（`--once` 対応） | ✅ | `ui/AutomationPanel.kt`, `core/Cli.kt` | 手動＋単体（エンジン経路） |
| AG-19 | 実行メトリクス（`_runs` の集計: 提出率・採用率・却下ファイル率＝修正量の代理指標・自動適用/巻き戻し・トークン。CLI `metrics`） | ✅ | `agent/Metrics.kt`, `core/Cli.kt` | 単体（`MetricsTest`） |
| AG-20 | 評価セット `_evals`（ゴールデン入力＋宣言的な期待結果による回帰評価。サンドボックスコピー上で実行し原本を変更しない。CLI `eval`） | ✅ | `agent/Evals.kt`, `schema/BuiltinSchemas.kt` | 単体（`EvalTest`） |
| AG-21 | 比較実験（評価セットの `variants`: モデル・エージェント・追加指示を差し替えたA/B実行と合格率・コストの比較） | ✅ | `agent/Evals.kt`（`EvalRunner`） | 単体（`EvalTest`） |
| AG-22 | メタアクティビティ（`meta: true` で `_agents` / `_activities` / `_evals` / `_skills` / `_views` への提案を許可。承認は human 固定、`_schemas`/`_runs` は常に不可、定義のパース可否を提出ゲートに追加） | ✅ | `agent/Definitions.kt`, `agent/Tools.kt`, `agent/Staging.kt` | 単体（`MetaActivityTest`, `DefinitionsTest`） |
| AG-23 | 自己改善パネルUI（メトリクス表示・評価セットの実行と変異別結果の比較） | ✅ | `ui/ImprovementPanel.kt` | 手動＋単体（実行系経路） |
| AG-24 | エージェントへのビュー開示（`read_view` ツール: ステージング反映済みの行×列を原本座標つきで返し、そのまま `apply_patch` の対象にできる。`context.views` による初期文脈への開示） | ✅ | `agent/Tools.kt`（`viewText`）, `agent/Kernel.kt` | 単体（`ViewsTest`） |
| AG-25 | 段階的詳細化のAI導線（ROADMAP P7）: `related` ツールが各出現の主要フィールド要約と現在フェーズ・記入進捗を1回で返す（出現ごとの `read_node` が不要）。`list_documents` のカタログにもフェーズ状態を付与。`context.include: [phase]` で対象文書のフェーズ状態を初期文脈に開示 | ✅ | `agent/Tools.kt`（`relatedText`, `catalogText`, `phaseText`）, `agent/Kernel.kt`, `core/Disclosure.kt`, `validate/Validator.kt`（`phaseSummaryText`） | 単体（`ToolsTest`, `KernelTest`） |

## 9. コアAPI・CLI（エージェント基盤）

UIに依存しないヘッドレス操作の層（[ROADMAP.md](ROADMAP.md) P1 の成果）。CLI の使い方は README §16 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| C-01 | ヘッドレスワークスペースAPI（開く・読む・保存・検証・相対パス解決の脱出防止） | ✅ | `core/Workspace.kt` | 単体（`WorkspaceCliTest`） |
| C-02 | YPathパッチ（set / insert / delete / move）と YPath 文字列パース | ✅ | `core/Patch.kt`（`Patcher`）, `model/YNode.kt`（`parseYPath`） | 単体（`PatchTest`, `YPathTest`） |
| C-03 | 変更セット（複数ファイルのパッチ束＋新規ファイル作成。baseハッシュ競合検出、全件成功時のみ書込、適用後状態のオーバーレイ索引で検証、`--require-valid` ゲート、ドライラン） | ✅ | `core/Patch.kt`（`ChangeSets`）, `core/Workspace.kt`（`apply`） | 単体（`WorkspaceCliTest`） |
| C-04 | 段階的開示API（カタログ→骨子→ノード全文の3段階、文字数予算付き文脈バンドル） | ✅ | `core/Disclosure.kt` | 単体（`WorkspaceCliTest`） |
| C-05 | 構造検索API（本文・キーの部分一致＋スキーマ／ファイルglob／パスglob条件、参照検索・未解決参照検索） | ✅ | `core/Search.kt` | 単体（`WorkspaceCliTest`） |
| C-06 | CLI（catalog / outline / read / bundle / search / view / validate / apply / hash / activities / agent / watch / revert / metrics / eval。出力はUTF-8のYAML。`validate` は `phases` 宣言スキーマの文書に `phaseProgress` を付与） | ✅ | `core/Cli.kt`, `build.gradle.kts`（`runCli`） | 単体（`WorkspaceCliTest` 一気通貫） |
| C-07 | ビュー解決API（`_views` 定義のロード・行×セルへの解決＝原本座標化・データパスからのスキーマフィールド解決・詳細文書スキャフォールド。CLI `view`） | ✅ | `core/Views.kt`, `core/Cli.kt` | 単体（`ViewsTest`） |

## 10. skill・テンプレート（フォーマット適合）

宣言型の入出力変換とテンプレートパック（[ROADMAP.md](ROADMAP.md) P5 の成果）。利用方法は README §14 を参照。

| ID | 機能 | 状態 | 主な実装 | テスト |
|---|---|---|---|---|
| SK-01 | skill定義 `_skills`（kind: import / export の宣言型変換。組込みスキーマでフォーム編集・検証、メタアクティビティの提案対象。コード実行なし） | ✅ | `skill/Skills.kt`, `schema/BuiltinSchemas.kt` | 単体（`ImportExportTest`） |
| SK-02 | インポート（xlsx / csv / json / markdown → 変更セット。新規行は文書作成、既存文書はマップ項目のみ更新＝追記保持。列マッピング: 型変換・値辞書・区切りによる配列化・必須・{列名}によるファイル解決） | ✅ | `skill/Importer.kt`, `skill/Tables.kt` | 単体（`ImportExportTest`, `TablesTest`） |
| SK-03 | エクスポート（対象glob×行（文書 or 配列要素）×列（YPath）→ xlsx / csv / markdown。YAMLへの出力は拒否＝派生成果物の原則） | ✅ | `skill/Exporter.kt`, `skill/Tables.kt` | 単体（`ImportExportTest`） |
| SK-04 | CLI（`skills` / `import`（プレビュー既定・`--apply`・`--require-valid`）/ `export` / `init`） | ✅ | `core/Cli.kt` | 手動（スモーク） |
| SK-05 | テンプレートパック（`init --template ledger`: 課題台帳のスキーマ＋エージェント＋アクティビティ＋評価＋skill一式。既存ファイルは上書きしない） | ✅ | `skill/TemplatePacks.kt` | 単体（`ImportExportTest`） |
| SK-06 | skillパネルUI（一覧・エクスポート実行・インポートのファイル選択→プレビュー→検証つき適用） | ✅ | `ui/SkillsPanel.kt` | 手動＋単体（実行系経路） |

## 11. 未実装・既知の制約

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
| L-09 | フォーム系UI・AppState の自動テスト | 🚧 | 単体テストは ai／diff／AI提案合成／core（パッチ・開示・検索・CLI）／カバレッジ検証／agent（ステージング・カーネル・定義・自動化・メトリクス・評価・メタ）。Compose UI と AppState は手動確認 |
| L-10 | AppState の core.Workspace への全面委譲 | 🚧 | 走査・検証・パッチ・レビュー適用（apply＋reload）は共通実装を共有済み。人間編集との排他は AG-14/15（編集中は自動適用を承認待ちへ降格）で実現。文書キャッシュ層の統合は継続課題 |
| L-11 | AI生成ダイアログ（A-01〜03）とエージェントカーネルの完全統合 | 🚧 | 接続設定（`ui/AiSettings`）は共有済み。実行系の統合は、フィールド生成が Structured Outputs の型保証を持つ利点を崩さない形で検討（ROADMAP R2-09） |
| L-12 | コード実行skill（宣言型で書けない変換・定型手順） | 📋 | サンドボックス外作用のため明示許可制の設計が前提（ROADMAP R5-01）。現状の skill は宣言型の import / export のみ |
| L-13 | エクスポートのHTML・JSON形式、インポートのWord等 | 📋 | 現状は xlsx / csv / markdown（入力は + json）。`skill/Tables.kt` に形式を追加する |
| L-14 | ビューグリッドの行集合はディスク索引基準 | 🚧 | セル値・編集は未保存編集を即時反映するが、行の増減（新しいID）は保存まで反映されない（索引の設計方針 ARCHITECTURE §4.5 に準拠）。セル直接編集はスカラー型のみで、配列・オブジェクトはジャンプして個別フォームで編集する |
| L-15 | 出現ベースのカバレッジルール | 📋 | 現状の `coverage` は被参照数のみ。「一覧の全IDに詳細文書（特定スキーマの出現）があること」の機械検証は未対応（ビューの詳細列で目視確認は可能）。ROADMAP P7（R7-02）で対応予定 |
| L-16 | `phases` / `coverage` のスキーマ設計UI未対応 | 🚧 | スキーマ全体設定のため `_schemas` のソース編集（YAML）で記述する。フィールド単位の `requiredFrom` はスキーマ設計UIに入力欄がある（S-05） |
