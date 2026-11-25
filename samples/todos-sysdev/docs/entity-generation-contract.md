# エンティティ定義の生成処理と対応範囲

[ADR-0039](../../../docs/decisions/0039-finalized-design-items.md) と、[属性・ビューの案](entity-definition-plan.md)に従う。
既存のエンティティ定義書・横断の項目一覧・C/U とテーブル生成は、検査した確定項目へ切り替えた。
これは実装の到達点であり、設計書のオーナーレビュー完了ではない。

## 到達点と残る作業

| 区切り | 到達条件 | 状況 |
|---|---|---|
| 既存ビューの切り替え | 明記を保って補正し、個別・横断・後段が同じ確定値を読む | 接続済み。型・参照・側面間の食い違い・未処理の入力を検査 |
| 追加と方式の拡張 | 人や規則が追加した項目も補正し、成立条件を検査する | 区分の追加・指定・補正を接続済み。その他の追加と楽観ロックなどの展開は残る |
| 内容のレビュー | 必要な属性・表示・根拠を確認し、オーナーが了承する | エンティティ・テーブルを含む設計書全体で、内容と整合性のレビューを継続 |

エンティティ・テーブル以外の対応範囲・接続・確認手順は、[設計書の確定と全体レビュー](design-document-migration-plan.md)にある。

条件付き必須・項目間の業務規則・CRUD の R/D は保留する。SQL・Java の具体的な型は詳細設計で扱う。
今の TODO だけで足りるかではなく、設計書として必要な内容と業務モデルの構造から過不足を判断する。

## 入力と保存先

| 場所 | 内容 | 編集する側 |
|---|---|---|
| `design/basic/derived/model.yaml` | スキーマ原文と導出記録 | 生成 |
| `design/basic/derived/targets.yaml` | 設計 ID と各側面の宣言・原文の場所 | 生成 |
| `design/basic/inputs/entities/corrections.yaml` | 補正と方式・対象の指定 | 人 |
| `design/basic/inputs/entities/additions.yaml` | 新しい設計項目の追加。現在は区分 | 人 |
| `design/basic/composed/` | 候補・採用記録・再解決結果・確定前の検査結果 | 生成 |
| `design/basic/final/entities.yaml` | 確定したエンティティ・項目・操作・C/U | 生成 |
| `design/basic/final/types.yaml` | 確定した論理型 | 生成 |
| `design/basic/final/classes.yaml` | 確定した区分 | 生成 |

旧 `ledger.yaml` の区分定義は追加へ、割当は補正一覧の `specifications` へ移した。
追加の `designId`・補正と指定の `target` は文字であり、生成中の集合で対象を確認する。
通常の設計 ID と参照は確定文書にだけ登録し、前回の生成物へ頼らない。

## 現在の変換経路

1. `basic-derived` が業務モデルとスキーマ原文を保持し、`basic-targets` が設計 ID と宣言を対応付ける。
2. `basic-descriptions` が説明を合成する。食い違いがあれば生成を止める。業務モデルの食い違いは業務モデルを直す（ADR-0042。TODO の ID の説明は業務モデルの側でそろえ、補正 `COR-001` を無くした）。
3. `basic-type-changes` → `basic-type-model` が名前付き共通型の補正を原文へ当て、`$schemaModel` で解決する。
4. `basic-field-uses` → `basic-field-changes` → `basic-field-model` が利用箇所を分けて各側面へ個別補正を当て、再解決する。継承値を明記へ変えない。
5. `basic-entity-candidates`・`basic-type-candidates` が保存形へ写す。同じ項目の値や入れ子が側面間で食い違えば停止する。
6. `basic-final` が区分の追加と指定、名称などの補正、根拠を合わせる。`$validate` で構造・参照と未処理の入力を検査する。
7. `basic-entities`・`basic-types`・`basic-classes` が確定項目を保存する。ビューと `detail-tables` はここから読む。

依存関係は `dependsOn` で明示する。今回成功した依存出力だけを読み、前回の生成物を隠す。
循環・競合・型の誤り・未処理の入力があれば、後続と計画全体の書き込みを止める。
新しい経路へ `stage` を再適用しない。ほかの設計書は移行まで従来の段を使う。

## 補正の対応範囲

| 対象 | 接続した属性 |
|---|---|
| 名前付き共通型 `TYP-` | `type`・`description`・`maxLength`・`minLength`・`min`・`max`・`pattern`・`format`・`default`・`values`・`required` |
| 利用箇所の項目 `FLD-` | 上記と `label`・`unique`。型に所属する項目の補正も各利用箇所へ渡す |
| エンティティ・操作・区分 | `label`・`description`。エンティティの区分は `entityClass` または指定で決める |

