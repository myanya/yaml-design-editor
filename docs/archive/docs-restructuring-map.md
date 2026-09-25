# ドキュメント再編の対応表（既存の節 → 新体系）

[docs-restructuring-plan.md](docs-restructuring-plan.md) の付属。既存の見出し 1 つごとに、行き先と処置を書く。
作成: 2026-09-24。計画とともに記録として archive に置く（完了 2026-09-25）。

処置の語: **維持**（そのまま）／**移す**（内容を変えずに場所を変える）／**書き直す**（今の姿だけを書く）／**統合**（他の節と 1 つにする）／**分配**（複数の新ファイルへ振り分ける）／**archive**（記録として移す）／**削除**（重複か不要）。
「鮮度確認」は、書き直すときに現状のコードと照合が要る節。

## 1. 文書単位

| 既存 | 行き先 | 処置 |
|---|---|---|
| README.md | README.md | 維持。文書一覧のリンク先を新体系へ |
| CLAUDE.md | CLAUDE.md | 書き直す（5 千字以内。§12） |
| tutorial.md | tutorial.md | 維持 |
| docs/MANUAL.md | docs/manual/（8 ファイル） | 分割（§3） |
| docs/ARCHITECTURE.md | docs/architecture/（12 ファイル） | 書き直す＋分配（§2）。原本は archive/architecture-2026-09.md |
| docs/FEATURES.md | docs/FEATURES.md | 書き直す（§5） |
| docs/ROADMAP.md | docs/ROADMAP.md ＋ archive/roadmap-history.md | 書き直す＋archive（§4） |
| docs/BACKLOG.md | docs/BACKLOG.md ＋ archive/backlog-closed.md | 未解決を維持・解決済を archive（§6） |
| docs/CONVENTIONS.md | docs/CONVENTIONS.md | §6・§8 を書き直す（§7） |
| docs/CORE-CALCULUS.md | docs/CORE-CALCULUS.md ＋ archive/core-calculus-phases.md | 記録の節だけ archive（§8） |
| docs/DESIGN-CHECK-CATALOG.md | 同じ | 維持 |
| docs/UX-SCENARIOS.md | docs/UX-SCENARIOS.md ＋ archive/ux-scenario-runs.md | 実施履歴だけ archive（§9） |
| docs/THREE-LAYER-CORE-MIGRATION-PLAN.md | archive/three-layer-core-migration-plan.md | archive |
| docs/MIGRATION-LEDGER.md | archive/migration-ledger.md | archive |
| docs/POST-MIGRATION-REFACTORING-PLAN.md | archive/post-migration-refactoring-plan.md | archive |
| docs/decisions/README.md・0001〜0009 | 同じ | 維持。関連文書のリンクを archive へ向ける |
| docs/decisions/INTENTIONAL-DIFFS.md | archive/intentional-diffs.md | archive（Q1） |
| （新規） | docs/README.md | 文書の地図。CLAUDE.md の表と ROADMAP §8 を統合 |
| （新規） | docs/archive/README.md | archive の一覧 |
| （新規） | scripts/docs-lint | 機械チェック |
| .claude/skills/sync-docs/SKILL.md | 同じ | 書き直す（§11） |
| Claude の memory | 同じ | 整理（§11） |

## 2. ARCHITECTURE.md

### §1〜§3・§5・§6

