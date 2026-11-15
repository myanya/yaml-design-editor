# TODO のシステム開発の設計書

[todos](../todos/README.md) の業務モデルを要件定義として、Spring Boot のアプリを作るための設計書体系を置くサンプルです。
設計書は、業務モデルから YAML の計算（生成の定義）で作ります。YDE の業務モデルから設計書体系へ、どこまで計算で変換できるかを確かめる場でもあります。

いまは操作系（アクション定義書 → 画面遷移設計 → 処理設計書）とデータ系（エンティティ定義書 → テーブル設計書 → テーブルアクセス設計書）までです。画面系・エラーとメッセージは、これから同じ進め方（議論 → 本体の改修 → 生成）で足します。

## 前提

- 要件定義は業務モデルそのもの（スコープ「業務モデル」＝`.yde/schemas/business/` と `.yde/views/business/`）です。機能要件が中心で、非機能要件は扱いません。
- アーキテクチャは決め打ちです: Thymeleaf・htmx・Java 21・Spring Boot 4・MyBatis（XML の Mapper に SQL を書く）・H2。Flyway は使いません。ログインはありません。
- 動きのある画面は htmx の部分更新（Controller が HTML の断片を返す）で作り、JSON の API は作りません。
- 型・桁・形式の入力検査は Controller（Bean Validation）、それ以外の前提は Service で確かめます。

## 構成

| 場所 | 中身 |
|---|---|
| `.yde/workspace.yaml` | 台帳（`design/`）と、定義体のスコープ（業務モデル・設計。設計は業務モデルを使ってよく、逆は誤り） |
| `.yde/schemas/business/`・`.yde/views/business/` | 業務モデルの写し（todos の `.yde/schemas` と `.yde/views` を丸ごと。手で直さない） |
| `.yde/schemas/design/` | 設計書の形（アクション定義書・画面遷移設計・処理設計書・状態の判定・エンティティ定義書・テーブル設計書・テーブルアクセス設計書）と、設計の対応の検査（`coverage.yaml`） |
| `.yde/transforms/design/` | 生成の定義（業務モデルのスコープから設計書を作る YAML の計算） |
| `.yde/views/design/` | 統合のビュー（操作ごと・エンティティ型ごとに基本から詳細までを 1 つの画面に・設計の対応表） |
| `design/generated/` | 生成物の設計書（手で直さない。業務モデルか生成の定義を直して作り直す） |

## 設計書と Spring Boot の対応

| 設計書 | 1 本の単位 | ID | 作り方 | Spring Boot |
|---|---|---|---|---|
| アクション定義書（基本設計） | 操作ごと | 業務の操作 ID（`complete-todo`） | 生成 | ― |
| エンティティ定義書（基本設計） | エンティティ型ごと | 業務のエンティティ型（`todo`） | 生成 | ― |
| 画面遷移設計（詳細設計） | エンティティ型ごと | 業務のエンティティ型 | 生成 | Controller |
| 処理設計書（詳細設計） | 操作ごと | 業務の操作 ID | 生成 | Service |
| 状態の判定（処理設計書） | Lifecycle ごと | 業務の Lifecycle ID（`todo-lifecycle`） | 生成 | 状態の enum と遷移の判定 |
| テーブル設計書（詳細設計） | エンティティ型ごと | 業務のエンティティ型 | 生成 | Entity・schema.sql |
| テーブルアクセス設計書（詳細設計） | エンティティ型ごと | 業務のエンティティ型 | 生成 | Repository（MyBatis の Mapper） |

設計書の ID は、どれも業務の ID そのものです（業務の定義の詳細化）。業務モデルから消えた操作やエンティティ型の設計書が残っていれば、「浮いた詳細化」として分かります。
前提（断りの種類）は `操作ID.前提ID`（`complete-todo.no-open-task`）、状態が合わない断りは `操作ID.state` です。

名前は業務の ID から機械的に作ります（`complete-todo` → `completeTodo`・`CompleteTodoForm`・`TodoController`）。英語の文法の規則（複数形など）は入れません。URL も業務の ID をそのまま使います（`POST /todo/{id}/complete-todo`）。

## データ系の決まり

