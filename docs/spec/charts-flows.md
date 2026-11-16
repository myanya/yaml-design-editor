# チャートと業務フロー図

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/charts-flows-md.md -->

あとから足せる種類のビュー、チャート（`kind: chart`）と業務フロー図（`kind: flow`）に書ける語の一覧。ビューに共通の語（`id`・`name`・`description`・`kind`）と並び（`order`）は [views.md](views.md) にある。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## チャート（kind: chart）

元の表（グリッドかクロス集計）の行を横軸の項目、数の列を系列にして描きます。データは元の表が持ち、チャートには集計を書きません。

- メタスキーマ: app-core の資源 view/chart/schema.yaml のルートの項目
- 読む実装: app-core/view/chart/ChartViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `chart` |  | bar・stacked-bar・horizontal-bar・stacked-horizontal-bar・line・area・stacked-area・scatter・pie のどれか |  | 図の種類。棒・積み上げ棒・横棒・積み上げ横棒・折れ線・面・積み上げ面・散布図・円です。円は最初の系列だけを描きます。 |
| `view` |  | ビュー ID |  | 元の表。グリッドかクロス集計のビューで、行が横軸の項目、列が系列になります。 |
| `series` |  | 列の見出しの配列 | 数として読める列すべて（クロス集計の合計と未分類の列は除く） | 系列にする列。 |
| `x` |  | 列の見出し | 行のタイトル（無ければ ID） | 横軸の文字にする列。散布図では、横軸にする数の列で必須です。 |
| `label` |  | 列の見出し | 行のタイトル（無ければ ID） | 散布図で、点の名前にする列。 |
| `y` |  | {label, format} |  | 縦軸。 |
| `drill` |  | popup・below・right・none のどれか | popup | 棒・点・扇形を押したときの、材料の一覧の出し方。そのセルに集めた要素と値が並び、各要素の業務詳細か原本へ進めます。 |
| `scale` |  | {x, y} |  | 散布図の軸の種類。対数の軸では、0 以下の値は描きません。 |

## 縦軸（y）

チャートの y。

