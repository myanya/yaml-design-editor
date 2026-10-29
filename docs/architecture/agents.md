# エージェントと AI

エージェントの実行は「LLM が自ら次の行動を選ぶマルチターンのツール使用ループ」。編集は必ずステージング（オーバーレイ）経由で、原本への書込は人間の承認だけ。権限はプロンプトでなくツールが強制する。
自動実行・自己改善・単発の AI 生成・AI 接続・コンテキストの設計・一括実行・skill もここに置く。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `AgentDef` / `ActivityDef` / `Definitions` / `ContextItemDef` / `ChainDef` | agent/Definitions.kt | `_agents` / `_activities` の実行時ロード（エラー収集つき）。フォーム編集と検証は組込みスキーマ |
| `AgentKernel` / `AgentHost` | agent/run/Kernel.kt | ツール使用ループ。上限（ターン・トークン・時間）・終了手続きの強制・質問の中継・イベント通知・`consult`（委譲）・初期文脈の組み立て（`initialContextSections`） |
| `Toolset` / `Tool` / `ToolContext`・`ToolPolicy` | app-core tools/Toolset.kt・Tool.kt・ToolContext.kt | 中核のツール（読む 8・作業コピーへ書く 2・検証 1・許可した操作）と書込権限のツール側での強制。入口（agent・MCP・CLI）に依らず、引数と結果は JSON の値 |
| `Readouts` | app-core tools/Readouts.kt | 読み出しの文（`catalogText`・`phaseText`・`viewText`・`traverseText`・`relatedText`・`entityText`・`refsFooterText`・`sampleText`・`validationText`）。ツールの結果と初期文脈が共用 |
| `WorkingCopy` | app-core tools/WorkingCopy.kt | 作業コピー（パッチ記録・新規文書・重ねた索引・影響ファイルを含む横断検証・変更セット化・`newErrors`）。`Staging` の土台 |
| `AgentToolbox` | agent/run/Tools.kt | `Toolset` をアクティビティのツール分類で選んで `ToolSpec` に包む。ask_human・delegate・plan・note・submit_changeset・abort はここだけ。`contextItemSection`（レシピ項目 → 読み出しの文） |
| `Staging` | agent/run/Staging.kt | `WorkingCopy` に定義文書のゲート（ステージした定義が実際のローダで読めること）を足したもの |
| `RunLog` / `RunMetrics` | agent/run/RunLog.kt・agent/eval/Metrics.kt | 実行記録の保存（会話記録・`exchange`・会計）と集計 |
| `AutomationEngine` / `AutoApply` | agent/auto/Automation.kt・AutoApply.kt | トリガ監視（`tick`）・直列キュー（`processOnce`）・承認・連鎖・リトライ・停止・巻き戻し可能な適用 |
| `ForeachDef` / `Foreach` | agent/ForeachDef.kt・agent/auto/Foreach.kt | 一括実行の定義（列挙元と条件）と、対象の列挙 |
| `Evals` / `EvalRunner` | agent/Evals.kt・agent/eval/EvalRunner.kt | 評価セットの定義の読み込みと、比較実験の実行（サンドボックスコピー上） |
| `GenerationRun` | agent/run/Generation.kt | AI 生成のカーネル統合（1 ターンで提出する特殊アクティビティ） |
| `AiGenerator` / `OpenAiGenerator` / `AnthropicGenerator` | agent/llm/ | プロバイダ振り分け（AiGenerator.kt）・入出力の型と共通の組み立て（AiGeneration.kt）・各 API のリクエスト構築とレスポンス解析（スキーマ → JSON Schema と参照候補の enum 制約は app-core の tools/JsonSchemas.kt） |
| `ToolChat` / `ToolChatSession` | agent/llm/ToolChat.kt | ツール使用対応のチャット抽象と OpenAI Responses / Anthropic Messages 実装（prompt caching の印つけ） |
| `AiConnection` / `AiHttp` | agent/llm/Connection.kt・Http.kt | OpenAI 互換の接続先の解決と、HttpClient の単一ファクトリ（プロキシ・Basic 認証・リトライ） |
| `Traverse` | core/Traverse.kt | 経路パターンの多段トラバース（`neighborIds` の上の emit レンダラ） |
| `SkillDef` / `Skills` / `Runner` / `Tables` / `Importer` / `Exporter` | skill/ | skill 定義・コード実行 skill・表形式の読み書き・インポート・エクスポート |
| `RunController` / `RunsSpace` | ui/run/RunsSpace.kt | 実行スペース（受信箱・手動実行・自動化・計測評価）。実行状態機械は `RunController` が持つ |
| `AiGenerationHost` | ui/run/AiGenerationDialog.kt | AI 生成ダイアログの状態機械（入力 → 生成 → プレビュー → 適用）と提案合成 |

