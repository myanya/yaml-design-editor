# ダッシュボード

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/dashboards-md.md -->

ダッシュボード（`kind: dashboard` のビュー）に書ける語の一覧。区画（`sections` の要素）の語は [views.md](views.md)「区画」にある。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## ダッシュボード（kind: dashboard）

業務の入口。区画ごとに件数と一覧を出し、対象の詳細と操作へ進みます。ここと共通の語のほかのキーは誤りになります。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDashboard

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `sections` |  | 区画の配列 |  | 区画。flow.lifecycle を書かないときは 1 つ以上が必要です。 |
| `actions` |  | 操作の ID の配列 |  | 対象を持たない操作を、表示の順に並べます。 |
| `default` |  | 真偽値 | 定義のファイル名の順で最初のもの | ワークスペースの開始の画面にするダッシュボード。 |
| `placement` |  | auto・horizontal・vertical のどれか | auto | 区画のタイル（見出しと件数）の並べ方。 |
| `inputs` |  | 入力の配列 |  | すべての区画で共有する入力。値は区画の filter と、区画が参照する表の受け取る値に $名前 で渡ります。空の値は絞りません。 |
| `flow` |  | {lifecycle, view, group} か {steps, moves, start, history} |  | 業務の流れ。区画を段、操作を工程として流れ図に並べます。 |

## 入力（inputs の要素）

ダッシュボードの inputs の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.inputs.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseInputs

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `key` | 必須 | 名前 |  | 入力の名前。$名前 で読みます。 |
| `label` |  | 文字列 |  | 表示名。 |
| `type` |  | date・enum・string・ref か、スキーマの型の名前 | string | 入力の型。型の名前なら、その型の選択肢か参照の絞り込みが候補になります。 |
| `values` |  | 値か {value, label} の配列 |  | type: enum を直接書いたときの選択肢。 |
| `idPattern` |  | glob の文字列 |  | type: ref を直接書いたときの、参照先の ID の形。 |
| `idPatterns` |  | glob の文字列の配列 |  | 参照先の ID の形（複数）。 |
| `scope` |  | {schemaIds, documentIds} |  | 参照先を探す範囲。 |
| `multiple` |  | 真偽値 |  | 選択と参照の入力で、複数を選べるようにします。条件は includesAny か oneOf で受けます。 |
| `default` |  | 値か、$today・$monthStart・$monthEnd |  | 初期の値。$ の名前は基準日から決めます。複数を選べる入力は、カンマで区切ります。 |

## 業務の流れ（flow）

ダッシュボードの flow。lifecycle を書くと、段・区画・工程・入口・履歴を Lifecycle から作ります。そのときは steps・moves・start・history を書きません。

- メタスキーマ: builtin-schemas/view.yaml の root.flow
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseFlow

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `lifecycle` |  | Lifecycle の ID |  | この Lifecycle から流れを作ります。段は State、工程は操作による遷移です。 |
| `view` |  | ビュー ID |  | lifecycle を書いたときの、段の区画で対象を並べるグリッド。 |
| `group` |  | 文字列 |  | lifecycle を書いたときの、段の区画の見出しの群。 |
| `direction` |  | right か down | right | 流れの向き。 |
| `start` |  | {action, to} |  | 入口。対象を持たない操作と、作った対象が入る段です。 |
| `steps` |  | 区画の ID の配列 |  | 流れに載せる区画を、流れの順に並べます。区画の filter が「その段にいる」の定義です。lifecycle を書かないときは必須です。 |
| `moves` |  | {from, action, to} の配列 |  | 工程。from の段の対象に操作を当てて、to の段へ移します。 |
| `history` |  | {path, date, action, from, to, note} |  | 履歴。業務詳細の「流れの中の位置」に出す、対象の要素の配列と行の項目の名前です。 |

## 入口（start）

flow の start。

- メタスキーマ: builtin-schemas/view.yaml の root.flow.start
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseFlow

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `action` | 必須 | 操作の ID |  | 対象を作る操作。 |
| `to` | 必須 | 区画の ID |  | 作った対象が入る段。 |

## 工程（moves の要素）

flow の moves の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.flow.moves.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseFlow

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `from` | 必須 | 区画の ID |  | 元の段。 |
| `action` | 必須 | 操作の ID |  | 当てる操作。 |
| `to` |  | 区画の ID | 段に留まる操作です | 移る先の段。 |

## 履歴（history）

flow の history。

- メタスキーマ: builtin-schemas/view.yaml の root.flow.history
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseFlow

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `path` | 必須 | パスの文字列 |  | 履歴の配列の場所（対象の要素からの相対パス）。 |
| `date` |  | 項目のキー | date | 行の日付の項目。 |
| `action` |  | 項目のキー | action | 行の操作の項目。 |
| `from` |  | 項目のキー | from | 行の元の段の項目。 |
| `to` |  | 項目のキー | to | 行の先の段の項目。 |
| `note` |  | 項目のキー | note | 行の補足の項目。 |

## 区画の条件（filter）

区画の filter。検査 1 件と同じ形のうち、ここに並ぶ語を書けます。

- メタスキーマ: builtin-schemas/view.yaml の root.sections.item.filter
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDashboard

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | 条件の ID。 |
| `description` |  | 文章 |  | 説明。 |
| `match` | 必須 | node と edge を交互に並べた配列 |  | クエリ。先頭の node が、区画の行の要素です。 |
| `where` |  | 条件の配列 |  | ノードを値で絞る条件。入力の値を $名前 で書けます。 |
| `also` |  | {from, steps} |  | クエリの分岐。 |
| `expect` |  | exists か absent |  | 解があること・無いこと。 |
| `count` |  | {min, max} |  | 解の件数の範囲。 |
| `check` |  | 比較の配列 |  | 値の比較。 |
| `forAll` |  | {node, from, steps} |  | もう 1 つの集まりのすべての要素について、解があること。 |
| `message` |  | ひな型の文字列 |  | 文面。 |
| `limit` |  | 整数 |  | 照合する解の数の上限。 |