| 既存 | 行き先 | 処置 |
|---|---|---|
| §1 技術スタック | architecture/README.md「技術スタック」 | 維持 |
| §2 パッケージ構成と依存方向 | architecture/README.md「モジュールと依存方向」 | 維持。日付と UX-119 の言及を削る |
| §3 主要クラスと責務（約 90 行の表） | 各主題ファイルの「部品」表 | 分配。README には入口のクラス（`AppState`・`Workspace`・`WorkspaceSession`・`AgentKernel`・`Validator`）だけ残す |
| §5 主要データフロー | 各主題ファイルの「流れ」 | 分配。編集・保存 → editing-history／外部変更・変更セット適用 → workspace／ジャンプ → index-references／ビューグリッド → views／AI生成・エージェント・自動実行・評価・入出力 → agents |
| §6 拡張ポイント | 各主題ファイルの「拡張ポイント」 | 分配。フィールド型・共通定義・派生・式 → schema／YAML表層 → yaml-model／ビュー → views／導出語彙 → transform／coverage・relations・consistency・値の条件・検査を足す → checks／エンティティ・関係・クエリ語彙 → graph-query／AIプロバイダ・入出力形式・テンプレートパック → agents |

### §4（49 節）

「コード参照」は Kotlin のコメントからの参照数（合計 196）。置換の対応表としても使う。

| 旧 | 新ファイル | 新しい見出し（案） | 処置 | コード参照 |
|---|---|---|---|---|
| 4.1 ツリーが単一の正 | yaml-model.md | 木が単一の正 | 書き直す | 7 |
| 4.2 履歴・dirty は正規化テキスト | editing-history.md | 履歴と dirty | 書き直す。鮮度確認（履歴の実体はセッションの操作列） | 8 |
| 4.3 Slot 抽象 | yaml-model.md | Slot | 書き直す | 6 |
| 4.4 YPath | yaml-model.md | YPath | 維持 | 0 |
| 4.5 索引はディスク基準・オーバーレイ | index-references.md | 索引の二層／オーバーレイ | 書き直す | 15 |
| 4.6 ID・参照のセマンティクス | index-references.md | ID と参照の意味 | 書き直す | 0 |
| 4.7 `$ref` 解決と実効フィールド | schema.md | `$ref` と実効フィールド | 維持 | 0 |
| 4.8 安全性の原則 | agents.md | 安全性 | 書き直す。書込経路の部分は workspace.md へ | 1 |
| 4.9 AI生成の構造保証 | agents.md | AI 生成 | 統合（4.19 と） | 3 |
| 4.10 エージェント実行 | agents.md | エージェント実行と Staging | 書き直す | 0 |
| 4.11 自動実行 | agents.md | 自動実行 | 書き直す | 0 |
| 4.12 自己改善 | agents.md | 自己改善 | 書き直す | 0 |
| 4.13 skill機構 | agents.md | skill | 統合（4.21 と） | 0 |
| 4.14 ビュー機構 | views.md | ビュー機構 | 書き直す | 2 |
| 4.15 フェーズ機構 | schema.md | フェーズ | 書き直す | 5 |
| 4.16 編集可能なID横ぐしビュー | views.md | ID 横ぐし | 書き直す | 3 |
| 4.17 コメント・表記スタイルの保持 | yaml-model.md | コメントと表記の保持 | 維持 | 0 |
| 4.18 AI接続機構 | agents.md | AI 接続 | 維持 | 0 |
| 4.19 AI生成のカーネル統合 | agents.md | AI 生成 | 統合（4.9 と） | 0 |
| 4.20 文書キャッシュ層の委譲 | workspace.md | 文書キャッシュと Workspace | 書き直す。鮮度確認（履歴の併走は解消済み） | 0 |
| 4.21 コード実行skill | agents.md | skill | 統合（4.13 と） | 0 |
| 4.22 表示解決と読み手体験 | index-references.md | 表示解決 | 書き直す | 1 |
| 4.23 スキーマ工学 | schema.md | 推測・メタスキーマ・派生 | 書き直す | 6 |
| 4.24 UIシェル | ui.md | UI シェル | 書き直す | 8 |
| 4.25 ワークスペース構造 | workspace.md | ワークスペース構造と layout | 書き直す | 7 |
| 4.26 計算列と決定論的射影 | transform.md | 計算列と決定論的射影 | 書き直す | 17 |
| 4.27 一括実行 | agents.md | 一括実行 | 書き直す | 1 |
| 4.28 コンテキスト会計 | agents.md | コンテキスト会計 | 書き直す | 9 |
| 4.29 エラー処理 | ui.md | エラー処理 | 維持 | 0 |
| 4.30 二層モデル① 関係モデルの宣言化 | graph-query.md | エンティティ型と関係レジストリ | 書き直す | 22 |
| 4.31 二層モデル② グラフ整合とエンティティ操作 | graph-query.md | エンティティ操作 | 書き直す。整合の検査の部分は checks.md へ | 16 |
| 4.32 二層モデル③ クエリコア | graph-query.md | クエリコア | 書き直す | 20 |
| 4.33 二層モデル④ エンティティページ | graph-query.md | エンティティページ | 書き直す | 28 |
| 4.34 編集履歴の合体とソースライブ同期 | editing-history.md | 履歴の合体／ソースとの同期 | 書き直す | 3 |
| 4.35 スキーマ・リファクタリング | schema.md | リファクタリング | 書き直す。文書の作成・削除の Undo は editing-history.md へ | 5 |
| 4.36 定義層 | checks.md | 定義層の 3 段と組込み定義体 | 書き直す | 0 |
| 4.37 検査の結果の4つと出どころ | checks.md | 4 つの結果／指摘の出どころ | 書き直す | 0 |
| 4.38 クエリの意味の整理 | graph-query.md | 線の意味 | 書き直す | 0 |
| 4.39 規模の計測 | checks.md ＋ README.md | 評価は 1 回／規模の計測（bench） | 分配 | 0 |
| 4.40 検査の定義の読み込みの共有 | checks.md | 検査の定義の読み込み | 書き直す | 0 |
| 4.41 配る部品のカーネル化 | checks.md | 配り方 | 統合（4.44 と） | 0 |
| 4.42 YAML から YAML への変換 | transform.md | 変換 | 書き直す | 0 |
| 4.43 構造のスキーマの導出 | schema.md | 構造スキーマの導出 | 書き直す | 3 |
| 4.44 root にだけ残っていた点検のカーネル化 | checks.md | 配り方 | 統合（4.41 と）。経緯は archive | 0 |
| 4.45 文書1つの検証 | checks.md | 文書 1 つの検証 | 書き直す | 0 |
| 4.46 変更セットの適用 | workspace.md | 変更セットの適用 | 書き直す | 0 |
| 4.47 root に残る読み出しの移設 | schema.md・views.md | ひな型／ビュー定義の読み取り／リファクタリングの影響 | 分配。経緯は archive | 0 |
| 4.48 場所の表し方の統一 | index-references.md | 場所は文書ID | 書き直す | 0 |
| 4.49 値の式 | schema.md | 値の式 | 書き直す | 0 |

