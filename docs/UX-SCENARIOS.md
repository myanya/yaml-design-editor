# UX試験シナリオ台帳

触り心地試験（[ROADMAP.md](ROADMAP.md) P17）のシナリオ（台本）を蓄積する台帳。機能追加の完了報告に添える「試し方」（[CONVENTIONS.md](CONVENTIONS.md) §7）を再利用可能な台本へ育て、定期的な通し試験で操作体験の劣化・取っ散らかりを検出する。

## 1. 運用

- **実施者**: プロダクトオーナー。開発側は台本の準備・試験環境の準備・指摘の記録（[BACKLOG.md](BACKLOG.md) への即時登録）を担う。
- **実施トリガ**: ①フェーズ（Px）完了ごと ②UIの骨格に触る変更（スペース・ナビゲーション・フォーム基盤）の後、に S1 を再実施する。目安として最低月1回。機能単位の「試し方」試験は従来どおり完了報告の都度（CONVENTIONS §7）。
- **記録**: 指摘は試験中に BACKLOG へ UX-nn として即時登録し、良かった点は所感メモへ残す。実施後、本書の該当シナリオの実施履歴へ1行追記する。優先度づけ・チューニングは R17-03（💬 の棚卸し）で行う。
- **台本の鮮度**: 機能の追加・変更で操作手順が変わったら、完了報告の「試し方」を該当シナリオの手順へ反映する。台本と現行UIのずれを見つけたら、台本を直すか BACKLOG へ登録する（ずれの放置は試験の信頼を損なう）。

## 2. シナリオカタログ

| ID | シナリオ | 状態 | 対象領域 |
|---|---|---|---|
| S1 | 全体通し（ゼロから承認まで） | 台本あり（§3） | 立ち上げ→スキーマ作成→記入→詳細化→ビュー→エージェント実行→承認 |
| S2 | 自動化・連鎖 | 未執筆 | trigger（on-change / schedule）・chain・retry・approval: auto・受信箱・revert |
| S3 | skill入出力 | 未執筆 | Excel/CSV エクスポート→再インポートの往復・テンプレートパック（ledger） |
| S4 | 値型・計算列・整合の一巡 | 未執筆（R17-01 の台本を再録する） | sample-workspace todos ワールド |
| S5 | CLI・ヘッドレス同一面 | 未執筆 | catalog / validate / view / search / agent の人間UIとの同一面確認 |

S2・S3・S5 は S1 で作る課題台帳ワークスペースを拡張して使う想定（拡張パスは §5）。台本は実施を決めた時点で執筆する。

## 3. S1: 全体通しシナリオ（ゼロから承認まで）

### 3.1 目的・前提

主要ワークフロー「スキーマ作成→文書記入→詳細化→ビュー→エージェント実行→承認」を、**空のワークスペースから通しで**触る。個別機能の正しさではなく、工程の継ぎ目（次に何をすべきか分かるか・行き来が自然か）を見る試験。

- 所要: 約80分（1回通し）。中断する場合は幕の区切りで保存すればディスクに残る。
- 前提: JDK 17+。AIのAPIキー（幕6で使用。環境変数 `ANTHROPIC_API_KEY` または `OPENAI_API_KEY` を**起動前に**設定するのが楽。接続ダイアログでの入力でも可＝メモリのみ保持）。
- ワークスペース: **毎回新しい空フォルダ**で行う（例 `C:\work\ux-s1-0808`。立ち上げ体験も試験対象のため使い回さない）。
- エージェント実行は実APIを使う（maxTurns 8 に制限済み。1回の実行で数万トークン程度を想定）。

**横断観点（全幕共通で気にする）**:

1. 4スペース（編集・台帳・実行・設計）の切替が「やりたいこと」の軸として機能しているか
2. 「検証に導かれて埋める」ループが工程の最初（スキーマ直後）から最後（エージェントのゲート）まで一貫しているか
3. 未保存編集の「見たまま判定」が全画面で一貫しているか（保存しないと反映されない画面が残っていないか）
4. 空状態・初回操作で「次の一歩」が画面から読み取れるか（人間向け段階的開示）

### 3.2 幕構成

#### 幕0: 準備（約5分・試験対象外）

