# ファイル体系の全体と、いまのファイルをどうするか

状態: **案（オーナーの承認前）**。2026-11-27 に、進め方を「種類ごとに横へ」から「系統ごとに縦へ」に変える提案として書きました。
承認されたら、[移行計画](system-model-plan.md) の「進め方」をこの内容で置き換えます。

構造と進み具合は、[移行の進み具合](design-progress.html) で見られます（目標のファイル体系を段と系統の格子に置き、箱ごとに済んだこと・残っていることを計算して出すページ）。

この文書は 2 つの一覧からなります。

1. **目標のファイル体系**: 移行が終わったときに在るファイル。
2. **いまのファイルの一覧**: いま在るファイルと変換定義のすべてについて、そのまま・変える・作り直す・消す・移すのどれにするかと、どの歩で行うか。

いまの一覧は、変換定義（`.yde/transforms/design/`）と、[ファイル単位の生成グラフ](design-file-graph.html) の箱から機械的に拾いました（変換定義 81 本、人の入力 14 種）。

## 進め方の要点

- 詳細設計は、読む向きが データ → 業務処理 → 画面 の 3 系統に分かれています。この順に、基本設計の項目からソースコードまでを 1 系統ずつ通します。
- 系統を通し終えたら、その系統の古い仕組み（導出・確定・文書・名前の付け替え表）を同じコミットで消します。
- 新しく作る段は、1 つ前の段の項目だけを ID で読みます。読む側のために元の形へ戻す変換（解いた形）は、新しく作りません。
- 変わってはいけないのは `app/` と、アプリの検証テストの結果だけです。途中の設計ファイルは、形が変わっても消えてもかまいません。
- 作業を始める前に、どこをどのように実現するかを説明して、オーナーの確認を取ります。中間ファイル（目標のファイル体系に無いファイル）が出るときは、なぜ要るのかと、いつ消すのかを説明します（オーナーの指示。2026-11-27）。

| 歩 | 系統 | 通す範囲 |
|---|---|---|
| 1 | 状態（試し） | 状態と遷移 → 状態の型 → 状態型（Java の enum） |
| 2 | データ | エンティティ・操作・検索条件・決まり → テーブル・テーブルへの読み書き → 行モデル・Mapper |
| 3 | 業務処理 | エラー・メッセージ → 処理の手順・エラーの扱い → Service・例外 |
| 4 | 画面 | 画面・画面遷移 → 画面の部品と入力・URL と遷移・セッション → Form・Controller と画面 |
| 5 | 補正の足場 | 操作・画面・エンティティ・型を、補正後の業務モデルを通さずに作る |
| 6 | 仕上げ | プログラム設計を別の置き場所へ。人の入力を、段ごとの全体の判断と個別の判断にまとめる |
| 7 | 確認 | 形の違う別の業務モデルで、変換定義を直さずに通るかを確かめる |

### 決まったこと（2026-11-27。オーナーの確認済み）

- 向きと合格条件: 系統ごとに縦へ通す。変わってはいけないのは `app/` とアプリの検証テストだけ。
- 補正前のファイルは持たない（当てた判断は、項目の `decisions` に残す）。
- 詳細設計のファイルの名前と場所は変えない。
- 詳細設計の個別の判断は、`design/detail/inputs/decisions.yaml` の 1 ファイルに書く（歩ごとにファイルを増やさない）。
- 詳細設計では、状態の遷移（どの状態のときに操作できるか）を変えられない。遷移は業務の事実なので、業務モデルを直す。
- 状態の型の ID は、型 `ENM-`・定数 `ENC-`・判定 `ENG-`。
- 歩 2 で、論理削除を「詳細設計の全体の判断から規則で足す」例として実装する（技術検証を兼ねる）。
  状態の遷移と、操作の効果「消す」は変えず、テーブルの列と、テーブルへの読み書き（消すを UPDATE に、読み出しに「削除されていない」の条件）を変える。
  対象は区分かエンティティで指定する。サンプルにエンティティを消す操作が無いので、業務モデルへの追加を伴う。

### 進めて変えたこと（2026-11-28。歩 2-1）

- テーブルのカラムの項目に、次の段（行モデルのプログラム設計）が要る値を運ばせた（`base` 基底の種類・`maxLength` 最大の長さ・`default` 初期値）。
  着手前の説明では「項目の形は変えない」としていたが、行モデルが基本設計を読まないようにするには、カラムが値を持つ必要があった（段をまたぐときは、次の段が要る情報を運ぶ）。
  名称・必須・選択肢は運ばず、テーブル設計書のビューが導出元をたどって読む（いままでどおり）。
