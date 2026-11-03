# 図の記法

グラフレンズとフローのノードを、YAMLで定義した枠・文字・画像で描けます。
記法の定義と適用指定はビュー領域（`_views/`、構成により `.yde/views/`）に置きます。
図は派生表示です。原本の編集・参照・検証は既存の仕組みを使います。

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

台帳のグラフレンズを開き、「記法: 標準」から「人物相関図」を選びます。
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

適用条件の未指定は全対象に当たります。同じ対象に複数の指定が当たる場合は診断します。
優先順位で上書きする方式ではありません。条件ごとの組み立ては一つの記法の中に書きます。
