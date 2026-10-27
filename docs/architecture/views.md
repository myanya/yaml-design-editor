# ビュー

業務の状況・一覧・詳細・操作を合成する画面は [ダッシュボード](dashboards.md) を参照。

ビュー（`_views`）はデータを持たない射影。セルは原本の座標（文書ID, YPath）に解決され、編集はその座標の原本の木へルーティングされる。`kind: grid` は行（論理 ID または束縛行）× 列（相対 YPath）の横断的な編集面、`kind: document` は文書単位の表示合成、`kind: chart` は元の表（grid / matrix）の数を図にする部品。
ビュー定義の型はカーネルの型で、読み取りとリントは workspace-core にある。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `VirtualGridDef` / `VirtualDocViewDef` / `VirtualViewDefinition`（root の `ViewDef` / `DocViewDef` は別名） | semantic-core/semantic/VirtualViews.kt | ビュー定義の宣言の型と解決（仮想文書・テキスト表現） |
| `VirtualFlowDef` / `FlowViews` / `FlowResolution` | semantic-core/semantic/FlowViews.kt | Constructによる文書の射影、原本対応、表示契約と接続先の診断 |
| `VirtualLensDef` / `VirtualLensRelation` / `LensArrow` | semantic-core/semantic/LensViews.kt | レンズの見え方の定義（`kind: lens`＝関係ごとの矢印の向き・表示だけ・消費は `Views.LoadResult.reversedRelations` → `LensGraphs`） |
| `VirtualChartDef` / `ChartKind` / `ChartViews` / `ChartData` | semantic-core/semantic/ChartViews.kt | チャート（`kind: chart`）の宣言と解決（元の表の解決結果を項目 × 系列の数に。`report` / `renderText` で CLI・read_view へ） |
| `ChartCard` / `ChartGridView` / `EmbeddedChartPane` | ui/ChartViewPane.kt | チャートの描画（台帳のビュータブ・文書ビューの区画・ダッシュボードの区画で共用）。図の部品は `ui/Charts.kt`（Vico を包む `BarChart` / `LineChart`〔面・積み上げ面も〕・自前の `PieChart` / `HorizontalBarChart`） |
| フロー表示契約 | semantic-core資源 `builtin-structures/flow-model.yaml` | 表示モデルの型・必須項目・形状をStructureValidatorで検査 |
| `FlowReports` | core/FlowReports.kt | CLI・read_viewへ同じ表示モデルと座標・診断を開示 |
| `GraphLayouts.arrangeFlow` / `FlowSelection` | ui/GraphLayouts.kt・semantic-core/semantic/FlowSelection.kt | 計測寸法からの決定的配置と文書・版を考慮した選択の再解決 |
| `FlowViewPane` | ui/FlowViewPane.kt | 単独表示・文書埋め込み・Canvas描画と既存の原本フォームへの委譲 |
| `ViewDefinitions` / `ViewCatalog` | workspace-core/workspace/ViewDefinitions.kt | 読み取り（`parseView`＝definition 参照の列展開を含む）とリント（`lint` / `lintDocument`＝ビュー → スキーマの整合・`staticFieldAt` / `idElements`） |
| `Views` | core/Views.kt | root の窓口（views ゾーンのファイルを読んで渡す `load`・行 × セルの解決 `resolve`・文書ビューの解決 `resolveDocument` / `resolveAttachments`・`renderText` / `renderDocText`・`fieldAt`＝`SchemaDef.fieldAt` へ委譲） |
| `ViewRow`（＝`VirtualRow`）/ `ViewCell` | core/Views.kt | 解決の結果（別名） |
| `ViewGridPane` | ui/ViewGridPane.kt | ビューグリッド。セル編集は文書キャッシュの木の直接編集、行アクションで詳細文書の作成・新規行 |
| `DocViewSections` / `AttachedBacklinks` | ui/FormView.kt | 文書ビューの描画（fields セクションは既存フォームへの委譲・backlinks と attachments は読み取り専用の表） |
| `DocumentSession.createViewDetail` / `addViewRowFile` / `viewRowTarget`、`AppState.addViewRow` | session/DocumentSession.kt・app/AppState.kt | 詳細文書の作成・新規行（配列モード／ファイルモード）。作った文書を開くのは `AppState` |
| `Exporter`（`source.view`） | skill/Exporter.kt | ビューで見えている表をそのまま帳票へ（グリッドのみ） |

