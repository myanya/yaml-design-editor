# 図の記法

グラフレンズとフローのノードや辺を、YAMLで定義した枠・文字・画像・端点記号で描けます。
記法の定義と適用指定はビュー領域（`_views/`、構成により `.yde/views/`）に置きます。
図は派生表示です。原本の編集・参照・検証は既存の仕組みを使います。

一式を試すには[人物相関図・クラス図・ER図のサンプル](../../samples/diagrams/README.md)をコピーして開きます。

## 人物カードを使う

同梱の `portrait-card` は、画像の下に名前を表示します。
次は、エンティティ型 `person` の `name` と `portrait` を使うレンズ定義です。

```yaml
id: people-lens
name: 人物相関図
kind: lens
appearance:
  nodes:
    - level: entity
      entityType: person
      notation: portrait-card
      inputs:
        title: {$copy: data.name}
        portrait: {$copy: data.portrait}
```

台帳のグラフレンズを開き、「見え方: 標準」から「人物相関図」を選びます。
「標準」で従来のIDとタイトルの表示に戻ります。選択はワークスペースを開いている間、保持します。
記法に誤りがある場合は標準のノードを残し、診断から定義や原本へ移動できます。

`data` は「一体」では既存のエンティティ合成が集めた所有項目です。
所有が重複した項目は診断し、任意の一方を採用しません。
「側面で分ける」では、その出現の原本の部分木です。
共通に `id`・`title`・`entityType` も使えます。

画像の場所はfile型と同じ書き方です。`./face.png` は画像の値を書いた原本文書から解決します。
原本の来歴を持たない画像の場所は記法定義が基準です。適用指定に定数を渡すときはワークスペース相対で書けます。
PNG・JPEG・SVGなどを読み、無い・読めない画像は代替アイコンにします。
外部ファイルの禁止はワークスペースの `files` 設定に従います。

## フローへ適用する

`kind: flow` の定義にも同じ `appearance.nodes` を書けます。
`inputs` からは射影したノードの項目を読み、`data` は `detail`、`title` は `label`、`id` は `key` と同じ値を指します。
分類が必要な場合は `projection` のノードに `entityType` を明示し、適用指定の `entityType` で選びます。
記法が変わっても、詳細欄の編集適格性や接続先の解決はフローの射影が決めます。

## クラス図を使う

`uml-class` はクラス名・属性・操作の区画を持ちます。
属性と操作は文字列の配列です。業務データの形に合わせて既存の変換ひな型で組み立てます。

```yaml
id: class-lens
name: クラス図
kind: lens
appearance:
  nodes:
    - level: entity
      entityType: class
      notation: uml-class
      inputs:
        title: {$copy: data.name}
        stereotype: {$copy: data.stereotype}
        attributes: {$copy: data.attributes}
        operations: {$copy: data.operations}
  edges:
    - relation: inherits
      notation: uml-generalization
      inputs: {}
    - relation: owns
      notation: uml-composition
      inputs:
        label: {$copy: label}
        sourceLabel: {$copy: data.sourceMultiplicity}
        targetLabel: {$copy: data.targetMultiplicity}
```

`$ref` は型の再利用であり、UMLの継承へ自動変換しません。
汎化などの意味は原本で関係として表し、記法を明示します。
区画の文字をクリックすると、来歴が一つに決まる場合は元の項目を開きます。
複数の原本から合成した文字は任意の一つを編集先に選びません。

| 同梱の辺記法 | 線と記号 |
|---|---|
| `plain-link` / `directed-link` | 実線／終点に矢印 |
| `uml-association` | 実線 |
| `uml-generalization` | 実線、終点に白三角 |
| `uml-realization` | 破線、終点に白三角 |
| `uml-dependency` | 破線、終点に矢印 |
| `uml-aggregation` / `uml-composition` | 始点に白／塗りつぶし菱形 |

UMLの辺は `label`（中央）・`sourceLabel`（始点側）・`targetLabel`（終点側）を受け取ります。
レンズの辺の入力は `relation`・`label`・`kind`・`source`・`target`・`references` です。
`source` と `target` は論理的な端点で、`ids` と一件の場合の `id` を持ちます。
参照一件の辺では、スキーマで定義した参照属性を `data` から読めます。
折り畳み等でまとめた辺は、各参照の `source`・`target`・`data` を `references` の配列から読みます。
表示を反転すると、論理的な始点・終点の記号と文字も一緒に入れ替わります。
フローの辺では射影した項目と `data`（`detail`）を使い、分類は `relation` に明示します。

独自の辺記法は `target: edge` とし、`build` に `stroke`（`solid` / `dashed` / `dotted`）、
`width`、`sourceMarker` / `targetMarker`、三つのラベル、`tone` を指定します。
端点記号は `none` / `arrow` / `triangle` / `diamond` / `filled-diamond` です。

## 記法を定義する

```yaml
id: name-card
name: 名前のカード
kind: diagram-notation
target: node
input:
  type: object
  fields:
    - {key: title, type: string, required: true}
build:
  kind: box
  shape: rounded
  width: 180
  align: center
  children:
    - {kind: icon, fallback: person, width: 48, height: 48}
    - {kind: text, text: {$copy: title}, maxLines: 3}
```

`input` は入力の構造契約です。型・必須・範囲などを検査します。
`inputs` と `build` は既存の変換ひな型を使い、値のコピー・条件・繰り返しを組み合わせられます。
同梱記法のIDと重ねることはできません。

