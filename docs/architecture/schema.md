# スキーマ

利用者が書くスキーマは意味のスキーマ（ID・参照・関係・フェーズ・表示のヒントを持つ項目の木）で、semantic-core が持つ。読むのはカーネルの経路だけで、app-core の `SchemaDef` はカーネルの `WorkspaceSchema` にファイルの場所を足す薄い型。
フェーズ・派生・推測・値の式・リファクタリング・ひな型もここに置く。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `FieldType` / `FieldDef` / `FieldHints` / `SchemaTree` | semantic-core/schema/Fields.kt・SchemaTree.kt | 利用者が書く項目の木とその読み方（`SchemaTreeParser`）。`resolve()` が `$ref` と型の名前を狭める規則で合成しつつ解決し、型の連鎖（`lineage`・`isA`）・`relation`・`role`・`options`・`expression` と、表示と編集のヒント（`FieldHints`）を持つ |
| `ValueSemantics` | semantic-core/schema/ValueSemantics.kt | 意味の基底型 `date` / `time` / `datetime` の値の表現（正準の形・読み＝存在の判定・案内・サンプル・JSON Schema の format）。`FieldType.value` から引く |
| `ValueFormat` | semantic-core/schema/ValueFormat.kt | 表示の形（`format`＝DecimalFormat / DateTimeFormatter のパターン）で値を画面の文字に組み立てる（値は変えない）。パターンの点検も |
| `BuiltinTypes` | semantic-core/schema/BuiltinTypes.kt・資源 `builtin-types/` | 組込みの型（定義体 `text` / `line` / `word`）。モジュール `builtin-types` としてカタログ（`SchemaCatalog`）に常に含まれ、非修飾の名前は自分のモジュールの次に引く |
| `MetaSchemaTypes` | workspace-core/workspace/MetaSchemaTypes.kt | 読み込み時に、型の名前をメタスキーマの `type` の選択肢と `cases` へ足す |
| `WorkspaceSchema` | workspace-core/workspace/WorkspaceReader.kt | スキーマの中身の原本（名前・説明・項目の木・関係の宣言・検査・式・導出した構造 `structures`） |
| `SchemaDef` / `SchemaLoader` / `SchemaCatalog` | schema/Schema.kt | app-core の薄い型（`WorkspaceSchema` ＋ ファイルの場所）と、カーネルの読み込み結果をアプリの形にする `fromKernel`。`SchemaCatalog` は全スキーマと library の定義を `(moduleId, definitionName)` でリンクする |
| `SchemaDocuments` | workspace-core/workspace/SchemaDocuments.kt | スキーマ文書 1 件の読み取り（ID・種類・適用先・derive・エンティティ型・関係レジストリ・項目の木・検査の展開）と関係レジストリの集約の 1 実装 |
| `StateDeclaration` / `StateDef` / `EventDef` / `LifecycleDef` / `RefinementDef` | semantic-core/schema/Behavior.kt | 名前付き State と Event 種別の宣言、Lifecycle の生成・遷移・終端の記述、State の詳細化（S-28・S-31・S-36） |
| `BehaviorChecks` | workspace-core/workspace/BehaviorChecks.kt | State / Event / Lifecycle の横断解決、原因と到達可能性の静的点検、詳細化の展開（S-28・S-31・S-36） |
| `BehaviorRuntime` | workspace-core/workspace/BehaviorRuntime.kt | 既存の `SemanticRules` による State 4 値判定と Action / Event の前後差分の Lifecycle 照合（S-29・S-31） |
| `BehaviorImpact` | workspace-core/workspace/BehaviorImpact.kt | Action の直接変更から参照・保有を介する State 影響候補の抽出（S-30） |
| `WorkspaceReader` | workspace-core | スキーマ文書・組込みスキーマ・derive の展開を読み込み、スキーマの定義の点検を読み込みエラーへ足す（`buildSchemas`） |
| `SchemaDefinitionChecks` | workspace-core/workspace/SchemaDefinitionChecks.kt | スキーマの定義の点検（種別ごとに書けるもの・型と制約の食い違い・`$ref` の参照先・cases・フェーズ・`filePattern`）と宣言のない関係名の点検 |
| `BuiltinSchemas` / `BuiltinSchemaResources` | schema/BuiltinSchemas.kt・workspace-core 資源 `builtin-schemas/` | 予約ディレクトリ用の組込みスキーマ（メタスキーマ `builtin-schema`・`builtin-relations`・`builtin-view`・`builtin-activity` ほか） |
| `PhasesDef` / `currentPhaseIndex` / `requiredAt` / `PhaseProgress` | semantic-core/schema/ | フェーズの宣言・文書の現在フェーズ・フェーズ考慮の実効必須・記入の進み具合 |
| `StructureSchema` / `StructureField` / `StructureParser` / `StructureValidator` | yaml-core/structure/ | 最小の構造のスキーマとその検証 |
| `DerivedStructures` / `BuiltinTransforms` | workspace-core/workspace/DerivedStructures.kt | 構造のスキーマの導出と構造の検証の入口（スキーマ 1 件につき 1 つ。フェーズごとに覚える） |
| `DeriveDef` / `SchemaDerivation` | semantic-core/schema/SchemaDerivation.kt | 派生スキーマの宣言と、導出元文書からのロード時コンパイル（parse・compile） |
| `SchemaInference` / `RefNaming` | schema/SchemaInference.kt | 既存文書群からのスキーマ推測（純ロジック） |
| `ValueExpr` / `SemanticValues` / `ExpressionFields` / `ExpressionMessages` | semantic-core/schema/ValueExpr.kt・semantic/Values.kt・schema/ExpressionFields.kt | 値の式・1 つの環境での評価・式を受け取る項目の共通の読み取り・文面の表 |
| `PathPattern` / `AnyIndex` | semantic-core/schema/PathPattern.kt | `[*]`（任意の番号）を含むパスの読み取り・照合・展開（集計のパス・ビューのアタッチ先・改名の影響が共有） |
| `FileNames` | semantic-core/schema/FileNames.kt | ファイル名に使える文字への直し方（新しい文書のファイル名と skill の取り込みが共有） |
| `SchemaScaffold` | semantic-core/schema/SchemaScaffold.kt | 項目の木から文書の中身を作る（`defaultTree`＝必須の初期値・`sample`＝プレビュー用） |
| `DocumentScaffolds` / `ScaffoldSource` / `FileNameRules` | workspace-core/workspace/DocumentScaffold.kt | 新しい文書のひな型（作り先 `pathFor`・初期の内容・ID）とファイル名の決まりと文面の表 |
| `SemanticSchemaRefactor` / `SemanticRewrite` | semantic-core/semantic/SchemaRefactor.kt・Rewrite.kt | スキーマ・リファクタリングの計画の生成と、書き換えの共有基盤 |
| `SchemaEditor` | ui/schema/SchemaEditor.kt | `_schemas` 用の構造化エディタ |