1. リポジトリで `./gradlew run` が起動することを確認する（初回はビルドに数分）。確認したら閉じる。
2. APIキーを環境変数で設定する（PowerShell 例: `$env:ANTHROPIC_API_KEY = "sk-..."`。設定したターミナルから起動すること）。
3. 空フォルダを作る（例 `C:\work\ux-s1-0808`）。

#### 幕1: 立ち上げ（約5分）

1. `./gradlew run` → スタート画面。履歴・「ワークスペースを開く…」の見え方を確認し、作った空フォルダを開く。
2. 画面を一巡する: レール（編集・台帳・実行・設計の4つ。実行は定義ゼロでも常時表示＝UX-20。開くと空状態の案内が出る）、空のファイルツリー、台帳スペースの各タブ、設計スペースの空状態（説明とCTA）。

**見てほしい観点**:
- 空のワークスペースを開いた直後、「次に何をすればよいか」が画面から読み取れるか
- 設計スペースの空状態の説明・CTAは次の一歩の助けになるか
- 実行スペースの空状態から「何が行える場所か」のイメージが湧くか（UX-20 の意図どおりか）

#### 幕2: データ先行でスキーマを作る（約15分）

1. ファイルツリーの「新規フォルダー」で `issues` と `members` を作る（新規はツリーで**選択中の場所の配下**に作られる。意図しない場所にできたら削除して作り直す——ここで詰まったらそれ自体を記録）。
2. `issues` を選択して「新規 YAML」→ `00-issues.yaml`。ソースタブに付録 A-1 を貼り付けて保存（Ctrl+S）。同様に `members/00-members.yaml` へ付録 A-2。
3. スキーマが無い状態を観察する: フォームタブ（汎用フォーム）、台帳スペースの一覧・ID検索（キー名 `id` は素で索引化されるため ISS-* / MEM-* が出るはず）。
4. ツリーで `issues/00-issues.yaml` を選択 → 魔法の杖アイコン「スキーマ推測」→ 対象globを確認して実行。`_schemas/` にドラフトが生成される。
5. 設計スペース「スキーマ」でドラフトを開き、スキーマ設計画面で仕上げる（目標形は付録 A-3）:
   - スキーマIDを `issue` にする
   - `status` / `priority` の enum 化の具合を確認する（候補化されていなければ values を設定: open / in-progress / done、low / medium / high）
   - `assignee` を `type: ref`＋`idPattern: "MEM-*"` にする（`assignee` は参照の命名規約外のため推測では ref にならない想定。この手数を観察）
   - `title` に `role: title` を付ける
   - `id` が `type: id` か、`required` の付き具合を確認する
   - サンプルYAML生成（プレビュー）で逆方向の検算をする
6. `members/00-members.yaml` も同様に推測→仕上げる（目標形は付録 A-4。`name` に `role: title`）。
7. 検証パネルで確認する: `_schemas` の2文書がメタスキーマ検証エラー0、`issues` / `members` がスキーマ適用でエラー0。一覧・参照候補のタイトルが件名・氏名になったこと。

**見てほしい観点**:
- 推測ドラフトの当たり具合（型・enum・required・id）。「直す量」は許容範囲か
- 設計UIで「意図の表現」（ref化・role付け）に詰まる箇所はないか
- 推測→仕上げ→検証の流れは「スキーマを書くのがつらい」を実際に下げているか

#### 幕3: 検証に導かれて記入する（約10分）

1. `issues/00-issues.yaml` をフォームで開く。課題の配列が**表形式**で表示されるか確認する。
2. 行を追加して ISS-004 を起こす: まず `id` だけ入れて他を空のまま → 検証パネルの必須エラーと入力枠の色づけを確認 → エラーに導かれて埋める（件名「バックアップ手順が属人化している」・status: open・priority: medium・assignee はドロップダウン候補から選択）。
3. 未保存のまま `members/00-members.yaml` に MEM-003（氏名「田中 葵」・role: po）を追加し、ISS-004 の担当を MEM-003 に変える → **保存していない**担当者が候補・参照解決に現れるか（見たまま判定）。
4. インスペクタを一巡する: 「検証」「関係」（assignee の参照とジャンプ）「出現」。
5. すべて保存する。

**見てほしい観点**:
- 「検証に導かれて埋める」がスキーマ作成直後の文書でも成立するか
- ref 補完の使い心地（候補の出方・タイトル表示）
- 未保存追随に例外を感じる画面がないか