## ビュー機構

- **ビューはデータを持たない**: 独自の永続化を一切持たない。セルは必ず原本上の座標に解決され、編集はその座標の原本の木へルーティングされる。
- **編集経路は既存と同一**: UI のセル編集は文書キャッシュ（`AppState.ensureDoc`）の木を `Slot` で直接変更し `markEdited()` する（dirty・Undo・保存・外部変更検出がそのまま効く）。エージェントは `read_view` で座標を得て既存の `apply_patch` で編集する。ビュー専用の書込経路を作らない。
- **行は索引基準・値は編集中優先**: 行の集合と座標は注入された索引から解決し、セル値の表示は loader 注入で「編集中の木（UI: docCache / エージェント: ステージング）→ ディスク」の順に読む。UI はオーバーレイ索引を渡すため未保存の新規 ID も行に現れる。ヘッドレス経路はディスク索引のまま。
- **`_views` は予約ディレクトリ**: 書込はメタアクティビティのみ、自動実行の監視対象外、ステージされた定義はパース可否が提出ゲートに加わる。
- **セルエディタの型解決**: 列の相対パスから `Views.fieldAt` でフィールド定義を辿る（`resolve` / `effectiveFields` を通す）。直接編集はスカラー系フィールドに限定し、複合値はジャンプで個別フォームへ。
- **バックリンク列は編集不可の派生表示**: `backlinks` 列は索引の被参照を relation・参照元スキーマで絞って解決し、出現の選択や `editable` とは排他（パースで拒否）。参照元要素の ID は「参照位置をパスの接頭辞に含む最も長いパスの出現」。セルが指す座標は参照値の位置＝ジャンプ先。
- **値の式の列**（`value`）は読み取り専用の派生表示（計算セル）で、行の出現の要素を「いまの要素」として値の式を評価する。座標は値の出どころ、来歴は材料、得られなければ値なしで理由を来歴に残す（[transform.md「計算列と決定論的射影」](transform.md)）。集合から値へ落とす `all` には、行を 1 つの解と見なした解の一覧と、行の要素を起点に固定した照合（環境の `solveFrom`＝`SemanticQuery.matchesFrom`）を渡す。値が配列ならセルの値は配列の項。
- **クロス集計は列を広げたグリッド**（`kind: matrix`。`VirtualGridDef.matrix`＝`VirtualMatrixDef`）: 列の軸（`VirtualMatrixKey`＝参照先の ID か決まった値）を索引から並べ、列ごとに「行から集めた要素をその列に絞って畳む」値の式を組み立てる（`VirtualViews.lower`。参照先は照合に `edge`＋`node` を足し、値は `where` の `equals`、未分類は `exists: false` と `oneOf` の否定、合計の列は絞らない）。
  評価は値の式の列と同じで、CLI・書き出し・`read_view`・桁区切り・並びは何も知らなくてよい。合計の行はセルの値を `SemanticExpression.aggregate` で畳んで足す。
  ヘッダを描く側は `Views.effective`（同じ索引で `lower`）を使う。セルの集計の宣言は `all` の中身を書かれたまま（raw）持つ。
- **軸を値の式で広げるクロス集計**（`rows.value`＝`VirtualMatrixDef.rowsValue`・`cells.columns.value`＝`VirtualMatrixKey.Expr`）は `lower` では列が決まらない（値を読むには原本が要る）ので、`VirtualViews.resolvePivot` が解決のときに軸を決める: 起点（行が値の軸なら照合の最初の node に合う要素すべて・ID の軸なら rows.idPattern の ID）ごとに cells の照合（`all` の形。path を外して要素そのものを集める）を起点を固定して評価し、集めた要素を行の値（起点で計算）× 列の値（参照先・項目の値・要素で計算した式）で振り分けて `SemanticExpression.aggregate` で畳む。値の軸は順序の種類（`comparatorFor`）で並べる。
  値の軸の行は `VirtualRow.valueRow`（原本の出現を持たないが合計の行ではない。ダッシュボードの件数に数える）。ヘッダと書き出しの見出しは `VirtualViews.columnsOf(view, rows)`（解決した行の列。ふつうのビューでは lower の列と同じ）から取る。