## スキーマの読み込み

- スキーマを読むのはカーネルの経路だけ。`Workspace.refresh` は、開いたセッションが読んだ結果を `SchemaLoader.fromKernel` でアプリの形にする。
- スキーマ文書 1 件の読み取りと関係レジストリの集約は `SchemaDocuments` の 1 実装。app-core とカーネルに別々の読み取りを持たない。
- 読み込みは「パース → リンク → 導出」の三段階。全ファイルをパースしてから `SchemaCatalog` を構築するため、ファイル順に関係なく横断参照を解決できる。
- `kind: library` の文書は `definitions` だけを公開し、文書には適用されない（`match` / `root` / `phases` / `coverage` を持たない）。`kind: relations` は関係レジストリ（[graph-query.md](graph-query.md)）。
- 検査の定義の読み込み（展開・パース・出どころ・メタスキーマへの語彙のキーの合成）は `CheckCatalog` の 1 実装（[checks.md「検査の定義の読み込み」](checks.md)）。
- スキーマの定義そのものの点検と、宣言のない関係名の点検は `SchemaDefinitionChecks` にあり、`WorkspaceReader.buildSchemas` がスキーマ置き場のスキーマについて行い、読み込みエラーへ足す。`SchemaDef.validateDefinition()` は 1 件だけを読んだ場面（設計 UI のプレビュー）の窓口。
- `_schemas/` 配下の文書にはメタスキーマ（`builtin-schema`）だけが適用される（`Workspace.schemaFor`。一般スキーマの `match` は及ばない）。`kind: relations` の文書には `builtin-relations`。利用者は同 ID のスキーマを置けば上書きできる。

## `$ref` と実効フィールド

- `SchemaDef.resolve()` は参照先定義に呼び出し側の `key` / `label` / `required` などを合成して返す。フィールドを使う側は必ず `schema.resolve(field)` を通す。
- ローカル参照は `$ref: definition`、横断参照は `$ref: moduleId#definition`。各 `FieldDef` は宣言元モジュールを保持するため、library から解決した定義の子の非修飾 `$ref` はその library を基準に解決する。definitions を利用側へマージしない。
- enum の `cases`（値ごとの追加フィールド）を持ちうる object の子フィールドを列挙する側は、必ず `schema.effectiveFields(fields, node)` を通す。データノードの enum 値に応じて case フィールドが判別フィールドの直後に合成され、再帰的に展開される。いまの値では使えない case のキーの案内（`inactive`）は、読まれたときに作る（知らない項目の指摘とフォームの案内だけが使う。節ごとに作ると、選択肢と cases の多いメタスキーマの検証で時間の大半を占めた）。
- `ai.fieldToJsonSchema` だけはデータノードが無いため `effectiveFields` を使わず、判別 enum が 1 つなら値ごとの `anyOf`、複数なら全 case の和集合に変換する。
- `resolve` は浅いコピーで、`fields` / `item` / `value` / `cases` のインスタンスを再生成しない。リファクタリングがフィールドをインスタンス同一性で同定する前提なので、deep copy にするならそこを見直す。
- 型の名前（`type: 名前`）は `$ref` と同じ解決（パーサが `FieldDef.ref` に同じ名前を入れ、`typeName` に書かれた名前を残す）。合成の規則は次の「型の連鎖と狭める規則」。

## 型の連鎖と狭める規則

型は 3 段に分かれる。基底の種類（Kotlin の `FieldType`。表現の基底＝YAML の値の形と、意味の基底型＝値の表現を持つ `id` / `ref`・`date` / `time` / `datetime`。意味の基底型の直列化は文字列）、組込みの定義体（semantic-core の資源 `builtin-types/`。文字列の階層 `text` / `line` / `word`）、ユーザ型（`definitions` のスカラーの定義）。組込みの型とユーザ型は同じ書き方・同じ能力で、組込みに特権は無い（CORE-CALCULUS「定義層モデル」）。

