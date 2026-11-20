# 設計書の確定と全体レビュー

現在の記載内容を基に、エンティティ・テーブル以外の設計書を、導出・補正と指定・追加から確定する経路へ移した。
設計書ごとの承認を待つ進め方から、全体をそろえて整合性をレビューする進め方に変更した。内容のレビュー完了はオーナーが判断する。
方針は [ADR-0039](../../../docs/decisions/0039-finalized-design-items.md)、エンティティの対応範囲は [生成処理](entity-generation-contract.md) に従う。

## 保存するもの

| 分類 | 場所 | 扱い |
|---|---|---|
| 導出 | `design/system/derived/` | 業務モデルと上流の確定値から生成。中の識別子を確定項目の ID として登録しない |
| 補正・指定 | `design/system/corrections.yaml` | 人が書く。個別補正と方式の既定値を区別する |
| 追加 | `design/system/additions.yaml` | 人が書く。文書、または既存文書のコレクションへ項目を追加する |
| 確定 | `design/generated/` | 従来の配置を維持。`designId` は `DOC-種類.導出元`、従来の `id` は導出元を示す |
| 根拠 | 確定文書の `origins` | 導出の規則、候補と採用結果、補正・指定・追加の理由 |
| 全体の検査記録 | `design/system/review.yaml` | 今回確定した文書と入力の一覧。オーナーの承認を意味しない |

`design/basic` の補正・追加は、引き続きエンティティと型の入力である。
`design/detail` の型対応表・索引の台帳・補正は物理設計の入力で、補正後の結果を `tables.yaml` に保存する。
テーブルのビューと DDL はその保存値を読む。型対応と台帳の候補を、確定項目と同じ ID の出現として二重登録しない。

## 対象と受け渡し

現行サンプルでは次の 20 種類、76 文書が対象となる。種類数は一覧・詳細・操作の画面や Controller を分けて数える。

| 設計書 | 種類（kind） | 主な接続 |
|---|---|---|
| アクション定義書 | `action-specs` | 入力・前提・状態・効果を後続へ渡す。C/U は基本設計の書込への参照 |
| 状態の判定 | `status-rules` | Lifecycle の状態・開始・遷移・終了を保存 |
| 画面定義書 | `screen-lists`・`screen-details`・`screen-operations` | 名称・入力・URL を詳細画面と Controller へ渡す |
| 画面遷移定義書 | `screen-transitions` | 確定した論理画面から画面一覧を作る |
| エラー定義書 | `errors` | 断りの種類、入力の誤り、既存のデータの決まりの扱い |
| メッセージ定義書 | `messages` | 確定した画面入力から入力メッセージを生成。本文を処理と画面へ渡す |
| テーブルアクセス設計書 | `table-access` | 確定したテーブル・列名と設計 ID を使い、メソッドの確定名を利用側へ渡す |
| 処理設計書 | `processes` | Mapper の確定名を使い、Service の確定名を Controller とエラー設計へ渡す |
| セッション情報設計 | `session-design` | 役割とキーを分け、補正後のキーを Controller へ渡す |
| 画面のクラスの対応表 | `screen-styles` | 規則から生成する対応表にも補正・指定・追加を適用する |
| 画面設計書 | `screen-designs-lists`・`screen-designs-details`・`screen-designs-operations` | 論理画面の確定値、入力制約と確定した読み出し先を使う |
| エラー設計書 | `error-handling` | メッセージ・処理・セッションを入力に例外と応答を保存 |
| 画面遷移設計 | `dashboard-flows`・`screen-flows` | 確定した URL・Mapper・Service・セッションキーを使う |
| ドメインと関連 | `domains`・`domain-relations` | 補正した業務情報を使い、関連の名前は確定したドメイン定義を読む |

生成順は `dependsOn` で宣言し、今回の依存出力を読む。前回の出力やファイル名の順番から値を補わない。
画面入力は `screen-operation-candidates` で先に確定する。同じ入力から Form とメッセージを作り、操作画面の表示文を最後に埋める。
これにより、画面の最大文字数を補正しても入力メッセージだけが古いままになる循環を避ける。

## 補正・指定・追加の例

生成物を直接編集しない。対象の `designId` は「全設計書の一覧」で確認する。

```yaml
# design/system/corrections.yaml
inputId: INPUT-system-corrections
corrections:
  - id: SCOR-mapper-name
    kind: table-access
    target: DOC-table-access.todo
    select: {collection: methods, key: methodId, equals: completeTodo}
    set: {name: saveCompletion}
    reason: 保存のメソッド名を統一する
specifications: []
```

`select` は先頭のコレクションからキーの値で一つを選び、順番が変わっても同じ項目を補正する。
選択先が無い・複数ある場合は止める。文書の属性は `set`、子の属性は `changes: [{path: [service, method], value: finish}]` で指定する。
数で配列の位置を指定する場合は、導出内容が変わったときに対象を再確認する。