新ファイルごとの受け入れは上の表のとおり。おおまかには、yaml-model と editing-history が木と編集、index-references が索引、schema が定義、graph-query がグラフとクエリ、checks が検証、transform が変換、views・workspace・agents・ui がそれぞれの主題を受ける。

## 3. MANUAL.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| 冒頭（概要・目次） | manual/README.md | 書き直す（目次） |
| §1 ビルドと起動 | manual/README.md | 移す |
| §2 画面構成・操作の役割・ショートカット | manual/README.md | 移す |
| §3 ワークスペースの構成・`.yde` 構成 | manual/README.md | 移す |
| §4 フィールド定義のプロパティ・型一覧・任意キーの Map・enum のリッチ選択肢・cases・名前付き定義と再帰 | manual/schema.md | 移す |
| §4 スキーマ・リファクタリング・カバレッジルール・置き場所とひな型・フェーズ機構 | manual/schema.md | 移す |
| §4 メタスキーマ・スキーマ推測・派生スキーマ | manual/schema.md | 移す |
| §4 整合ルール・エンティティ型宣言・関係レジストリ・属性付き参照・文書参照 | manual/checks.md | 移す |
| §4 パターン制約・検査の定義と語彙・網羅マトリクス・遷移検証・エンティティ操作とファイル移動 | manual/checks.md | 移す |
| §5 ビューグリッド・ビュー定義・文書ビュー | manual/views.md | 移す |
| §6 ID とリンクの仕組み | manual/ids.md | 移す |
| §7 検証 | manual/checks.md「検証パネル」 | 移す |
| §8 外部変更・競合・安全性 | manual/README.md | 移す |
| §9 テーマ | manual/README.md | 移す |
| §10 アーキテクチャ（88 字） | 削除 | 目次から architecture/README.md へリンク |
| §11 AI による作成支援 | manual/ai.md | 移す |
| §12 エージェント（定義・レシピ・プロバイダ・流れ・安全性・自動実行・一括実行） | manual/ai.md | 移す |
| §13 自己改善 | manual/ai.md | 移す |
| §14 skill | manual/ai.md | 移す |
| §15 既知の制約 | manual/limits.md | 移す。BACKLOG の未解決行と対にする |
| §16 ヘッドレス CLI | manual/cli.md | 移す |
| §17 ライセンス | 削除 | README.md にある |