- **書き方と解決順**: 項目の `type:` に基底の種類でない名前を書くと定義の名前。解決順は基底の種類 → 自分のモジュール → `モジュール#名前` → 組込みの型（`SchemaCatalog.find`。同じ名前は自分のモジュールが勝つ）。
- `$ref` は同じ意味の別記法。入れ物（object / array / map）の定義も `type: 名前` で使える（オブジェクト型）。
- **オブジェクト型**（第三弾）: object（名前付きでも、その場でも）の同一性は `FieldDef.entity`（`{type, defines, aspect, owns}`。名前付きの型で `type` を省くと定義名）。走査・エンティティの整合・全体像の合成は、要素の解決した object の `entity` を文書スキーマの `entity:`（ID の形）より優先する。
- 入れ物の型を使う側の `fields` / `item` / `value` は `SchemaTree.mergeFields` / `mergeChild` が合成する: 定義に無いキーは末尾に足し、定義にあるキーは同じ項目を狭める（型を省いた書き直しは定義の型のまま。`FieldDef.typeGiven` で見分ける）。点検は `SchemaDefinitionChecks.checkContainerNarrowing`。
- 型の検査は `FieldDef.checks`（`ElementRule`。使う側の検査を足す）。評価は [checks.md「文書 1 つの検証」](checks.md)。
- **連鎖**: 解決した項目は `lineage`（通った定義の修飾名。使う側に書いた名前が先頭）を持ち、`type` は末端の基底の種類。使う側は `isA(BuiltinTypes.TEXT)` のように連鎖で判定し、`FieldType` に分岐を増やさない。
- **循環**: 定義自身の `ref` を辿って戻る循環は定義の点検（`SchemaTree.typeChainCycle`）が誤りにする。object の再帰は `fields` / `item` / `value` を通るので循環にならない。
- **狭める合成**（`SchemaTree.narrow`）: 最小は大きい方・最大は小さい方・形式は重ねる（`patterns`＝全てに一致）・選択肢は部分集合・`cases` は定義に無いときだけ・参照の絞り込みは層を重ねる（`idPatternLayers`。全ての層に合う ID だけ）・範囲は共通部分・未定義の項目の扱いは厳しい方。
- 表示のヒントと `default` は使う側が優先。緩める書き方は `SchemaDefinitionChecks.checkNarrowing` が定義エラーにし、合成は誤りがあっても広がらない向きに倒す。
- **構造への写し**: 導出（`DerivedStructures`）はスカラーの連鎖を導出の前に解決し、基底の種類と合成した決まりを写す。入れ物の定義への参照は、追加の無い使い方なら `$ref` のまま（再帰のため）、追加のある使い方なら合成した中身を展開して写す。yaml-core は型の名前を知らず、足したのは形式の一覧（`StructureField.patterns`）だけ。
- **意味の基底型の直列化**: `id` / `ref` は `word`（空白なし）で、導出が同じ形式を足す。`date` / `time` / `datetime` は正準の形（`ValueSemantics.canonicalPattern`）を足した `string`。存在の検証はコードの検査 `value`（`CodedDocumentChecks.valueIssues`）が行う。
- **ファイルの場所（`file`）**も `string` のままで、書き方の判別と正規化は `FilePath`、許す拡張子は `FieldDef.extensions`（狭める制約）、存在と許可はコードの検査 `fileIssues`。
- **メタスキーマ**: `builtin-schema` の `type` は基底の種類の選択肢だけを持ち、`MetaSchemaTypes` が読み込み時に型の名前（入れ物の定義を含む）を選択肢と `cases` へ足す。`$ref` ＋決まりのキーの警告は残る（型は `type:` で書くのが正）。
- **道具**: `extract-definition` はスカラーなら `type: 名前` を書き、`rename-definition` / `rename-schema-id` は `type` の言及も書き換える。設計 UI の型の選択肢は `TypeChoices`（ui/schema/SchemaEditor.kt）。
- **ヒントは型から引き継ぐ**: 表示のヒントは `FieldHints.over` で使う側が優先し、無ければ型のもの。UI は型の名前でなくヒントで判定する（`FieldDef.multiline` / `placeholder`）。
- 意味の基底型は基底の種類で判定し（`type == FieldType.DATE`。列幅・AI 生成の `format`）、案内とサンプル値はヒントが無ければ `ValueSemantics` の既定。
- **順序は型から**: 順序の種類（`OrderKind.ofType`）は読んだ項目の型で決まり、式の `type` は上書き。項目の定義を引く `fieldAt` を渡すのはスキーマを知る側（`WorkspaceChecks`・`Views.resolve`。「値の式」）。
- **ID の型をエンティティ型の判別に**: `entity` の `idType` は型の名前で、`WorkspaceSchema.entities` が木で解決する（`EntityDecl.resolvedIn` → `SchemaTree.resolveTypeName`。未知の名前・id でない型は `idTypeError`＝定義エラー）。判定は `EntityDecl.matchesId` の 1 か所。読み方は数で読む（文字の金額は読まない）。

## 表示のヒント

- 項目の表示と編集のためのヒント（ラベル・説明・選択肢のラベル・一覧の見せ方 `display`・桁区切り `display: grouped`・複数行 `display: multiline`・入力欄の案内 `placeholder`・サンプル値 `sample`・表示の形 `format`・初期値）は `FieldDef.hints`（`FieldHints`）の区画。
- **値と表示形式**: `format` は画面で見せるときだけ `ValueFormat.display` で文字を組み立てる（数は DecimalFormat・日付などは DateTimeFormatter）。ファイル・編集中・検証・CLI・書き出しは素の値。フォームは非フォーカス時に整形（`CompactTextField.unfocusedText`）、グリッドは列の `format` と項目の `format` のどちらも効く。
- 型（定義）に書いたヒントは使う側へ引き継がれる（「型の連鎖と狭める規則」）。検証・走査・検査はここを読まない（例外は構造のスキーマへ写す `label` だけ）。
- タイトルの役割 `role` は索引のタイトルを決めるので項目の側（意味）に置く。
- 画面合成のためのオプションをスキーマへ足さない。合成の要望はビュー側の語彙で受ける（[views.md](views.md)）。

## フェーズ

- フェーズは文書が申告する。スキーマは `phases`（順序付きの一覧と申告キー。既定 `phase`）を宣言し、文書はルート直下のそのキーで現在フェーズを申告する。申告がない・未知の値は先頭フェーズとして扱い、未知の値には警告を出す。ワークスペース全体のフェーズという概念は持たない。
- `requiredFrom` は累積的（そのフェーズ以降は必須）。現在フェーズより先の未記入はエラーにしない。`required: true` は無条件必須で、併記は定義エラー。`$ref` 解決では呼び出し側の `requiredFrom` を優先合成する。
- 検査の `activeFrom` は「ID を定義している文書の現在フェーズがそのフェーズに達してから判定する」。判定主体は常に定義側文書のフェーズ。判定式は全検証語彙で共有する `semantic.phaseActive`。
- 未到達は記入進捗（`phaseProgress`: フェーズ × 記入済み／総数。対象は `requiredFrom` を持つフィールド）として見せる。`phaseSummaryText` は UI・CLI・エージェントツールが共用する 1 行サマリ。
- 提出ゲートは ERROR 件数で判定するため、フェーズ未到達の未記入はゲートを塞がない。

## 構造スキーマの導出

構造の検証は、利用者が書いた木をそのまま読まず、そこから導出した最小の構造のスキーマで行う。

```
利用者が書いたスキーマの木（FieldDef）
  → FieldYaml（利用者が書く形の YAML の項へ書き出す）
  → SemanticTransform × 組込みの変換定義 builtin-transforms/structural-schema.yaml（＋到達したフェーズの一覧）
  → StructureParser → StructureSchema（yaml-core の structure パッケージ）
  → StructureValidator（文書の構造の検証）
```