## 安全性

- 破壊的操作は未保存文書があれば拒否または確認。外部変更は dirty でなければ自動反映、dirty なら競合バナー。
- AI 提案は必ずプレビュー → 明示的な適用、適用は Undo 可能、自動保存しない。API キーとプロキシパスワードはディスクへ保存しない。
- 文書内のテキストはデータであって指示ではない。プロンプトインジェクションの影響は権限スコープとゲートで半径を絞る。
- すべての自動実行に上限（試行回数・トークン予算・時間）と停止手段を付ける。

## エージェント実行と Staging

- **編集は必ずステージング経由**。`Staging`（app-core の作業コピー `WorkingCopy`）がベース内容のスナップショットに対するオーバーレイとしてパッチを記録し、提出物はそのまま変更セットになる。原本はレビュー承認まで変わらない。
- **検証は「ステージ済み＋影響ファイル」**。影響ファイル＝ステージ対象が変更前後に定義する ID の参照元。ID 改名で壊れる参照元をステージング外でも検出しつつ、無関係な既存エラーで提出を塞がない。
- **権限はツール側で強制**。許可ファイル glob・許可パス glob・予約ディレクトリの書込禁止は app-core の `Toolset`（`ToolPolicy`）が拒否する。`AgentToolbox` はアクティビティの定義を `ToolPolicy` に写し、分類で選んで `ToolSpec` に包むだけ。定義文書（`_agents` / `_activities` / `_evals` / `_skills` / `_views`）への書込は `meta: true` のアクティビティのみ。`meta` かつ `approval: auto` はロード時に定義エラー。`_schemas` / `_runs` は meta でも書込不可。
- 実行直前にも `specs()` の許可ツール名と照合する。非許可の呼出は、引数の解釈やステージングより前に拒否する。
- **操作のツール**（[actions.md](actions.md)）: アクティビティの `actions` で許可した操作（アクションの id か `型名.操作名`）が 1 つずつツールになる（ツール名は `action_` ＋名前。`.` と ASCII 以外は `_`）。引数は受け取る値の定義から JSON Schema にし、受け手は `target`。
- 操作の実行はステージングを重ねたセッション（`ws.mirror.fork()` に置き換え・作成を当てる）で計画し、変更セットをステージングへ積む。許可した操作は人が許可した手順なので `allowedFiles` / `allowedPaths` は見ない（予約ディレクトリと台帳の外は不可）。ツールの分類 `actions` を外すか、許可一覧が無ければツールは出ない。無い操作を許可した定義は実行を始められない。
- **終了手続きの強制**。`submit_changeset`（ゲート通過必須）か `abort` でのみ正常終了。上限到達時はステージングを未提出ドラフトとして保全する。
- **提出ゲート** `gates.validation`: `error-free`（検証エラー 0）／`no-new-errors`（適用前の原本との差分で新規エラーなし。同じエラーかは指摘の出どころの `identity` で見分ける。[checks.md「指摘の出どころ」](checks.md)）／`none`。
- **対話**: `ask_human` は受信箱・実行タブの非モーダルカードで、文書を確認しながら回答できる。`delegate` は親のステージングを読める読み取り専用の相談（深さ 1）。
- **実行記録** `_runs`: ターン・ツール呼び出し・質問と回答・採否・トークン・会話記録（`transcript`。1 件 2 万字上限）。索引対象外。
- **テスト容易性**: LLM セッションは `sessionFactory` 注入で疑似化し、ツール実行・ステージング・検証は本物を通す結合テスト（`KernelTest`）。
- 変更セットの適用（人間の承認 `Workspace.apply`）の本体はカーネル（[workspace.md「変更セットの適用」](workspace.md)）。