## 4. ROADMAP.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| §1 ビジョン | ROADMAP「ビジョン」 | 書き直す（3 分の 1 に） |
| §2 なぜこの方向か | ROADMAP「ビジョン」 | 統合 |
| §3 対象ユースケース | ROADMAP「ビジョン」 | 統合（表 1 つ） |
| §4 中核コンセプト 4.1〜4.9 | ROADMAP「ビジョン」＋ architecture | 各 1〜2 文の箇条書きに。説明の本文は 4.3〜4.6・4.8 → agents.md、4.7 → checks.md、4.9 → graph-query.md。原文は archive/roadmap-history.md |
| §5 設計原則 | ROADMAP「設計原則」 | 維持 |
| §6 冒頭の状態表 | ROADMAP「状態表」 | 書き直す。全 P を 1 行にし、完了は archive の節へリンク |
| §6 P1〜P11・P13〜P15・P19〜P23・P25〜P27 | archive/roadmap-history.md | archive（本文そのまま） |
| §6 P12・P16 | archive/roadmap-history.md | archive。見出しは 🚧／📋 だが状態表は ✅ で食い違う。完了として扱い、需要待ちの残りは BACKLOG の ⏳ 行へ |
| §6 P17（R17-03 第2弾を含む） | ROADMAP「進行中のテーマ」 | 書き直す。残りの項目と判断点だけ |
| §6 P18・P24 | ROADMAP「進行中のテーマ」 | 書き直す。完了した R の本文は archive |
| §6 P28・P29・P30・P31 | ROADMAP「進行中のテーマ」 | 書き直す。P29 の Phase 1〜6 の記録は archive |
| §7 リスク | ROADMAP「リスク」 | 維持。R 番号・§番号を文言に置き換える |
| §8 本書の運用 | docs/README.md・CONVENTIONS §6 | 統合。ROADMAP からは削除 |

## 5. FEATURES.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| 冒頭の説明 | 同じ | 書き直す（列の定義） |
| §1〜§10 の 178 行 | 同じ節構成 | 書き直す。1 行 200 字・列は ID／機能／実装／テスト／設計。経緯とチケット番号を削る。ID は変えない |
| 📋（未実装）の行 | ROADMAP | 移す |
| §11 L-01〜L-08・L-10〜L-16（解消済み） | archive/backlog-closed.md | archive |
| §11 L-09（自動テストの範囲） | BACKLOG | 移す |

## 6. BACKLOG.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| 冒頭の運用 | 同じ | 維持。「解決したら archive へ移す」を足す |
| UX の表のうち未解決（57 行） | 同じ | 維持 |
| UX の表のうち ✅（70 行）・❌（3 行） | archive/backlog-closed.md | archive |
| テーマ管理 T-01・T-02 | 同じ | 維持 |
| 所感メモ | 同じ | 維持 |

