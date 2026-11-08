# 開発管理（devtrack）

YDE で YDE 自身の開発を管理するワークスペースです。指摘（未解決と、解決・見送り）・テーマ・所感メモと機能（機能索引の行）とロードマップのテーマの**原本**を、1 件 1 ファイルの YAML で持ちます。
[docs/BACKLOG.md](../docs/BACKLOG.md)（未解決の指摘台帳）・[docs/archive/backlog-closed.md](../docs/archive/backlog-closed.md)（記録）・[docs/FEATURES.md](../docs/FEATURES.md)（エージェント向けの機能索引）・[docs/ROADMAP.md](../docs/ROADMAP.md)（ビジョンと未完のテーマ）は、ここから生成する派生物です。

## 中身

| 場所 | 内容 |
|---|---|
| `backlog/UX-*.yaml` | 指摘。未解決も、解決・見送りも同じ場所で、状態・解決の日・解決の記録で見分けます |
| `themes/T-*.yaml` | テーマ（複数の指摘が絡む横断課題）。指摘の側が `theme` で参照します（束ねる指摘） |
| `notes/NOTE-*.yaml` | 生成する文書に添える文章。`NOTE-01` は BACKLOG.md の所感メモ（良かった点・方向性の確認）、`NOTE-02`〜`04` は ROADMAP.md のビジョン・中核コンセプト・設計原則 |
| `features/<ID>.yaml` | 機能（docs/FEATURES.md の行）。ID は系統の接頭辞（W・E・S・V・I・X・A・AG・C・SK・OP・LC）と番号 |
| `roadmap/P<番号>.yaml` | ロードマップのテーマ（docs/ROADMAP.md の P）。本文・節・R 項目もこの中です |
| `roadmap/outlook/OUTLOOK.yaml` | ROADMAP.md の末尾の 2 節（テーマの候補・リスクと未解決課題） |
| `.yde/schemas/common.yaml` | 型（ID・状態・日付）と、指摘・解決の記録・テーマ・メモ・機能・ロードマップ（テーマ・節・本文のかたまり・箇条・R 項目・展望）の形と検査 |
| `.yde/schemas/item.yaml` | 指摘の文書と、業務の操作（登録・状態の変更・一部の解決・解決・再開） |
| `.yde/schemas/theme.yaml`・`note.yaml`・`relations.yaml` | テーマとメモの文書、指摘からテーマへの関係（束ねる指摘）・指摘どうしの関係（関連する指摘）・指摘から機能への関係（関わる機能） |
| `.yde/schemas/feature.yaml` | 機能の文書と、操作「機能を登録する」（系統ごとの採番） |
| `.yde/schemas/roadmap.yaml`・`roadmap-outlook.yaml` | ロードマップのテーマと展望の文書。R 項目から移した先の R 項目への関係（`moved-to`）は `relations.yaml` |
| `.yde/views/` | ダッシュボード「開発管理」と一覧・クロス集計。`doc-` で始まるものは生成用。`item-lens`（グラフレンズの見え方「指摘の状態」）と記法 `item-card` |
| `.yde/reports/` | docs/BACKLOG.md・記録・docs/FEATURES.md・docs/ROADMAP.md を作るレポート（定義とひな形） |

## 運用

指摘の登録・状態の変更・解決は devtrack で行い、生成物を作り直して一緒にコミットします。

1. **登録**: 気になりポイントは、見つけた時点で 1 件登録します（完璧な分析は不要）。登録の操作で ID を省くと、使われている最大の番号の次を振ります。
2. **状態の変更・一部の解決・解決・再開**: 下の操作で行います。解決したら記録へ移す手作業はありません（状態で記録へ出ます）。操作は解決の記録（日付・種類・内容）を足していきます。
3. **関連する指摘**: 重複・派生・同じ原因などで一緒に見たい指摘は、`related` に番号を書きます（相手の個票に「関連する指摘から」として出ます）。
   **関わる機能**: 指摘が関わる機能（docs/FEATURES.md の ID）は `features` に書きます。未解決の指摘は、その機能の行の「未解決の指摘」に出ます（着手前に読まれる）。対応先 `targets` は手を入れた先の記録の文字で、機能は `features` に参照で書きます。
4. **作り直し**: `./gradlew devtrackDocs` で docs/ の生成物（台帳・記録・機能索引・ロードマップ）を作り直し、YAML と一緒にコミットします。
5. **テーマと所感メモ**: `themes/T-nn.yaml` と `notes/NOTE-01.yaml` の `body`（Markdown）を直します。指摘をテーマに束ねるときは、指摘の `theme` に T-nn を書きます。

