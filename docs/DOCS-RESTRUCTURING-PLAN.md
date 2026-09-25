# ドキュメント再編計画（役割の分離とサイズの削減）

作成: 2026-09-24。状態: 提案（オーナーの判断待ち）。
完了したら本書は `docs/archive/` へ移す。

## 1. 現状と原則

現状（2026-09-24 計測）: docs 配下 14 ファイル・約 74.5 万字。うち 6 割超が過去の経緯。
コミット 123 件のうち ROADMAP 78・ARCHITECTURE 74・FEATURES 71・CLAUDE.md 51 件で追記し、削除する運用がない。
1 行が 1KB を超える行が 4 文書で計 190 行あり、grep・diff・部分読みが効かない。
ARCHITECTURE §4 の番号をコードのコメント 91 ファイル 196 箇所が参照し、節を並べ替えられない。

原則:

1. 「今の姿」と「記録」を分ける。今の姿は書き直して小さく保つ。記録は追記だけで `docs/archive/` に置き、読まない前提にする。
2. 1 つの事実は 1 か所に書き、他からはリンクする。
3. 道具に向いた形にする。1 行は 200 字まで。表のセルに 2 文以上を書かない。見出しは短い名詞句にし、番号で参照しない。
4. 1 ファイルは 2 万字まで。超えたら主題で分ける。
5. 毎セッション自動で読まれる CLAUDE.md は 5 千字まで。
6. 今の姿の文書にチケット番号（P／R／D／UX）と日付を書かない。経緯は git log と archive が持つ。

## 2. 最終形のファイル構成

```
README.md                      公開の入口（現状維持）
CLAUDE.md                      5 千字以内。コマンド・ワークフロー・規約の要点・落とし穴（1 行 1 件）
tutorial.md                    現状維持
docs/
  README.md                    文書の地図。役割・読む順・更新規則の表（CLAUDE.md の表を移す）
  CONVENTIONS.md               規約。§6 を本書 §3 の規則で書き直す
  FEATURES.md                  機能索引。1 行 1 機能・200 字以内・列は ID／機能／実装／テスト／設計
  ROADMAP.md                   ビジョンの要約・全テーマの状態表・未完のテーマだけ本文・リスク
  BACKLOG.md                   未解決の指摘だけ・テーマ T-nn・所感
  CORE-CALCULUS.md             現状維持（Phase の記録は archive へ）
  DESIGN-CHECK-CATALOG.md      現状維持
  UX-SCENARIOS.md              台本だけ（実施履歴は archive へ）
  architecture/                設計。主題ごとに 1 ファイル。今の姿だけを書く
    README.md                  技術スタック・モジュールと依存方向・主要クラス表・データフロー・拡張ポイント・主題の目次
    yaml-model.md              YAML の木・YPath・Slot・コメントと表記の保持・複数文書
    editing-history.md         編集・dirty・Undo（横断）・履歴の合体・文書の作成と削除
    index-references.md        索引の二層・ID と参照の意味・場所は文書ID・オーバーレイ・表示解決
    schema.md                  $ref と実効フィールド・フェーズ・推測とメタスキーマと派生・リファクタリング・値の式・構造スキーマの導出・読み込みの一本化
    graph-query.md             エンティティ型・関係レジストリ・属性付き参照・クエリコア・線の意味・エンティティページ
    checks.md                  定義層の3段・組込み定義体・4つの結果と出どころ・評価は1回・配り方・文書1つの検証・専用チェッカー
    transform.md               YAML から YAML への変換・計算列と決定論的射影
    views.md                   ビュー機構・ID横ぐし・文書ビュー
    workspace.md               ワークスペース構造・layout・外部変更の取り込み・変更セットの適用・保存
    agents.md                  エージェント実行・Staging・自動実行・自己改善・コンテキスト会計・一括実行・AI接続・AI生成・skill
    ui.md                      UIシェル・FormCtx・readOnly・ショートカット・エラー処理
  manual/                      利用者マニュアル。主題ごとに分割（内容は維持）
    README.md                  目次・ビルドと起動・画面構成・ワークスペースの構成・テーマ・外部変更
    schema.md                  スキーマ定義リファレンス（型・項目・enum・cases・名前付き定義・フェーズ・カバレッジ・置き場所とひな型・メタスキーマ・推測・派生・リファクタリング）
    checks.md                  設計の検証（エンティティ型・関係・属性付き参照・文書参照・パターン制約・検査の定義・マトリクス・遷移・整合・エンティティ操作・検証パネル）
    views.md                   ビュー
    ids.md                     ID とリンク
    ai.md                      AI 生成・エージェント・自動実行・自己改善・skill
    cli.md                     ヘッドレス CLI
    limits.md                  既知の制約（BACKLOG と対）
  decisions/                   ADR（現状維持）
  archive/                     記録。読まない前提。CLAUDE.md からリンクしない
    README.md                  何があるか 1 行ずつ
    three-layer-core-migration-plan.md
    migration-ledger.md
    post-migration-refactoring-plan.md
    intentional-diffs.md       D-01〜D-39
    roadmap-history.md         完了したテーマの本文（P1〜P27）
    backlog-closed.md          解決・見送りの指摘
    architecture-2026-09.md    旧 ARCHITECTURE.md の丸ごと。§番号から新ファイルへの対応表を先頭に置く
    core-calculus-phases.md    Phase 1〜6 の記録
    ux-scenario-runs.md        実施履歴
    docs-restructuring-plan.md 本書（完了後）
```