## 7. CONVENTIONS.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| §1〜§5（スタイル・Compose・エラー処理・テスト・Git） | 同じ | 維持 |
| §6 ドキュメント更新規約 | 同じ | 書き直す（計画書 §3 の規則。役割表は docs/README.md を正にする） |
| §7 UX フィードバック運用 | 同じ | 維持 |
| §8 の 2（移動であって複製ではない）・3（パッケージ名）・4（依存方向）・7（fixture 読み取り専用） | §8「モジュールの規約」 | 書き直す（短く） |
| §8 の 1（台帳を見る）・5（挙動差分の登録）・6（コミット分類）・8（台帳の記録） | 削除 | 移行が終わった規則。5 は Q1 と連動 |
| §9 Core Calculus | 同じ | 維持。参照先を CORE-CALCULUS の節名と architecture のファイル名＋見出しへ |

## 8. CORE-CALCULUS.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| §1 背景・§2 基本方針・§3 設計原則・§4〜§14 各体系 | 同じ | 維持。見出しの階層（# と ## の混在）を揃える |
| §15 3層コアとの対応 | 同じ | 維持 |
| §16 P27 との関係・§17 P28 との関係 | archive/core-calculus-phases.md | archive（経緯） |
| §18 LLM との境界・§19 導入しないもの・§20 設計レビュー・§21 既存機能の再分類 | 同じ | 維持 |
| §22 移行方針 Phase 1〜6・§23 完了条件 | archive/core-calculus-phases.md | archive |
| §24 最終原則・§25 定義層モデル・§26 Construct Calculus | 同じ | 維持。日付とオーナー方針の文言を削る |

## 9. UX-SCENARIOS.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| §1 運用・§2 カタログ | 同じ | 維持 |
| §3.1・§3.2 S1 の目的と幕構成 | 同じ | 維持 |
| §3.3 第2回の重点観点・§3.4 実施履歴・§3.5 第3回の重点観点 | archive/ux-scenario-runs.md | archive |
| §4 S1 付録（貼り付け用データ） | 同じ | 維持。labs/ への実ファイル化は別途 |
| §5 拡張パス | 同じ | 維持 |
| §6.1・§6.2 S2 | 同じ | 維持 |
| §6.3 実施履歴 | archive/ux-scenario-runs.md | archive |
| §7 S2 付録 | 同じ | 維持 |
| §8.1・§8.2 S6 | 同じ | 維持 |
| §8.3 実施履歴 | archive/ux-scenario-runs.md | archive |

## 10. decisions/ と移行 3 文書

| 既存 | 行き先 | 処置 |
|---|---|---|
| decisions/README.md | 同じ | 維持。移行計画・台帳・INTENTIONAL-DIFFS へのリンクを archive へ向ける |
| ADR 0001〜0009 | 同じ | 維持 |
| INTENTIONAL-DIFFS.md D-01〜D-39 | archive/intentional-diffs.md | archive（Q1） |
| THREE-LAYER-CORE-MIGRATION-PLAN.md | archive/three-layer-core-migration-plan.md | archive |
| MIGRATION-LEDGER.md | archive/migration-ledger.md | archive |
| POST-MIGRATION-REFACTORING-PLAN.md | archive/post-migration-refactoring-plan.md | archive |

## 11. sync-docs スキルと memory

| 既存 | 行き先 | 処置 |
|---|---|---|
| sync-docs の役割表 | docs/README.md を参照 | 書き直す（表を持たない） |
| sync-docs の手順 | 同じ | 「消し忘れ」（完了したのに今の姿に残る記述・チケット番号）の観点と `scripts/docs-lint` の呼び出しを足す |
| MEMORY.md（0.9 万字） | 同じ | 1 行 100 字の索引に書き直す |
| 完了テーマのメモ（three-layer-core-migration・post-migration-refactoring・p25・p19・graph-document-fusion・r18・r17-02・s1-round2・ai-generate-entry・p27・core-calculus の経緯） | 削除 | archive と重複 |
| 残すメモ（decision-requests-need-concrete-examples・publication-prep・p31・yaml-transform-vision・design-verification-direction・sample-workspace-todos-baseline・docs-restructuring） | 同じ | 各 2 千字以内に |