- メタスキーマ: app-core の資源 view/chart/schema.yaml の y
- 読む実装: app-core/view/chart/ChartViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` |  | 文字列 |  | 軸の見出し。 |
| `format` |  | DecimalFormat のパターン |  | 値の形（例 #,##0）。軸と、マウスを載せたときの文字に使います。 |

## 軸の種類（scale）

チャートの scale。

- メタスキーマ: app-core の資源 view/chart/schema.yaml の scale
- 読む実装: app-core/view/chart/ChartViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `x` |  | linear か log | linear | 横軸。 |
| `y` |  | linear か log | linear | 縦軸。 |

## チャートの区画

文書ビューの区画の chart。チャートを文書の表示に埋め込みます。view でチャートを指すか、rows で開いた文書の配列からその場で描きます。fields・backlinks・flow とは一緒に書けません。

- メタスキーマ: app-core の資源 view/chart/schema.yaml の section
- 読む実装: app-core/view/chart/ChartViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: X-14

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `view` |  | ビュー ID |  | 埋め込むチャート。rows とは一緒に書きません。 |
| `rows` |  | パスの文字列 |  | その場のチャート。開いた文書の中の配列の場所で、要素を行（横軸の項目）にします。series・x・label は要素のキーで書きます。 |
| `name` |  | 文字列 |  | その場のチャートの、図の見出し。 |
| `chart` |  | bar・stacked-bar・horizontal-bar・stacked-horizontal-bar・line・area・stacked-area・scatter・pie のどれか |  | その場のチャートの、図の種類。 |
| `series` |  | キーの配列 | 数のキーすべて | その場のチャートの、系列にするキー。 |
| `x` |  | キー | 要素の番号（1 から） | その場のチャートの、横軸の項目にするキー。散布図では横軸にする数のキーです。 |
| `label` |  | キー |  | その場のチャートの散布図で、点の名前にするキー。 |
| `y` |  | {label, format} |  | その場のチャートの縦軸。 |
| `drill` |  | popup・below・right・none のどれか |  | その場のチャートの、押したときの一覧の出し方。 |
| `scale` |  | {x, y} |  | その場のチャートの散布図の、軸の種類。 |

## 業務フロー図（kind: flow）

文書を、変換のひな型（projection）で箱・線・レーンへ変えて描きます。

- メタスキーマ: app-core の資源 view/flow/schema.yaml のルートの項目
- 読む実装: app-core/view/flow/FlowViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `sources` |  | {id, description, from, build, rules} の配列 |  | 複数の文書からの入力。クエリで選んで組み立てた結果を、id の名前の配列として projection へ渡します。元の文書は document から読みます。 |
| `layout` |  | {direction} |  | フローの配置。 |
| `projection` |  | {id, name, description, build, rules} |  | フローへの変換。出力は、フローの図のモデル（nodes・edges・lanes）です。 |

## 入力（sources の要素）

業務フロー図の sources の要素。

- メタスキーマ: app-core の資源 view/flow/schema.yaml の sources.item
- 読む実装: app-core/view/flow/FlowViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 名前 |  | 入力の名前。projection の中で、この名前の配列として読みます。 |
| `description` |  | 文章 |  | 説明。 |
| `from` | 必須 | {match, also, where, limit} |  | 入力を選ぶクエリ。 |
| `build` | 必須 | ひな型 |  | 選んだものから組み立てる中身。 |
| `rules` |  | {id, when, with, build} の配列 |  | 名前を付けた組み立て。 |

## 配置（layout）

業務フロー図の layout。

- メタスキーマ: app-core の資源 view/flow/schema.yaml の layout
- 読む実装: app-core/view/flow/FlowViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `direction` |  | down か right | down | 流れの向き。 |

## フローへの変換（projection）

業務フロー図の projection。変換定義と同じ形のうち、ここに並ぶ語を書きます。

- メタスキーマ: app-core の資源 view/flow/schema.yaml の projection
- 読む実装: app-core/view/flow/FlowViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | 変換の ID。 |
| `name` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `build` | 必須 | ひな型 |  | 出力の中身。フローの図のモデルの形に組み立てます。 |
| `rules` |  | {id, when, with, build} の配列 |  | 名前を付けた組み立て。 |

## フローの区画

文書ビューの区画の flow。fields・backlinks とは一緒に書けません。

- メタスキーマ: app-core の資源 view/flow/schema.yaml の section
- 読む実装: app-core/view/flow/FlowViewKind.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `view` | 必須 | ビュー ID |  | 埋め込む業務フロー図。 |

## フローの図のモデル

projection の build が組み立てる出力の形。ここに無いキーは誤りになります。

- メタスキーマ: app-core の資源 view/flow/flow-model.yaml の root
- 読む実装: app-core/view/flow/FlowViews.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `nodes` | 必須 | 箱の配列 |  | 箱。 |
| `edges` | 必須 | 線の配列 |  | 線。 |
| `lanes` |  | レーンの配列 |  | レーン。 |

## 箱（nodes の要素）

フローの図のモデルの nodes の要素。

- メタスキーマ: app-core の資源 view/flow/flow-model.yaml の root.nodes.item
- 読む実装: app-core/view/flow/FlowViews.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `key` | 必須 | 空白だけでない文字列 |  | 箱の鍵。線の from・to から指します。 |
| `label` | 必須 | 文字列 |  | 箱に出す文字。 |
| `entityType` |  | エンティティ型の名前 |  | 箱のエンティティ型。記法を当てるのに使います。 |
| `shape` |  | box・diamond・rounded のどれか |  | 箱の形。 |
| `lane` |  | レーンの鍵 |  | 箱を置くレーン。 |
| `detail` | 必須 | 任意の値 |  | 箱の材料。押したときの詳細と、記法の入力に使います。 |

## 線（edges の要素）

フローの図のモデルの edges の要素。

- メタスキーマ: app-core の資源 view/flow/flow-model.yaml の root.edges.item
- 読む実装: app-core/view/flow/FlowViews.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `key` |  | 空白だけでない文字列 |  | 線の鍵。 |
| `relation` |  | 関係の名前 |  | 線の関係。記法を当てるのに使います。 |
| `from` | 必須 | 箱の鍵 |  | 線の元の箱。 |
| `to` | 必須 | 箱の鍵 |  | 線の先の箱。 |
| `label` |  | 文字列 |  | 線に出す文字。 |
| `detail` | 必須 | 任意の値 |  | 線の材料。 |

## レーン（lanes の要素）

フローの図のモデルの lanes の要素。

- メタスキーマ: app-core の資源 view/flow/flow-model.yaml の root.lanes.item
- 読む実装: app-core/view/flow/FlowViews.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-17

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `key` | 必須 | 空白だけでない文字列 |  | レーンの鍵。箱の lane から指します。 |
| `label` | 必須 | 文字列 |  | レーンに出す文字。 |