- 段の指定（`stage: detail`）を `detail-tables` から外した。段の規則と補正（`.yde/stages`）は使っておらず、外しても生成物は変わらない。

## 1. 目標のファイル体系

「いま」の欄は、そのファイルがいまどうなっているかです。「歩」は、目標の形になる歩です。

### 人が書くもの

| ファイル | 中身 | いま | 歩 |
|---|---|---|---|
| `.yde/schemas/business/`・`.yde/views/business/` | 業務モデル | そのまま | ― |
| `design/basic/inputs/`（全体の判断） | 区分の定義、方式の指定（同時更新の検出など） | `entities/corrections.yaml` の中に、個別の判断と一緒に書いている | 6 |
| `design/basic/inputs/`（個別の判断） | 区分の割当、項目・操作・画面などへの判断（対象の ID と置き換える値） | `entities/`・`operations/`・`screens/` の 4 ファイルに分かれている | 6 |
| `design/detail/inputs/`（全体の判断） | SQL の型対応、Java の型対応、テーマ | 3 ファイル。そのまま | ― |
| `design/detail/inputs/`（個別の判断） | テーブルの台帳、テーブルと列への判断、詳細設計の項目への判断 | テーブルの分はそのまま。ほかは文書への補正・追加（`documents/`）として書いている | 1〜4 |
| `design/program/inputs/settings.yaml` | パッケージ、版、ポートなど | `design/detail/inputs/application/settings.yaml` | 6 |

### 基本設計（抽象システムモデル）: `design/basic/final/`

どれも項目の形で、同じ段の項目を ID で指します。作る元は業務モデルだけです。

| ファイル | 中身 | 数 | いま | 歩 |
|---|---|---|---|---|
| `types.yaml` | 型 | 1 | そのまま | ― |
| `classes.yaml` | 区分 | 1 | そのまま | ― |
| `entities/<エンティティ>.yaml` | エンティティと項目 | 4 | 項目の形。1 ファイル（約 2,800 行）にまとまっているので分ける | 5 |
| `states.yaml` | 状態と遷移 | 1 | そのまま | ― |
| `rules.yaml` | データの決まり | 1 | そのまま | ― |
| `queries.yaml` | 一覧の検索条件 | 1 | そのまま | ― |
| `operations/<操作>.yaml` | 操作 | 14 | 項目の形。補正後の業務モデルを読んで作っている | 5 |
| `screens/<画面>.yaml` | 画面 | 18 | 項目の形。補正後の業務モデルを読んで作っている | 5 |
| `transitions.yaml` | 画面遷移 | 1 | 文書の形（`screens/transitions/todo-dashboard.yaml`） | 4 |
| `errors.yaml` | エラー | 1 | 文書の形 | 3 |
| `messages.yaml` | メッセージ | 1 | 文書の形 | 3 |
| （検査記録） | 確定前の検査の結果 | 2 | `composed/final.yaml`・`composed/documents/input-check.yaml` | ― |

定義書（ビュー。ファイルを持たない）: エンティティ・アクション・画面は済み。画面遷移（歩 4）・エラー・メッセージ・ドメイン（歩 3）を足します。

### 詳細設計（具体システムモデル）: `design/detail/final/`

どれも項目の形にします。作る元は基本設計の項目だけで、業務モデルは読みません。ファイルの名前と置き場所は、いまのものを使い続けます。