- 生成物（docs/BACKLOG.md・記録・FEATURES.md・ROADMAP.md）を直接直しません。コミット前のフックと CI（repo-tests の `DevtrackTest`）が、作り直していないものを止めます。
- 操作の代わりに `backlog/UX-nnn.yaml` を直接書いてもかまいません。そのときは起点の日（`origin_on`）も書き、解決なら解決の日（`closed_on`）と解決の記録（`resolutions`。種類 `kind` つき）も書きます（操作と画面では自動で入ります）。
- 並行するセッションが同じ番号を取ると、同じ名前のファイルになるので、マージで気づきます。後から振った方の番号を振り直します。

### 操作

画面ではダッシュボード「開発管理」の「操作」（登録）と、指摘の個票の「この対象の操作」から呼びます。CLI では次のとおりです（`--apply` を外すと計画の表示だけ）。

```bash
./gradlew runCli -q --args="action devtrack register-item --param title=件名 --param origin=見つけた場面 --apply"
./gradlew runCli -q --args="action devtrack change-status --on UX-267 --param status=discussing --apply"
./gradlew runCli -q --args="action devtrack record-progress --on UX-267 --param date=2026-11-07 --param note=一部を直した --apply"
./gradlew runCli -q --args="action devtrack resolve-item --on UX-267 --param date=2026-11-07 --param note=直した内容 --param targets=FEATURES-X-01 --apply"
./gradlew runCli -q --args="action devtrack reopen-item --on UX-267 --param date=2026-11-08 --param note=再開の理由 --apply"
./gradlew devtrackDocs
```

- `--param` の値は YAML として読みます。`**` で始まる本文や `: ` を含む値は、`--param 'body="**件名**: 詳しく"'` のように引用符で囲みます。
- 登録で ID を省くと採番します（画面の入力でも、ID の欄の候補の先頭に「採番」が出ます）。

| 操作 | 内容 |
|---|---|
| `register-item` 指摘を登録する | 件名・起点（ほかに ID・状態・本文・技術メモ・テーマ・関連する指摘・対応先）で 1 件作る。ID を省くと採番し、起点の日には今日が入る |
| `change-status` 状態を変える | 未着手・議論待ち・対応中・必要になったらの間で変える |
| `record-progress` 一部を解決する | 種類「一部」の解決の記録（日付・内容・対応先）を足す。指摘は未解決のまま |
| `resolve-item` 解決にする | 解決（`result=declined` で見送り）にし、解決の日と、種類「解決」（見送りなら「見送り」）の記録を入れる。対応先を書くと指摘の対応先も置き換える |
| `reopen-item` 再開する | 解決・見送りの指摘を未解決（既定は未着手）に戻し、解決の日を消して、種類「再開」の記録（理由）を足す |

### 関係をレンズで見る

台帳スペースの「レンズ」で、上の段の「見え方: 標準」から「指摘の状態」を選びます。

- 指摘の箱が状態で描き分けられます。解決・見送りは「✅ UX-nn」「❌ UX-nn」だけの小さな薄い札、未解決は状態の札（💬 議論待ち・🚧 対応中など）と件名つきの箱です。
- 上の段の「解決・見送りを隠す」「議論待ち・対応中だけ」を押すと、その指摘を隠します（もう一度押すと戻ります）。テーマ・メモの箱には効きません。
- 「解決・見送りを隠す」でも、関連する指摘（`related`）で未解決の指摘と直接つながるものは、経緯として小さな札のまま残します（残した数が上の段に出ます）。
- 機能の箱は、既定で「機能は未解決の指摘があるものだけ」が入り、未解決の指摘が「関わる機能」で指す機能だけが出ます（外すと全部の機能）。
  `item-lens.yaml` の `depth` を 2 にすると、関連を 2 段たどった先に未解決があるものも残します（いまは 17 件が 26 件になります）。
- 絞り込みを増やすときは `.yde/views/item-lens.yaml` の `filters` に足します（書き方は [docs/manual/views.md](../docs/manual/views.md)「箱の絞り込み」）。

### 機能を足す・直す

docs/FEATURES.md はエージェント向けの索引で、行の原本は `features/<ID>.yaml` です。機能を足したら 1 件足し、直したらその文書を直して、`./gradlew devtrackDocs` で作り直します。

```bash
./gradlew runCli -q --args="action devtrack register-feature --param family=LC --param title=機能の要約 --param path=server/LocalServer.kt --param module=local-server --param design_label=ローカル接続 --param design_link=architecture/local-connections.md --param manual_link=manual/local-connections.md --param manual_heading=有効・無効と状態 --apply"
```