- **チャート**（`kind: chart`＝`VirtualChartDef`）は元の表（grid / matrix）の解決結果を受け取る純粋な変換（`ChartViews.resolve`）: 行＝横軸の項目（クロス集計の合計の行は除く）、系列＝`series` の列か数として読める列すべて〔散布図は `x` の数の列を項目ごとの横軸の値 `xValues` にして系列から除き、名前は `label` の列〕（合計・未分類の列は除く）、値は `SemanticExpression.numberOf`、並びは元の表の行の順か `order`（系列の値の順。項目と全系列と材料を同じ置換で並べ替える）。データも評価器も持たず、描画は画面（`ChartCard`）だけで、CLI `view` / `read_view` は `ChartViews.report` / `renderText` の数を返す。
  形を押したときの材料は `Views.chartSelection`: 対応するセルの来歴の入力（集めた要素の値と場所）に、その場所を含む最も内側の ID を索引から添える（表のセルの来歴メニューと同じ材料）。押した位置から項目と系列を決めるのは `ui/Charts.kt`（Vico のマーカーの対象を `CartesianMarkerVisibilityListener` で覚え、消費しない押下 `tapWithoutConsuming` で読む。積み上げ棒は高さで系列・円は角度）。一覧の置き方は `VirtualChartDef.drill`（popup / below / right / none）で、`ChartCard` が組む。置き場は台帳のビュータブ（`ViewGridView` がチャートの ID なら `ChartGridView`）・文書ビューの区画（`VirtualDocSection.chartView`）・ダッシュボードの区画（`DashboardSectionResult.chart`）。
- **要素ごとに列を広げる**（columns の `each`＝`VirtualEachDef`）は、クロス集計と同じ `VirtualViews.lower` で索引の要素ごとの列に広がる。値の式は書かれたまま（raw）を持ち、列ごとに `$bind` の文字をその要素の ID に置き換えて（`substituteColumnRef`）値の式にする。
  置き換え前の式でも `$cat` は決まった ID の文字として読めるので、定義の文書の検証（メタスキーマの式の点検）とリントはそのまま通る（`{same: …}` は「前のステップの束縛」の点検に引っかかるので使わない）。列の集まりの並べ方（`entitiesOf`）はクロス集計と共有。
- **セルの併記は画面だけ**（`ViewGridPane`）: 編集できない列の enum は `FieldDef.optionFor` のラベル、参照は索引のタイトルを「値（ラベル）」「ID（名前）」で添える。編集できる参照のセルは入力欄の下に名前を出す。セルの値・CLI・書き出し・`read_view` は変えない（`read_view` は元から「ID（タイトル）」を注記する）。
- **行の並びは 3 段**（`VirtualViews.sortRows`）: `order.path`（`VirtualOrderValue`）があれば並び基準の出現から読んだ値の順、無ければ `order` の出現の文書内順、どちらも無ければ ID 順。値の読み出しは `resolve` の木の読み込みを共有する。
  値の順の種類は `type` の宣言か、読む項目の型（`resolve` に渡す `fieldAt` で引く）。数値は `SemanticExpression.numberOf`・日付などは `ValueSemantics.orderKey`・決まらなければ文字の順。読めない行は後ろ、同値は ID 順。
- **項目の型を知るのは呼び出し側**: `VirtualViews.resolve` の `fieldAt`（文書とパス → 解決済みの項目）はアプリの `Views.resolve` が `schemaFor` から組み立てて渡す（CLI・エクスポート・エージェント・グリッドの全経路）。値の式の列の順序の種類（`SemanticCheckSide.kind`）と合計の行の畳み方（`VirtualCell.kind`）もこれで決まる。
- **桁区切りと表示の形は見せ方だけ**: 列の `display: grouped` / `format`（`VirtualViewColumn.display` / `format`）か、`path` の列の項目のヒント（`FieldHints.numberDisplay` / `format`）で、グリッドのセルの文字を `ValueFormat.display` に通す（値の式の列は値が数か日付として読めれば整形）。セルの値・`read_view`・CLI `view`・エクスポートは元のまま。編集できる列はエディタに同じヒントを渡す。
- **クエリ行**（`rows: match:`。`rows: idPattern` と排他）: 行＝クエリの束縛行（`limit` を書かなければ上限なし。書けば打ち切り、全行数は `ResolvedRows.total` で一覧・ダッシュボード・CLI に示す）。行キーは `rowKey:`（省略時は先頭の束縛。kind: entity 限定）。列の `of:` で結合先ノードの値をセル化でき、セルは各束縛ノードの原本座標へ解決される。バックリンク列・値の式の列も `of` の束縛 ID を対象にでき、値の式は行の束縛を式の `of` で読める。行の同一性は `ViewRow.key`（束縛値の組）。`order` は併用不可。
- **新規行の入口は 2 モード**: `newRow` は配列モード（`file` ＋ `path`: 一覧文書の配列へ要素追加＝未保存編集として積む）とファイルモード（`filePattern`: 詳細文書をスキャフォールドしてディスクへ書込＝索引更新で行が現れる）の排他。スキャフォールドは `detail.filePattern` の「作成」と共通（[schema.md「ひな型」](schema.md)）。`newRow.filePattern` に書ける穴は `{id}` だけ。ビューが宣言するのは「新しい原本がどこで生まれるか」だけ。