## 12. CLAUDE.md

| 既存 | 行き先 | 処置 |
|---|---|---|
| 概要 | 同じ | 維持 |
| コマンド | 同じ | 維持 |
| ドキュメント体系の表 | docs/README.md | 移す。CLAUDE.md には 3 行の案内 |
| 作業のワークフロー | 同じ | 4 行に圧縮。「着手前」は architecture の主題 1 ファイル |
| 3層コアの節 | CLAUDE.md「規約の要点」＋ archive | 生きている規則（依存方向・移動であって複製ではない・パッケージ名・fixture）だけ要点へ。経緯は削除 |
| 落とし穴 30 項目 | 1 行 1 件（上限 20）＋ architecture の「規律」 | 下表 |

落とし穴の行き先（説明は右のファイルの「規律」節へ。CLAUDE.md には 1 行の禁止・必須とリンクだけ）:

| 既存の項目 | architecture のファイル |
|---|---|
| YNode は Compose の State ではない | ui.md |
| IdIndex はディスク基準・overlayIndex・editVersion | index-references.md |
| noteDiskChanges の振り分け・2 つのセッション・fork | workspace.md |
| DocumentId と Path・typealias・fileOf/documentOf | index-references.md |
| コメント保持・inheritMetaFrom・dumpPlain・OpenDocument | yaml-model.md |
| 構造の検証は導出スキーマ・DerivedStructures・structural-schema.yaml | schema.md（導出）・checks.md（検証の入口） |
| スキーマを読むのはカーネル・SchemaDef は薄い型・FieldHints | schema.md |
| 値の式 expression: true・ExpressionFields・SemanticValues | schema.md |
| resolve・effectiveFields | schema.md |
| フィールド型の追加箇所 | schema.md「拡張ポイント」 |
| requiredAt・currentPhaseIndex・definingRoot | schema.md |
| Display 一元化・IdEntry.title・enum の label は表示専用 | index-references.md |
| FormCtx・readOnly | ui.md |
| UiSpace・showSpace・モーダル禁止・RunController・handleShortcut | ui.md |
| Staging 経由・ChangeSetApplier・AgentToolbox | agents.md（Staging・権限）・workspace.md（適用） |
| 検査は定義体で・builtin-definitions・flow は専用チェッカー・matrix・「: 」の引用 | checks.md |
| クエリの線 edge・ownRefs・contains・via・overlayIndexWith | graph-query.md |
| WorkspaceChecks・DocumentValidator・CodedDocumentChecks・SchemaDefinitionChecks・CheckCatalog・SchemaDocuments | checks.md（検証）・schema.md（読み込み） |
| 変換定義 SemanticTransform・$each・$apply・$$ | transform.md |
| 評価は 1 回・shareEvaluations・validateGrowth | checks.md |
| 4 つの結果・SemanticRules・IssueSource・identity・withheld | checks.md |
| createDocumentFile・reload は Undo を失う・afterHistoryStep・YamlOperation と PatchOp | editing-history.md |
| WorkspaceLayout・.yde・ledger・_runs | workspace.md |
| _schemas にはメタスキーマだけ・derive の導出元・SchemaDerivation | schema.md |
| 定義文書への書込は meta・approval: auto の禁止 | agents.md |
| ビューは射影・read_view→apply_patch・VirtualViews・ViewDefinitions・planRefactoring | views.md |
| skill は宣言型・Runner の Approval・Tables.kt | agents.md |
| AiHttp・Connection | agents.md |
| EvalRunner・RunLog と RunMetrics | agents.md |
| AgentKernel のテスト・AutomationEngine のテスト | agents.md「規律（テスト）」 |