| 部品・項目 | 用途 |
|---|---|
| `box` / `group` | 枠のある／ない入れ物 |
| `text` | `text` の文字を折り返して表示 |
| `image` / `icon` | `source` の画像／代替アイコン |
| `divider` | 区切り線 |
| `children` | 子部品の配列 |
| `layout` | `column`（縦）または `row`（横） |
| `align` | 並びと直交する方向の `start` / `center` / `end` |
| `width` / `height` | 図の座標での寸法（1〜2048、未指定は計測） |
| `padding` / `gap` | 入れ物の余白／子部品の間隔 |
| `maxLines` | 文字の最大行数（既定12、超える部分は省略） |
| `shape` | 枠の `box` / `rounded` / `diamond` |
| `fit` | 画像の `contain`（全体）/ `cover`（領域を満たす） |
| `fallback` | 画像がない場合の `person` / `image` |
| `tone` | `default` / `muted` / `accent` / `warning` / `error` |
| `port` / `portSide` | 部品の接続口の名前／接続する面（下記） |

適用条件の未指定は全対象に当たります。同じ対象に複数の指定が当たる場合は診断します。
優先順位で上書きする方式ではありません。条件ごとの組み立ては一つの記法の中に書きます。

## ポートで行へ接続する

ポートは、ノード内の表示部品に付ける接続口です。名前はノード内で一意にし、辺の `sourcePort`・`targetPort` で選びます。
flowとlensで同じ記法を使えます。カラムをエンティティにする必要はなく、論理的な参照先は従来どおりテーブルです。

例えばノード記法の `build` で、列の行を次のように組み立てます。

```yaml
kind: box
children:
  $each: columns
  $build:
    kind: group
    port: {$copy: name}
    portSide: auto
    children:
      - {kind: text, text: {$copy: name}}
```

対応する辺記法の `build` では、外部キーの列名を両端のポート名へ写します。

```yaml
stroke: solid
sourcePort: {$copy: column}
targetPort: {$copy: targetColumn}
```

`portSide` は `auto`（既定）・`left`・`right`・`top`・`bottom` です。
`auto` は相手の位置に応じて左右を選びます。左右なら部品の中心の高さ、上下なら中心の横位置で、外側のノードの輪郭へ接続します。
行の並べ替え・折り返し・ノードの移動後も、計測した同じ名前の部品へ接続します。接続点の座標は保存しません。

ポートを省略した端は箱全体へ接続します。空の名前・重複・存在しない名前は診断します。
表示範囲から切れた部品や、障害物で経路を確保できない線は描かず、箱の中央などへ代替しません。
ポートの検査は図の診断であり、DBのキー制約や型の整合性の検査ではありません。

lensではポートを使う参照の線を元の参照ごとに描き、同じテーブル間の複数の外部キーや自己参照を区別します。
矢印の反転ではポートも交換します。畳みによって端が祖先の箱へ移った場合、その端は祖先の枠に接続します。
ポートを使わない関係の線は従来どおりまとめます。

## 条件付き表示

値の条件は既存の `$when`、候補の切替は `$first`、配列は `$each` を使います。
例えば次の部品は、入力の `status` が `away` のときだけ出ます。

```yaml
- $when: {path: status, equals: away}
  $then: {kind: text, text: 留守, tone: warning}
```

構文は変換ひな型と共通です。式を持つ文字列として独自に解釈したり、記法の中で原本を書き換えたりしません。

## 入れ子の外部キーをフローへ写す

[ER図サンプル](../../samples/diagrams/README.md)のフローは、テーブルに入れ子で書いた外部キーを辺にします。
`$bind` は繰り返しの現在の要素に名前を付け、子の繰り返しから `$of` で読むための指定です。
`$flatten: true` は、各テーブルから作った辺の配列を一つにつなげます。

```yaml
edges:
  $each: tables
  $bind: table
  $flatten: true
  $build:
    $each: foreignKeys
    $build:
      from: {$copy: id, $of: table}
      to: {$copy: to}
      detail: {$copy: .}
```

どちらも汎用の変換ひな型の指定です。`$bind` はその繰り返しの中だけで有効で、同名なら内側を読みます。
`$eachEntry` にも名前を付けられますが、`$flatten` は配列を作る `$each` だけに書けます。
平坦化しない場合は従来どおり入れ子の配列になり、平坦化する場合に配列以外を作ると診断します。
辺には原本への来歴が残りますが、この入れ子の射影の詳細欄は直接編集の対象外です。テーブルの詳細欄から外部キーを編集します。

## 型の属性を表示する

レンズの「型」の段に使う適用指定は `level: type` です。
`data.attributes` は既存のスキーマグラフが導いた直下の属性で、
`key`・`label`・`type`・`required`・`requiredFrom`・`condition`・`schema`・`path` を持ちます。
複数のスキーマで現れる属性は出どころを残し、ケース固有の属性には条件が付きます。

```yaml
appearance:
  nodes:
    - level: type
      notation: uml-class
      inputs:
        title: {$copy: title}
        attributes:
          $each: data.attributes
          $build: {$format: '{key}: {type}'}
```

ここでの属性はスキーマから導いた説明です。データの編集座標とは扱わず、クリックでは従来のスキーマへの移動を使います。
YDEの型の操作をプログラムのメソッドへ自動変換しません。

## 出力と定義の読み取り

レンズとフローの「PNG に保存」は、画面と同じ計測・描画を使います。
画像・区画・端点記号・端のラベルも含め、長辺は最大8000ピクセルです。
保存ダイアログから出力先を選びます。

CLIの `view <workspace>` はビュー一覧と記法一覧を返します。
`view <workspace> uml-class` のように記法IDを指定すると、同梱記法を含むYAML定義を読めます。
エージェントの `read_view` も同じ記法を読めます。
フローの解決結果には `rendering.model` と `rendering.origins` が加わり、原本と表示の対応を確認できます。
CLIの `schema-graph` は型の `members` に属性の材料を返します。