- 最小の構造のスキーマが持つのは、値の形（string / int / number / boolean / enum / date / array / map / object / any）・必須・値の形式的なチェック（選択肢・範囲・文字数・形式＝複数なら全てに一致）・`cases`・未定義の項目の扱い・`$ref`・値の形ごとの決まり（`shapes`）・指摘の文面のための表示名だけ。書けないキーは読み取りの段階で誤りになる。
- 型の連鎖（`type: 名前` と、スカラーの定義への `$ref`）は導出の前に解決して、基底の種類と合成した決まりを写す（「型の連鎖と狭める規則」）。`$ref` のまま写すのは、追加の無い入れ物の定義への参照だけ（項目を足した使い方は展開する）。組込みの定義体 `text` / `line` / `word` と意味の基底型 `date` / `time` / `datetime`・`id` / `ref`・`file` は構造のスキーマでは `string`（＋形式）。
- 分け方の基準: その値 1 つとその項目の決まりだけで決まるチェックは yaml-core。他の値・要素・文書・索引・フェーズを見るチェックは semantic-core。`cases` だけは選択肢の一部として yaml-core に残す。ID の形式（`pattern`）と日付の形式は yaml-core、参照先の絞り込み（`idPatterns`・`scope`）とエンティティ型の ID のパターンからの逸脱は semantic-core。
- 導出の規則（組込みの変換定義）: ID と参照は文字列にする。属性付き参照は値の形ごとの決まりにする。フェーズに達したら必須は素の必須へ畳む。意味と表示の語彙（`role`・`display`・`description`・`default`・`relation`・`scope`・`idPatterns`・`target`・選択肢のラベル）は落とす。`$ref` は写すだけで辿らない。式を受け取る項目は「文字列か object」の形として通す。
- 導出の入口は `DerivedStructures` の 1 つ。アプリ（app-core の `Validator`）とカーネル（`WorkspaceReader.validateOne`・セッションの増分導出・cli）が同じ `validate(root)` を通る。派生スキーマや語彙のキーを足したメタスキーマのように、読み込みの後で組み立てたスキーマも同じ経路。
- `DerivedStructures` が文書のフェーズを読み、到達したフェーズの一覧を変換へ渡す。導出した構造のスキーマは文書のフェーズごとに 1 つを覚える。定義されていないフェーズの申告の警告も意味の側が出す。
- 導出はスキーマごと・フェーズごとに 1 回（数十ミリ秒の固定費）で、文書ごとの検証の時間は増えない。

## 推測・メタスキーマ・派生

- `any` 型は「検証しない」の明示。検証は必須チェックだけ、フォームは汎用エディタ、AI 生成は構造化出力を無効化、索引は未知キーと同じ走査境界。業務スキーマで安易に `any` へ逃げない。
- スキーマ推測は純ロジック（`SchemaInference`。相対パス × ルートの木のリストから `_schemas` 文書の木を構築）。型推測・参照認識（`RefNaming` を流用）・enum 候補・required 候補は複数文書の合議。生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通る形で出す。入口は CLI `infer-schema` とファイルツリーの推測ダイアログ。
- メタスキーマ `builtin-schema` は `FieldDef` の再帰を再帰 `$ref`、「`type` の値ごとに持てる属性」を `cases` で表現する。型の名前（組込みの型・利用者の型）は読み込み時に `MetaSchemaTypes` が `type` の選択肢と `cases` へ足す。
- 派生スキーマは「ロード時にコンパイルされる読み取り専用の成果物」。スキーマ文書の `derive:`（from / each / id / match / fields の写像。語彙は skill の列マッピングと共通）が導出元文書 → 検証スキーマの写像を定義し、読み込みの第 3 段階でコンパイルされる。導出されたスキーマはファイルとして存在しない。
- 宣言側スキーマは文書にマッチしない（自身の `match` を持てず、`root` は導出スキーマの共通フィールド）。導出の失敗（プレースホルダ欠落・キー欠落・ID 重複・未知の型）は理由付きでスキーマ読込エラーになる。
- 導出元文書の保存・外部変更・変更セット適用は、スキーマ変更と同じ扱いで全再構築（`Workspace.noteDiskChanges`）。自動適用（`approval: auto`）だけは導出元文書への書込で承認待ちへ降格する（検証ルールが無人で変わる経路を塞ぐ）。

## 値の式

検査の `check` に書ける値の式を、検査の外（ひな型・表示・組み立て）でも同じ意味で使う。新しい式言語は作らない。

- **印（宣言）**: `FieldDef.expression`（`expression: true`。`$ref` の合成で引き継ぐ。string / text 型のみ）。意味は「この項目の値は値の式でもよい。短い書き方（`{パス}` を含む文字列＝`format` の省略形）でも、完全な書き方（式の object）でも。式が返す値の型が `type`」。メタスキーマの項目（`filePattern`・ビューの `detail.filePattern` / `newRow.filePattern`）も利用者のスキーマの項目も同じ印を書く。
- **読み取り（共通）**: `ExpressionFields.read` / `readAll` がスキーマの木に沿って文書を辿り（`SemanticRewrite.walkDocument`）、印のある項目の値を `ExpressionValue`（書かれたままの文字・式・場所・表示名）にする。スキーマ文書・ビュー定義・利用者の文書が同じ関数を通り、結果は `WorkspaceSchema.expressions`（場所 → 式）。使う側は `expressionAt("filePattern")` のように引く。
- **評価（共通）**: `SemanticValues.evaluate(式, 環境)`。環境は使う側が作る（いまの要素・予約の名前 `{id}` `{title}`・穴の値の直し方・代表値）。クエリの check 判定も、解と割当てを環境に写してこれを呼ぶ。`of` の省略はいまの要素、`format` の `with` は穴の名前 → 式。埋まらなかった穴は `Result.missing`。
- **文面（共通）**: `ExpressionMessages`＝式として読めない・穴が埋まらない、の 1 つの表。
- **式の中身**: 読み出し `{of, in, path, via}`（項をそのまま返す。`[*]` は合う場所の値の配列）・決まった値・`format`・`map`・`first`・`length` / `upper` / `lower` / `number`・日付の部分 `year` / `month` / `day` / `yearMonth` / `weekStart`・日付の差 `daysBetween`・文字の一部を取り出す `extract`・四則演算・`refCount`・`occurrenceCount`・集計（`sum` / `min` / `max` / `count`＝配列を受け取る関数）・集合から配列へ `all`・要素ごとの式 `each`。
  条件分岐・利用者の関数・ラムダ・非決定的な値（今日の日付・乱数・連番）は入れない。連番は `IdSuggest` の領分。