#### 幕4: 詳細化（1:1 同一ID）（約10分）

1. 「課題の対応方針は1課題1ファイルの詳細文書に書く」運用にする。設計スペース「スキーマ」→「新規作成」で `issue-detail.yaml` を作り、今度は**設計UIでゼロから**定義する（幕2の推測経由との対比。目標形は付録 A-5。任意でメタスキーマの「AIで作成」も試せる）。
2. ツリーで `issues` の下に「新規フォルダー」→ `details`、その下に「新規 YAML」→ `ISS-001.yaml`。フォームで id: ISS-001、対応方針を記入して保存する（例: 「エラー文言を原因別に分岐し、入力欄の下へ表示する」）。
3. 詳細文書の上部「他文書での定義」ストリップ（一覧側の件名・状態が見える）を確認する。
4. インスペクタ「出現」→「ID横ぐしビューで全出現を編集」→ 横ぐしビュー上で一覧側の status を in-progress へ変更する（詳細側と並べたまま編集できること）。

**見てほしい観点**:
- 「同一IDで詳細化する」規約が、READMEを読み返さずに画面の情報（ストリップ・出現タブ）から運用できるか
- 設計UIゼロから作成と推測経由、どちらが楽か・使い分けは自然か
- ID横ぐし編集の体験（前回高評価の再確認）

#### 幕5: ビューで束ねる（約10分）

1. 設計スペース「ビュー」→「新規作成」`issue-catalog.yaml` → 付録 A-6 を貼り付けて保存する。
2. 台帳スペース「ビュー」タブ → 課題カタログ: 全課題が1行ずつ結合され、詳細列は ISS-001 がリンク・他の行が「作成」ボタンになっていること。
3. グリッドで直接編集する: ISS-003 の状態セルを done へ（元文書に未保存●が付き、Undo・保存が通常どおり効くこと）。
4. 「新規行」で ISS-005 を追加する（件名「ログ保管期間が未定義」。必須項目も入力）。
5. ISS-002 の詳細列「作成」→ スキャフォールドされた詳細文書に対応方針を記入する。
6. わざと壊す: ビュー定義の `path: title` を `path: titl` に変えて保存 → グリッドの定義エラー表示（リント）を確認 → 元へ戻す。
7. **幕6の前提**: 検証パネルで全体をエラー0にしてから進む（エージェントの提出ゲートが検証エラー0のため）。

**見てほしい観点**:
- ビュー定義の書き心地（列とパスの宣言で詰まらないか・リントに助けられるか)
- グリッド編集が「元文書への通常編集」だと自然に理解できるか
- 新規行・詳細作成の導線は「一覧から起こして詳細化する」運用に足りるか

#### 幕6: エージェントに委託する（約15分）

1. 設計スペース「エージェント」→「新規作成」`reviewer.yaml` → 付録 A-7（`model` は書かない＝手動実行タブの選択で補われる）。「アクティビティ」→「新規作成」`review-issues.yaml` → 付録 A-8。フォームタブ（組込みスキーマ）でも書けるので、一度はフォームでの入力を試す。
2. **レールに「実行」スペースが現れた**ことを確認する（定義を作った瞬間の変化に気づけるか）。
3. 実行スペース「手動実行」→ `review-issues` を選択 → 「AI接続」欄で実際に使われるプロバイダ・モデルを確認（キー未設定ならここで入力）→「実行」。
4. ターンごとの行動（探索→質問→パッチ→検証）の流れを観察する。エージェントの**質問**（レビュー観点の確認）がカードに届いたら、文書を見ながら回答する（例: 「優先度と期限の整合を重視して」）。回答の前後で台帳スペースへ移動して戻り、**実行が継続している**ことを確認する。
5. 提出まで待つ（maxTurns 8・数分想定）。途中で異常を感じたらキャンセルしてよい（その場合は再実行）。

**見てほしい観点**:
- エージェント・アクティビティ定義の敷居（フォームで書き切れるか・どの項目で迷うか）
- 実行中の可観測性（いま何をしているか、不安にならないか）
- 非モーダルの質問応答（文書を確認しながら答えられる価値を感じるか）

#### 幕7: 承認する（約10分）

