# ビュー

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/views-md.md -->

ビューの定義（`.yde/views/` の YAML）に書ける語の一覧。グリッド（表）・クロス集計・文書ビューを扱う。ダッシュボードは [dashboards.md](dashboards.md)、グラフレンズと図の記法は [lens-diagrams.md](lens-diagrams.md)。チャートと業務フロー図は、あとから足せる種類で、ここには載せていない。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## ビューに共通の語

どの種類のビューにも書く語。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | ビューの ID。ダッシュボードの区画・レポートの $view・文書ビューの区画から指します。 |
| `name` |  | 文字列 | ID を表示します | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `kind` |  | grid・document・matrix・lens・diagram-notation・dashboard のどれか（あとから足せる種類は chart・flow） | grid | ビューの種類。 |

## グリッド（kind: grid）

複数の文書を横断する表。行は論理 ID か、クエリの解です。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `rows` |  | {idPattern, schemaIds} か {match, also, where, rowKey, limit} |  | 行になるもの。必ず書きます。 |
| `columns` |  | 列の配列 |  | 一覧の列。必ず書きます。 |
| `order` |  | {schemaId, fileGlob, path, type, direction} | ID の順（クエリの行はクエリの順） | 行の並び。 |
| `params` |  | 名前の配列 |  | 受け取る値の名前。rows.where に $名前 で書きます。ダッシュボードの inputs から渡り、値が無ければその条件は外れます。 |
| `detail` |  | {schemaId, fileGlob, filePattern} |  | 詳細の文書。行の ID の詳細と見なす出現と、「作成」の作り先です。 |
| `newRow` |  | {file, path} か {filePattern} |  | 新しい行の入口。一覧の文書の配列へ要素を足すか、新しい文書を作ります。 |
| `corrections` |  | {schemaIds, target, set} |  | 補正を重ねます。path の列のセルに、補正の文書の置き換える値を重ねて出します（元の文書は変えません）。重ねたセルは読み取り専用です。 |

## 行（rows）

グリッドとクロス集計の rows。論理 ID の形（idPattern）か、クエリ（match）か、値の軸（value。クロス集計だけ）のどれかで書きます。

- メタスキーマ: builtin-schemas/view.yaml の root.rows
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView・parseMatrixView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `idPattern` |  | glob の文字列 |  | この形に合う論理 ID が行になります（例 SCR-*）。match とは一緒に書けません。 |
| `schemaIds` |  | スキーマ ID の配列 |  | どれかのスキーマの文書に現れる ID だけを行にします。 |
| `match` |  | node と edge を交互に並べた配列 |  | クエリの行。解が行になります。 |
| `also` |  | {from, steps} |  | クエリの分岐。 |
| `where` |  | 条件の配列 |  | 束縛したノードの値で絞ります。受け取る値を $名前 で書けます。 |
| `rowKey` |  | 束縛の名前 | 先頭の bind | クエリの行の、行の鍵にするノード。 |
| `limit` |  | 整数 | 100 | クエリの行の上限。 |
| `value` |  | 値の式 |  | クロス集計だけ。起点の要素ごとに計算した値を行にし、同じ値を 1 行にまとめます。idPattern とは一緒に書けません。 |
| `unclassified` |  | 見出しの文字列か false | 列の unclassified と同じ | クロス集計で value と一緒に書きます。値が得られない要素をまとめた行の見出しです。false で出しません。 |

## 並び（order）

グリッドとクロス集計の order。条件に合う出現の文書の中の順か、path の値の順で並べます。