- **参照を辿る（`via`）**: 読み出し `{via: type, in: 指定, path}` と `format` の入れ物 `{via: type, in: 指定}`。行の要素に書かれた参照の線（検査の `edge` と同じ突き合わせ）をちょうど 1 本辿り、相手の出現を `in` で選ぶ（定義出現優先）。`via` を並べると段を重ねて辿る（各段で参照が 1 本に決まること。2 段目からは相手の ID に書かれた参照をどの出現からでも辿り、出現を `in` で選ぶのは最後の相手だけ。クロス集計の列の軸にも書ける）。検査の中では書けない（辿るのはクエリの `edge`）。`scopes` を書いた式でも、末尾に「いまの要素」が続く。
- **値は項と出どころ**（ADR-0010）: 式の値はスカラー・配列・マップと、その出どころ（`SemanticCheckSide` の `node` / `elements`）。関数（集計・四則演算・`length` など）は引数を先に評価し、型が合わなければ得られない。比較・穴埋め・四則演算はスカラーだけを受け取る。形を受け取る語は `format` / `first` / `all` / `each`。
- **集合から配列へ（`all`）**: 同じ起点の全ての解にわたる形（`{all: {of, in, path}}`。環境の `solutions`＝検査の解の一覧か、行を 1 つの解と見なすビュー）と、起点を固定した照合の形（`{all: {from, match, where, also, of, in, path}}`。環境の `solveFrom`＝`SemanticQuery.matchesFrom`。`from` は起点の束縛で、省略はいまの要素。検査の中でも書ける）。
  値を読む出現は定義した側を優先して 1 つ（`in` で選ぶ）。`[*]` は平らに広がる。読めない要素が 1 つでもあれば配列そのものが得られない。古い書き方（集計の中の `of`・`count` に `match`）は定義エラーで `all` を案内する。
- **要素ごとの式（`each`）**: `{each: 配列の式, value: 式}`。各要素をいまの要素（ノードならその要素の出現・場所ならその場所）として `value` を評価し、配列にする。名前を付けた引数は無い。
- **順序の種類**（`ValueExpr.OrderKind`＝number / date / time / datetime）: `min` / `max`・順序の比較（`lt` / `lte` / `gt` / `gte`）・行の並びの比べ方。式に `type` を書かなければ、値を読んだ項目の型（`OrderKind.ofType`。環境の `fieldAt` が引く定義）で決め、決まらなければ数値。表記から意味を決めない。
  読んだ値は `SemanticCheckSide.kind` に種類を持ち（読み出しは項目の型・集計は決めた種類・計算は数値・決まった値は null）、比較は `term.kind ?: left.kind ?: right.kind`。日付などの読みと順序は `ValueSemantics` の 1 か所。
- **集計の規則**: 値が書かれていない・種類として読めない要素が 1 つでもあれば得られない（理由は `Result.reason`）。最小・最大の出どころは選ばれた要素、来歴は全ての要素。計算は `SemanticExpression.aggregateDetailed` の 1 実装で、検査とビューが共有する。
- **使う側**: 新しい文書のファイル名（`filePattern`）・ひな型の中身（`scaffold`）・ビューの列 `value`（[views.md](views.md)）。
- 印を読むのは読み取り・評価・「式として読めるか」の検証だけで、走査・検査は式を評価しない。置き場は semantic-core（印・読み取り・評価・文面）と workspace-core（ファイル名の決まり）。

## ひな型

- 初期値の木（`SchemaScaffold.defaultTree`＝必須の項目だけ。default を尊重・cases に対応）とサンプル（`sample`）は項目の木だけを見る処理。
- 新しい文書のひな型（`DocumentScaffolds`）は、作り先に合うスキーマを選ぶので workspace-core。作り先は `filePattern`（値の式）を元の要素（`ScaffoldSource`＝ID・タイトル・定義出現・木の読み手）の環境で評価する `pathFor` の 1 実装。穴が埋まらなければエラー。書かなければ詳細スキーマの `match` から導く後退（`filePatternOf`。単純な glob だけ）。
- ファイル名として使う決まりと文面は `FileNameRules` の 1 つの表（書き方・作り先が `match` に合うこと・`{id}` の有無・newRow は `{id}` だけ）。`SchemaDefinitionChecks`・`ViewDefinitions`・`DocumentScaffolds` が共用する。穴に入った値は `FileNames.safeToken` で直し、予約の `{id}` は直さず拒否。
- スキーマの `scaffold`（変換のひな型。[transform.md](transform.md)）で元の要素の値を新しい文書へ写す。`DocumentScaffolds.scaffold` は必須の初期値の上に出力を重ね、ID の項目へ ID を入れる。
- 使う側: 検証パネルの「詳細文書を作成」（`{create.places}` に実際のパス）・ビューの「作成」・新規行ダイアログ・CLI `view` の `detailCreatePath`・`read_view`。

## リファクタリング

スキーマ定義の変更（改名・移動・順序・型変換・定義や関係の改名）に既存文書とビューを 1 つの計画で追随させる。方式は操作ベース（差分からの推測はしない）。

- 12 操作: rename-field・move-field・align-order・rename-enum-value・delete-field・require-field・convert-type・rename-definition・extract-definition・inline-definition・rename-relation・rename-schema-id。
- 計画（`RefactoringPlan`＝文書ごとの型付き操作列＋警告＋サマリ）は `session.planRefactoring` が作り、`session.applyRefactoring` が 1 トランザクション（Undo 1 回）で適用する。
- 入口は人間専用（`_schemas` はエージェント書込不可）: スキーマエディタの「一括改名…」「一括削除…」「文書をスキーマ順へ整列…」と CLI `workspace refactor`。
- **フィールドの同定はインスタンス同一性**: 対象はドット区切りパスからスキーマ定義上の実体へ解決し、以降は `FieldDef` の同一インスタンス（`===`）で同定する。再帰スキーマ・横断参照・derive 派生を通じても正しく同定できるのは、`resolve` がインスタンスを再生成しないから。enum 値の改名だけは `$ref` の最終実体で同定する。
- **書換の 3 層**: 文書（該当スキーマの全文書をスキーマ誘導で走査。cases は実効フィールドだけ。改名・移動先の既存キーや値・変換できない値は生成段階で拒否）／スキーマ（定義キー・`phases.key`・cases キー・`phases.order`・`requiredFrom` / `activeFrom`・`entity.owns`。生成前にディスクの実体を照合）／ビュー（静的解決が全て対象を指す箇所だけ自動書換。多義は警告）。
- **移動の決定性**: 移動元と移動先は要素コンテキスト署名が一致するときだけ許可する。境界をまたぐ移動・共有定義をまたぐ移動は拒否。
- **警告に留める消費者**: スキーマの宣言節（consistency / constraints / coverage / derive）・定義文書の言及・多義な列パス・アタッチ・skill の写像。言及箇所を列挙して判断は人間に委ねる。フィールドパス・関係名・スキーマ ID を参照する機能を増やすときは「書換か警告か」を決める。
- **計画生成の共有基盤**: 高水準操作は「場所の列挙（Query）× 絞り込みと中止条件（Predicate）× 場所ごとの操作」の形で、列挙と集積は `SemanticRewrite`（`walkDocument`・`reach`・`forEachSchemaEntry`・`rewriteDocuments`・`PlanOps`）を通る。中止条件に当たった文書は理由を集め、全文書の評価後にまとめて中止する。移動・定義の抽出／インライン化・型変換のスキーマ側は操作側が持つ戦略。
- ビュー定義の追随と言及の警告は、カーネルの `RefactoringImpact` が作り、`planRefactoring` が計画に含める（[workspace.md「リファクタリングの影響」](workspace.md)）。どの入口でも同じ計画になる。
- 計画の決定性は `RefactoringPlanGoldenTest`（全操作と拒否ケースの計画を正準テキストで固定）が守る。意図した変更のときだけ `-DupdateGolden=true` で期待値を更新し、差分をレビューする。

