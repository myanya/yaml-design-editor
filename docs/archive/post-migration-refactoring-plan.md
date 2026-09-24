# 移行後リファクタリング計画（root 5パッケージの解体）

> **状態: ✅ 完了（2026-09-02。S1 第2回の実施＝完了ラインの全消化を確認）。**
> 作業単位 RC-01〜07・RA-01〜07・RB-01〜09（記録: [MIGRATION-LEDGER.md](migration-ledger.md)）。S1 第2回の指摘は UX-86〜94 として BACKLOG 登録（横断 Undo のフィードバック＝UX-86 が最優先の手当て候補）。Phase D 相当と残置分は [ROADMAP.md](../ROADMAP.md) **P27** で独立管理。
> （旧状態: 承認済み・進行中——2026-08-21 分析・2026-08-23 オーナー決定を反映。）
> 3層コア移行（[THREE-LAYER-CORE-MIGRATION-PLAN.md](three-layer-core-migration-plan.md)・全フェーズ完了）の続編として、
> ルートに残る `app` / `core` / `schema` / `index` / `validate` の5パッケージ（計 11,167 行）を解体し、
> 併存期（アプリ＝旧経路・コア＋cli＝新経路）を解消する。作業規約は docs/CONVENTIONS.md §8 と ADR を引き続き適用する。
>
> **オーナー決定（2026-08-23・§5 の論点への回答）:**
> 1. **6E（C-1 仮想文書）を最初に実施する**（実施順: C-1 → A → B → C-2〜4 → D）
> 2. A-2 は **A-2a（人間の保存経路）と A-2b（エージェント適用経路）に分割**する
> 3. root Cli の探索系（catalog / read / query / traverse / view 等）は**残置**でよい
> 4. WorkspaceRecords・bulkUpdate 等の**トランザクション化を含める**（挙動改善は INTENTIONAL-DIFFS へ登録して進める）

## 1. 目的

- **二重実装の解消**: リファクタリング（EntityOps / SchemaRefactor / DocMove 等 約2,000行）と索引・検証の意味部が、カーネル（semantic-core / workspace-core）と重複している。修正のたびに両方を直す状態を終わらせる。
- **Undo の単一所有の実現**: 現行UIは文書単位の `EditBuffer`（テキストsnapshot履歴）で、ADR-0006（履歴は workspace-core 単一所有）・D-03/D-07 は新経路でしか実現されていない。
- **最終ゴールへの接続**: ユーザ定義スキーマからの**2層スキーマ（構造・意味）導出**と意味検証の拡張（計画書§17-④⑧）は、`schema/Schema.kt`（SchemaDef）の解体が前提。その前にアプリが新経路の上に立っている必要がある。

## 2. 現状分析（5パッケージの棚卸し）

行数は 2026-08-21 時点。「分類」= この計画での扱い。

### 2.1 app（1,043行・1ファイル）

| 対象 | 行 | 内容 | 分類 |
|---|---:|---|---|
| AppState.kt 内 `OpenDocument` | ~100 | docCache 単位のUI文書（EditBuffer 保持・dirty・外部競合） | **A: セッション置換**（生テキスト編集のUndoのみUI層に残す＝D-08） |
| AppState.kt 内 `AppState` | ~900 | ①Workspace/IdIndex への仲介（overlayIndex・validateWorkspace・plan系）②UiSpace・ナビゲーション③AI要求・一括更新等のUI用途 | ①は **A: セッション置換**・②③はUIシェルとして残置 |

### 2.2 core（5,985行・14ファイル）