## 自動実行

`AutomationEngine` がトリガ・キュー・承認・連鎖・リトライを司る。

- **直列実行**: エージェント実行は常に 1 件ずつ。並行変更と索引競合を構造的に排除する。
- **自動適用の 3 条件**: ゲート通過・書込スコープ内・対象がエディタで非編集中。ひとつでも欠けたら承認待ちへ降格する（黙って失敗させない）。派生スキーマの導出元文書への書込も降格する。
- **自己トリガの防止**: 適用の直後にファイルスタンプ基準を取り直す。予約ディレクトリは監視対象外。
- **Undo 保証**: 自動適用は適用前の原文を実行記録へバックアップしてから書き込む（`AutoApply`）。巻き戻し（`revert`）は適用後の追加変更を競合として拒否し、`--force` で上書き。
- バックアップは `prepared` として原子的に保存してから適用し、成功後に `applied` と保存後ハッシュを記録する。準備失敗なら原本に触れず、完了記録失敗も成功としない。`prepared` の復旧は内容確認後の `--force` が必要。
- **連鎖のガード**: 深さ上限（既定 5）と visited 集合による循環検出。人間承認の実行では連鎖は採否決定後に発火する。`chain.rules` は終わり方・採用変更ファイル glob・変更数・メモの部分一致の AND 条件で分岐先を選ぶ。
- **リトライの収束判定**: 同一の失敗メッセージを繰り返したら `maxAttempts` 未満でも打ち切る。
- **監視はイベント駆動＋フォールバック**: ネイティブ監視が使えるときは変更イベント時と 10 秒毎のフォールバック時だけスタンプ全走査を行う。
- **時刻と LLM は注入可能**: `tick(now)` / `processOnce()` / `sessionFactory` により、タイマーや実 API なしで全経路をテストできる（`AutomationTest`）。

## 自己改善

- **メトリクスは読むだけ**: `RunMetrics` は既存の実行記録だけから集計する（提出率・採用率・却下ファイル率・自動適用と巻き戻し・トークン・キャッシュ読取）。
- **評価はサンドボックス**: `EvalRunner` はケース × 変異ごとにワークスペースの一時コピー（`_runs` 除外）を作って実行し、原本には書かない。レポート（`_runs/eval-*.yaml`）は実行記録の必須キーを持たず、メトリクス集計から除外される。
- 評価の `setup.file` はロード時と実行時に相対パス・親参照・予約領域を検査し、コピー元と書込先の実体も検査する。直接構築した定義もこの検査を迂回できない。
- **期待結果は宣言的**: 終了状態・検証エラー・変更ファイル（glob）・パス内容の構造条件だけ。出力文字列の完全一致は提供しない。`variants` はモデル・エージェント・追加指示・`context`（レシピ）を差し替えた A/B。
- **メタは人間承認固定**: `meta: true` は定義文書への提案だけを解禁する。ステージされた定義は実際のローダでパースできることが提出ゲートに加わる。

## AI 生成