## 業務上の State / Lifecycle（S-28）

- `kind: behavior` は文書に `match` せず、`states` と `lifecycles` を公開する宣言文書。既存の `kind: document` のデータや Action を変更しない。
- State は **エンティティに対する名前付き Predicate**。状態値の保存を要求しない。明示定義の `predicate` は既存 `ConstraintRule`（Query と Predicate）で読む。`source: {entity, path, enum}` からは enum の各値に対する `equals` + `expect: exists` を同じ `ConstraintRule` として構築する。`StateDef` 自体は保存対象ではない。
- Lifecycle は State の集合、生成入口（`entry`）、許される遷移（`transitions`）、終端方針（`termination`）を持つ。入口と遷移は原因を `by: {action: ...}` または `by: {event: ...}` で指す。終端は **業務の終了** であって、文書の削除を意味しない。終端しない可能性があるときは `openEnded: true` を明示する。
- `BehaviorChecks.compile` は全スキーマの定義カタログをリンクした後で enum を展開する。State と Lifecycle の ID 重複、参照の不存在、対象 Entity 不一致、原因 Action の不存在／重複、終端からの遷移、非終端の行き止まり、入口からの到達不能、無期限でない Lifecycle の終端への到達不能をスキーマ読込エラーとして報告する。同じ enum の State の一部しか Lifecycle に含めていなければ網羅性不足も検出する。**Action の対象 Entity と Lifecycle の対象 Entity は一致しなくてもよい**（S-30：関連 Entity に波及する操作）。**単一 enum から全値を展開した State 同士の排他性は値の等価比較から成立する**が、任意の複合述語まで含めた状態集合の排他性・網羅性の証明は動的検証の対象。
- Action の参照が存在することは、**Action の実際の効果が宣言した遷移を必ず成立させることの証明ではない**。実データの State 判定と Action 変更候補の前後差分の照合は S-29、関係先への波及は S-30、Event の種類の宣言と前後差分の照合は S-31、Event 発生の**申告記録**は S-32 で追加。Event の発生自体の真偽は証明せず、外部通知の自動収集はまだ扱わない。業務仕様が未完成であることを誤って「検証済み」としない。
- 計算体系の分類: State = `Query + Predicate`、Lifecycle = 名前付き定義間の関係（`Term` / 静的整合）、Action 原因 = 既存 `Rewrite` への参照。新しいデータの永続化・評価器・変更経路は導入しない（CORE-CALCULUS の S-28 設計レビュー）。
- **Lifecycle を正にする**（ADR-0029）: enum から作った State は値の項目（`StateDef.enumPath`）と述語の原文（`predicateRaw`）を持つ。アクションの `lifecycle`（`ActionLifecycle`）は、`ActionPlanner` の前提（`BehaviorRuntime.evaluate` のいまの State から、この操作の遷移があるか）・状態の値の `PatchOp.Set`・`LifecycleDef.history` の配列への記録の行を作る（操作の変更セットに入り、適用は今までどおり）。ダッシュボードの `flow.lifecycle` は `ViewDefinitions` が段・区画（filter は `predicateRaw`）・工程・入口・履歴へ展開する。
  点検（`BehaviorChecks.actionLifecycleErrors`）は、入口か遷移の存在・受け手の型・行き先が State ごとに 1 つ・行き先が enum 由来であることを確かめる。
- **State の詳細化**（ADR-0030）: `refinements`（`RefinementDef`）は親の State の中の流れを同じ ID で別に持つ。親は述語を書かない State（`StateDeclaration.Composite`）で、`BehaviorChecks.compile` が葉の enum の値の `oneOf` を述語として導き、`Catalog.states` に入れる。
  `Catalog.lifecycles` は展開済み（`Flattener`。親へ入る遷移は同じ原因の詳細化の入口の子へ、親から出る遷移は `exits` で狭めた子か全部の子から、中の遷移はそのまま）で、計画器・業務の流れ・照合・シナリオ・監査はこれだけを読む。書いたままの粗い Lifecycle は展開の前に同じ規則で点検し、展開した流れも点検する。
  `Catalog.children` と `collapse` は、断る文面の「できるのは」を、子がそろった親でまとめるのに使う。

### State の実評価と Action の遷移照合（S-29）

- `BehaviorRuntime.evaluate` は `BehaviorChecks` の展開済み State を `SemanticRules.evaluate` へ渡す。結果は `TRUE`（条件成立）／`FALSE`（条件不成立）／`UNKNOWN`（保留）／`NOT_APPLICABLE`（対象外）の **4 値**で返す。Constraint の `where` が偽のとき既存検査では `NOT_IN_SCOPE` になるため、`SemanticQuery.startMatches` で `where` を除いた起点の適用性を確認し、対象なら `FALSE` に戻す。値の欠落と未解決参照を「真」と扱わない。
- `BehaviorRuntime.review` は `ChangeSetApplier.prepare` の書込前候補を `WorkspaceSession.derivedWith` へ重ね、前後の ID・参照索引と実データを比較する。新しい評価器や書込経路は持たない。State が一意に決まらない場合は不整合、判定保留が混じる場合は未確定とする。
- 新規エンティティは `entry.to` と `by.action`、既存エンティティの State が変われば `transitions.from/to` と `by.action` の一致を確認する。宣言なしは `UNDECLARED`、一致は `MATCHED`。State 不変の Action は `UNCHANGED` とし、すべての Action に遷移定義を要求しない。削除は現行 Lifecycle に原因宣言がないため未宣言とする。
- S-29 の初期スコープは **変更対象文書内のエンティティ＋Action の明示的な受け手**。S-30 で参照・保有のグラフに沿う影響候補へ拡張した。診断はデスクトップの操作確認画面と `OperationPreview.behaviorReviews` に出すが、仕様モデルの移行期は保存の可否を変えない（構造・Constraint 検証は従来どおり）。

