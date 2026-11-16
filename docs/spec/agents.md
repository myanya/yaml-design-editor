# エージェント・アクティビティ・評価セット

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/agents-md.md -->

エージェント（`.yde/agents/`）・アクティビティ（`.yde/activities/`）・評価セット（`.yde/evals/`）の定義に書ける語の一覧。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## エージェントの定義

エージェント 1 つ。性格と、使ってよい道具の範囲を持つ実行者です。

- メタスキーマ: builtin-schemas/agent.yaml の root
- 読む実装: agent/Definitions.kt の parseAgent

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | エージェントの ID。アクティビティから指します。 |
| `name` |  | 文字列 |  | 表示名。 |
| `persona` | 必須 | 文章 |  | 性格と行動の指針。システムプロンプトに入ります。 |
| `rubric` |  | 文字列の配列 |  | レビューや作成で重く見る観点。 |
| `model` |  | {provider, id} | 画面で選んだモデル | 使うモデル。 |
| `tools` |  | 道具の分類の配列 | すべて | 使ってよい道具の分類。管理の道具はいつも使えます。 |

## モデルの設定

エージェントの model と、評価セットの変異の model。

- メタスキーマ: builtin-schemas/agent.yaml の root.model
- 読む実装: agent/Definitions.kt の parseAgent

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `provider` |  | openai か anthropic |  | プロバイダ。 |
| `id` |  | 文字列 |  | モデルの ID。 |

## アクティビティの定義

仕事の単位 1 つ。対象・指示・文脈・書いてよい範囲・合格の条件を持ちます。

- メタスキーマ: builtin-schemas/activity.yaml の root
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | アクティビティの ID。 |
| `name` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `agent` | 必須 | エージェントの ID |  | 実行するエージェント。 |
| `target` |  | {match} |  | 対象の文書。 |
| `instruction` | 必須 | 文章 |  | 指示。 |
| `context` |  | {items} | 開示なし | 文脈のレシピ。初めに開示するものを並べます。 |
| `allowedFiles` |  | glob の文字列の配列 | すべてのファイル | 書いてよいファイル。 |
| `allowedPaths` |  | パスの glob の配列 | すべての場所 | 書いてよい場所（文書の中のパス。例 review_comments*）。 |
| `tools` |  | 道具の分類の配列 | すべて | 使ってよい道具の分類。actions は、下の actions で許した操作の道具です。edit を外して actions だけにすると、書き込みを操作に限った仕事になります。 |
| `actions` |  | 操作の ID か「型名.操作名」の配列 | なし | エージェントが道具として使える操作。書いたものだけが道具になります。許した操作の書き込みは、allowedFiles・allowedPaths の外でも通ります。 |
| `limits` |  | {maxTurns, maxTokens, maxSeconds} |  | ループの上限。 |
| `gates` |  | {validation} |  | 提出のゲート。 |
| `approval` |  | human か auto | human | 承認のしかた。auto は、ゲートを通り・範囲の中で・人が編集していないときだけ自動で適用します（条件を欠くと承認待ちになります）。 |
| `trigger` |  | {type, match, debounceSeconds, everyMinutes} |  | 起動のきっかけ。 |
| `chain` |  | {onSuccess, onFailure, rules} |  | 連鎖。終わったあとに起動するアクティビティです。 |
| `retry` |  | {maxAttempts} |  | 無人の実行での再試行。 |
| `foreach` |  | {source, …} |  | 一括の実行。手動で起動したときに対象を並べ、1 件を 1 回の実行としてキューへ広げます。trigger が manual のときだけ書けます。 |
| `meta` |  | 真偽値 |  | 定義の文書（エージェント・アクティビティ・評価セット・skill・ビュー）への書き込みを許します。承認は human に固定で、スキーマと実行の記録には書けません。 |

## 対象

アクティビティの target。

- メタスキーマ: builtin-schemas/activity.yaml の root.target
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `match` |  | glob の文字列 |  | 対象のファイル。 |

## 文脈のレシピ

アクティビティの context。

- メタスキーマ: builtin-schemas/activity.yaml の root.context
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `items` |  | 開示の項目の配列 | 開示なし | 初めの文脈へ、書いた順に開示する項目。 |