- **型保証は Structured Outputs**: 対象フィールドのスキーマ → JSON Schema 変換で行い、プロンプトに依存しない。`map` / `any` を含む場合だけプロンプト指示へ後退する。参照候補は enum 制約（200 件超は省略）。
- **カーネルへの橋渡しは疑似セッション**: `GenerationRun` が生成値を `apply_patch` → `submit_changeset` のツール呼び出しへ変換する `ToolChatSession` 実装を注入し、ステージング・書込権限・実行記録（アクティビティ ID `ai-generate`）を通常実行と同じ経路で通す。
- **入出力は実行記録に残す**: 送信したシステム指示・プロンプト全文・応答スキーマ・生出力・実測トークンを `AiExchange` として `exchange` 節へ上限つきで記録する。失敗時も記録し、記録パスをダイアログのエラーに示す。
- **入口はスペース非依存**: `AppState.AiRequest` が対象文書を持ち、`AiGenerationHost` はシェル直下にマウントする。フォーム部品からの✨は `FormCtx.doc` を渡す。新しい入口は対象文書を明示して `requestAi(doc, field, path)` を呼ぶ。
- **関連文書は「文脈」・参考文書は「手本」**: 同一スキーマの参考文書（`<reference-document>`。新しい順に最大 3 件）と、対象に関連する文書（`<related-document>`。呼び出し元が `AiRequest.relatedFiles` で注入。エンティティページは出現・参照先・参照元）を区別して送る。文字数予算（合計 12 万字）は関連 → 参考の順に共有。
- **出現単位の生成はマージ適用・ID は対象外**: 生成フィールドは所有キーに限定し `type: id` は常に除外。適用は既存 Map へのキー単位マージ（非生成キーとキー順を保持）。新規作成のたたき台（`requestDocumentDraft`）も同じ機構でルート object を id 抜きでマージ生成する。
- **ダイアログの UX**: 適用先は編集中の木（プレビュー → 未保存適用・Undo 可・自動保存なし）。プレビューは提案の内容を重ねた索引で検証する。

## AI 接続

- HttpClient は `AiHttp` だけが生成する。プロキシ（システム設定尊重 / DIRECT / MANUAL）と Basic 認証はここで一元管理。HTTPS トンネルの Basic 認証は初期化で許可する。
- OpenAI 互換の接続先は `AiConnection` が解決する（ベース URL・認証ヘッダ形式・API バージョン。公式 OpenAI・Azure OpenAI・ollama / vLLM 等）。接続先の分岐をクライアント側に書かない。設定の永続化は `ui/AiSettings`、CLI は環境変数（`AZURE_OPENAI_ENDPOINT` / `AZURE_OPENAI_API_VERSION` / `OPENAI_BASE_URL`）が初期値。
- 一時エラーの自動リトライは送信経路に一元化: `AiHttp.post` は 408 / 429 / 5xx を `retry-after`（なければ指数バックオフ＋ジッタ、上限 60 秒／回・3 回）で待機して再送する。待機は `delay` ベースでキャンセルで即中断。判定と待機計算は純粋関数（`HttpTest`）。
- Anthropic prompt caching: `cache_control` の切れ目を system・ツール定義の末尾・最新メッセージの末尾ブロック（毎ターン前進）の 3 点に自動配置する。トラバースやレシピの決定的出力はこのプレフィクス安定性を壊さないための前提。

## コンテキスト

- **会計**: 初期コンテキストは `initialContextSections`（システム指示・ツール定義・指示・`context` 宣言由来の各セクション）で組み立て、`context.sections`（文字数・概算トークン）・`context.turnInputTokens`（送信別の実測）・`toolCalls[].resultChars` を実行記録に残す。
- キャッシュの正規化: `ChatUsage.cachedInputTokens` は入力の内数に正規化（Anthropic は `input_tokens + cache_read + cache_creation` を総入力へ）。予算判定（`limits.maxTokens`）は総入力基準。
- **レシピ**（`context.items`）: `catalog`（match）／`phase`／`view`（rows・columns）／`doc`（file / path）／`related`（id）／`entity`（id）／`graph`（start・steps）／`sample`（schema / count）。各項目に `when`（always / has-target / no-target）と `budgetChars`（決定的打切り。全文系は既定 8,000 字）。無指定の既定は空。
- レシピのプレースホルダ `{name}` は `targetValues`（foreach の候補値）で解決し、解決できない項目は開示しない。解決の例外は本文として開示する。
- **段階的開示**: 最初から全文を渡さず、カタログ → 骨子 → ノード全文の順に必要な分だけ（`Disclosure`）。
- **トラバース**: `start` ＋ `steps` の連鎖（`follow: out / in`・`relation`・`via`・`schema`・`emit: title / summary / outline / full`・`limitPerHop`）。決定的（ID 昇順・「他 n 件」打切り・訪問済み停止・`budgetChars`）。消費面はレシピ `graph`・ツール `traverse`・CLI `traverse` で評価器は 1 実装。
- **参照の名前解決とツール減量**: read_node / doc 項目の「参照の解決」フッタ（`ID = タイトル [定義元]`。上位 30 件）・related の参照先タイトル・ビュー renderText の ref 値注記で、ID 解決のためだけのターンを消す。related の被参照は既定 20 件打切り（`limit`）、`read_view` の `rows` / `columns`、`list_documents` の `match`。注記は括弧とフッタに閉じ、値そのものは ID のまま。
- **開示の面**: `related`（隣接 1 段の要約・フェーズ・進捗）／`read_entity`（合成・両方向の関係・充足を 1 回で。related の上位互換）／`read_view`（行 × 列を原本座標つき）／`traverse`。人間 UI と同じ合成・同じ要約を返す（共有契約）。