### 関係を介する State の間接変化（S-30）

- `BehaviorImpact.affected` は **変更前後両方**の `SemanticSnapshot` から参照と保有の線を組み立て、変更文書にある ID と明示的な Action 受け手を起点に無向連結成分を辿る。文書参照は参照先文書の Entity に展開し、ID を持たない文書への参照も候補の起点として補う。線を削除・付け替えした場合も旧線を使って影響元へ辿れる。
- グラフの辺生成と到達候補の探索は O(V + E)。全ワークスペースに対して State を無差別に再評価せず、**候補に入った Entity に限って**前後の State を `BehaviorRuntime` の既存の評価器で照合する。同じ連結成分でも State の真偽・保留理由が変化しない別文書の Entity は診断から除く。
- Action の受け手とは別の Entity の Lifecycle 遷移を検出してよい。静的検査が「原因 Action の対象 Entity = Lifecycle の対象 Entity」を要求することはできない。表示と `LifecycleReview.indirectlyAffected` で別文書への影響を明示する。
- これは **参照・保有を経由して影響する場合の保守的な候補抽出**。全件集計、外部データや参照関係を持たない任意の式で変化する State の完全な依存解析ではない。巨大な連結成分では State 判定の件数も増えるため、必要なら将来、Query の参照関係と変更パスに基づく絞り込みを導入する。

### Event の種類と Lifecycle 前後差分の照合（S-31）

- `kind: behavior` の `events` は **発生した事実の種類**を `id`・任意の `entity`（事実の対象型）・`label`・`description` で宣言する。Action の `do` のような実行命令は持たない。`entity` は Lifecycle の対象型と一致しなくてよい。例えば「許可フラグへの許可通知」は、そのフラグを参照する「依頼」の Lifecycle を変えうる。
- `BehaviorChecks.compile` は Event の ID の重複と Lifecycle 原因 `by: {event: id}` の未定義参照をエラーにする。Action / Event は**別の原因**として区別する。どちらも同じ State 遷移を起こしうるが、Action の一致だけをもって Event として扱わない。
- `BehaviorRuntime.reviewEvent(session, EventOccurrence(eventId, subjectId), prepared)` は、呼び出し側が観測した事実と、その事実に伴う変更候補を受け取り、S-29・S-30 と同じ State 評価・関連先の影響抽出を実行する。イベントが定義されていること、対象 ID と宣言型が一致することを検査してから、原因の種類が一致する `entry` / `transitions` のみ `MATCHED` にする。判定は読み取り専用で `LifecycleReview.cause` に Action / Event を保持する。
- このAPIは **事実の発生をデータから自動推測しない**。Event の申告・保存は後述の独立した記録機構であり、いずれも事実の真偽を保証しない。外部通知の自動受信とデスクトップの専用画面は後続の開発とする。

### Event 発生申告と記録（S-32）

- `BehaviorEventJournal.preview` は申告された Event の種類と対象 ID・Entity 型を `BehaviorRuntime.validateEvent` で照合する。変更候補を渡した場合は S-31 の `reviewEvent` を再利用し、前後 State・Lifecycle の適合性を判定する。**申告元（source）は必須**、証拠参照（evidence）と発生時刻は明示入力できる。これらは利用者の申告であり、真正性を認証するものではない。
- `BehaviorEventJournal.record` は申告情報を `ChangeSetApplier` の既存の作成・検証・保存経路で `${'$'}{layout.runsDir}/events/EVT-<ID>.yaml` に記録する。runs ゾーンは ID 索引と業務データ検証の対象外。記録 ID の重複は拒否し、業務データへの更新や Event の自動発生は行わない。
- 記録は `kind: event-occurrence`、`occurredAt` / `recordedAt`（UTC ISO 8601）、`source`、任意の `evidence`、`occurrenceStatus: reported-not-verified` を持つ。変更候補を照合した場合だけ `review.basis: proposed-changes-only`、対象ファイルの前後 SHA-256、Lifecycle 判定を残す。これは **未適用の変更候補の診断** であって、外部変更が実際に反映された証拠ではない。照合しなければ `review.basis: not-reviewed` と記録する。
- CLI `event` の既定はプレビューだけで、`--record` を明示した場合に限り追記する。`--history` は記録一覧を読み取る。書込み時は `WorkspaceOwnership` の排他を取得する。受信連携・外部署名の検証・業務操作とEvent記録の原子的な同時確定・デスクトップの専用画面は対象外。
- Core Calculus では Event 宣言は Term、対象の照合は既存 Query / Predicate、前後の Lifecycle 判定は既存の State 評価の再利用である。記録 YAML は結果の直列化であり、業務文書の Rewrite ではない。永続化は既存の変更セット適用経路のみで実行する。

### 業務仕様の診断レポート（S-33）

- `BehaviorAudit.inspect` は既存の `BehaviorChecks.compile` の定義カタログと構造エラーを再利用する。State ごとの生成入口・流入・流出・終端を **Lifecycle の宣言の別の射影** として表示するだけで、独自の到達可能性計算や業務データの変更経路は増やさない。
- `schema-errors` は元の宣言の参照漏れ・到達不能等を示す。単独の State と記録専用 Event は正当な場合があるため `info` とし、**欠落と決めつけない**。同じ State・同じ原因から複数の遷移先に進む定義は `review` として人による分岐意図の確認を求め、直ちにエラーとはしない。
- 結果の `status` は `schema-errors` / `not-modeled` / `needs-review` / `structurally-checked`。最後の値は **宣言の構造が点検できた** という意味であって、業務仕様が完全という意味ではない。`proofStatus: not-proven` と、Action の実際の効果・Event の真正性・任意の State 述語の排他性や全業務シナリオの網羅性を証明していない旨を必ず返す。
- CLI `behavior-audit` は読み取り専用で、`validate` の既存 `schemaErrors` もそのまま表示する。構造エラーがあっても健全と誤表示しない。次段階で具体的なシナリオによる動的検証を結び付けるための閲覧面であり、現段階で自動的な業務仕様の完成判定は提供しない。