```yaml
# design/system/additions.yaml
inputId: INPUT-system-additions
additions:
  - id: SADD-review-preference
    kind: session-design
    target: DOC-session-design.session-design
    collection: attributes
    items:
      - {key: reviewPreference, role: reviewPreference, contents: 表示設定, purpose: タブをまたいで共有する設定}
    reason: 表示方式による追加
```

コレクションの項目は `key`・`id`・`name`・`class`・`action`・`constant`・`method`・`kind` のうち先に得られる値を識別子にする。
同じ値の新規追加も重複として止める。追加してから個別補正を適用するので、既存項目の補正と別項目の追加を同時に書ける。
各設計書の先頭のコレクションを合成でき、未知の追加先は保存しない。ただし、後続への接続がない構造の変更は下記の整合性検査で止める。入れ子への追加は既存属性の補正として書く。
文書の追加は `document` に内容を書く。導出元の参照、形式、保存先の一意性も満たす必要がある。

方式の既定値は `specifications` に `{id, kind, target, rule: defaults, settings, reason}` を書く。
異なる既定値は競合し、個別補正が採用値を決めた場合だけ解決する。不採用の候補と理由も残る。
`false`・`0`・空文字・空配列は値として保持し、未指定と区別する。

## 検査と試し方

`system-input-gate` が入力文書を、各合成が対象消失・重複・競合を検査する。
`system-final` は今回の文書を集め、型・参照・識別子・未処理の入力を `$validate` で確かめる。
業務の効果・状態・書込などをアクション定義書だけで変える補正も止める。基本設計の C/U や状態の判定と共通の原本なので、業務モデルで変更して導出し直す。
Form のクラス名が画面設計書と Controller で異なる場合も止める。片方だけを補正して確定することはできない。
現在は状態の判定の構造、一覧画面の絞り込み・ボタン・区画の構成、Controller 名、Form のクラス名の変更を後続へ接続していないため、これらを導出値と異なる形に補正・追加しても確定しない。名称・URL・入力制約・物理名・Mapper と Service の呼出名など、上表の受け渡しとは区別する。
失敗すれば生成計画全体を書き込まず、前回の確定文書と手書き入力を保つ。
業務の要素と設計書の対応は、スコープを持つワークスペースの対応検査で確かめる。

1. `samples/todos-sysdev` を開き、ダッシュボードの切替欄から「アクション定義書」「処理設計書」「テーブルアクセス設計書」などを選ぶ。各種類に専用のダッシュボードがある。
2. 「対象の設計書」で文書を選び、「概要と根拠」「設計内容」を確認する。候補と「この設計書の一覧」には同じ種類だけが出る。対象を空にすると、その種類の全件を比較できる。一覧の行から詳細も開ける。
3. 「設計書の全体レビュー」で全設計書と、操作・エンティティの対応を見る。
4. 操作の設計書で `complete-todo` を開き、基本設計の入力・効果と処理の呼出先を比較する。関連設計書への移動も確認する。
5. 操作の画面の設計書で入力・確認・Form を比較し、メッセージ定義書の同じ欄を照合する。
6. テーブル設計書と Mapper で物理名を、処理と Controller で呼出名を比較する。
7. `origins` で導出元、採用した値、入力の理由を確認する。

補正の試験は作業用コピーで `generate <コピー> --apply` と `report <コピー> ddl` を実行する。
CLI の個別選択は `view <コピー> system-review --param document=DOC-action-specs.complete-todo`、文書ビューは
`view <コピー> action-design design/generated/actions/complete-todo.yaml` を使う。単独グリッドの `--param` では絞られない。
設計書別のダッシュボードは `design-review-<kind>`。たとえば `view <コピー> design-review-processes --param document=DOC-processes.complete-todo` で処理設計書を確認できる。

`TodosSystemDocumentsTest` は物理名 → Mapper → Service → Controller、画面入力 → Form・メッセージ、
セッション、追加と補正、競合と対象消失、未対応入力、再生成を確認する。
個別ビューと横断ビューの内容の一致、対応表に設計の型を混ぜないこと、複数のダッシュボードの到達範囲も確認する。
`TodosSysdevTest` と `TodosEntityFoundationTest` は従来の生成、ビュー、DDL と H2 への適用を維持する。

## 今回広げない範囲

条件付き必須・項目間の規則・CRUD の R/D の新しい扱いは追加していない。既存の Java・SQL・対象外の判断と理由を保つ。
`any` で保持する式や部品は各生成規則の対応範囲に従う。任意の補正を Java や HTML として実行できると検証したものではない。
「手で書く」などの既存の未対応表現、楽観ロック、ログイン、将来扱いの CSRF、H2 以外の実 DB、Spring Boot のアプリ生成は別の作業である。
次は設計書全体の内容をレビューし、コード生成に必要な不足や不整合を具体的な項目として整理する。