## 一括実行

- **列挙は機械が行う**: `foreach`（source: `view`（`where: detail-missing`）/ `issues` / `search` / `files` / `ids` / `query`）が決定的に列挙する。「次の対象選び」を LLM に委ねない。予約ディレクトリは対象にしない。
- **実行は既存のアクティビティのまま**: 各項目は「対象を明示した追加指示」（`as` テンプレート。`{id}` `{title}` `{file}` `{path}` `{message}` と束縛名）を注入した通常の実行。
- **編成はキューが行う**: `enqueueForeach` が起動時に 1 回だけ列挙し、`QueueItem.targetKey` 付きで直列キューへ積む。重複ガードは targetKey 単位。項目の失敗はリトライで隔離される。
- 展開は起動時 1 回・`limit`（既定 20）で上限。`trigger: manual` 専用（パースで強制）。CLI は `agent --foreach`、GUI は「一括実行（対象を選択）」（列挙結果をチェックボックスで選択。状態は `RunController`）。

## skill

- **YAML が唯一の原本**: インポートは取り込んだ時点で YAML 側を正とする。エクスポートは派生成果物で、YAML への出力は定義段階で拒否する。
- **インポートは変更セット経由**: 直接ファイルへ書かず `Workspace.apply` を通す。既存文書はマップした項目だけを、値が変わる場合だけ set する（エクスポート → 再インポートが空の変更セットになる）。
- **行の組み立ては変換定義**: 列の対応表 `mapping` は素朴な形で、複雑な行は `build`（`TransformDef`）に書く。`Importer` は行を項（見出し → セル）にして `SemanticTransform.run` に渡すだけで、組み立ての語彙（`$format` `$first` `$when` `$date` `$number` `$trim`）を Importer に持たない。取り込み先（`target.file`）と採番の接頭辞（`target.id.prefix`）も同じひな型で、組み立てた明細を「いまの場所」として埋める。
- **配列への取り込みは追記と採番**（`target.path`）: 明細は配列の末尾へ `PatchOp.Insert`。`target.key` が同じ明細は飛ばす（更新しない＝手で直した値を戻さない）。ID の無い明細は `IdSuggest.nextInSeries`（接頭辞を決めた系列の次）で番号を付け、既存の ID は索引と取り込み先の配列から集める。1 行 1 文書の取り込み（`target.file` だけ）の意味は変えていない。
- **宣言型が既定**: import / export にコード実行はない。宣言型で書けない変換・定型手順だけがコード実行 skill（kind: run）の対象。
- **形式は Table 抽象に集約**: xlsx / csv / json / markdown / docx / html の差は拡張の `FormatCodec`（format-text・format-xlsx・format-docx）に閉じ、中核の `skill/Tables.kt` は skill 定義から形式を選んでファイルと bytes の間を受け持つだけ。`source.view` は `Views.load` / `Views.resolve` の結果をそのまま表にする。
- **コード実行 skill の明示許可制**: `Runner.run` は `Runner.Approval` を必須引数に取り、`Approval` は `approve(def)` でしか作れない。承認はコマンド・作業ディレクトリ・書込宣言・タイムアウトの指紋に紐づき、定義が変わると失効する。エージェント・自動実行からは起動不可。実行後に `writes` 宣言外の変更を警告し、予約ディレクトリの変更を逸脱として報告する。プロセス起動は注入可能（`RunSkillTest`）。
- `_skills` も予約ディレクトリ（メタアクティビティのみ提案可・監視対象外）。