| 対象 | 行 | 内容 | 分類 |
|---|---:|---|---|
| SchemaRefactor.kt | 1,419 | スキーマ・リファクタリング（カーネル版=SemanticSchemaRefactor 完成済み） | **B: 削除**（呼び出しは AppState / SchemaEditor / Cli のみ） |
| EntityOps.kt / DocMove.kt / ChangeSets.kt / Bench.kt | 536 | エンティティ操作・文書移動・変更セット・計測（すべてカーネル版あり） | **B: 削除** |
| Workspace.kt | 336 | 旧ワークスペース（open/refresh/save/noteDiskChanges/overlayIndex） | **A: セッション置換→削除** |
| Documents.kt | 202 | EditBuffer・WorkspaceRecords（台帳一覧・一括更新） | **A: 置換**（WorkspaceRecords は derived 上へ再実装） |
| FileWatcher.kt | 96 | ポーリング監視 | **A: externalChanges＋イベントwatch へ**（計画書どおり最終段階） |
| Views.kt | 1,638 | ビュー（射影・グリッド・書込解決） | **C: 6E 仮想文書**（SM-008。semantic-core 化＋互換シム） |
| Traverse.kt / Disclosure.kt / Search.kt | 497 | 開示計画・全文検索（読み出し系） | **C: 読み出しモデル層**の置き場を決めて移設 |
| Cli.kt | 1,154 | root CLI（探索系はエージェント運用が現役依存） | **B: 重複コマンドのみ削除**・探索系は当面残置 |
| Aspects.kt | 107 | エンティティ合成（変換シェル済み） | Aと共に縮退 |

### 2.3 schema（2,970行・7ファイル）

| 対象 | 行 | 内容 | 分類 |
|---|---:|---|---|
| Schema.kt | 771 | SchemaDef（構造＋意味宣言＋表示＋coverage/consistency/derive 宣言の束）＋SchemaLoader（構造・意味パーサへは委譲済み） | **D: 2層スキーマ化の本丸**（構造/意味宣言/表示の3分割） |
| BuiltinSchemas.kt | 1,630 | 組込スキーマのコード内文字列 | **C: 資源化**（YM-006b と合流） |
| DerivedSchemas.kt | 189 | 派生スキーマ（derive）。reader 未対応の残 | **C: reader/session 対応** |
| SchemaInference.kt / IdSuggest.kt | 297 | スキーマ推論・ID候補（読み出し系） | **C: 置き場判断**（当面アプリ側でも可） |
| Display.kt / RelationsSchemaExt.kt | 83 | 委譲シェル | 残置（消費側の置換完了後に縮退） |

### 2.4 index（607行・5ファイル）

| 対象 | 行 | 内容 | 分類 |
|---|---:|---|---|
| IdIndex.kt | 262 | ディスク索引＋overlay＋走査シェル | **B: derived 参照へ置換→削除** |
| EntityGraph.kt | 120 | 導出グラフ（SM-003 の一時併存） | **B: 削除**（SemanticEntityGraph へ） |
| Query.kt / Derivation.kt | 160 | 委譲シェル | Bと共に縮退 |
| SemanticBridge.kt | 65 | Path↔DocumentId 変換の一元点 | **最後まで残る**（UIが Path 座標を使う間の境界） |

### 2.5 validate（562行・2ファイル）

| 対象 | 行 | 内容 | 分類 |
|---|---:|---|---|
| Validator.kt | 497 | Issue型（Path座標）＋構造検証の写し＋**索引依存の意味部**（参照解決・consistency・coverage・phase 進捗） | **B: derived 参照へ**＋coverage/consistency/phase は **semantic-core の検証族へ移設**（§17-⑧ 2層検証の第一弾） |
| GraphIntegrity.kt | 65 | 委譲シェル | Bと共に縮退 |

### 2.6 依存の現状（誰が旧経路を使うか）

- 旧経路型（Workspace / IdIndex / Issue / SchemaDef / AppState）への依存: **ui 16/20・agent 9/11・skill 3/6・ai 1/6 ファイル**。
- ただしリファクタリング呼び出し（EntityOps / SchemaRefactor / DocMove）は **AppState と ui/SchemaEditor の2ファイル・17箇所＋Cli.kt に集中**——B-1 の急所は狭い。
- カーネルへの橋は index/SemanticBridge.kt（6ファイルが利用）。UI の Path 座標はここで変換されており、**UIの座標系を変えずに土台だけ差し替えられる**構造になっている。