| 決まり | 例 |
|---|---|
| 業務モデルで 2 か所に書かれた項目（一覧の行と個票）は 1 テーブル | `todo`（タイトル・概要も状態・日付も） |
| テーブル名は型の名前のスネーク | `todo-task` → `todo_task` |
| 明細テーブルは「親のテーブル名_項目のキー」（ID の無い繰り返し・参照の並び） | `todo_comments`・`todo_history`・`todo_tags`（英語の単数・複数はそろえない） |
| カラム名は項目のキーのまま。SQL の予約語（H2）に当たれば末尾に `_` | 状態の履歴の `from_`・`to_` |
| 入れ子の型と明細テーブルは、親の ID と並び順の列を足す | `todo_id`・`seq` |
| 文字は上限があれば `VARCHAR(n)`、無ければ長さなしの `VARCHAR` | タイトルは `VARCHAR(100)`、番号・文章は `VARCHAR` |
| 選択肢は値の CHECK、参照は外部キー。形式（pattern）は DB では確かめない | `status` の CHECK・`category` → `category(id)` |
| 採番は Service が「使われている番号の次」を計算する | `TodoMapper.maxId` → `TODO-` と 3 桁 |
| 同じテーブルへの値の書き込みは、操作ごとに 1 つの update | 完了は `UPDATE todo SET completed_on, result, status, updated_on` |
| 前提のうち「受け手の中の要素の条件」は件数を数え、受け手の値の条件は読んだ行で確かめる | 完了の前提は `TodoTaskMapper.countCompleteTodoNoOpenTask`（0 件なら通す） |

エンティティ定義書の CRUD 表は、項目ごとに、どの操作がその項目を作る（C）・書き換える（U）かを並べます（列は操作の ID）。

## 試し方

1. 業務モデルを展開した形（生成の入力）を見ます。

   ```bash
   ./gradlew runCli -q --args="generate samples/todos-sysdev --model --scope business"
   ```

   `actions` の `complete-todo` の `effects` に、do の手（完了日・結果）と、Lifecycle が書く状態の値と履歴の行と、保存時に自動で入る更新日が、1 列に並ぶことを確かめます。`entities` の `todo-task` に採番（`numbering`）と外側の型（`owner`）があることも見ます。
2. 生成物が最新かを確かめます。`upToDate: true` で、作る・作り直す・消す文書が無ければ最新です。

   ```bash
   ./gradlew runCli -q --args="generate samples/todos-sysdev"
   ```

3. 作業用コピーで、業務モデルを変えて作り直します。たとえばコピーの `.yde/schemas/business/operations.yaml` で完了の操作の `label` を変え、`generate <コピー> --apply` を実行します。該当箇所だけが作り直され、git の差分で変わった所を読めます。
4. 作業用コピーで、生成物を 1 つ消して検証します（`validate <コピー>`）。設計の対応の検査が「操作「complete-todo」に処理設計書がありません」「エンティティ型「todo」にテーブル設計書がありません」のように指摘します。
5. YDE で開き、次のビューを見ます。
   - `design/generated/actions/complete-todo.yaml` の「ビュー」タブの「操作の設計書（基本から詳細まで）」: アクション定義書と処理設計書が 1 つの画面に並び、この操作を扱う Controller と Mapper へ移れます。
   - `design/generated/entities/todo.yaml` の「ビュー」タブの「エンティティの設計書（基本から詳細まで）」: 項目・CRUD 表・テーブル・Mapper のメソッドが 1 つの画面に並びます。
   - 台帳のビュー「操作の設計の対応」「エンティティの設計の対応」: 操作・エンティティ型ごとに、どの設計書へ降りているかを一覧できます。

## 業務モデルの追従

業務モデルを直すときは、まず samples/todos を直し、一通り終えたら、todos の `.yde/schemas` と `.yde/views` を、それぞれこのサンプルの `business/` へ丸ごと写します（いまは指示があったときに手で写します）。
写したら `generate samples/todos-sysdev --apply` で作り直し、git の差分で設計書の変わった所を読みます。前回の設計との違いを見る専用の仕組みは、後の作業です。

## いま計算で作れないもの

- **SQL と Java の文字**: 設計書には文の組み立て（テーブル・項目・値・条件）を持たせ、MyBatis の XML・schema.sql・Entity クラスの文字は③で、この組み立てから作ります。テーブルアクセス設計書の列は業務の項目のキーで書き、カラム名はテーブル設計書から引きます。
- **項目ごとの名前の付け替え**: テーブル名・カラム名を、既定の決まりと別の名前にする仕組みはまだありません（予約語の `_` の辞書は、テーブル設計書の生成の定義にあります）。
- **番号と参照の文字数の上限**: 業務モデルに上限が無いので、長さなしの `VARCHAR` です（業務モデルの側の課題）。
- **前提の形**: 件数（受け手の中の要素の条件）と読んだ行（受け手の値の条件）のほかの形は、処理設計書に「手で書く」として出ます。todos の前提は、どちらかの形です。
- **一覧の読み出し**: ビュー（絞り込み・並び・集計）の SELECT は、画面系で扱います。