## 規律

- agent のファイルの間に循環を作らない（規則テスト 18）。カーネルとツールが共に使う小さな型は run/AgentHost.kt、各プロバイダが使う生成の入出力は llm/AiGeneration.kt、定義（Definitions・Evals・ForeachDef）は実行器と別のファイルに置く。パッケージは llm・定義・run・auto・eval の順に一方向（規則テスト 2）。

- エージェントの編集は必ず `agent/run/Staging` 経由。原本への書込は人間の承認（`Workspace.apply`）のみ。
- 定義文書への書込は `meta: true` のアクティビティのみ。`meta` かつ `approval: auto` は定義エラー。`_schemas` / `_runs` は meta でも書込不可。
- カーネルのテストは疑似セッション注入で書く。実 API を叩くテストは書かない。自動化のテストは `tick(now)` / `processOnce()` を直接呼ぶ（タイマー・sleep を使わない）。自動適用後はスタンプ基準を更新し、自己変更で on-change を再発火させない。
- 評価はワークスペースの一時コピー上で実行し、原本に書くのはレポートだけ。実行記録の形式を変えたら `RunLog` と `RunMetrics` の両方を直す。
- 実行記録と評価レポートは保存領域の実体を検査し、ファイル名に使う ID で階層を変更させない。
- AI 呼び出しの HTTP 送信は `AiHttp.post` に一元化する。独自に `HttpClient` を作らない。呼び出し側でリトライを書かない。
- 新しい AI 生成の入口は対象文書を明示して `requestAi` を呼ぶ。`openDoc` 前提の入口を作らない。
- skill は宣言型が既定。コード実行 skill は `Runner.Approval` 経由でしか実行できない。入出力形式の追加は拡張モジュール（`FormatCodec` の実装 1 つ）と入口の登録の一覧に閉じる。
- エージェントツールを増やすときはコンテキスト税を考える。既存ツールで実現できる操作を新ツールにしない。
- レシピ・トラバース・ツール結果の出力は決定的に保つ（prompt caching・再現性・テスト容易性）。

## 拡張ポイント

- **AI プロバイダの追加**: OpenAI 互換なら `AiConnection` で足りる。独自 API は `AiProvider` に enum を足し、`AiGenerator.generate` の分岐と専用クライアント・`ToolChat.newSession` の分岐を実装する。HTTP 送信は `AiHttp.post`。
- **ツールの追加**: 中核なら app-core の `Toolset`（`coreTools`）に、拡張なら `extra` で足す（引数と結果は JSON の値。書込は作業コピー越し）。会話の相手が要るものだけ `AgentToolbox` に置く。書込権限の強制と実行記録への載せ方を揃える。
- **レシピ項目の追加**: `ContextItemDef` のパース・`contextItemSection` の解決・`builtin-activity` メタスキーマをそろえて更新する。
- **入出力形式の追加**: 拡張モジュール（`format-<名前>`。README・3 行の build・`FormatCodec` の実装 1 つ・テスト）を作り、入口の登録の一覧（`desktop/ext/DesktopExtensions.kt` と `cli/CliExtensions.kt`）に 1 行ずつ足す。形・依存・登録の漏れは規則テスト 15〜17 が見る。skill 定義の `format` の値が `id`、出力ファイルの拡張子が `extensions`。中核（app-core）には足さない。
- **出発点のテンプレートの追加**: `templates/` に置く（[workspace.md「ワークスペースの初期化」](workspace.md)）。skill を含むテンプレートも、展開後の検証エラー 0 を `WorkspaceTemplatesTest` が保証する。