## 3. 方針（原則）

1. **引き続き strangler**: 一括書き換えはしない。AppState の下を WorkspaceSession に差し替え、旧経路をシェル化→削除の順で進める。各ステップで `./gradlew test` 緑。
2. **座標系は2段階**: UI・エージェントは当面 Path のまま（SemanticBridge が変換）。Path 全廃・パッケージ改名は最終段（Phase D）。
3. **挙動差分は登録が先**（INTENTIONAL-DIFFS）。特に EditBuffer→session 履歴（D-03/D-07/D-08）と、リファクタリングのビュー追随（D-10。6E完了までの過渡挙動）。
4. **UX 回帰**: AppState に触るフェーズは UX-SCENARIOS S1（全体通し）の再実施をトリガにする（R17 運用）。

## 4. フェーズ計画

### Phase A: セッション接続（アプリの土台差し替え）

| 単位 | 内容 | 効果 |
|---|---|---|
| A-1 | ✅ **完了**（RA-01 2026-08-24・RA-02 2026-08-25）: AppState に WorkspaceSession を併設（noteStoreChanges＝増分取り込み・derivedWith＝見たまま派生）し、`state.overlayIndex()` の供給元をセッション派生へ切替（`index.derivedIdIndex` ファサード。UI の全消費箇所＝検証・カバレッジ・ビュー・参照解決が乗り換え）。一致証明: 並び含む完全一致テスト＋todos 実測。残: 一覧（WorkspaceRecords）は A-2a で・Validator 本体の移設は B-3 で。**UX S1 再実施のトリガ対象** | 「見たまま判定」が構造から保証され、overlayIndex（UX-03 の二層構造）が不要になる |
| A-2a | ✅ **完了**（RA-03・2026-08-25）: 保存＝`session.replaceDocument`＋`save`（履歴は A-3 まで EditBuffer 所有）・一括更新＝1トランザクション適用＋保存（**D-11**: 部分適用の廃止・atomic 書込・A-3 後は Undo 1回）。書込バイト列の同一性（コメント・表記込み）をテストで固定。残: createYaml / rename / delete 等は B-1 と併せて・エージェント適用は A-2b | 書込集約（ADR-0005）の前半 |
| A-2b | ✅ **完了**（RA-04・2026-08-26）: 承認済みエージェント変更の適用を `Workspace.applyViaSession`（書込段のみセッション＝1トランザクション・atomic）へ。カーネルに `ReplaceDocument` 操作を追加。検証・競合判定・バイト列は旧経路と同一をテストで固定。残置: AutoApply（自動化の headless Workspace）・skill インポート（B 段で整理） | 書込集約の後半。ADR-0004（共通エンジン＋overlay）と統合 |
| A-3 | ✅ **完了**（RA-05/06・2026-08-28）: 編集のセッション直結（RA-05）＋ Undo/Redo・dirty のセッション所有（RA-06: `recordEdit`＝合体つき文書単位の型付き記録・**ワークスペース横断 Undo＝D-07 発効**・dirty はセッション供給）。EditBuffer はセッション不在時のフォールバックとして併走（縮退は B 段）。細粒度の型付き操作化（D-03 の完成形）と D-08 の UI 層テキストバッファは将来の改善。**reload 後に Undo 履歴が残らない挙動変化あり**（台帳記録参照） | Undo 単一所有。フォーム編集は型付き操作で記録 |
| A-4 | ✅ **完了**（RA-07・2026-08-29）: FileWatcher は R8-03 で既にイベント駆動（WatchService。本表の「ポーリング監視」記載は古かった）——残っていた検出のカーネル接続を実施: イベント起点（`pollExternalChanges(eventHint)`）でスタンプ不変でも `session.externalChanges()`（content diff）が既知文書の変更を検出（同一 mtime 上書きの盲点解消・dirty は競合提示） | 旧計画で「最終段階」とした残の解消 |