## 文書ビュー

- **スキーマ＝データ契約、ビュー＝画面の合成**。表示決定の優先順位は「①文書ビュー（合成）＞ ②スキーマの固有表示ヒント（`role` / `display` / `label`）＞ ③予約語規約」。画面合成のためのオプションをスキーマへ足さない。
- **マッチしない文書は従来のスキーマ駆動フォーム**（暗黙の既定ビュー）。適用は `match`（glob）と `schemaId` の AND、複数定義は定義ファイル名順の先勝ち。台帳の文書に限る。
- **fields セクションは既存フォームへの委譲**: ルート全体は従来のルート描画、部分表示（`path`）は `Views.fieldAt` ＋ `slotAt` ＋ `FieldRow`。`include` / `exclude` は描画対象の選別で、編集経路・実効フィールド合成は変えない。
- **backlinks セクションは編集不可の派生表示**: グリッドのバックリンク列と同じ解決規則。行＝文書内の ID（`idPattern` で絞る）。`columns` は参照元要素からの相対 YPath の値を表示専用で添える。
- **要素単位アタッチ（`attachments`）**: `at` パターン（YPath ＋ `[*]`）に一致する要素の展開フォーム内に、その要素の ID への被参照表を差し込む。解決結果を `FormCtx.attachments` で運び、`ObjectFields` の末尾で描画する（全描画経路に効く。専用の分岐を作らない）。
- **人間と AI が同じ面を見る**: `read_view`（`file` 引数）・CLI `view <id> [file]` が同じ解決（`resolveDocument` / `resolveAttachments` / `renderDocText`）を返す。エクスポートはグリッドのみ。

## フローの解決

`kind: flow`は文書の項目を、ノード・辺・任意のレーンへ射影する。GUI・CLI・read_viewは同じコア解決を使う。
`match`と`schemaId`をANDで適用し、`projection`は既存のTransformDefを読む。入力は呼び出し側が渡す1文書で、fromと外部paramsは認めない。

- 値の導出はSemanticTransformに委譲し、FlowResolutionに表示モデル・TransformOrigin・診断を分けて返す。
- 型・必須・列挙値は組込みの構造スキーマで検査し、重複key・図内の接続・レーン対応は意味側で解決する。
- 不完全な配列要素も出力位置で保持し、未知の形状はboxへ代替する。診断の位置は値の来歴、detailの来歴、定義の順で補う。
- 図内に接続先がなくても、単なる文字列からワークスペース内の不存在を断定しない。
  原本のref型と索引が裏付ける場合だけ、既存の参照解決で「表示対象外」と「未解決」を区別する。
- detailの編集適格性は、配列eachの通常マップ直下のcopy（パス`.`、of・inなし）と、そのeach同士のconcatに限定する。
  出力番号・原本位置・来歴を照合し、条件付き・規則経由などは原本座標があっても編集不可とする。
- editableは射影上の適格性を表し、利用者の権限や閲覧専用状態を解除するものではない。
- 解決は原本を書き換えず、図の派生データを保存しない。相対DocumentIdと、同じスナップショットの索引・原本loader・スキーマを渡す。

## フローの画面と配置