| ファイル | 中身 | 作る元（基本設計） | 同じ段で指すもの | 数 | いま | 歩 |
|---|---|---|---|---|---|---|
| `java-types.yaml`・`application-theme.yaml` | 全体の判断の確定値 | ― | ― | 2 | そのまま | ― |
| `tables.yaml` | テーブル | エンティティ、型 | ― | 1 | 済み（変換定義は 1 本。カラムが、次の段の要る値を運ぶ） | 2 |
| `table-access/<エンティティ>.yaml` | テーブルへの読み書き | 操作、検索条件、決まり、エンティティ | テーブル | 4 | 文書の形。業務モデルも読んでいる | 2 |
| `status/<状態の流れ>.yaml` | 状態の型 | 状態と遷移 | ― | 1 | 文書の形 | 1 |
| `processes/<操作>.yaml` | 処理の手順 | 操作、決まり、エラー | 読み書き、状態の型 | 14 | 文書の形 | 3 |
| `error-handling.yaml` | エラーの扱い | エラー、メッセージ | 処理の手順 | 1 | 文書の形 | 3 |
| `screen-designs/styles.yaml` | 画面のクラスの対応表 | ― | ― | 1 | 文書の形 | 4 |
| `screen-designs/{lists,details,operations}/<画面>.yaml` | 画面の部品と入力 | 画面、メッセージ、エンティティ | クラスの対応表 | 18 | 文書の形。業務モデルも読んでいる | 4 |
| `controllers/<エンティティ>.yaml`・`controllers/dashboards/<一覧>.yaml` | URL と遷移 | 画面遷移、画面 | 処理の手順、部品と入力、セッション、読み書き | 5 | 文書の形。業務モデルも読んでいる | 4 |
| `session.yaml` | セッション | 画面遷移 | ― | 1 | 文書の形 | 4 |
| （検査記録） | 検査の結果 | ― | ― | 2〜3 | `composed/` の下 | 4 |

設計書（ビュー。ファイルを持たない）: テーブルは済み。ほかの 7 種は、項目にした歩で足します。

### プログラム設計: `design/program/final/`

中身は変えません。置き場所を移し、読む元を詳細設計の項目だけにします。

| ファイル | 中身 | 読むもの（詳細設計） | いま | 読む元を直す歩 | 移す歩 |
|---|---|---|---|---|---|
| `application.yaml` | アプリの設定 | ― | `design/detail/final/` | ― | 6 |
| `application-data-model.yaml` | 行モデル | テーブル | 同上。エンティティ定義書も直接読んでいる | 2 | 6 |
| `application-access.yaml` | Mapper | 読み書き、テーブル | 同上 | 2 | 6 |
| `application-runtime-model.yaml` | 状態型と例外 | 状態の型、エラーの扱い | 同上 | 1・3 | 6 |
| `application-service-inputs.yaml`・`-bindings.yaml`・`-programs.yaml` | Service | 処理の手順、読み書き | 同上。操作とエンティティ定義書も直接読んでいる | 3 | 6 |
| `application-web-forms.yaml` | Form | 画面の部品と入力 | 同上。メッセージ定義書も直接読んでいる | 4 | 6 |
| `application-web-bindings.yaml`・`application-web.yaml` | Controller と画面 | URL と遷移、部品と入力、セッション | 同上。基本設計の画面・画面遷移・メッセージも直接読んでいる | 4 | 6 |
| `application-output-plan.yaml` | 出力計画 | ― | 同上 | ― | 6 |
| （検査記録） | 入力と受け渡しの検査 | ― | `design/detail/composed/application/` | ― | 6 |

### ソースコード: `app/`

約 130 ファイル。プログラム設計からレポートで作ります（DDL と CSS は詳細設計から直接）。**最初から最後まで変わりません。** これが合格条件です。

## 2. いまのファイルをどうするか

### 集計

| どうする | 変換定義 | 行数 | 意味 |
|---|---|---|---|
| そのまま | 8 本 | 526 | 手を付けない |
| 変える | 19 本 | 16,769 | 残す。読む元や分け方を変える |
| 作り直す | 14 本 | 10,098 | 項目を読んで項目を作る変換定義に書き直す（古いものは消す） |
| 消す | 37 本 | 17,869 | 無くす（確定の変換定義・足場・付け替え表） |
| 移す | 3 本 | 277 | プログラム設計の置き場所へ移すだけ |
| 計 | 81 本 | 45,539 | |

歩ごとの、消す・作り直す変換定義:

| 歩 | 本数 | 行数 |
|---|---|---|
| 1 状態 | 2 本 | 801 |
| 2 データ | 3 本 | 4,282 |
| 3 業務処理 | 13 本 | 8,019 |
| 4 画面 | 22 本 | 12,251 |
| 5 補正の足場 | 11 本 | 2,614 |

作り直す 14 本（約 10,000 行）の置き換えとして、新しく書く変換定義は 13 本です（下の「新しく書く変換定義」）。移行が終わると、変換定義は 81 本から 43 本前後になります。

### 人の入力（14 種）

