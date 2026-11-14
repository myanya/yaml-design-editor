# TODO のシステム開発の設計書

[todos](../todos/README.md) の業務モデルを要件定義として、Spring Boot のアプリを作るための設計書体系を置くサンプルです。
設計書は、業務モデルから YAML の計算（生成の定義）で作ります。YDE の業務モデルから設計書体系へ、どこまで計算で変換できるかを確かめる場でもあります。

いまは「操作の筋」（アクション定義書 → 画面遷移設計 → 処理設計書 → テーブルアクセス設計書）までです。データ・画面・エラーとメッセージの筋は、これから同じ進め方（議論 → 本体の改修 → 生成）で足します。

## 前提

- 要件定義は業務モデルそのもの（`.yde/schemas/business/` と `.yde/views/business/`）です。機能要件が中心で、非機能要件は扱いません。
- アーキテクチャは決め打ちです: Thymeleaf・htmx・Java 21・Spring Boot 4・MyBatis（XML の Mapper に SQL を書く）・H2。Flyway は使いません。ログインはありません。
- 動きのある画面は htmx の部分更新（Controller が HTML の断片を返す）で作り、JSON の API は作りません。

## 構成

| 場所 | 中身 |
|---|---|
| `.yde/schemas/business/`・`.yde/views/business/` | 業務モデルの写し（todos の `.yde/schemas` と `.yde/views` を丸ごと。手で直さない） |
| `.yde/schemas/design/` | 設計書の形（アクション定義書・画面遷移設計・処理設計書・状態の判定・テーブルアクセス設計書）と、設計の対応の検査（`coverage.yaml`） |
| `.yde/transforms/design/` | 生成の定義（業務モデルから設計書を作る YAML の計算） |
| `.yde/views/design/` | 統合のビュー（操作ごとに基本から詳細までを 1 枚に・操作の設計の対応表） |
| `design/generated/` | 生成物の設計書（手で直さない。業務モデルか生成の定義を直して作り直す） |

## 設計書と Spring Boot の対応

| 設計書 | 1 枚の単位 | ID | 作り方 | Spring Boot |
|---|---|---|---|---|
| アクション定義書（基本設計） | 操作ごと | 業務の操作 ID（`complete-todo`） | 生成 | ― |
| 画面遷移設計（詳細設計） | エンティティ型ごと | 業務のエンティティ型（`todo`） | 生成 | Controller |
| 処理設計書（詳細設計） | 操作ごと | 業務の操作 ID | 生成 | Service |
| 状態の判定（処理設計書） | Lifecycle ごと | 業務の Lifecycle ID（`todo-lifecycle`） | 生成 | 状態の enum と遷移の判定 |
| テーブルアクセス設計書（詳細設計） | エンティティ型ごと | 業務のエンティティ型 | 生成 | Repository（MyBatis の Mapper） |

設計書の ID は、どれも業務の ID そのものです（業務の定義の詳細化）。業務モデルから消えた操作の設計書が残っていれば、「浮いた詳細化」として分かります。
前提（断りの種類）は `操作ID.前提ID`（`complete-todo.no-open-task`）、状態が合わない断りは `操作ID.state` です。

名前は業務の ID から機械的に作ります（`complete-todo` → `completeTodo`・`CompleteTodoForm`・`TodoController`）。英語の文法の規則（複数形など）は入れません。URL も業務の ID をそのまま使います（`POST /todo/{id}/complete-todo`）。

## 試し方

1. 業務モデルを展開した形（生成の入力）を見ます。

   ```bash
   ./gradlew runCli -q --args="generate samples/todos-sysdev --model"
   ```

   `actions` の `complete-todo` の `effects` に、do の手（完了日・結果）と、Lifecycle が書く状態の値と履歴の行と、保存時に自動で入る更新日が、1 列に並ぶことを確かめます。
2. 生成物が最新かを確かめます。`upToDate: true` で、作る・作り直す・消す文書が無ければ最新です。

   ```bash
   ./gradlew runCli -q --args="generate samples/todos-sysdev"
   ```

3. 作業用コピーで、業務モデルを変えて作り直します。たとえばコピーの `.yde/schemas/business/operations.yaml` で完了の操作の `label` を変え、`generate <コピー> --apply` を実行します。アクション定義書・処理設計書・画面遷移設計の該当箇所だけが作り直され、git の差分で変わった所を読めます。
4. 作業用コピーで、生成物を 1 つ消して検証します（`validate <コピー>`）。設計の対応の検査が「操作「complete-todo」に処理設計書がありません」のように指摘します。
5. YDE で開き、`design/generated/actions/complete-todo.yaml` の「ビュー」タブで「操作の設計書（基本から詳細まで）」を開きます。アクション定義書と処理設計書が 1 枚に並び、この操作を扱う Controller と Mapper へ移れます。台帳のビュー「操作の設計の対応」では、操作ごとにどの設計書へ降りているかを一覧できます。

## 業務モデルの追従

業務モデルを直すときは、まず samples/todos を直し、一通り終えたら、todos の `.yde/schemas` と `.yde/views` を、それぞれこのサンプルの `business/` へ丸ごと写します（いまは指示があったときに手で写します）。
写したら `generate samples/todos-sysdev --apply` で作り直し、git の差分で設計書の変わった所を読みます。前回の設計との違いを見る専用の仕組みは、後の作業です。

## いま計算で作れないもの

- **同じ表への書き込みを 1 つの文にまとめる**: 完了の操作は、todo の表への UPDATE が列ごとに 4 文になります。効果を表ごとにまとめる計算（グループ化）が、変換にまだありません。
- **前提を SQL に写す**: 「済んでいない作業項目が無い」のような前提は、テーブルアクセス設計書に「手で書く」として出ます。前提の形ごとの写し方を、データの筋で決めます。
- **表と列の名前**: テーブルアクセス設計書の表と列の名前は仮です（型の名前のスネーク・子の表は「型_項目」）。業務の項目名 `from`・`to` は SQL の予約語なので、データの筋で名前の決まりを決めます。
- **SQL の文字**: 設計書には文の組み立て（表・列・値・条件）を持たせ、MyBatis の XML の文字は③で、この組み立てから作ります。