- GraphLayouts.arrangeFlowは計測寸法を受け取り、FlowGeometryの矩形と折れ線を返す。ELKの階層配置で閉路・自己ループ・条件ラベルをまとめて配置する。
  深さ優先の閉路処理で入力順の流れを保ち、循環する工程も複数段に置く。グループ表示は所属ノードを枠で包み、レーンなし表示は全体を一括配置する。
  接続不明の辺もラベル領域を残し、菱形への接続は輪郭まで延ばす。乱数種を固定し、選択は配置の入力に含めない。
- スイムレーン表示はFlowSwimlanesで、ELKの段を保ちつつ定義順の帯へ配置する。下向きでは縦帯、右向きでは横帯とし、全レーンの開始・終了位置を揃える。
  空レーンも残し、未割当ノードは帯の外へ置く。条件ラベルは段間に、長い辺・戻り線は工程を避けてレーン境界に配置する。
- FlowDisplayOptionsはAppStateがビューID・文書IDごとに保持する。レイアウトと方向の変更は再配置と全体表示を行い、選択や原本を変更しない。
  台帳と文書埋め込みで共有し、画面の往復でも維持する。ワークスペース切替時に解除し、ファイルへの保存やUndo履歴には含めない。
- FlowViewPaneは台帳のフロータブと文書内埋め込みで共用。配置計算はUIスレッド外で行い、初期表示とサイズ変更時は全体を画面に収める。
  Canvasのパン・ズームと「全体」操作は画面内の状態であり、原本へ保存しない。
- Constructによる表示モデルと来歴は変更せず、配置だけをUI層で処理する。新しい評価語彙・書込経路は増やさない。
- 原本と定義の読取は同じ未保存優先loader、索引はoverlayIndexを使う。再解決はdoc.version・indexVersion・editVersionを購読する。
- detailは編集用の木ではない。適格な単一原本座標をensureDoc・slotAt・FormCtx・FieldRowへ渡し、通常のUndo・保存・閲覧専用を適用する。
- 選択はビューID・文書ID・種別・keyで現在のモデルへ再解決する。keyなしの辺だけ出力座標と原本・定義の版を使い、どちらかの変更で解除する。
  OpenDocumentの生成番号も版に含め、文書が再生成されても誤復元しない。NavLocationは図の対象文書と選択を保持する。
- sections[].flow.viewは一意なflowビューを参照する。静的リントは存在・種別を確認し、globの包含は判定しない。
  適用条件は現在の文書に対する通常のフロー解決で診断する。CLI・read_viewも同じ文書を渡す。

## definition 参照とリント

- **named definition がビューの一級語彙**: ビューからスキーマ構造を指す単位は named definition（`module#name`。非修飾名は `schemaId` から補完）。グリッド列の `definition:` は定義のセル型フィールド（`Display.tableColumns` と同じ規則。ID 型は除外）を列へ展開し、ラベル・パスをスキーマから導出する。展開はパース時で、展開後は通常の列と同一。
- **リントは load 時の助言**: `Views.load(workspace, schemas)` はビュー → スキーマの参照（schemaId・パス・definition・`newRow.filePattern` の作成先とスキーマの有無・`detail` 認定条件）の整合を検査し、指摘を errors として返す。ビューは無効化しない。
- パスの検査は「スキーマの ID 要素のいずれかから静的に解決できるか」（`staticFieldAt`）。ID 要素が見つからないスキーマは判定しない（誤検知で作者を煩わせないことを優先）。`fileGlob` だけで出現を選ぶ列も、glob の代表パスにマッチするスキーマで検査する。
- **definition の存在はパースで拒否**: 未知の definition への参照はパースエラーで、提出ゲートでも拒否される。本番経路はすべて schemas を渡す。
- 指摘はグリッドのバナー・`read_view`・CLI `view` に出る。`_views` の定義文書を開いている間は検証パネルにも出て、編集中の木で判定するため未保存でも追随する（`lintDocument`）。

## ビュー定義の読み取り