型参照の補正は `TYP-` の設計 ID を指定し、今回の導出にある YDE の型名へ変換して再解決する。
値の置換と型の狭め方は別であり、YDE の型の規則に反する補正は失敗する。
共通型を最大 100 から 200 に変えても、項目に明記した 100 は残る。未指定の項目は 200 を継承する。
`false`・`0`・空文字・空配列はキーが無い場合と区別する。

組込み型の補正、その他の属性、利用箇所が得られない項目、継承した `cases` の子の個別補正は未対応である。
中間の `pending`・`builtinPending` に残し、確定前に停止する。直書きの `cases` の子は反映できる。
区分以外の追加や未対応の方式も、確定前に停止する。

## 区分を追加して指定する例

```yaml
# design/basic/inputs/entities/additions.yaml
additions:
  - id: ADD-001
    kind: class
    reason: 業務モデルから導けない区分を定義する
    class: {designId: CLS-master, name: master, label: マスタ}
```

```yaml
# design/basic/inputs/entities/corrections.yaml
specifications:
  - id: SPC-category-class
    target: ENT-category
    rule: entity-class
    settings: {entityClass: CLS-master}
    reason: 参照されるデータとして分類する
```

追加した区分の表示名も `target: CLS-master` の補正で変更できる。
補正対象の消失は、前回の確定文書に同じ ID が残っていても指摘する。

## 確定値の形とレビュー

論理型への参照を保持し、各項目にも適用する基底・制約・初期値を保存する。
配列は `item`、マップは `value`、object は `fields`、選択肢の子は `cases` に保持する。
利用箇所の子は `FLD-todo.comments.item.body` のように入れ物を含む ID を持つ。再帰する型は `type` の参照で閉じる。
参照条件は `reference` の対象種別・関係名・探索範囲・ID 条件の層・対象エンティティの配列に保持する。
`attributeStates` は `present`・`unconstrained`・`not-applicable` を使い、値へ表示用の文字を混ぜない。
`origins` と導出・合成の記録に、原文の各宣言と補正・追加・指定を残す。
操作と C/U の原本は `operations[].writes` 一つであり、別の `crud` 表を保存しない。
並びの項目への書込は、子要素（並びの要素）への作用も同じ書込に保存する。`element` は C（足す）・U（変える）・D（消す）の並びで、`elementItems` は書く子の項目の ID である。
足す（`insert`）は C と書いた子の項目、丸ごと入れ替える（`set`）と消す（`delete`）は C・U・D と子要素のすべての項目になる。作るときに値を入れる並びは C、空で作る並びには付けない。
子のエンティティの中への作用（型の操作）は、親のエンティティの操作にも、その並びの項目への U の書込として現れる（`element: [U]` と、変えた子の項目）。
ビューは選択・並び・集計・表示を担当し、補正や型継承を計算し直さない。

確認は作業用コピーで生成し、次を比較する。

1. エンティティ定義書で TODO を選び、タイトルの最大文字数 100、日付形式、`tasks` の初期値 `[]` を確認する。
2. エンティティの指定を外し、横断の項目一覧で同じ値を確認する。保存された `entities.yaml` とも一致する。
3. 共通型を 200、TODO のタイトルだけを 60 に補正して再生成する。タイトルは 60、カテゴリ名は 200、DDL もそれぞれの長さになる。
4. 概要の区分、操作一覧と CRUD、コメントの子項目の制約と導出元を確認する。
5. 対象のない補正や未対応の方式を入れると生成が止まり、前回の生成物を変えないことを確認する。

CLI は `generate <コピー> --apply`、`view <コピー> entity-definition --param entity=ENT-todo`、`report <コピー> ddl` を使う。
単独グリッドの CLI はパラメータで絞らないため、個別表示はダッシュボードを使う。
`TodosEntityFoundationTest`・`TodosSysdevTest` は保存値とビュー・DDL、再生成、検証、H2 への適用を確認する。

## 次に接続する追加と方式

区分以外の追加と方式も、準備 → 展開 → 確定の順を守る。
規則は YAML で追加・変更の候補と成立条件を出し、処理順を `dependsOn` で指定する。
個別補正は規則の既定値の競合を解決できるが、成立条件を免除しない。
同じ値の候補は全根拠を保持し、同じ ID やキーの新規追加は重複として指摘する。
既存項目の兼用は使用項目を明示し、指定がなければ専用項目を追加する。
楽観ロックなら版の項目・操作との関係・照合と更新の成立条件を一緒に扱う。
一般的な合成規則は `TodosDesignCompositionTest`・`TodosDesignGenerationTest` で検証済みだが、実サンプルの方式の展開は未接続である。