## 3. 文書ごとの修正方針

### CLAUDE.md

- 構成: 概要 1 段落・コマンド・文書の地図 3 行（詳細は docs/README.md）・ワークフロー 4 行・規約の要点 8 行以内・落とし穴。
- 落とし穴は 1 行 1 件・120 字以内。末尾に architecture のファイル名と見出しを添える。上限 20 件。説明は architecture 側の「規律」節に置く。
- 「3層コア」の節は削除する。生きている規則（依存方向・CoW・Undo の単一所有・表記から意味への禁止）は規約の要点へ、経緯は archive へ。

### architecture/

- 各ファイルの型: 概要 1 段落 → 部品（表: クラス／責務／モジュール）→ 流れ（箇条書き）→ 規律（変更時に守ること。1 項目 3 文以内）→ 拡張ポイント。
- 今の姿だけを書く。「当初は」「〜へ移した」「段D で」を書かない。理由が要るときは ADR か archive へリンクする。
- 旧 §4 の 49 節を主題へ振り分ける。対応表は archive/architecture-2026-09.md の先頭に置く。
- コードからの参照は「ファイル名＋見出しの文言」にする。例: `§4.33` → `architecture/graph-query.md「エンティティページ」`。196 箇所・91 ファイルを対応表で一括置換する。以後、番号で参照しない。
- 旧 §4 のうちコードから参照が多い節: 4.33（28）・4.30（22）・4.32（20）・4.26（17）・4.31（16）・4.5（15）。この順に新ファイルを書くと置換の効果が早い。

### FEATURES.md

- 列: ID／機能（1 文）／主な実装／テスト／設計（architecture のファイル）。状態の列は廃止し、実装済みだけを載せる。
- 未実装は ROADMAP、制約は BACKLOG と manual/limits.md に置く。§11（L-xx）は廃止し、L-09 だけ BACKLOG へ移す。
- 経緯（どの P で入ったか・何が変わったか）を書かない。ID は変えない。

### ROADMAP.md

- 構成: ビジョン（§1〜5 を 3 分の 1 に）→ 状態表（全 P を 1 行: ID／テーマ／状態／残り／記録へのリンク）→ 未完のテーマの本文 → リスク → 運用。
- 未完のテーマは P12・P16・P17・P18・P24・P28・P29・P30・P31。各 3 千字以内で、目的・残り・判断点だけを書く。
- 完了した P の本文は archive/roadmap-history.md へそのまま移す。以後、テーマが完了した時点で移す。
- 中核コンセプト（§4）の詳細は architecture へ。

### BACKLOG.md

- ✅ と ❌ の行を archive/backlog-closed.md へ。以後、解決した行は解決した時点で移す。
- 新しい行は 300 字以内。技術メモが長くなるなら ROADMAP のテーマへ起こす。

### CONVENTIONS.md §6（新しい規則）