- メタスキーマ: builtin-schemas/view.yaml の root.order
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseOrderValue

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaId` |  | スキーマ ID |  | 出現のスキーマ。 |
| `fileGlob` |  | glob の文字列 |  | 出現のファイル。 |
| `path` |  | パスの文字列 | 出現の文書の中の順 | 並びの基準にする値の場所。 |
| `type` |  | number か date | 文字の順 | 値の比べ方。 |
| `direction` |  | asc か desc | asc | 向き。desc は大きい順・新しい順です。値の無い行は、どちらでも後ろです。 |
| `series` |  | 系列の名前 | 最初の系列 | チャートだけ。この系列の値の順に項目を並べます。 |

## 列（columns の要素）

グリッドの列 1 つ。path・backlinks・definition・value・each のどれか 1 つで、列の中身を決めます。

- メタスキーマ: builtin-schemas/view.yaml の root.columns.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` |  | 文字列 |  | 列の見出し。path・backlinks・value の列では必須です。definition・each の列には書きません。 |
| `schemaId` |  | スキーマ ID |  | 値を読む出現のスキーマ。 |
| `fileGlob` |  | glob の文字列 |  | 値を読む出現のファイル。 |
| `path` |  | パスの文字列 |  | 出現の要素から読む値の場所（例 name）。 |
| `definition` |  | 定義の名前（モジュールID#名前） |  | 名前付きの定義の項目を、列の集まりに広げます。見出しと場所は定義から決まります。 |
| `of` |  | 束縛の名前 | 行の鍵 | クエリの行だけ。この列の値を取るノード。 |
| `editable` |  | 真偽値 |  | 一覧の上で編集できる列にします。 |
| `display` |  | grouped |  | 桁区切りで見せます。画面の見せ方だけです。 |
| `format` |  | DecimalFormat か DateTimeFormatter のパターン |  | 画面で見せる形。値は変えません。 |
| `each` |  | {idPattern, schemaIds, order, bind} |  | idPattern に合う要素ごとに列を作ります（見出しは要素のタイトル）。value の式の中で、列の要素の ID を $bind の名前で指します。 |
| `backlinks` |  | {relation, schemaIds} |  | 行の ID への参照元を並べる列にします。path・editable とは一緒に書けません。 |
| `value` |  | 値の式 |  | 列の値を式で決める、読み取り専用の列。path・backlinks・definition・editable とは一緒に書けません。 |

## 要素ごとの列（each）

列の each。

- メタスキーマ: builtin-schemas/view.yaml の root.columns.item.each
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `idPattern` | 必須 | glob の文字列 |  | 列にする要素の ID の形。 |
| `schemaIds` |  | スキーマ ID の配列 |  | 列にする要素が現れるスキーマ。 |
| `order` |  | {schemaId, fileGlob} |  | 列の並びの基準にする出現。 |
| `bind` | 必須 | 名前 |  | value の式の中で、列の要素の ID を指す名前。 |

## 参照元の列（backlinks）

列の backlinks。

- メタスキーマ: builtin-schemas/view.yaml の root.columns.item.backlinks
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `relation` |  | 関係の名前 | すべての参照元 | この関係の参照元だけを並べます。 |
| `schemaIds` |  | スキーマ ID の配列 | 限りません | 参照元の文書のスキーマを限ります。 |

## 詳細の文書（detail）

グリッドの detail。