| いまのファイル | 作る変換定義（行数） | どうする | 歩 | 行き先・理由 |
|---|---|---|---|---|
| `design/basic/inputs/documents/additions.yaml` | （人が書く） | 消す | 4 画面 | 基本設計の文書への追加。文書の形が無くなるので、項目への判断に置き換わる |
| `design/basic/inputs/documents/corrections.yaml` | （人が書く） | 消す | 4 画面 | 基本設計の文書への補正。同上 |
| `design/basic/inputs/operations/corrections.yaml` | （人が書く） | 変える | 6 仕上げ | 操作への個別の判断。歩 6 で「個別の判断」にまとめる |
| `design/basic/inputs/screens/corrections.yaml` | （人が書く） | 変える | 6 仕上げ | 画面への個別の判断。同上 |
| `design/detail/inputs/appearance/theme.yaml` | （人が書く） | そのまま | ― | テーマ（詳細設計の全体の判断） |
| `design/detail/inputs/application/java-types.yaml` | （人が書く） | そのまま | ― | Java の型対応（詳細設計の全体の判断） |
| `design/detail/inputs/application/settings.yaml` | （人が書く） | 移す | 6 仕上げ | プログラム設計の全体の判断へ移す |
| `design/detail/inputs/documents/additions.yaml` | （人が書く） | 消す | 4 画面 | 詳細設計の文書への追加。項目への判断に置き換わる |
| `design/detail/inputs/documents/corrections.yaml` | （人が書く） | 消す | 4 画面 | 詳細設計の文書への補正。同上 |
| `design/basic/inputs/entities/corrections.yaml` | （人が書く） | 変える | 6 仕上げ | 区分の定義と方式の指定（全体の判断）と、区分の割当と補正（個別の判断）が同居している。歩 6 で分ける |
| `design/basic/inputs/entities/additions.yaml` | （人が書く） | 変える | 6 仕上げ | エンティティへの追加（個別の判断） |
| `design/detail/inputs/tables/corrections.yaml` | （人が書く） | そのまま | ― | テーブルと列への個別の判断 |
| `design/detail/inputs/tables/ledger.yaml` | （人が書く） | そのまま | ― | テーブルの台帳（詳細設計の個別の判断） |
| `design/detail/inputs/tables/sql-types.yaml` | （人が書く） | そのまま | ― | SQL の型対応（詳細設計の全体の判断） |

### 基本設計の生成物と変換定義（35 本）

「どうする」は、そのファイルを作る変換定義の扱いです。行数は変換定義の行数です。