- 機能の欄（`title`）には、何の機能かと grep の手がかりになる語彙（バッククォートの名前）だけを 100 字前後で書きます。振る舞いの説明は、操作の説明と設計の指し先（manual・architecture）に書きます。
- 登録で ID を省くと、その系統の接頭辞の中で最大の番号の次を振ります。テスト名（`test`）を省くと「手動」の行になります。2 つ目からの場所やテストは文書に足します。
- 項目は、機能の欄（`title`）・主な実装（`implementation`。`path` と補足 `detail`・モジュール `module`、場所の形にならないものは `text`）・テスト（`tests`。`name`・種類 `kind`・補足 `note`）・手動で確かめる印（`manual_check`・場面 `manual_check_note`）・ほかのテストもある（`more_tests`）・設計（`design`。`label` と `link`）・操作の説明（`manual`。`link` と見出し `heading`）か内部の基盤の印（`internal: true`）です。
- 操作の説明は、その機能を変えたら直す manual の見出しです。利用者が操作しない内部の基盤（共通状態・検証の組み立てなど）だけ、代わりに `internal: true` と書きます。
- テストの種類は、名前のあるテストなら省くと単体です。Compose の画面を組み立てるテストは `ui`、`./gradlew test` に含まれない検査は `check` と書きます。
  手動でも確かめるなら `manual_check: true`（場面は `manual_check_note`。例 画面）を書きます。自動のテストが無い機能は、FEATURES.md の末尾「手動確認だけの機能」とダッシュボードの区画に出ます。
- 節の並び・見出し・接頭辞・範囲（冒頭の系統の表）は、レポート `features-md` が系統ごとに持ちます。行は節の中で ID の順です。新しい系統を足すときは、レポートと `common` の「機能の系統」「機能ID」に足します。
- 設計（`design`）と操作の説明（`manual`）は、見出し（`heading`）まで書くと「architecture/ui.md「UI シェル」」の形で出ます。見出しの実在は `DocReferencesTest` が見ます。

### ロードマップを直す

docs/ROADMAP.md の原本は `roadmap/P<番号>.yaml`（1 テーマ 1 ファイル）です。状態表も未完のテーマの本文も、ここから作ります。

- テーマの項目は、ID・番号（`number`。ID の番号で、状態表と本文の並びの順）・名前（`title`。見出し）・表の名前（`table_title`。状態表の名前が見出しと違うとき）・状態（`status`: todo・in-progress・ongoing・done）・状態の要約（`status_note`。状態表の欄にそのまま出ます）・本文（`body`）・節（`sections`。見出し `title` と本文 `body`）です。
- 本文は「本文のかたまり」の並びです。かたまり 1 つに、文（`text`。Markdown のまま。段落・表・コード）・箇条の並び（`points`）・R 項目の表（列の形 `columns` と `items`）のどれか 1 つを書きます。
- 箇条は、太字の見出し `label`（後ろに続く文字は `label_note`）と文 `text` です。決定は `decision`（決めたこと・決定・合意した方向）・状態 `status`（decided・pending）・日 `date`・決めた人 `by` を書くと「**決めたこと（2026-11-08 オーナー）**: 文」の形で出ます。文の続きの行は 2 字下げで書きます。
- R 項目は、ID（R52-05 の形）・`title` と、表の形に応じた欄（基本は `content`、判断つきは `content` と `decision`、作業は `places`・`prereq`・`done_criteria`）・状態（`state`: todo・in-progress・partial・done・moved）・状態の日 `state_on`・補足 `state_note`・移した先 `moved_to` です。
  状態の文字（「✅ 2026-11-08（補足）」「🚧 一部完了（日付: 補足）」「→ R47-41」など）はレポートが作ります。
- テーマを終えたら `status: done` にします。未完のテーマから外れ、状態表だけに残ります。本文は記録（docs/archive/roadmap-history.md）へ移します（記録はまだ手で書く文書です）。
- ビジョン・中核コンセプト・設計原則は `notes/NOTE-02`〜`04`、テーマの候補とリスクは `roadmap/outlook/OUTLOOK.yaml` を直します。

### 検査

検証（`./gradlew runCli -q --args="validate devtrack"`）が、次の決まりを見ます。