1. 変更セットレビュー: ファイル単位の差分を読む。1ファイルを試しに「却下」へ切り替え、採用組み合わせの**再検証**表示を観察してから戻す。
2. 「選択した変更を適用」→ 原本に `review_comments` が追記されたことをフォーム・差分で確認する（スキーマ外キーの警告が付く。正式フィールド化は §5 の拡張パス）。適用は Undo 不可（適用前の変更セットは `_runs/` から辿れる）。
3. `_runs/` の実行記録を開く: ターン・質問と回答・採否・`usage`（トークン実測）・`transcript`。実行スペースの計測（メトリクス）タブも一瞥する。

**見てほしい観点**:
- 「適用すると何が起こるか」への信頼感（差分・再検証・記録で足りているか）
- 部分採用の分かりやすさ
- 実行後に「後から追える」感覚（記録の粒度は適切か）

#### 幕8: CLI同一面（任意・約5分）

1. `chcp 65001`（Windows コンソールの文字化け回避。BACKLOG UX-09）
2. `./gradlew runCli -q --args="validate <ワークスペースの絶対パス>"`
3. `./gradlew runCli -q --args="view <パス> issue-catalog"`
4. `./gradlew runCli -q --args="search <パス> --text バックアップ"`

**見てほしい観点**: GUIで見ていた検証・ビュー・検索と同じ面がCLIにも出ているか（人間とAIの同一面）。

### 3.3 実施履歴

| 回 | 日付 | 実施範囲 | 指摘 | 備考 |
|---|---|---|---|---|
| 1 | 2026-08-08 | 幕1〜7（幕8は未実施） | UX-20〜39（20件）＋所感3件（[BACKLOG.md](BACKLOG.md)） | enum 候補化はデータ3件では発動しない（`ENUM_MIN_SAMPLES=4`。UX-27）。台本の A-1 は当面3件のまま維持＝閾値議論の再現ケースとして使う。ビュー列リントの `fileGlob` 列スキップ（UX-36）・date 型の保存形式（UX-29）はバグ疑いとして要調査 |

## 4. S1 付録: 貼り付け用データと完成形リファレンス

### A-1 `issues/00-issues.yaml`（幕2で貼り付け）

```yaml
issues:
  - id: ISS-001
    title: ログイン画面でパスワード誤入力時のメッセージが不親切
    status: open
    priority: high
    assignee: MEM-001
    due: 2026-08-21
    summary: 誤入力時に「エラー」とだけ表示され、原因が分からない。
  - id: ISS-002
    title: 一覧画面の読み込みが遅い
    status: in-progress
    priority: medium
    assignee: MEM-002
    due: 2026-08-28
    summary: 課題が200件を超えると表示に5秒以上かかる。
  - id: ISS-003
    title: 操作マニュアルの更新漏れ
    status: open
    priority: low
    assignee: MEM-001
    summary: 8月リリースの新機能がマニュアルに載っていない。
```

### A-2 `members/00-members.yaml`（幕2で貼り付け）

```yaml
members:
  - id: MEM-001
    name: 佐藤 拓
    role: developer
  - id: MEM-002
    name: 李 静
    role: qa
```

### A-3 `_schemas/issue.yaml`（幕2の仕上げの目標形）

```yaml
id: issue
name: 課題
match: "issues/*.{yaml,yml}"
root:
  type: object
  fields:
    - key: issues
      label: 課題一覧
      type: array
      item:
        type: object
        fields:
          - { key: id, label: 課題ID, type: id, required: true }
          - { key: title, label: 件名, type: string, required: true, role: title }
          - { key: status, label: 状態, type: enum, values: [open, in-progress, done], default: open, required: true }
          - { key: priority, label: 優先度, type: enum, values: [low, medium, high], default: medium, required: true }
          - key: assignee
            label: 担当
            type: ref
            idPattern: "MEM-*"
            scope: { schemaIds: [member] }
          - { key: due, label: 期限, type: date }
          - { key: summary, label: 概要, type: text }
```

### A-4 `_schemas/member.yaml`（幕2の仕上げの目標形）

```yaml
id: member
name: 担当者
match: "members/*.{yaml,yml}"
root:
  type: object
  fields:
    - key: members
      label: 担当者一覧
      type: array
      item:
        type: object
        fields:
          - { key: id, label: 担当者ID, type: id, required: true }
          - { key: name, label: 氏名, type: string, required: true, role: title }
          - { key: role, label: 役割, type: enum, values: [developer, qa, po] }
```