### Phase B: 旧経路の削除（重複 約2,600行）

| 単位 | 内容 |
|---|---|
| B-1 | ✅ **完了**（RB-01・2026-08-28）: plan 系17箇所を `session.planRefactoring` へ切替・適用は `applyRefactoring`＝1トランザクション＋保存（reload しない＝**Undo 1回で戻る**。D-12）→ **EntityOps / SchemaRefactor / DocMove / Bench 削除**（約2,300行）。ビュー追随・言及警告は `core/RefactorImpact`（新設）でアプリ層再接続＝**D-10 解決**（CLI は警告案内のまま）。root Cli の entity 変更系・refactor・move・bench は新CLIへ誘導（B-4 の一部前倒し）。ChangeSets はエージェント経路が使用中のため残置（B-2/B-4 で再判断）。一括更新も reload 廃止＝D-11 の Undo 1回が発効 |
| B-2 | 🚧 **進行中**（第1弾 RB-02・2026-08-28）: EntityGraph の導出をカーネル `SemanticEntityGraph` へ一本化（root は Path 座標の変換シェル＋backlinksTo の全体順のみ。EntityGraphTest シェル経由緑＝一致証明）。第2弾（RB-07・2026-08-28）: **legacy `Workspace` の索引供給をセッション派生へ**——内部カーネルセッション＋`index` ファサード化（noteDiskChanges＝noteStoreChanges 増分・overlayIndex / apply 検証も derivedWith。フォールバック残置）＝ヘッドレス経路も新経路供給に。第3弾（RB-08・2026-08-29): **ステージングを一本化**——`Staging.index()` の直接ディスク走査を廃止し `Workspace.overlayIndex`（セッション派生）へ。第4弾（RB-09・2026-08-29）: **IdIndex のディスク走査経路を削除**——rebuild / update / overlay / scanFile / diskLoader 撤去・Workspace フォールバック撤去（セッションが唯一の供給）・旧テストは本番経路＋`testIndex` fixture へ切替。**RB-10（`state.overlayIndex()`＝IdIndex 型の解消）は P27・D-4 へ統合**（2026-08-31 判断）: 走査経路撤去後の IdIndex は「Path 座標の読み出しファサード」＝SemanticBridge 境界の一部で、その解消は UI 16ファイルの座標切替（P27・D-3 の Path 全廃）と不可分——いま単独でやると同じ箇所を二度触ることになるため |
| B-3 | ✅ **完了**（RB-03〜06・2026-08-28）: 検証の意味部がカーネル所有になった——①カバレッジ②フェーズ進捗③consistency④文書内重複・参照解決（詳細は各弾の記述↓）。`validateWorkspace` の参照元は RA-02 以降セッション派生（overlayIndex ファサード）＝供給・判定とも新経路。**残置の整理**: エンティティ整合 lint（3.5）・テンプレート健全性（5）は SchemaDef 静的解決依存で root 残置（Phase D 再判断）・`state.overlayIndex()` という **IdIndex 型の解消は B-2 続き**（全消費者の型切替＝legacy Workspace 解消と連動）へ統合。（第1弾 RB-03）**カバレッジ判定族を移設**——宣言 `CoverageRule` → semantic-core schema/・判定コア → `semantic.SemanticCoverage`（入次数・出現数カウンタ＝グラフ整合との共有規則ごと）。root `Validator.coverageStatuses` は per-ID 変換シェル（CoverageTest シェル経由緑＝一致証明）。第2弾（RB-04・2026-08-28）: **phase 進捗を移設**——`phaseProgress` / `phaseSummaryText` → yaml-core `StructuralSchema` 拡張（構造層の関心）。root はシェル・型は typealias（PhaseTest シェル経由緑）。第3弾（RB-05・2026-08-28）: **consistency（derive 一致）を移設**——判定コア → `semantic.SemanticConsistency`（来歴ごと。ConsistencyTest シェル経由緑）。第4弾（RB-06・2026-08-28）: **validate() 本体の意味部（重複・参照解決）を単一所有化**——`SemanticEngine.duplicateIdIssues / referenceIssues` を aggregate と root が共用（二層供給・INFO 降格はカーネル判定。**D-13**: 重複文言を「この文書内」へ統一）。残: `validateWorkspace` の derived 置換（B-3 最終）・エンティティ lint（3.5）とテンプレート健全性（5）は root 残置 |
| B-4 | 🚧 entity（変異系）/ refactor / move / bench は削除済み（RB-01）。**validate / apply は延期**（2026-08-29 判断）: cli モジュールに同等コマンドが無く（apply は不在・validate は出力形式が別）、現役の運用導線（S2 幕7 の changeset 救済 apply・revert・完了確認コマンド）が root 形式に依存するため、**C-4（cli への組込接続）で同等を整備してから削除**する。探索系（catalog / read / query / traverse / view 等）は決定③どおり当面残置 |