- メタスキーマ: builtin-schemas/view.yaml の root.detail
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaId` |  | スキーマ ID |  | 出現のスキーマ。 |
| `fileGlob` |  | glob の文字列 |  | 出現のファイル。 |
| `filePattern` |  | 値の式（{パス} を含む文字列） |  | 「作成」の作り先。穴は行の要素の値で埋めます（{id} は行の ID）。 |

## 新しい行の入口（newRow）

グリッドの newRow。file と path の組か、filePattern のどちらかで書きます。

- メタスキーマ: builtin-schemas/view.yaml の root.newRow
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `file` |  | ファイルの場所 |  | 配列へ足す形。path と組で書き、一覧の文書の中の配列へ新しい要素を足します。 |
| `path` |  | パスの文字列 |  | 要素を足す配列の場所。 |
| `filePattern` |  | {id} を含む文字列 |  | 文書を作る形。入力した ID で {id} を埋めた場所に、詳細の文書を作ります。 |

## 補正を重ねる（corrections）

グリッドの corrections。

- メタスキーマ: builtin-schemas/view.yaml の root.corrections
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseGridView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `schemaIds` | 必須 | スキーマ ID の配列 |  | 補正の文書のスキーマ。 |
| `target` |  | 項目のキー | target | 補正の要素の中の、当たり先（行の要素）を指す参照の項目。 |
| `set` |  | 項目のキー | set | 補正の要素の中の、置き換える値を書いた object の項目（キーは列の path）。 |

## クロス集計（kind: matrix）

行と列の軸で要素を数える・集計する表。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseMatrixView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `rows` |  | {idPattern, schemaIds} か {value, unclassified} |  | 行の軸。cells.series を書かないときは必須です。 |
| `cells` |  | {match, where, also, of, in, path, aggregate, type, series, columns} |  | セルに集めるもの。必ず書きます。 |
| `order` |  | {schemaId, fileGlob, path, type, direction} |  | 行の並び。 |
| `params` |  | 名前の配列 |  | 受け取る値の名前。cells.where に $名前 で書きます。 |
| `totals` |  | 真偽値 | true | 合計の行と列を出します。 |
| `cumulative` |  | 真偽値 |  | 行の順に、各列のセルをそれまでの行と合わせて畳みます（件数なら足し上げ）。行が値の軸のときだけ書けます。合計の行は出しません。 |
| `unclassified` |  | 見出しの文字列か false | （未分類） | 列の軸に当たらない要素の列の見出し。false で出しません。 |
| `display` |  | grouped |  | 桁区切りで見せます。画面の見せ方だけです。 |

## セルの集計（cells）

クロス集計の cells。行の要素を起点に、数える要素を集めます。

- メタスキーマ: builtin-schemas/view.yaml の root.cells
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseMatrixView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `match` |  | node と edge を交互に並べた配列 |  | 行の要素を起点に、セルに数える要素までたどるクエリ。 |
| `where` |  | 条件の配列 |  | 数える要素を絞る条件。受け取る値を $名前 で書けます。 |
| `also` |  | {from, steps} |  | クエリの分岐。 |
| `of` | 必須 | 束縛の名前 |  | セルに数える要素。 |
| `in` |  | {schemaId, fileGlob} |  | 値を読む文書。 |
| `path` |  | パスの文字列 |  | 集計する値の場所。sum・min・max のときは必須です。 |
| `aggregate` |  | count・sum・min・max のどれか | count | 集計の種類。 |
| `type` |  | number か date | number | min・max の比べ方。 |
| `series` |  | {label, key} の配列 |  | 列ごとの行の鍵。列ごとに、数える要素をどの行に数えるかを式で決めます。columns・rows・order・unclassified とは一緒に書けません。 |
| `columns` |  | {via, idPattern, schemaIds, order} か {path, values} か {value, label, order} |  | 列の軸。要素の参照先（via）・要素の値（path）・式で計算した値（value）のどれかです。 |

## 列ごとの行の鍵（series の要素）

cells の series の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.cells.series.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseSeriesKey

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` | 必須 | 文字列 |  | 列の見出し。 |
| `key` | 必須 | 値の式 |  | 要素ごとに計算した値の行に数えます。得られなければ、その列に数えません。 |

## 列の軸（cells の columns）

cells の columns。

- メタスキーマ: builtin-schemas/view.yaml の root.cells.columns
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseMatrixKey

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `value` |  | 値の式 |  | 要素ごとに計算した値で列を広げます。列は、データに現れた値を順序の種類で並べます。 |
| `label` |  | 値の式 | 値の文字 | value の列の見出しを計算する式。 |
| `via` |  | 参照の項目 |  | 要素の参照先を列にします。 |
| `idPattern` |  | glob の文字列 |  | via の列にする ID の形。 |
| `schemaIds` |  | スキーマ ID の配列 |  | via の列にする要素が現れるスキーマ。 |
| `order` |  | {schemaId, fileGlob} か、値の配列 |  | 列の並び。via では並びの基準の出現、value では先に並べる値です。 |
| `path` |  | パスの文字列 |  | 要素の値を列にします。 |
| `values` |  | 値か {value, label} の配列 |  | path の列の並びと見出し。 |

## 文書ビュー（kind: document）

1 つの文書を、区画に分けて見せるビュー。

- メタスキーマ: builtin-schemas/view.yaml の root
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `match` |  | glob の文字列 |  | このビューを当てる文書。schemaId と両方書くと、両方に合う文書です。 |
| `schemaId` |  | スキーマ ID |  | このスキーマの文書に当てます。 |
| `sections` |  | 区画の配列 |  | 区画。必ず書きます。1 つの区画は fields・backlinks・grid（と、あとから足せる種類の区画）のどれか 1 つです。 |
| `attachments` |  | {label, at, backlinks} の配列 |  | at の形に合う要素のフォームの中に、その要素の ID への参照元の表を差し込みます。 |