- ID の形（`UX-` と番号）と重複（同じ ID の定義が 2 つ）、状態の値、テーマの参照の切れ。
- 未解決の指摘には起点の日があり、解決の日が無い。解決・見送りの指摘には解決の日がある。解決の日は起点の日以降。
- 「必要になったら」の指摘には技術メモ（実装の当たり）がある。テーマには束ねる指摘がある。解決の記録には日付と種類がある。関連する指摘の参照が切れていない。
- 機能の ID は系統の接頭辞と番号。主な実装と設計が 1 つ以上あり、自動のテストか手動で確かめる印がある。実装の場所には場所か文が、テストには名前か種類がある。
- docs/FEATURES.md に出る場所・テスト名・見出しの実在は、repo-tests の `DocReferencesTest` が生成物の上で見ます。
- ロードマップのテーマの ID は P と番号、R 項目は R と番号。完了した R 項目には状態の日が、移した R 項目には移した先（切れていない参照）がある。本文のかたまりは種類が 1 つで、R 項目の表には列の形がある。決定の箇条には状態・日・決めた人がある。

### 見る

```bash
./gradlew run --args="devtrack"                               # 画面で開く（ダッシュボード「開発管理」）
./gradlew runCli -q --args="view devtrack backlog-dashboard"  # ダッシュボードを CLI で見る
```

画面の起動（`run`）も CLI（`runCli`）も、相対パスはリポジトリの根から解決します。
ダッシュボードには、見つけてから 60 日以上たっても未着手か議論待ちのままの指摘の区画（基準日で数えます）と、手動確認だけの機能の区画があります。

## 生成のしくみ

- レポート `backlog-md`（台帳）と `backlog-closed-md`（記録）が、生成用のビュー（`doc-items`・`doc-closed`・`doc-themes`・`doc-notes`）の行をひな形にはめ込みます。表のセルは `{パス:cell}` で `|` と改行を逃がします。
- レポート `features-md`（機能索引）は、生成用のビュー `doc-features` の行を、ID の接頭辞で系統の節に振り分けます。並びの欄は値の式（`each` と `first`・`format`）で 1 件ずつ文字にし、ひな形が `{#if more}・{/if}` でつなぎます。
- レポート `roadmap-md`（ロードマップ）は、生成用のビュー `doc-roadmap-themes`（番号の順）の行を状態表にし、未完のテーマを変換の `$each` でビューの行から 1 件ずつ組み立てます。本文のかたまりと R 項目の状態の文字は、名前を付けた組み立て（`rules` の `block`・`state`）が 1 か所で作ります。
- レポートの出力先はワークスペースの中に限られるので、`./gradlew devtrackDocs`（cli/build.gradle.kts）が CLI の `report --print` の文字を受けて docs/ へ書きます。

## 経緯

- 段階 0（2026-11-07）: Markdown の台帳を原本のまま、取り込みの写しを作って画面と CLI で眺めた。番号の重なり 3 件を見つけて振り直した。
- 段階 1（2026-11-07）: 原本を YAML に切り替えた。記録の解決の日は、状態の欄・技術メモ・節の見出しの日付から、無ければ git の履歴（状態が ✅ になったコミット）から補った。一部解決の記録は、その指摘の解決の記録へ移した。
- 段階 2（ROADMAP の P52）: 2a（2026-11-07）で指摘どうしの関連・記録の種類・再開・登録の採番・長く手付かずの区画と、製品の改善（create の採番・日付の差など）を入れた。
  2b-1（2026-11-07）で FEATURES の原本を `features/` に切り替えた。取り込みでは欄の区切りを「・」にそろえ、テストに種類（単体・UI・検査・手動）を持たせ、節の中を ID の順に並べ直した。
  続けて全行に操作の説明（manual の見出し）か内部の基盤の印を付けた。
  2b-4（2026-11-07）で冒頭の系統の表と、設計の欄の見出し（`design[].heading`）を足した。
  2b-2（2026-11-07）で指摘に関わる機能（`features`）を足した。対応先の文字に書いてあった機能の ID（今ある ID だけ。昔の制約の L-14 は X-06 へ）を取り出し、
  台本の番号を機能と取り違えたもの（UX-122・UX-124 の V-10）は直した。未解決の指摘は全件を読んで関わる機能を付けた（文書・テスト環境・開発環境・サンプルの指摘には付けない）。
  2c-1（2026-11-08）で ROADMAP の原本を `roadmap/` に切り替えた。取り込みでは R 項目の状態の書き方をそろえ（「📋 未着手」「🚧 一部完了（日付: 補足）」など）、状態表を番号の順に並べ直した。本文の表・コード・文は Markdown のかたまりのまま持つ。記録（archive/roadmap-history.md）の取り込みは 2c-2。