## 開示の項目（items の要素）

context の items の要素。種別（kind）ごとに、書ける語が変わります。値には {target} などの穴を書けます。

- メタスキーマ: builtin-schemas/activity.yaml の root.context.items.item
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `kind` | 必須 | catalog・phase・view・doc・related・entity・sample・graph のどれか |  | 開示の種別。catalog と phase は match、view は view・rows・columns、doc は file・path、related と entity は id、sample は schema・count、graph は start・steps を一緒に書きます。 |
| `when` |  | always・has-target・no-target のどれか | always | 開示する条件。 |
| `budgetChars` |  | 整数 | doc・related・sample は 8000 字 | 文字数の予算。超えた分は決まった形で切ります。 |

## ループの上限

アクティビティの limits。

- メタスキーマ: builtin-schemas/activity.yaml の root.limits
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `maxTurns` |  | 整数 |  | ターンの数の上限。 |
| `maxTokens` |  | 整数 |  | トークンの数の上限。 |
| `maxSeconds` |  | 整数 |  | 実行の秒数の上限。 |

## 提出のゲート

アクティビティの gates。

- メタスキーマ: builtin-schemas/activity.yaml の root.gates
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `validation` |  | error-free・no-new-errors・none のどれか | error-free | 検証の条件。error-free は検証のエラーが無いこと、no-new-errors はエラーを増やさないことです。 |

## 起動のきっかけ

アクティビティの trigger。

- メタスキーマ: builtin-schemas/activity.yaml の root.trigger
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `type` |  | manual・on-change・schedule・on-invalid のどれか | manual | きっかけの種別。 |
| `match` |  | glob の文字列 | target.match | 見張るファイル。 |
| `debounceSeconds` |  | 整数 |  | 続けて起きた変更をまとめる秒数。 |
| `everyMinutes` |  | 整数 |  | 周期（分）。schedule のときは必須です。 |

## 連鎖

アクティビティの chain。

- メタスキーマ: builtin-schemas/activity.yaml の root.chain
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `onSuccess` |  | アクティビティの ID |  | 成功のときに起動するもの。 |
| `onFailure` |  | アクティビティの ID |  | 失敗のときに起動するもの。 |
| `rules` |  | {when, then} の配列 |  | 出力の値による分岐。上から順に調べ、最初に合った規則の then へ連鎖します。合わなければ onSuccess・onFailure です。 |

## 連鎖の分岐（rules の要素）

chain の rules の要素。

- メタスキーマ: builtin-schemas/activity.yaml の root.chain.rules.item
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `when` |  | {outcome, changedFiles, minChangedFiles, noteContains} |  | 条件。書いたものすべてに合うこと。 |
| `then` | 必須 | アクティビティの ID |  | 分岐の先。 |

## 連鎖の条件

連鎖の分岐の when。

- メタスキーマ: builtin-schemas/activity.yaml の root.chain.rules.item.when
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `outcome` |  | any・success・failure のどれか | any | 終わり方。 |
| `changedFiles` |  | glob の文字列 |  | 提出した変更に、この形に合うファイルがあること。 |
| `minChangedFiles` |  | 整数 |  | 変えたファイルの数の下限。 |
| `noteContains` |  | 文字列 |  | エージェントのメモと提出の文に、この文字が含まれること。 |

## 再試行

アクティビティの retry。

- メタスキーマ: builtin-schemas/activity.yaml の root.retry
- 読む実装: agent/Definitions.kt の parseActivity

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `maxAttempts` |  | 整数 | 1（再試行なし） | 試す回数の上限。失敗の理由を伝えて、もう一度実行します。 |

## 一括の実行

アクティビティの foreach。並べる元（source）ごとに、使う語が変わります。