### Phase C: 未移行機能の着地

| 単位 | 内容 |
|---|---|
| C-1 | ✅ **6E 仮想文書**（SM-008）**完了**（RC-01〜03・2026-08-24）: 解決（グリッド・文書ビュー）＋テキスト表現を semantic-core の仮想文書（写像＋来歴＋読み出しモデル）としてカーネル化し、root Views は変換シェル（ViewsTest がシェル経由で緑＝一致証明）。書込翻訳は不要と整理（原本座標へ解決済み→既存経路で完結。§4.14）。宣言パース・リント・スキャフォールドは SchemaDef 依存のため root 残置＝ Phase D / C-2 で移す（RC-03 記録）。UIの新API接続は Phase A で実施 |
| C-2 | ✅ **derive＋メタスキーマ**の reader / session 対応 **完了**（RC-04・2026-08-25。オーナー承認による前倒し）: SchemaDerivation（宣言・パース・展開コア）を semantic-core へ移動し root DerivedSchemas はシェル化。reader / session は buildSchemas（組込注入＋derive 展開）・assignSchemaId（メタスキーマ割当）を共有し、導出元文書の変更はスキーマ変更と同じ扱い。組込は BuiltinSchemas.kernelSchemas で AppState から注入（cli への接続は C-4 の資源化で） |
| C-3 | ✅ **完了（決定）**（RC-05・2026-08-31）: Traverse emit・Disclosure・Search・SchemaInference・IdSuggest は**アプリ側維持**を正式決定——①カーネルに対応物がなく二重実装ではない ②入出力が SchemaDef / Path 座標のため、いま移すと変換シェルが増えるだけで削減ゼロ ③SchemaDef 解体・Path 整理（P27・D-1/D-3）の際に自然な形で移せる。DoD の「読み出しモデルの root 残置」の枠に含める |
| C-4 | ✅ **完了**（RC-06/07・2026-08-31）: ①組込みYAML 7本（1,540行）を workspace-core resources へ資源化（root BuiltinSchemas は資源読みシェル 1,647→約85行）②cli の全コマンドへ `BuiltinSchemaResources.kernelSchemas` を注入（**D-14**: `_schemas`・定義ゾーンの検証面がアプリと揃う）。root / コアの2経路パースの統一は P27。**B-4 残部の整理**: root validate / apply は「意図して残す運用コマンド」へ再分類（apply の cli 同等には changeset 語彙（PatchOp）のカーネル化が必要＝P27 以降とセット。validate はカーネル cli 側が D-14 で同等の検証面になったが出力形式は運用が root 形式に依存） |

### Phase D: スキーマ2層化 → **独立テーマ P27 へ切り出し（2026-08-31 オーナー決定）**

D-1（SchemaDef 3分割）・D-2（2層導出の正式機構）・D-3（改名・Path 整理）は「解体の後片付け」ではなく §17 の新機構づくりのため、[ROADMAP.md](../ROADMAP.md) の **P27** として独立管理する（SchemaDef 依存で root 残置した項目＝スキャフォールド・ビュー宣言パース・リント・エンティティ整合 lint・テンプレート健全性・RefactorImpact・EditBuffer 削除も P27・D-4 へ引き継ぎ）。

