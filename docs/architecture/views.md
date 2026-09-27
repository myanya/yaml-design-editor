# ビュー

ビュー（`_views`）はデータを持たない射影。セルは原本の座標（文書ID, YPath）に解決され、編集はその座標の原本の木へルーティングされる。`kind: grid` は行（論理 ID または束縛行）× 列（相対 YPath）の横断的な編集面、`kind: document` は文書単位の表示合成。
ビュー定義の型はカーネルの型で、読み取りとリントは workspace-core にある。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `VirtualGridDef` / `VirtualDocViewDef` / `VirtualViewDefinition`（root の `ViewDef` / `DocViewDef` は別名） | semantic-core/semantic/VirtualViews.kt | ビュー定義の宣言の型と解決（仮想文書・テキスト表現） |
| `ViewDefinitions` / `ViewCatalog` | workspace-core/workspace/ViewDefinitions.kt | 読み取り（`parseView`＝definition 参照の列展開を含む）とリント（`lint` / `lintDocument`＝ビュー → スキーマの整合・`staticFieldAt` / `idElements`） |
| `Views` | core/Views.kt | root の窓口（views ゾーンのファイルを読んで渡す `load`・行 × セルの解決 `resolve`・文書ビューの解決 `resolveDocument` / `resolveAttachments`・`renderText` / `renderDocText`・`fieldAt`＝`SchemaDef.fieldAt` へ委譲） |
| `ViewRow`（＝`VirtualRow`）/ `ViewCell` | core/Views.kt | 解決の結果（別名） |
| `ViewGridPane` | ui/ViewGridPane.kt | ビューグリッド。セル編集は文書キャッシュの木の直接編集、行アクションで詳細文書の作成・新規行 |
| `DocViewSections` / `AttachedBacklinks` | ui/FormView.kt | 文書ビューの描画（fields セクションは既存フォームへの委譲・backlinks と attachments は読み取り専用の表） |
| `AppState.createViewDetail` / `addViewRow` / `addViewRowFile` | app/AppState.kt | 詳細文書の作成・新規行（配列モード／ファイルモード） |
| `Exporter`（`source.view`） | skill/Exporter.kt | ビューで見えている表をそのまま帳票へ（グリッドのみ） |

## ビュー機構

- **ビューはデータを持たない**: 独自の永続化を一切持たない。セルは必ず原本上の座標に解決され、編集はその座標の原本の木へルーティングされる。
- **編集経路は既存と同一**: UI のセル編集は文書キャッシュ（`AppState.ensureDoc`）の木を `Slot` で直接変更し `markEdited()` する（dirty・Undo・保存・外部変更検出がそのまま効く）。エージェントは `read_view` で座標を得て既存の `apply_patch` で編集する。ビュー専用の書込経路を作らない。
- **行は索引基準・値は編集中優先**: 行の集合と座標は注入された索引から解決し、セル値の表示は loader 注入で「編集中の木（UI: docCache / エージェント: ステージング）→ ディスク」の順に読む。UI はオーバーレイ索引を渡すため未保存の新規 ID も行に現れる。ヘッドレス経路はディスク索引のまま。
- **`_views` は予約ディレクトリ**: 書込はメタアクティビティのみ、自動実行の監視対象外、ステージされた定義はパース可否が提出ゲートに加わる。
- **セルエディタの型解決**: 列の相対パスから `Views.fieldAt` でフィールド定義を辿る（`resolve` / `effectiveFields` を通す）。直接編集はスカラー系フィールドに限定し、複合値はジャンプで個別フォームへ。
- **バックリンク列は編集不可の派生表示**: `backlinks` 列は索引の被参照を relation・参照元スキーマで絞って解決し、出現の選択や `editable` とは排他（パースで拒否）。参照元要素の ID は「参照位置をパスの接頭辞に含む最も長いパスの出現」。セルが指す座標は参照値の位置＝ジャンプ先。
- **値の式の列**（`value`）は読み取り専用の派生表示（計算セル）で、行の出現の要素を「いまの要素」として値の式を評価する。座標は値の出どころ、来歴は材料、得られなければ値なしで理由を来歴に残す（[transform.md「計算列と決定論的射影」](transform.md)）。
- **クエリ行**（`rows: match:`。`rows: idPattern` と排他）: 行＝クエリの束縛行。行キーは `rowKey:`（省略時は先頭の束縛。kind: entity 限定）。列の `of:` で結合先ノードの値をセル化でき、セルは各束縛ノードの原本座標へ解決される。バックリンク列・値の式の列も `of` の束縛 ID を対象にでき、値の式は行の束縛を式の `of` で読める。行の同一性は `ViewRow.key`（束縛値の組）。`order` は併用不可。
- **新規行の入口は 2 モード**: `newRow` は配列モード（`file` ＋ `path`: 一覧文書の配列へ要素追加＝未保存編集として積む）とファイルモード（`filePattern`: 詳細文書をスキャフォールドしてディスクへ書込＝索引更新で行が現れる）の排他。スキャフォールドは `detail.filePattern` の「作成」と共通（[schema.md「ひな型」](schema.md)）。`newRow.filePattern` に書ける穴は `{id}` だけ。ビューが宣言するのは「新しい原本がどこで生まれるか」だけ。