- 文書の 2 種類（今の姿・記録）と、完了したら移す規則。
- 形の規則: 1 行 200 字・セルに 2 文以上書かない・1 ファイル 2 万字・見出しは名詞句・番号で参照しない。
- チケット番号と日付は記録側にだけ書く。
- コードからの参照の形（ファイル名＋見出しの文言）。
- sync-docs スキルを対にして更新し、「書き漏れ」に加えて「消し忘れ」（完了したのに今の姿に残る記述）を見る。
- 機械チェック `scripts/docs-lint`（行長 300 超・今の姿の文書のチケット番号・ファイル 2 万字超・切れたリンク）を用意し、sync-docs から呼ぶ。

### manual/

- 分割のみ。文章の見直しは UX-125 で行う。
- 分割後も 1 ファイル版が要るなら、分割ファイルを結合して作れる（結合の向きだけ許す）。

### decisions/INTENTIONAL-DIFFS.md

- 退役して archive へ（判断 Q1）。移行の新旧比較テストのための台帳だった。
- 以後、挙動を変えるときの記録はコミットメッセージ。規則を変えるなら ADR。

### CORE-CALCULUS.md・UX-SCENARIOS.md

- 記録の部分（Phase 1〜6・実施履歴）だけ archive へ。本文は現状維持。

### AI 側のメモリ（Claude の memory ディレクトリ）

- 同じ規則で整理する。索引 MEMORY.md は 1 行 100 字以内。完了したテーマのメモは削除（archive と重複）。合計 1.5 万字以内。

## 4. 目標サイズ

| 文書 | 現在（万字） | 目標（万字） |
|---|---|---|
| CLAUDE.md | 1.7 | 0.5 |
| architecture/ 合計 | 13.6 | 8.0（12 ファイル・各 1.2 以下） |
| FEATURES.md | 7.9 | 3.5 |
| ROADMAP.md | 13.9 | 3.0 |
| BACKLOG.md | 5.7 | 2.5 |
| manual/ 合計 | 9.6 | 9.6（各 2.5 以下） |
| CONVENTIONS.md | 1.0 | 1.2 |
| CORE-CALCULUS.md | 2.3 | 1.8 |
| UX-SCENARIOS.md | 2.8 | 2.5 |
| DESIGN-CHECK-CATALOG.md | 1.1 | 1.1 |
| decisions/ | 4.3 | 1.7 |
| 移行 3 文書 | 13.3 | 0 |
| 今の姿の合計 | 74.5 | 約 36 |
| archive/ | 0 | 約 45 |
| 1 タスクで読む量（CLAUDE.md＋主題 1 つ＋FEATURES の該当行） | 4〜6 | 2 以下 |

## 5. 進め方

| 段 | 内容 | 規模 | 確認 | 状態 |
|---|---|---|---|---|
| 0 | docs/README.md・archive/・CONVENTIONS §6・docs-lint | 半セッション | lint が動く | ✅ 2026-09-24 |
| 1 | 記録の移動（移行 3 文書・INTENTIONAL-DIFFS・ROADMAP 完了 P・BACKLOG 解決行・CORE-CALCULUS Phase・UX 実施履歴）とリンク更新 | 1 セッション | 切れたリンク 0 | ✅ 2026-09-24 |
| 2 | CLAUDE.md の圧縮（落とし穴の 1 行化。リンク先は暫定で旧 §番号） | 半セッション | 5 千字以内 | ✅ 2026-09-24 |
| 3 | FEATURES の刈り込み | 1 セッション | 各行 200 字以内 | ✅ 2026-09-24 |
| 4 | architecture/ の主題別書き直し。順: checks → index-references → schema → graph-query → agents → 残り。最後にコード参照の一括置換と旧ファイルの削除 | 3〜4 セッション | `./gradlew test` 緑・§参照 0 | ✅ 2026-09-25 |
| 5 | manual/ の分割 | 1 セッション | 切れたリンク 0 | 未着手 |
| 6 | memory の整理 | 半セッション | 1.5 万字以内 | 未着手 |

各段を 1 コミットにする。段 4 は主題 1 つごとにコミットする。
段 1 と 2 だけで、今の姿の文書は約 74.5 万字から約 45 万字へ減る。

## 6. 判断してほしい点