- 定義文書の座標（`source`）はワークスペース相対の文書ID。読み取り（`parseView`＝グリッド・文書ビュー・definition 参照の列展開）とリントは workspace-core（作り先に合うスキーマを選ぶ `WorkspaceSchema.matches` を使うため）。
- `filePattern` と列の `value` は値の式（[schema.md「値の式」](schema.md)）で、共通の読み取り `ExpressionFields` を通る（`viewSchemaTree`）。`value` は `path` / `backlinks` / `definition` / `editable` と排他。古い `computed` は定義エラー（同じ意味の `value` を案内）。
- 値の式の列のリントは、式が読む項目（穴・読み出し・集計のパス）が出現のスキーマの ID 要素から解決できるか、`via` が参照型の項目か、`in` のスキーマがあるか、相手から読む `path` が相手のスキーマから解決できるか、集計の順序の種類と項目の型（date）が食い違っていないかを見る。
- `[*]` を含むパス（アタッチ先・集計のパス）の読み取りは `PathPattern`。`-1` への置き換えを機能側に書かない。
- リファクタリングのビュー定義の追随（フィールド改名）と言及の警告は、カーネルの `RefactoringImpact` が作り `planRefactoring` が計画に含める（[workspace.md「リファクタリングの影響」](workspace.md)）。
- `flow.view`は組込みスキーマのref型で、ビューIDの改名・被参照の削除拒否は既存の意味的リファクタリングを使う。
- projectionの相対パス・規則・固定値は自動改名せず、`RefactoringImpact`がキーと値の言及を場所付きで計画へ開示する。
- 原本文書の移動でflowのmatchから外れる場合も計画で警告する。
- レンズの定義（`kind: lens`）が持つのは見え方（関係ごとの矢印の向き）だけ。節点の選択（型・関係の絞り込み・起点・畳み）は画面の操作（`ScreenMemory`）のままで、定義に持たせない。
  読み取りは `parseView` の 1 経路、リントは関係レジストリがあるときだけ関係名の宣言を見る。関係の改名は言及を警告で開示する（`flowDefinitionWarnings`）。消費はレンズだけ（[graph-query.md](graph-query.md)「スキーマレンズ（型の段）」）。
- スキーマと関係レジストリに見え方を足さない。レンズの見え方の要望（矢印の向き・色・既定の配置）は `kind: lens` の語彙で受ける。

## 規律

- ビュー専用の書込経路を作らない。UI は docCache の木の直接編集、エージェントは `read_view` → `apply_patch`。
- ビュー定義の型はカーネルの型（`semantic/VirtualViews.kt`）で、root の型はその別名。root にビュー定義の型や読み取りを書き足さない。
- 定義項目の追加は、宣言の型・`ViewDefinitions.parseView`・メタスキーマ `builtin-view`（`builtin-schemas/view.yaml`）を対で更新する。
- 画面合成のためのオプションをスキーマ（`FieldDef`）へ足さない。合成の要望はビュー側の語彙で受ける。
- リファクタリングの計画をアプリ側で補強しない（入口によって計画が食い違う）。
- チャートに集計を書かない（集計は元の表の仕事。チャートは見せ方だけ）。図の部品（Vico）の型は `ui/Charts.kt` の外へ出さない。
- 受け取る値（`params`）は解決のときに差し込む（`VirtualViews.bindParams`＝クエリ行の原文 `rowsRaw` とクロス集計の `cells.all` の `$名前`）。値の無い名前の条件は `ActionRequire.prune` で外す（空＝絞らない）。定義の読み取りで `$名前` が `params` に無ければ誤り。
- 列がデータから決まるビュー（軸を値の式で広げたクロス集計）のヘッダは `view.columns` でなく `columnsOf(view, rows)` から取る。

## 拡張ポイント

- **ビュー定義の項目追加**: 宣言の型・`parseView`・`builtin-view` を対で更新する。
- **図の種類の追加**: `ChartKind` に種類を足し、`ChartCard` の分岐と `ui/Charts.kt` の部品を足す（宣言・解決・CLI は変わらない。横軸の項目 × 系列の数という形に収まる図だけ。散布図は項目ごとの横軸の値 `xValues` を添える）。積み上げ面のように積んだ値で描く図は、積むのを部品の中で行い、解決の数は元の値のまま。横棒のように Vico に無い図は自前で描く（円と同じ）。
- **横断ビューの追加**: データソースとして `AppState.workspaceRecords()` と `IdIndex` を使い、`ui/Shell.kt` の `LedgerSpace` に足す。1 行要約は `Display` を使う。
- **セルの型の追加**: `Views.fieldAt` の解決と `ViewGridPane` のセルエディタに足し、書込は `Slot` ＋ `markEdited()` のまま。