### 具体例による State 遷移の検証（S-34）

- `BehaviorScenarios.parse` は外部の `cases.yaml` を、原因（`action` または `event`）・入力・`expect`（Lifecycle、Entity、前後の State、判定）の並びとして読む。業務モデルのスキーマ語彙には増やさず、シナリオは独立したテスト入力とする。原因は `TransitionCause` を再利用し、Action と Event を混同しない。
- `BehaviorScenarios.run` は各ケースを **同じ元の `WorkspaceSession`** から独立に確認する。Action は `ActionPlanner.plan`、Event は申告者が与えた `ChangeSet` を使い、共通の `ChangeSetApplier.prepare` と `BehaviorRuntime.review` / `reviewEvent` に渡す。保存経路は呼ばず、Event 発生の真正性も判定しない。ケースごとの `at` で自動値を再現できる。
- 検証前の ChangeSet が不正、期待する照合が存在しない、前後の State / 判定が違う、または余分な Entity の照合が現れた場合は `failed` にする。期待値と一致しても `UNDECLARED`・`UNDETERMINED`・`INCONSISTENT` の照合が含まれる場合は `needs-review` とし、期待値の一致と仕様の健全性を分ける。スキーマエラー時はケースを走らせず `schema-errors` とする。
- Lifecycle の `entry` と `transitions` に対し、期待値が一致したケースの `MATCHED` 照合のみを `exercisedBy` に記録する。未実行の辺を明示するが、有限個の具体例で任意の条件分岐・全データ・外部 Event 発生の網羅性は証明できない。CLI `behavior-scenarios` は `proofStatus: not-proven` を返し、全体の仕様完成判定には利用しない。連続操作のケース間データ引き継ぎはこの段階では対象外。

## 規律

- `FieldDef` を使うときは必ず `schema.resolve(field)`、object の子フィールドの列挙は必ず `schema.effectiveFields(fields, node)` を通す。
- 実効必須は素の `field.required` を見ず、`schema.requiredAt(field, phaseIndex)` を通す（`phaseIndex` は `schema.currentPhaseIndex(root)`）。検査の状況の `activeFrom` も `definingRoot` 引数を省略しない。
- スキーマを読むのはカーネルの経路（`WorkspaceReader`）だけ。スキーマに持たせる情報を足すときは `WorkspaceSchema`（と `SchemaDocuments`）へ足し、`SchemaDef` には読み出しの窓口だけを足す。`SchemaDef` は data class ではない（`copy` は無い）。
- スキーマ文書に新しいキーを足すときは、`SchemaDocuments` とメタスキーマ `builtin-schemas/schema.yaml` を直す。
- 表示のヒントは `FieldHints` に置き、検証・走査・検査から読まない。
- file 型の値の書き方は `FilePath` で判定し、機能側に直書きしない。
- 型は解決した項目の連鎖で判定する（`isA`）。`FieldType` は基底の種類だけで、`text` のような組込みの型の分岐を足さない。組込みの型を足すなら semantic-core の資源 `builtin-types/` に YAML で書く。
- 使う側の決まりの合成は `SchemaTree.narrow` の 1 実装（狭めるだけ）。緩める書き方の検出は `SchemaDefinitionChecks.checkNarrowing`。参照の絞り込みは `idPatternLayers` で見る（`idPatterns` だけを見ると型の層が抜ける）。
- 値の形式的なチェックを変えるなら yaml-core の `structure/StructureValidator.kt`、利用者の語彙から構造への写し方を変えるなら `builtin-transforms/structural-schema.yaml` を直す。利用者の語彙を足しただけでは構造の検証に現れない。
- yaml-core へ ID・参照・関係・フェーズ・表示のヒントに関わるものを戻さない。
- 式を受け取る項目を増やすときは、項目の定義に `expression: true` を書き、使う側が `WorkspaceSchema.expressionAt(場所)` で式を引いて環境を渡す。キー名で値を読む・`パターン.replace("{id}", …)`・独自の `{…}` の穴埋め・文面の直書きを書き足さない。
- 新しい文書のファイル名は `DocumentScaffolds.pathFor` の 1 実装。
- 派生スキーマの写像項目を増やすときは `SchemaDerivation`（parse・compile）とメタスキーマを対で更新する。
- 新しい変更操作を足すときは、走査や文書ループを書かずに `SemanticRewrite` の列挙器と基本戦略を使う。

## 拡張ポイント

- **組込みの型の追加**（まずこちら）: semantic-core の資源 `builtin-types/` に `kind: library` の定義を足し、`BuiltinTypes.names` に並べる。UI が専用の部品を出すなら `BuiltinTypes` に修飾名の定数を置き、解決した項目の `isA` で判定する。
- **基底の種類の追加**（YAML の値の形か、コードが要る意味のときだけ）: `FieldType` と yaml-core の `ValueType` に足し、`StructureValidator`（形式的なチェック）・`FormView.ValueEditor`・`ai.fieldToJsonSchema`・`SchemaScaffold`（`sample` / `defaultTree`）・変換定義 `structural-schema.yaml`・メタスキーマの `type` の選択肢と `cases` に分岐を足す。
- **共通スキーマ定義の追加**: `_schemas` に `kind: library` を置き、利用側は `moduleId#definition` で参照する。解決規則を増やすなら `SchemaCatalog` に閉じる。
- **派生スキーマの写像項目の追加**: `SchemaDerivation.parse` / `compile` とメタスキーマの `derive` 定義をそろえて更新する。語彙は skill の列マッピングと揃える。
- **式を受け取る項目の追加**: 印を付け、使う側が `expressionAt` で引いて `SemanticValues.evaluate` を呼ぶ。読み取り・構造の検証・指摘・文面は共通。
- **値の計算の追加**: 関数（引数を先に評価）か形を受け取る語かを決め、`ValueExpr`（パースと `sources` / `reads` / `binds`）・`SemanticExpression`（純粋な関数）・`SemanticValues` に足し、型が合わない・評価できないときは null を返す規則を守る。メタスキーマの説明と manual/ を対で更新する。
- **リファクタリング操作の追加**: `SemanticRewrite` の列挙器と基本戦略で書き、`RefactoringPlanGoldenTest` に計画を固定する。CLI `workspace refactor` とスキーマエディタに入口を足す。