| いまのファイル | 作る変換定義（行数） | どうする | 歩 | 行き先・理由 |
|---|---|---|---|---|
| `basic/final/classes.yaml` | `basic-classes`（12） | そのまま | ― | 区分の確定値 |
| `basic/final/types.yaml` | `basic-types`（12） | そのまま | ― | 型（項目の形） |
| `basic/final/queries.yaml` | `basic-queries`（151） | そのまま | ― | 一覧の検索条件（項目の形） |
| `basic/final/rules.yaml` | `basic-rules`（68） | そのまま | ― | データの決まり（項目の形） |
| `basic/final/states.yaml` | `basic-states`（170） | そのまま | ― | 状態と遷移（項目の形） |
| `basic/derived/documents/errors/errors.yaml` | `errors-derived`（469） | 作り直す | 3 業務処理 | 補正前のファイルは無くなる。エラーを項目の形で作る変換定義 1 本にする（操作・状態・決まりの項目を読む） |
| `basic/derived/documents/messages/messages.yaml` | `messages-derived`（682） | 作り直す | 3 業務処理 | 補正前のファイルは無くなる。メッセージを項目の形で作る変換定義 1 本にする（操作・画面・決まり・エラーの項目を読む） |
| `basic/derived/documents/domains/*.yaml（4）` | `domains-derived`（481） | 消す | 3 業務処理 | ドメイン定義書は、項目を読むビューだけにする |
| `basic/final/domains/*.yaml（3）` | `domains`（640） | 消す | 3 業務処理 | 同上 |
| `basic/composed/documents/system-screen-input-model.yaml` | `system-screen-input-model`（245） | 消す | 3 業務処理 | 補正後の業務モデル（3 段目）。メッセージを項目にしたら消す |
| `basic/derived/documents/domain-relations/domain-relations.yaml` | `domain-relations-derived`（206） | 消す | 3 業務処理 | ドメイン関連定義書も、ビューだけにする |
| `basic/final/domain-relations.yaml` | `domain-relations`（634） | 消す | 3 業務処理 | 同上 |
| `basic/final/errors.yaml` | `errors`（634） | 消す | 3 業務処理 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `basic/final/messages.yaml` | `messages`（634） | 消す | 3 業務処理 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `basic/derived/documents/screen-transitions/*.yaml（1）` | `screen-transitions-derived`（592） | 作り直す | 4 画面 | 補正前のファイルは無くなる。画面遷移を項目の形で作る変換定義 1 本にする（画面の項目だけを読む） |
| `basic/composed/operations-resolved.yaml` | `basic-operations-resolved`（801） | 消す | 4 画面 | 操作を解いた形（足場）。読む側が項目を直接読むようになったら消す |
| `basic/composed/screens-resolved.yaml` | `basic-screens-resolved`（1,156） | 消す | 4 画面 | 画面を解いた形（足場） |
| `basic/composed/documents/system-screen-bindings.yaml` | `system-screen-bindings`（106） | 消す | 4 画面 | URL の付け替え表。読む側が画面の項目の URL を直接読む |
| `basic/final/screens/transitions/*.yaml（1）` | `screen-transitions`（640） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `basic/composed/final.yaml` | `basic-final`（728） | 変える | 5 補正の足場 | 検査記録として残す。業務モデルと判断を直接読んで、型・エンティティ・区分を確定する形にする |
| `basic/final/entities.yaml` | `basic-entities`（13） | 変える | 5 補正の足場 | 中身は同じ。エンティティごとのファイルに分ける（entities/<エンティティ>.yaml） |
| `basic/final/operations/*.yaml（14）` | `basic-operations`（1,075） | 変える | 5 補正の足場 | 項目はそのまま。読む元を、補正後の業務モデルから、業務モデルと確定した型・項目に替える |
| `basic/final/screens/*.yaml（18）` | `basic-screens`（1,354） | 変える | 5 補正の足場 | 項目はそのまま。読む元を、補正後の業務モデルから業務モデルに替える |
| `basic/derived/model.yaml` | `basic-derived`（12） | 消す | 5 補正の足場 | 補正の足場（業務モデルを写したもの） |
| `basic/derived/targets.yaml` | `basic-targets`（166） | 消す | 5 補正の足場 | 補正の足場 |
| `basic/composed/descriptions.yaml` | `basic-descriptions`（71） | 消す | 5 補正の足場 | 補正の足場 |
| `basic/composed/type-changes.yaml` | `basic-type-changes`（62） | 消す | 5 補正の足場 | 補正の足場 |
| `basic/composed/type-model.yaml` | `basic-type-model`（183） | 消す | 5 補正の足場 | 補正の足場（補正後の業務モデルの手前） |
| `basic/composed/field-uses.yaml` | `basic-field-uses`（57） | 消す | 5 補正の足場 | 補正の足場 |
| `basic/composed/field-changes.yaml` | `basic-field-changes`（55） | 消す | 5 補正の足場 | 補正の足場 |
| `basic/composed/field-model.yaml` | `basic-field-model`（188） | 消す | 5 補正の足場 | 補正後の業務モデル（1 段目） |
| `basic/composed/entity-candidates.yaml` | `basic-entity-candidates`（1,080） | 消す | 5 補正の足場 | エンティティと項目を、業務モデルと判断から直接作る形にまとめる |
| `basic/composed/type-candidates.yaml` | `basic-type-candidates`（624） | 消す | 5 補正の足場 | 型を、業務モデルと判断から直接作る形にまとめる |
| `basic/composed/documents/model.yaml` | `system-model`（116） | 消す | 5 補正の足場 | 補正後の業務モデル（2 段目）。操作と画面の項目が読まなくなったら消す |
| `basic/composed/documents/input-check.yaml` | `basic-document-input-gate`（56） | 変える | 6 仕上げ | 判断の入力の検査。入力を全体・個別にまとめ直すときに合わせる |

### 詳細設計の生成物と変換定義（29 本）

「どうする」は、そのファイルを作る変換定義の扱いです。行数は変換定義の行数です。