- メタスキーマ: builtin-schemas/activity.yaml の root.foreach
- 読む実装: agent/ForeachDef.kt

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `source` | 必須 | view・issues・search・files・ids・query のどれか |  | 対象を並べる元。 |
| `view` |  | ビュー ID |  | source が view のときの、行を並べるビュー。 |
| `where` |  | detail-missing か all | detail-missing | source が view のときの行の条件。detail-missing は、詳細の文書がまだ無い行だけです。 |
| `severity` |  | error か warning | error | source が issues のときの重さ。warning は警告以上です。 |
| `match` |  | glob の文字列 |  | ファイルの絞り込み（issues・files・search）。 |
| `idPattern` |  | glob の文字列 |  | ID の形（ids）。 |
| `schemaIds` |  | スキーマ ID の配列 |  | スキーマの絞り込み（ids・search）。 |
| `text` |  | 文字列 |  | 探す文字（search）。 |
| `pathGlob` |  | パスの glob |  | 場所の絞り込み（search）。 |
| `refTo` |  | ID |  | この ID を参照しているもの（search）。 |
| `unresolved` |  | 真偽値 |  | 解決できない参照だけ（search）。 |
| `query` |  | {match, where, limit} |  | クエリ（query）。解 1 件が 1 回の実行になり、束縛の名前を穴（{名前}）で読めます。 |
| `as` |  | 穴を含む文字列 | 並べる元ごとの既定の文 | 1 件ごとの追加の指示のひな型。{id}・{title}・{file}・{path}・{message} などが対象の値に置き換わります。 |
| `limit` |  | 整数 | 20 | 1 回で広げる件数の上限。 |

## 評価セットの定義

評価セット 1 つ。アクティビティを、決まった入力で実行して結果を確かめます。

- メタスキーマ: builtin-schemas/eval.yaml の root
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | 評価セットの ID。 |
| `name` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `activity` | 必須 | アクティビティの ID |  | 評価するアクティビティ。 |
| `variants` |  | 変異の配列 | 今の定義のまま 1 系統（baseline） | 比べる変異（A/B）。 |
| `cases` | 必須 | ケースの配列 |  | 評価のケース。 |

## 変異（variants の要素）

評価セットの variants の要素。

- メタスキーマ: builtin-schemas/eval.yaml の root.variants.item
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `name` | 必須 | 文字列 |  | 変異の名前。 |
| `model` |  | {provider, id} |  | モデルの差し替え。 |
| `agent` |  | エージェントの ID |  | エージェントの差し替え。 |
| `instruction` |  | 文章 |  | 追加の指示。 |
| `context` |  | {items} |  | 文脈のレシピの差し替え。アクティビティの context を丸ごと置き換えます。 |

## ケース（cases の要素）

評価セットの cases の要素。

- メタスキーマ: builtin-schemas/eval.yaml の root.cases.item
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | ケースの ID。 |
| `name` |  | 文字列 |  | ケースの名前。 |
| `setup` |  | {file, content} の配列 |  | 決まった入力。試す場所へ上書きします。 |
| `instruction` |  | 文章 |  | 追加の指示。 |
| `expect` |  | {status, errorFree, changedFiles, unchangedFiles, content} |  | 期待する結果。 |

## 決まった入力（setup の要素）

ケースの setup の要素。

- メタスキーマ: builtin-schemas/eval.yaml の root.cases.item.setup.item
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `file` | 必須 | ファイルの場所 |  | 上書きするファイル。 |
| `content` | 必須 | YAML の文字 |  | 中身。 |

## 期待する結果

ケースの expect。

- メタスキーマ: builtin-schemas/eval.yaml の root.cases.item.expect
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `status` |  | submitted・aborted・limit・error・cancelled・any のどれか | submitted | 終わり方。 |
| `errorFree` |  | 真偽値 |  | 検証のエラーが 0 であること。 |
| `changedFiles` |  | glob の文字列の配列 |  | 変わるはずのファイル。 |
| `unchangedFiles` |  | glob の文字列の配列 |  | 変わってはならないファイル。 |
| `content` |  | {file, path, exists, contains, equals} の配列 |  | 中身の確かめ。 |

## 中身の確かめ（content の要素）

期待する結果の content の要素。

- メタスキーマ: builtin-schemas/eval.yaml の root.cases.item.expect.content.item
- 読む実装: agent/Evals.kt の parseEval

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `file` | 必須 | ファイルの場所 |  | 確かめるファイル。 |
| `path` |  | パスの文字列 |  | 確かめる場所。 |
| `exists` |  | 真偽値 | true | あること。 |
| `contains` |  | 文字列 |  | 含まれるはずの文字。 |
| `equals` |  | 文字列 |  | 一致するはずの値。 |