## 文書ビュー

- **スキーマ＝データ契約、ビュー＝画面の合成**。表示決定の優先順位は「①文書ビュー（合成）＞ ②スキーマの固有表示ヒント（`role` / `display` / `label`）＞ ③予約語規約」。画面合成のためのオプションをスキーマへ足さない。
- **マッチしない文書は従来のスキーマ駆動フォーム**（暗黙の既定ビュー）。適用は `match`（glob）と `schemaId` の AND、複数定義は定義ファイル名順の先勝ち。台帳の文書に限る。
- **fields セクションは既存フォームへの委譲**: ルート全体は従来のルート描画、部分表示（`path`）は `Views.fieldAt` ＋ `slotAt` ＋ `FieldRow`。`include` / `exclude` は描画対象の選別で、編集経路・実効フィールド合成は変えない。
- **backlinks セクションは編集不可の派生表示**: グリッドのバックリンク列と同じ解決規則。行＝文書内の ID（`idPattern` で絞る）。`columns` は参照元要素からの相対 YPath の値を表示専用で添える。
- **要素単位アタッチ（`attachments`）**: `at` パターン（YPath ＋ `[*]`）に一致する要素の展開フォーム内に、その要素の ID への被参照表を差し込む。解決結果を `FormCtx.attachments` で運び、`ObjectFields` の末尾で描画する（全描画経路に効く。専用の分岐を作らない）。
- **人間と AI が同じ面を見る**: `read_view`（`file` 引数）・CLI `view <id> [file]` が同じ解決（`resolveDocument` / `resolveAttachments` / `renderDocText`）を返す。エクスポートはグリッドのみ。

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
- 宣言的レンズ定義（`kind: lens`）は需要待ちで導入しない。

## 規律

- ビュー専用の書込経路を作らない。UI は docCache の木の直接編集、エージェントは `read_view` → `apply_patch`。
- ビュー定義の型はカーネルの型（`semantic/VirtualViews.kt`）で、root の型はその別名。root にビュー定義の型や読み取りを書き足さない。
- 定義項目の追加は、宣言の型・`ViewDefinitions.parseView`・メタスキーマ `builtin-view`（`builtin-schemas/view.yaml`）を対で更新する。
- 画面合成のためのオプションをスキーマ（`FieldDef`）へ足さない。合成の要望はビュー側の語彙で受ける。
- リファクタリングの計画をアプリ側で補強しない（入口によって計画が食い違う）。

## 拡張ポイント

- **ビュー定義の項目追加**: 宣言の型・`parseView`・`builtin-view` を対で更新する。
- **横断ビューの追加**: データソースとして `AppState.workspaceRecords()` と `IdIndex` を使い、`ui/Shell.kt` の `LedgerSpace` に足す。1 行要約は `Display` を使う。
- **セルの型の追加**: `Views.fieldAt` の解決と `ViewGridPane` のセルエディタに足し、書込は `Slot` ＋ `markEdited()` のまま。
