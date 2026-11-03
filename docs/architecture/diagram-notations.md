# 図の記法

図の見え方はビュー領域の `kind: diagram-notation` で定義する。
記法は入力の形を `input`、表示モデルを組み立てるひな型を `build`、変換の規則を `rules` に持つ。
`target` は `node` または `edge`。組込みも利用者の定義も同じカタログと評価器を通る。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `VirtualDiagramNotationDef` / `DiagramAppearance` | semantic-core/semantic/DiagramNotations.kt | 記法と適用指定の型 |
| `DiagramNotations` / `DiagramInputPlace` | semantic-core/semantic/DiagramNotations.kt | Constructによる入力と出力の組み立て・契約検査・来歴の保持 |
| `DiagramDefinitions` | workspace-core/workspace/DiagramDefinitions.kt | 定義の読み取り・同梱記法・参照の点検 |

## 流れ

既存のグラフやフローから渡された値を、適用指定の `inputs` で記法の入力へ変換する。
記法の `input` は最小の構造スキーマであり、画像の場所も構造上はstringで受け取る。
原本のfile型の意味・存在・許可は既存のファイル規則が受け持つ。
入力の検査後、記法の `build` と `rules` で表示モデルを作り、同梱の表示契約で検査する。
`DiagramInputPlace` は合成した入力のパスを最長一致の来歴で原本へ戻す。
`SemanticTransform.runAt` は原本座標を持つ入力を受け取る共通の入口で、通常の変換と評価器を共有する。

## 規律

- 部品の入れ子と、グラフの保有を混同しない。
- 表示の変更は原本の意味・参照・検証を変えない。
- 入力不足、未知の部品、対象違い、複数の適用は理由を持つ診断にする。
- 組込みだけが使える部品を作らず、条件・値・繰り返しは既存の変換記法へ委譲する。
- 来歴の無い文字を、編集できる原本座標と見なさない。