**本計画の完了ライン（同決定・2026-08-31 更新）: C-3 ✅ → C-4 ✅ → UX S1 第2回（オーナー実施）のみ残**。
（RB-10 は P27・D-4 へ統合＝B-2 行参照。B-4 残部の validate / apply は「意図して残す運用コマンド」へ再分類＝C-4 行参照。）

> **追記（2026-09-21・ROADMAP P27 段D の D-b）**: B-4 の残りの前提だった「変更セットの語彙のカーネル化」と「cli の同等のコマンド」が済んだ——文書1つの検証の全体（`validate.DocumentValidator`）と、変更セットの適用（`workspace.ChangeSetApplier`）はカーネルにあり、cli モジュールに `workspace validate` / `workspace apply` がある。root の CLI の `validate` / `apply` は、同じ部品を通る運用コマンドとして残す（出力の形は、運用の導線が root の形に依存しているため、そのまま）。

## 5. 実施順（オーナー決定済み・2026-08-23）

**C-1（6E 仮想文書。カーネル→互換シム→UI接続の順）→ A-1 → A-2a → A-2b → A-3 → B-1 → B-2 → B-3 →（A-4 / B-4 は合間に独立で）→ C-2〜4 → D**

決定の記録（旧論点）: ①6E最優先 ②A-2 は人間（A-2a）とエージェント（A-2b）に分割 ③root Cli 探索系は残置
④WorkspaceRecords / bulkUpdate はトランザクション化を含める（INTENTIONAL-DIFFS 登録のうえ）。

## 6. リスク

| リスク | 対応 |
|---|---|
| AppState 差し替えは UI 全域に波及（体感の変化・退行） | 薄切り＋各フェーズ完了時に UX S1 通し試験（R17 運用）。docCache→session の載せ替えは読み取りから |
| メモリ・起動時間: docCache（開いた文書のみ）→ session（全文書 working） | 大規模WSで bench 系計測を先に取る。必要なら session 側に遅延ロードを入れる（workspace-core の設計判断） |
| EditBuffer 廃止で生テキスト編集の挙動が変わる | D-08 どおり UI 層バッファを残す。ソースペインの操作感は S1 で重点確認 |
| エージェント経路（Staging の overlay 意味論） | ADR-0004（共通エンジン＋overlay入力）に沿って session の候補機構と統合。KernelTest / StagingTest で固定 |

## 7. 完了条件（DoD案）

（2026-08-31 の完了ライン再定義を反映）

- root に**カーネルと重複する実装が残っていない**（残るのは UI シェル・変換シェル・読み出しモデル・SemanticBridge・意図して残した root Cli 探索系・SchemaDef 依存で P27 へ引き継ぐ残置分のみ）。✅ RB-01〜09 で達成
- Undo / dirty / 履歴の所有が WorkspaceSession に一本化されている（D-03/D-07 完了。EditBuffer は**セッション不在時フォールバックとして残置**＝削除は P27・D-4）。✅ RA-06 で達成
- 検証・索引の**供給・判定**が セッション派生／カーネルに一本化されている（✅ RA-02〜RB-09 で達成）。`state.overlayIndex()`（IdIndex 型）は走査経路撤去済みの **Path 座標読み出しファサード**として残置＝SemanticBridge 境界の枠（型の解消は P27・D-4）。
- `./gradlew test` 全緑＋UX-SCENARIOS **S1 第2回**通しで従来と同等の操作感（差分は BACKLOG 登録）。✅ 2026-09-02 実施——操作感の劣化なし・指摘 UX-86〜94 登録（D-07 のフィードバック不足＝UX-86 は完了後の最優先手当て候補）
- 進捗・記録は [MIGRATION-LEDGER.md](migration-ledger.md) の様式を踏襲（作業単位 RA-xx / RB-xx / RC-xx）。✅ 継続中