| いまのファイル | 作る変換定義（行数） | どうする | 歩 | 行き先・理由 |
|---|---|---|---|---|
| `detail/derived/documents/status-rules/*.yaml（1）` | `status-rules-derived`（161） | 作り直す | 1 状態 | 補正前のファイルは無くなる。状態の型を項目の形で作る変換定義 1 本にする |
| `detail/final/status/*.yaml（1）` | `status-rules`（640） | 消す | 1 状態 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/tables.yaml` | `detail-tables`（285） | 変える | 2 データ | 項目はそのまま。導出と確定の 2 本を 1 本にする |
| `detail/derived/documents/table-access/*.yaml（4）` | `table-access-derived`（1,769） | 作り直す | 2 データ | 補正前のファイルは無くなる。テーブルへの読み書きを項目の形で作る変換定義 1 本にする（操作・検索条件・決まり・エンティティ・テーブルの項目だけを読む） |
| `detail/derived/tables.yaml` | `detail-tables-derived`（1,873） | 消す | 2 データ | テーブルの項目を作る変換定義 1 本にまとめる（判断は直接当てて、項目に記録を残す） |
| `detail/final/table-access/*.yaml（4）` | `table-access`（640） | 消す | 2 データ | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/derived/documents/processes/*.yaml（14）` | `processes-derived`（1,621） | 作り直す | 3 業務処理 | 補正前のファイルは無くなる。処理の手順を項目の形で作る変換定義 1 本にする |
| `detail/derived/documents/error-handling/error-handling.yaml` | `error-handling-derived`（499） | 作り直す | 3 業務処理 | 補正前のファイルは無くなる。エラーの扱いを項目の形で作る変換定義 1 本にする |
| `detail/final/processes/*.yaml（14）` | `processes`（640） | 消す | 3 業務処理 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/error-handling.yaml` | `error-handling`（634） | 消す | 3 業務処理 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/composed/review.yaml` | `system-final`（1,441） | 変える | 4 画面 | 全設計書の確定前検査。文書の形が無くなるので、項目の検査記録に作り直す（歩 1〜3 では、消した種類の分だけ削る） |
| `detail/derived/documents/screen-styles/screen-styles.yaml` | `screen-styles-derived`（117） | 作り直す | 4 画面 | 補正前のファイルは無くなる。画面のクラスの対応表を項目の形で作る変換定義 1 本にする |
| `detail/derived/documents/session-session-design.yaml` | `session-design-derived`（186） | 作り直す | 4 画面 | 補正前のファイルは無くなる。セッションを項目の形で作る変換定義 1 本にする |
| `detail/derived/documents/screen-designs-details/*.yaml（4）` | `screen-designs-details-derived`（987） | 作り直す | 4 画面 | 補正前のファイルは無くなる。画面の部品と入力（詳細）を項目の形で作る。画面とエンティティの項目だけを読む |
| `detail/derived/documents/screen-designs-lists/*.yaml（1）` | `screen-designs-lists-derived`（897） | 作り直す | 4 画面 | 補正前のファイルは無くなる。画面の部品と入力（一覧）を項目の形で作る。画面と検索条件の項目だけを読む |
| `detail/derived/documents/screen-designs-operations/*.yaml（14）` | `screen-designs-operations-derived`（1,120） | 作り直す | 4 画面 | 補正前のファイルは無くなる。画面の部品と入力（操作）を項目の形で作る。画面と操作の項目だけを読む |
| `detail/derived/documents/dashboard-flows/*.yaml（1）` | `dashboard-flows-derived`（394） | 作り直す | 4 画面 | 補正前のファイルは無くなる。URL と遷移（一覧画面）。上と同じ変換定義にまとめる |
| `detail/derived/documents/screen-flows/*.yaml（4）` | `screen-flows-derived`（604） | 作り直す | 4 画面 | 補正前のファイルは無くなる。URL と遷移を項目の形で作る変換定義 1 本にする |
| `detail/final/screen-designs/styles.yaml` | `screen-styles`（634） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/session.yaml` | `session-design`（634） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/composed/documents/system-access-bindings.yaml` | `system-access-bindings`（75） | 消す | 4 画面 | Mapper の呼び名の付け替え表。最後の読み手（画面の系統）が項目を読むようになったら消す |
| `detail/composed/documents/system-session-bindings.yaml` | `system-session-bindings`（37） | 消す | 4 画面 | セッションのキーの付け替え表 |
| `detail/final/screen-designs/details/*.yaml（3）` | `screen-designs-details`（640） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/screen-designs/lists/*.yaml（1）` | `screen-designs-lists`（640） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/screen-designs/operations/*.yaml（14）` | `screen-designs-operations`（641） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/composed/documents/system-process-bindings.yaml` | `system-process-bindings`（70） | 消す | 4 画面 | Service の呼び名の付け替え表 |
| `detail/final/controllers/dashboards/*.yaml（1）` | `dashboard-flows`（640） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/final/controllers/*.yaml（4）` | `screen-flows`（640） | 消す | 4 画面 | ファイルは同じ場所に、項目の形で残る。この変換定義（確定）は消し、判断は項目へ直接当てる |
| `detail/composed/documents/input-check.yaml` | `detail-document-input-gate`（42） | 変える | 6 仕上げ | 判断の入力の検査。入力をまとめ直すときに合わせる |

### プログラム設計と、詳細設計の全体の判断の確定値（17 本）

「どうする」は、そのファイルを作る変換定義の扱いです。行数は変換定義の行数です。

| いまのファイル | 作る変換定義（行数） | どうする | 歩 | 行き先・理由 |
|---|---|---|---|---|
| `detail/final/java-types.yaml` | `application-java-types`（61） | そのまま | ― | Java の型対応の確定値（詳細設計の全体の判断） |
| `detail/composed/appearance/input-check.yaml` | `application-theme-input-gate`（35） | そのまま | ― | テーマの入力の検査 |
| `detail/final/application-theme.yaml` | `application-theme`（17） | そのまま | ― | テーマの確定値（詳細設計の全体の判断） |
| `detail/final/application-runtime-model.yaml` | `application-runtime-model`（216） | 変える | 1 状態 | 読む元を、状態の型の項目（歩 1）とエラーの扱いの項目（歩 3）にする。歩 6 で移す |
| `detail/final/application-data-model.yaml` | `application-data-model`（193） | 変える | 2 データ | 読む元をテーブルの項目だけにする（エンティティ定義書を直接読まない）。歩 6 でプログラム設計へ移す |
| `detail/final/application-access.yaml` | `application-access`（2,310） | 変える | 2 データ | 読む元を、テーブルへの読み書きの項目にする。歩 6 で移す |
| `detail/composed/application/process-contract.yaml` | `application-process-gate`（153） | 変える | 3 業務処理 | 読む元を項目にする。歩 6 で移す |
| `detail/final/application-service-inputs.yaml` | `application-service-inputs`（486） | 変える | 3 業務処理 | 読む元を、処理の手順の項目だけにする（操作を直接読まない）。歩 6 で移す |
| `detail/final/application-service-bindings.yaml` | `application-service-bindings`（1,063） | 変える | 3 業務処理 | 読む元を、処理の手順とテーブルへの読み書きの項目にする。歩 6 で移す |
| `detail/final/application-service-programs.yaml` | `application-service-programs`（4,594） | 変える | 3 業務処理 | 同上（エンティティ定義書を直接読まない）。歩 6 で移す |
| `detail/composed/application/form-check.yaml` | `application-form-gate`（32） | 変える | 4 画面 | 読む元を項目にする。歩 6 で移す |
| `detail/final/application-web-forms.yaml` | `application-web-forms`（538） | 変える | 4 画面 | 読む元を、画面の部品と入力の項目だけにする（メッセージ定義書を直接読まない）。歩 6 で移す |
| `detail/final/application-web-bindings.yaml` | `application-web-bindings`（1,231） | 変える | 4 画面 | 読む元を項目にする。歩 6 で移す |
| `detail/final/application-web.yaml` | `application-web`（959） | 変える | 4 画面 | 読む元を詳細設計の項目だけにする（基本設計の画面・画面遷移・メッセージを直接読まない）。歩 6 で移す |
| `detail/composed/application/input-check.yaml` | `application-input-gate`（35） | 移す | 6 仕上げ | プログラム設計へ移す |
| `detail/final/application.yaml` | `application-configuration`（17） | 移す | 6 仕上げ | プログラム設計へ移す |
| `detail/final/application-output-plan.yaml` | `application-output-plan`（225） | 移す | 6 仕上げ | プログラム設計へ移す |

### 新しく書く変換定義（13 本）

| 歩 | 変換定義 | 作るもの | 読むもの | 置き換えるもの |
|---|---|---|---|---|
| 1 | 状態の型 | `detail/final/status/` | 状態と遷移 | `status-rules-derived`・`status-rules` |
| 2 | テーブルへの読み書き | `detail/final/table-access/` | 操作、検索条件、決まり、エンティティ、テーブル | `table-access-derived`・`table-access` |
| 3 | エラー | `basic/final/errors.yaml` | 操作、状態、決まり | `errors-derived`・`errors` |
| 3 | メッセージ | `basic/final/messages.yaml` | 操作、画面、決まり、エラー | `messages-derived`・`messages`・`system-screen-input-model` |
| 3 | 処理の手順 | `detail/final/processes/` | 操作、決まり、エラー、読み書き、状態の型 | `processes-derived`・`processes` |
| 3 | エラーの扱い | `detail/final/error-handling.yaml` | エラー、メッセージ、処理の手順 | `error-handling-derived`・`error-handling` |
| 4 | 画面遷移 | `basic/final/transitions.yaml` | 画面 | `screen-transitions-derived`・`screen-transitions` |
| 4 | 画面のクラスの対応表 | `detail/final/screen-designs/styles.yaml` | （詳細設計の判断） | `screen-styles-derived`・`screen-styles` |
| 4 | 画面の部品と入力（一覧・詳細・操作の 3 本） | `detail/final/screen-designs/` | 画面、検索条件、エンティティ、操作、メッセージ | `screen-designs-*-derived`・`screen-designs-*` の 6 本 |
| 4 | URL と遷移 | `detail/final/controllers/` | 画面遷移、画面、処理の手順、セッション | `screen-flows-*`・`dashboard-flows-*` の 4 本 |
| 4 | セッション | `detail/final/session.yaml` | 画面遷移 | `session-design-derived`・`session-design` |

テーブル（歩 2）は、2 本（導出・確定）を 1 本にまとめました（2026-11-28）。エンティティ・型（歩 5）は、いまの確定の変換定義（`basic-final`）を、業務モデルと判断を直接読む形に直します。

## 3. スキーマ・ビュー・レポート

スキーマとビューは、生成物に付いています。生成物を消す・作り直すときに、同じコミットで合わせます。

| 種類 | いま | 扱い |
|---|---|---|
| 設計のスキーマ（`.yde/schemas/design/`） | 89 | 消す生成物のスキーマ（文書の形・足場・付け替え表）を消し、項目にした種類に 1 つずつ足す。突き合わせできた範囲で、消す生成物に付くものが 34 |
| 生成入力のビュー（`.yde/views/design/*/input/`） | 113 | 生成物を読むためのビュー。消す生成物の分を消し、項目の種類ごとに 1 つにする。消す生成物に付くものが 41 以上 |
| レビュー用のビュー（`.yde/views/design/*/review/`） | 69 | 文書を選んで見るダッシュボード（`design-review-*`）は、文書の形が無くなる歩で消す。定義書・設計書のビューを、項目の種類ごとに足す |
| レポート（`.yde/reports/`） | 84 | ソースコードのひな形と出力定義。そのまま（読む元のプログラム設計の形は変えない） |

## 4. 終わったときに無くなるもの

- **足場**: 補正を当てる途中経過と補正後の業務モデル（12 種）、解いた形（2 種）、名前の付け替え表（4 種）、画面の入力を重ねたモデル（1 種）。
- **確定の変換定義**: 文書ごとに 1 本ずつある、ほぼ同文の 16 本（約 10,000 行）。判断は項目へ直接当てます。
- **文書の形のファイル**: 基本設計の 5 種と、詳細設計の 11 種。
- **文書への補正・追加の入力**: `design/*/inputs/documents/` の 4 ファイル。項目への判断に置き換わります。

## 5. 決めていただきたい点

1. **向きと合格条件。** 系統ごとに縦へ通し、通すたびに古い仕組みを消す。変わってはいけないのは `app/` とアプリの検証テストだけにする。
2. **補正前のファイルを持たない。** 理想のファイル体系には「補正前（比較用）」の箱がありますが、操作と画面では、補正前のファイルを作らず、当てた判断を項目（`decisions`）に残す形にしました。ほかの種類もこれにそろえる前提で書いています。箱を残したい場合は、歩ごとに導出のファイルが残ります。
3. **ファイルの名前は変えない。** 詳細設計は、いまの名前（`table-access`・`processes`・`screen-designs`・`controllers`）を使い続けます。理想のファイル体系の箱の名前（テーブルへの読み書き・処理の手順 など）に合わせて名前を変えることもできますが、差分が増えるだけなので、変えない案にしています。
4. **歩 1（状態）から始めてよいか。**