## 区画（sections の要素）

文書ビューとダッシュボードの sections の要素。文書ビューでは label と、fields・backlinks・grid のどれかを書きます。ダッシュボードでは id・label・group・view・filter を書きます。

- メタスキーマ: builtin-schemas/view.yaml の root.sections.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocView・parseDashboard

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` |  | 文字列 |  | ダッシュボードの区画の ID。業務の流れの段として指します。 |
| `group` |  | 文字列 | 名前の無い群として先頭に並びます | ダッシュボードで、同じ名前の区画を見出し付きでまとめます。 |
| `view` |  | ビュー ID |  | ダッシュボードの区画で対象を並べる、グリッドかクロス集計のビュー。 |
| `filter` |  | 検査（match と、expect・count・check・forAll のどれか） |  | ダッシュボードの区画に出す行の条件。行の要素を起点に検査を評価し、満たした行を出します。$today は基準日です。 |
| `label` |  | 文字列 |  | 区画の見出し。 |
| `fields` |  | {path, in, definition, include, exclude} |  | 文書ビューで、スキーマのフォームを区画に出します。backlinks・grid とは一緒に書けません。 |
| `backlinks` |  | {idPattern, relation, schemaIds, columns} |  | 文書ビューで、文書の中の ID への参照元を並べます。 |
| `grid` |  | {view, params, columns} |  | 文書ビューで、グリッドかクロス集計のビューを区画に置きます。 |

## フォームの区画（fields）

区画の fields。

- メタスキーマ: builtin-schemas/view.yaml の root.sections.item.fields
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `path` |  | パスの文字列 | ルート全体 | フォームにする場所（文書のルートからの相対パス）。in とは一緒に書けません。 |
| `in` |  | {schemaId, fileGlob} |  | 開いた文書のルートの ID について、同じ ID の別の出現を選び、その要素のフォームを出します（編集はその文書へ入ります）。 |
| `definition` |  | 定義の名前 |  | この区画が参照する名前付きの定義。場所の項目が、実際にこの定義を参照しているかを点検します。 |
| `include` |  | キーの配列 |  | 並べたキーだけを、この順で出します。exclude とは一緒に書けません。 |
| `exclude` |  | キーの配列 |  | 並べたキーを外します。 |

## 表の区画（grid）

区画の grid。

- メタスキーマ: builtin-schemas/view.yaml の root.sections.item.grid
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `view` | 必須 | ビュー ID |  | 置くグリッドかクロス集計のビュー。 |
| `params` |  | 名前 → {path} のマップ |  | 表の受け取る値に、開いた文書の中の値を渡します。値が無ければ区画は出ません。 |
| `columns` |  | 見出しの配列 | 表のすべての列 | 見せる列を、この順で並べます。クロス集計の表には書けません。 |

## 参照元の一覧（backlinks）

区画と attachments の backlinks。

- メタスキーマ: builtin-schemas/view.yaml の definitions.docBacklinks
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocBacklinks

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `idPattern` |  | glob の文字列 | すべての ID | 文書の中の、この形に合う ID だけを対象にします。 |
| `relation` |  | 関係の名前 | すべての参照元 | この関係の参照元だけを出します。 |
| `schemaIds` |  | スキーマ ID の配列 | 限りません | 参照元の文書のスキーマを限ります。 |
| `columns` |  | {label, path} の配列 | ID とタイトルの要約だけ | 参照元の要素から出す値の列。 |

## 差し込み（attachments の要素）

文書ビューの attachments の要素。

- メタスキーマ: builtin-schemas/view.yaml の root.attachments.item
- 読む実装: workspace-core/workspace/ViewDefinitions.kt の parseDocView

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `label` |  | 文字列 |  | 見出し。 |
| `at` | 必須 | パスの形（[*] を書けます） |  | 差し込む先の要素（例 actors[*]）。 |
| `backlinks` | 必須 | {idPattern, relation, schemaIds, columns} |  | 差し込む参照元の条件。 |