### A-5 `_schemas/issue-detail.yaml`（幕4で設計UIから作る目標形）

```yaml
id: issue-detail
name: 課題詳細
match: "issues/details/*.{yaml,yml}"
root:
  type: object
  fields:
    - { key: id, label: 課題ID, type: id, required: true }
    - { key: approach, label: 対応方針, type: text, required: true }
    - key: steps
      label: 作業手順
      type: array
      item: { type: string }
    - { key: risk, label: リスク・懸念, type: text }
```

### A-6 `_views/issue-catalog.yaml`（幕5で貼り付け）

```yaml
id: issue-catalog
name: 課題カタログ
rows:
  idPattern: "ISS-*"
order:
  fileGlob: issues/00-issues.yaml
columns:
  - label: 件名
    fileGlob: issues/00-issues.yaml
    path: title
    editable: true
  - label: 状態
    fileGlob: issues/00-issues.yaml
    path: status
    editable: true
  - label: 優先度
    fileGlob: issues/00-issues.yaml
    path: priority
    editable: true
  - label: 担当
    fileGlob: issues/00-issues.yaml
    path: assignee
    editable: true
  - label: 対応方針
    fileGlob: "issues/details/*.yaml"
    path: approach
detail:
  fileGlob: "issues/details/*.yaml"
  filePattern: "issues/details/{id}.yaml"
newRow:
  file: issues/00-issues.yaml
  path: issues
```

### A-7 `_agents/reviewer.yaml`（幕6で作成）

```yaml
id: reviewer
name: レビュー担当
persona: |
  慎重で、指摘には必ず対象IDと根拠を添える。
  推測で断定せず、文書に書かれている内容だけを評価する。
  指摘は具体的な改善案とセットで書く。
rubric:
  - 具体性
  - 実行可能性
  - 優先度と期限の整合
```

### A-8 `_activities/review-issues.yaml`（幕6で作成）

```yaml
id: review-issues
name: 課題レビュー
description: 課題と対応方針をレビューし、指摘を追記する。
agent: reviewer
target:
  match: "issues/**/*.{yaml,yml}"
instruction: |
  最初に ask_human で「今回のレビューで特に重視する観点」を1回だけ質問し、
  回答を踏まえて課題と対応方針をレビューしてください。
  - 課題: 件名・概要から状況が具体的に分かるか。優先度と期限は整合しているか
  - 対応方針: 方針が課題の原因に対応しているか。手順は実行可能か
  指摘は各文書のルートに review_comments 配列として追記してください。
  各指摘は { target: 対象ID, comment: 指摘内容, suggestion: 改善案 } の形式にします。
allowedFiles:
  - "issues/**"
allowedPaths:
  - "review_comments*"
limits:
  maxTurns: 8
gates:
  validation: error-free
```

## 5. 課題台帳の拡張パス（今後の機能お試しの土台）

S1 の課題台帳は、以降の新機能の試験土台として拡張していく前提で設計している。拡張の当たり:

| 領域 | 拡張 | 使う機構（[FEATURES.md](FEATURES.md)） | 関連シナリオ |
|---|---|---|---|
| スキーマ | `review_comments` を issue スキーマへ正式フィールド化（幕7で残る未知キー警告の解消） | fields 追加 | S1 再実施時 |
| フェーズ | 課題詳細に工程（分析→対応→完了）を `phases` で宣言し、`steps` / `risk` を `requiredFrom` に | S-11・V-06 | — |
| カバレッジ | 「全課題は詳細文書を持つ」（`detailIn`）・「担当者は1件以上の課題から参照される」（`requiredBy`） | S-09・V-05 | — |
| 制約・整合 | 期限・見積工数の制約語彙（min / max）、工数の値型化と整合検証 | S-18・V-07 | S4 |
| ビュー | 担当者の文書ビュー（担当課題の被参照表 `backlinks`）・要素単位アタッチ | X-08 | — |
| skill | 課題台帳の Excel エクスポート→再インポートの往復 | SK-02・SK-03 | S3 |
| 自動化 | on-change の自動トリアージ・schedule の定期棚卸し・approval: auto と revert | AG-11〜16 | S2 |
| 自己改善 | review-issues の評価セット（`_evals`）・variants でモデル比較・メタアクティビティで rubric 改善 | AG-19〜22 | — |
