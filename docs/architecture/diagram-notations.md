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
| `DiagramInputs` | app-core/core/DiagramInputs.kt | 既存のエンティティ合成から値と来歴を渡す |
| `DiagramBodies` / `ImageAssets` | desktop/ui/common/ | 共通の計測・描画・画像読込 |
| `DiagramEdges` | desktop/ui/common/DiagramEdges.kt | 線種・端点記号・中央と両端のラベルの計測描画 |
| `DiagramPng` / `FlowDrawing` / `GraphLensDrawing` | desktop/ui/common/・ui/view/・ui/graph/ | 共通の画像出力境界と画面・PNGで共有する描画 |
| `FileLocations` / `FilePolicy.outsideLevel` | app-core/core/・workspace-core/layout/ | file型と画像の場所・許可の共通境界 |

## 流れ

既存のグラフやフローから渡された値を、適用指定の `inputs` で記法の入力へ変換する。
記法の `input` は最小の構造スキーマであり、画像の場所も構造上はstringで受け取る。
原本のfile型の意味・存在・許可は既存のファイル規則が受け持つ。
入力の検査後、記法の `build` と `rules` で表示モデルを作り、同梱の表示契約で検査する。
`DiagramInputPlace` は合成した入力のパスを最長一致の来歴で原本へ戻す。
`SemanticTransform.runAt` は原本座標を持つ入力を受け取る共通の入口で、通常の変換と評価器を共有する。

レンズは外観を持つ定義を一つ選び、選択はScreenMemoryが持つ。
外観のない従来のレンズ定義は矢印指定の和として適用する。
実体の入力はAspectsの所有項目、出現の入力は原本の部分木から作る。
所有が重複した項目は診断して合成入力から外す。
フローは射影後のノードへ記法を適用し、ViewContextからカタログを受け取る。
フロー固有の入力の対応付けはFlowViewsが持ち、app.coreへ依存しない。

計測した部品の寸法をGraphLayoutsへ渡し、同じ計測結果を描画する。
レンズのPNGも同じ描画を使う。画像は値の来歴の文書からFileLocationsで解決する。
ImageAssetsはfile画面と図で共有し、画像の更新時刻の変化に追随する。

LensGraphsは表示する辺と別にDiagramLinkSourceを保持し、重複排除・畳みでも原本の参照を残す。
参照属性はScannedRef.attributesから読み、記法入力の論理的な端点は表示反転に影響されない。
描画の端点記号とラベルだけをLensGraphsのreversedに従って交換する。
来歴が複数の部品はlocationsに全件を残し、locationは一つに決まる場合だけ返す。
ノード内の文字・画像からの移動は既存のopenFileへ渡し、図専用の書込を作らない。

型の属性はSemanticSchemaGraphのSchemaGraphMemberから渡す。
共通項目とケースの属性を条件付きで列挙し、子エンティティの属性は子の型に閉じる。
これはスキーマの説明であり、データ文書の編集座標を作らない。
CLIのschema-graphも同じmembersを写す。

フローの報告は記法の表示モデルと来歴を含み、CLIとread_viewが共有する。
同梱・利用者の記法定義は読み込んだYAMLを保持し、同じ入口から開示する。
レンズのappearanceと記法のひな型は既存のRefactoringImpactで言及を点検する。
PNGは全ノード・経路・端のラベルの境界を取り、画面と同じ描画へ渡す。

## 規律

- 部品の入れ子と、グラフの保有を混同しない。
- 表示の変更は原本の意味・参照・検証を変えない。
- 入力不足、未知の部品、対象違い、複数の適用は理由を持つ診断にする。
- 組込みだけが使える部品を作らず、条件・値・繰り返しは既存の変換記法へ委譲する。
- 来歴の無い文字を、編集できる原本座標と見なさない。