| ID | 判断点 | 推奨 |
|---|---|---|
| Q1 | INTENTIONAL-DIFFS を退役させるか | 退役。移行の比較テスト用だった。記録はコミットメッセージと ADR で足りる |
| Q2 | FEATURES.md を残すか | 残す。機能から実装ファイルとテストを引く索引として。ただし 1 行索引に |
| Q3 | MANUAL を分割するか | 分割。利用者向けの 1 ファイル版は結合で作る |
| Q4 | コードからの参照の形 | ファイル名＋見出しの文言。番号を使わない |
| Q5 | archive を repo に置くか、git 履歴だけにするか | repo に置く。grep で引ける |
| Q6 | 段の順番 | 0→1→2→3→4→5→6。効果の早い CLAUDE.md 圧縮を architecture の書き直しより先に |
| Q7 | CHANGELOG.md を作るか | 作らない。git log とコミットメッセージが記録。リリース時のノートは別途 |

## 7. 例（before / after）

### FEATURES の行

before（W-03。1,252 バイト）:

```
| W-03 | ファイル／ディレクトリの作成・改名・削除（未保存保護、削除確認、新規YAMLは…。**文書の新規作成はセッションの1トランザクション＝Ctrl+Z で取り消せ…**（P29 Phase 4・RP-03／D-18。…）… | ✅ | … | … |
```

after:

```
| W-03 | ファイル／ディレクトリの作成・改名・削除。新規 YAML はスキーマの初期値で生成し、作成は Undo できる | `ui/FileTreePane.kt`, `AppState.createDocumentFile` | 単体 | architecture/editing-history.md |
```

### ARCHITECTURE の節

before（§4.45。見出しがチケット名で、本文は移す前との差分）:

```
### 4.45 文書1つの検証の全体のカーネル化（P27 段D の D-b の1つ目）

段D の D-a までで、検証の部品はすべてカーネルにあったが、それらを組み立てるところは root に残っていた……
- root の `Validator` は……窓口になった（当初は、ファイルの座標 `Path` をカーネルの形へ写し……段D の D-d・D-e で……）
```

after（architecture/checks.md）:

```
## 文書1つの検証

`validate.DocumentValidator`（workspace-core）が、1 文書の指摘を組み立てる。
入力はワークスペースの全スキーマ・関係レジストリ・走査結果の一式。未保存の編集や適用後の姿を重ねたものでもよい。
指摘の並び: 構造 → ID の重複 → 参照の解決 → エンティティ型 → 宣言のない関係名 → 定義体の検査 → テンプレートの健全性 → 直せる場所への案内。
台帳の外の文書では、未解決参照を情報へ降格し、同じ指摘の重複を除く。
root の `Validator` と CLI の `workspace validate` は、この 1 実装を呼ぶ窓口。`KernelCheckLoadingTest` が一致を確かめる。

規律:
- 検証の組み立てや文面を root に書き足さない。CLI と結果が食い違う。
```

### CLAUDE.md の落とし穴

before（1 項目 800 字）:

```
- `IdIndex` はディスク基準（正）。未保存編集の参照解決は `DocScanner.scan` によるローカルIDを重ねて判定する二層構造（docs/ARCHITECTURE.md §4.5）。索引の中身は、カーネルのセッションの意味解析の結果そのもの（……）で、ディスクの変更の取り込み（……）の振り分けは `Workspace.noteDiskChanges` に一元化されている（R9-02）——AppState 側に振り分けを書かない。**UI層は `state.index` でなく `state.overlayIndex()`**（……）
```

after（3 行に分かれ、各 120 字以内）:

```
- UI は `state.index` でなく `state.overlayIndex()` を読み、`remember` のキーに `state.editVersion` を含める → architecture/index-references.md「オーバーレイ」
- ディスク変更の取り込みの振り分けは `Workspace.noteDiskChanges` だけ。AppState に書かない → architecture/workspace.md「外部変更の取り込み」
- `DocumentId == Path` はコンパイルが通って常に偽。`state.documentOf(doc.file)` に直してから比べる → architecture/index-references.md「場所は文書ID」
```

### コードのコメント

```
before: // 並び: 定義出現 → レジストリのアスペクト宣言順 → 文書 → 要素パス → アスペクト名（決定的。§4.33）
after:  // 並び: 定義出現 → レジストリのアスペクト宣言順 → 文書 → 要素パス → アスペクト名（決定的。architecture/graph-query.md「エンティティページ」）
```

## 8. 対応表

既存の節ごとの行き先と処置は [DOCS-RESTRUCTURING-MAP.md](DOCS-RESTRUCTURING-MAP.md) にある。
