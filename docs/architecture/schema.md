# スキーマ

利用者が書くスキーマは意味のスキーマ（ID・参照・関係・フェーズ・表示のヒントを持つ項目の木）で、semantic-core が持つ。読むのはカーネルの経路だけで、root の `SchemaDef` はカーネルの `WorkspaceSchema` にファイルの場所を足す薄い型。
構造の検証は、この木から導出した最小の構造のスキーマ（yaml-core）で行う。フェーズ・派生・推測・値の式・リファクタリング・ひな型もここに置く。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `FieldType` / `FieldDef` / `FieldHints` / `SchemaTree` | semantic-core/schema/Fields.kt・SchemaTree.kt | 利用者が書く項目の木とその読み方（`SchemaTreeParser`）。`resolve()` が `$ref` と型の名前を狭める規則で合成しつつ解決し、型の連鎖（`lineage`・`isA`）・`relation`・`role`・`options`・`expression` と、表示と編集のヒント（`FieldHints`）を持つ |
| `BuiltinTypes` | semantic-core/schema/BuiltinTypes.kt・資源 `builtin-types/` | 組込みの型（定義体 `text`）。モジュール `builtin-types` としてカタログ（`SchemaCatalog`）に常に含まれ、非修飾の名前は自分のモジュールの次に引く |
| `MetaSchemaTypes` | workspace-core/workspace/MetaSchemaTypes.kt | 読み込み時に、型の名前をメタスキーマの `type` の選択肢と `cases` へ足す |
| `WorkspaceSchema` | workspace-core/workspace/WorkspaceReader.kt | スキーマの中身の原本（名前・説明・項目の木・関係の宣言・検査・式・導出した構造 `structures`） |
| `SchemaDef` / `SchemaLoader` / `SchemaCatalog` | schema/Schema.kt | root の薄い型（`WorkspaceSchema` ＋ ファイルの場所）と、カーネルの読み込み結果をアプリの形にする `fromKernel`。`SchemaCatalog` は全スキーマと library の定義を `(moduleId, definitionName)` でリンクする |
| `SchemaDocuments` | workspace-core/workspace/SchemaDocuments.kt | スキーマ文書 1 件の読み取り（ID・種類・適用先・derive・エンティティ型・関係レジストリ・項目の木・検査の展開）と関係レジストリの集約の 1 実装 |
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
| `SchemaEditor` | ui/SchemaEditor.kt | `_schemas` 用の構造化エディタ |

## スキーマの読み込み

- スキーマを読むのはカーネルの経路だけ。`Workspace.refresh` は、開いたセッションが読んだ結果を `SchemaLoader.fromKernel` でアプリの形にする。
- スキーマ文書 1 件の読み取りと関係レジストリの集約は `SchemaDocuments` の 1 実装。root とカーネルに別々の読み取りを持たない。
- 読み込みは「パース → リンク → 導出」の三段階。全ファイルをパースしてから `SchemaCatalog` を構築するため、ファイル順に関係なく横断参照を解決できる。
- `kind: library` の文書は `definitions` だけを公開し、文書には適用されない（`match` / `root` / `phases` / `coverage` を持たない）。`kind: relations` は関係レジストリ（[graph-query.md](graph-query.md)）。
- 検査の定義の読み込み（展開・パース・出どころ・メタスキーマへの語彙のキーの合成）は `CheckCatalog` の 1 実装（[checks.md「検査の定義の読み込み」](checks.md)）。
- スキーマの定義そのものの点検と、宣言のない関係名の点検は `SchemaDefinitionChecks` にあり、`WorkspaceReader.buildSchemas` がスキーマ置き場のスキーマについて行い、読み込みエラーへ足す。`SchemaDef.validateDefinition()` は 1 件だけを読んだ場面（設計 UI のプレビュー）の窓口。
- `_schemas/` 配下の文書にはメタスキーマ（`builtin-schema`）だけが適用される（`Workspace.schemaFor`。一般スキーマの `match` は及ばない）。`kind: relations` の文書には `builtin-relations`。利用者は同 ID のスキーマを置けば上書きできる。

## `$ref` と実効フィールド

- `SchemaDef.resolve()` は参照先定義に呼び出し側の `key` / `label` / `required` などを合成して返す。フィールドを使う側は必ず `schema.resolve(field)` を通す（`FormView`・`Validator`・`DocScanner`・`ai` すべて同じ）。
- ローカル参照は `$ref: definition`、横断参照は `$ref: moduleId#definition`。各 `FieldDef` は宣言元モジュールを保持するため、library から解決した定義の子の非修飾 `$ref` はその library を基準に解決する。definitions を利用側へマージしない。
- enum の `cases`（値ごとの追加フィールド）を持ちうる object の子フィールドを列挙する側は、必ず `schema.effectiveFields(fields, node)` を通す。データノードの enum 値に応じて case フィールドが判別フィールドの直後に合成され、再帰的に展開される。素の `field.fields` を直接列挙すると case フィールドが漏れる。
- `ai.fieldToJsonSchema` だけはデータノードが無いため `effectiveFields` を使わず、判別 enum が 1 つなら値ごとの `anyOf`、複数なら全 case の和集合に変換する。
- `resolve` は浅いコピーで、`fields` / `item` / `value` / `cases` のインスタンスを再生成しない。リファクタリングがフィールドをインスタンス同一性で同定する前提なので、deep copy にするならそこを見直す。
- 型の名前（`type: 名前`）は `$ref` と同じ解決（パーサが `FieldDef.ref` に同じ名前を入れ、`typeName` に書かれた名前を残す）。合成の規則は次の「型の連鎖と狭める規則」。

## 型の連鎖と狭める規則

型は 3 段に分かれる。基底の種類（Kotlin の `FieldType`＝YAML の値の形と、索引・参照解決のようにコードが要る意味 `id` / `ref`）、組込みの型（semantic-core の資源 `builtin-types/` の定義体。`text`）、ユーザ型（`definitions` のスカラーの定義）。組込みの型とユーザ型は同じ書き方・同じ能力で、組込みに特権は無い（CORE-CALCULUS「定義層モデル」）。

- **書き方と解決順**: 項目の `type:` に基底の種類でない名前を書くと定義の名前。解決順は基底の種類 → 自分のモジュール → `モジュール#名前` → 組込みの型（`SchemaCatalog.find`。利用者が同じ名前を自分のモジュールに置けばそちらが勝つ）。
- `$ref` は同じ意味の別記法で、入れ物（object / array / map）の定義はこちらで使う。
- **連鎖**: 解決した項目は `lineage`（通った定義の修飾名。使う側に書いた名前が先頭。例 `common#社員メール → common#メールアドレス`）を持ち、`type` は末端の基底の種類。使う側は `isA(BuiltinTypes.TEXT)` のように連鎖で判定し、`FieldType` に分岐を増やさない（フォームの複数行の欄・表のセルの幅がこの判定）。
- **循環**: 定義自身の `ref` を辿って戻る循環は定義の点検（`SchemaTree.typeChainCycle`）が誤りにする。object の再帰は `fields` / `item` / `value` を通るので循環にならない。
- **狭める合成**（`SchemaTree.narrow`）: 最小は大きい方・最大は小さい方・形式は重ねる（`patterns`＝全てに一致）・選択肢は部分集合・`cases` は定義に無いときだけ・参照の絞り込みは層を重ねる（`idPatternLayers`。全ての層に合う ID だけ。`ScannedRef.outerIdPatterns` → `isAllowedBy`）・範囲は共通部分・未定義の項目の扱いは厳しい方。
- 表示のヒントと `default` は使う側が優先。緩める書き方は `SchemaDefinitionChecks.checkNarrowing` が定義エラーにし、合成は誤りがあっても広がらない向きに倒す。
- **構造への写し**: 導出（`DerivedStructures`）はスカラーの連鎖を導出の前に解決し、基底の種類と合成した決まりを写す。`$ref` のまま写すのは入れ物の定義だけ（再帰のため）。yaml-core は型の名前を知らず、足したのは形式の一覧（`StructureField.patterns`）だけ。
- **メタスキーマ**: `builtin-schema` の `type` は基底の種類の選択肢だけを持ち、`MetaSchemaTypes` が読み込み時に型の名前を選択肢と `cases`（基底の種類の分を写す）へ足す。組込みの型は `BuiltinSchemaResources.kernelSchemas`、利用者の型は `WorkspaceReader.buildSchemas`。`$ref` ＋決まりのキーの警告は残る（型は `type:` で書くのが正）。
- **道具**: `extract-definition` はスカラーなら使う側に `type: 名前` を書く。`rename-definition` / `rename-schema-id` は `type` の言及も書き換える。`convert-type` の string ↔ text は型の名前の書き換え。設計 UI の型の選択肢と基底の種類の解決は `TypeChoices`（ui/SchemaEditor.kt）。
- **これから**: 表示と編集のヒント（複数行・入力欄の案内・サンプル値・順序）を型に持たせ、`date` を組込みの型へ移して `FieldType` と `ValueType` から外す。オブジェクト型は object の定義に同一性・表示・検査を足す（ROADMAP のテーマ）。

## 表示のヒント

- 項目の表示と編集のためのヒント（ラベル・説明・選択肢のラベル・一覧の見せ方 `display`・数の桁区切り `display: grouped`＝`NumberDisplay`・初期値）は `FieldDef.hints`（`FieldHints`）の区画。検証・走査・検査はここを読まない（例外は、指摘の文面のために構造のスキーマへ写す `label` だけ）。
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
- 型の連鎖（`type: 名前` と、スカラーの定義への `$ref`）は導出の前に解決して、基底の種類と合成した決まりを写す（「型の連鎖と狭める規則」）。`$ref` のまま写すのは入れ物の定義だけ。複数行の文字列 `text` は組込みの型なので、構造のスキーマでは `string`。
- 分け方の基準: その値 1 つとその項目の決まりだけで決まるチェックは yaml-core。他の値・要素・文書・索引・フェーズを見るチェックは semantic-core。`cases` だけは選択肢の一部として yaml-core に残す。ID の形式（`pattern`）と日付の形式は yaml-core、参照先の絞り込み（`idPatterns`・`scope`）とエンティティ型の ID のパターンからの逸脱は semantic-core。
- 導出の規則（組込みの変換定義）: ID と参照は文字列にする。属性付き参照は値の形ごとの決まりにする。フェーズに達したら必須は素の必須へ畳む。意味と表示の語彙（`role`・`display`・`description`・`default`・`relation`・`scope`・`idPatterns`・`target`・選択肢のラベル）は落とす。`$ref` は写すだけで辿らない。式を受け取る項目は「文字列か object」の形として通す。
- 導出の入口は `DerivedStructures` の 1 つ。アプリ（root の `Validator`）とカーネル（`WorkspaceReader.validateOne`・セッションの増分導出・cli）が同じ `validate(root)` を通る。派生スキーマや語彙のキーを足したメタスキーマのように、読み込みの後で組み立てたスキーマも同じ経路。
- `DerivedStructures` が文書のフェーズを読み、到達したフェーズの一覧を変換へ渡す。導出した構造のスキーマは文書のフェーズごとに 1 つを覚える。定義されていないフェーズの申告の警告も意味の側が出す。
- 導出はスキーマごと・フェーズごとに 1 回（数十ミリ秒の固定費）で、文書ごとの検証の時間は増えない。

## 推測・メタスキーマ・派生

- `any` 型は「検証しない」の明示。検証は必須チェックだけ、フォームは汎用エディタ、AI 生成は構造化出力を無効化、索引は未知キーと同じ走査境界。業務スキーマで安易に `any` へ逃げない。
- スキーマ推測は純ロジック（`SchemaInference`。相対パス × ルートの木のリストから `_schemas` 文書の木を構築）。型推測・参照認識（`RefNaming` を流用）・enum 候補・required 候補は複数文書の合議。生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通る形で出す。入口は CLI `infer-schema` とファイルツリーの推測ダイアログ。
- メタスキーマ `builtin-schema` は `FieldDef` の再帰を再帰 `$ref`、「`type` の値ごとに持てる属性」を `cases` で表現する。型の名前（組込みの型・利用者の型）は読み込み時に `MetaSchemaTypes` が `type` の選択肢と `cases` へ足す。組込みスキーマ全件と実用形スキーマの検証エラー 0 を `MetaSchemaTest` が確かめる。
- 派生スキーマは「ロード時にコンパイルされる読み取り専用の成果物」。スキーマ文書の `derive:`（from / each / id / match / fields の写像。語彙は skill の列マッピングと共通）が導出元文書 → 検証スキーマの写像を定義し、読み込みの第 3 段階でコンパイルされる。導出されたスキーマはファイルとして存在しない。
- 宣言側スキーマは文書にマッチしない（自身の `match` を持てず、`root` は導出スキーマの共通フィールド）。導出の失敗（プレースホルダ欠落・キー欠落・ID 重複・未知の型）は理由付きでスキーマ読込エラーになる。
- 導出元文書の保存・外部変更・変更セット適用は、スキーマ変更と同じ扱いで全再構築（`Workspace.noteDiskChanges`）。自動適用（`approval: auto`）だけは導出元文書への書込で承認待ちへ降格する（検証ルールが無人で変わる経路を塞ぐ）。

## 値の式

検査の `check` に書ける値の式を、検査の外（ひな型・表示・組み立て）でも同じ意味で使う。新しい式言語は作らない。

- **印（宣言）**: `FieldDef.expression`（`expression: true`。`$ref` の合成で引き継ぐ。string / text 型のみ）。意味は「この項目の値は値の式でもよい。短い書き方（`{パス}` を含む文字列＝`format` の省略形）でも、完全な書き方（式の object）でも。式が返す値の型が `type`」。メタスキーマの項目（`filePattern`・ビューの `detail.filePattern` / `newRow.filePattern`）も利用者のスキーマの項目も同じ印を書く。
- **読み取り（共通）**: `ExpressionFields.read` / `readAll` がスキーマの木に沿って文書を辿り（`SemanticRewrite.walkDocument`）、印のある項目の値を `ExpressionValue`（書かれたままの文字・式・場所・表示名）にする。スキーマ文書・ビュー定義・利用者の文書が同じ関数を通り、結果は `WorkspaceSchema.expressions`（場所 → 式）。使う側は `expressionAt("filePattern")` のように引く。
- **評価（共通）**: `SemanticValues.evaluate(式, 環境)`。環境は使う側が作る（いまの要素・予約の名前 `{id}` `{title}`・穴の値の直し方・代表値）。クエリの check 判定も、解と割当てを環境に写してこれを呼ぶ。`of` の省略はいまの要素、`format` の `with` は穴の名前 → 式。埋まらなかった穴は `Result.missing`。
- **文面（共通）**: `ExpressionMessages`＝式として読めない・穴が埋まらない、の 1 つの表。
- **式の中身**: 読み出し `{of, in, path, via}`（項をそのまま返す。`[*]` は合う場所の値の配列）・決まった値・`format`・`map`・`first`・`length` / `upper` / `lower` / `number`・四則演算・`refCount`・`occurrenceCount`・集計（`sum` / `min` / `max` / `count`＝配列を受け取る関数）・集合から配列へ `all`・要素ごとの式 `each`。
  条件分岐・利用者の関数・ラムダ・非決定的な値（今日の日付・乱数・連番）は入れない。連番は `IdSuggest` の領分。
- **参照を辿る（`via`）**: 読み出し `{via: type, in: 指定, path}` と `format` の入れ物 `{via: type, in: 指定}`。行の要素に書かれた参照の線（検査の `edge` と同じ突き合わせ）をちょうど 1 本辿り、相手の出現を `in` で選ぶ（定義出現優先）。検査の中では書けない（辿るのはクエリの `edge`）。`scopes` を書いた式でも、末尾に「いまの要素」が続く。
- **値は項と出どころ**（ADR-0010）: 式の値はスカラー・配列・マップと、その出どころ（`SemanticCheckSide` の `node` / `elements`）。関数（集計・四則演算・`length` など）は引数を先に評価し、型が合わなければ得られない。比較・穴埋め・四則演算はスカラーだけを受け取る。形を受け取る語は `format` / `first` / `all` / `each`。
- **集合から配列へ（`all`）**: 同じ起点の全ての解にわたる形（`{all: {of, in, path}}`。環境の `solutions`＝検査の解の一覧か、行を 1 つの解と見なすビュー）と、起点を固定した照合の形（`{all: {from, match, where, also, of, in, path}}`。環境の `solveFrom`＝`SemanticQuery.matchesFrom`。`from` は起点の束縛で、省略はいまの要素。検査の中でも書ける）。
  値を読む出現は定義した側を優先して 1 つ（`in` で選ぶ）。`[*]` は平らに広がる。読めない要素が 1 つでもあれば配列そのものが得られない。古い書き方（集計の中の `of`・`count` に `match`）は定義エラーで `all` を案内する。
- **要素ごとの式（`each`）**: `{each: 配列の式, value: 式}`。各要素をいまの要素（ノードならその要素の出現・場所ならその場所）として `value` を評価し、配列にする。名前を付けた引数は無い。
- **順序の種類**: `type: number / date`（`min` / `max` の比べ方。省略時は数値。表記から意味を決めないため、日付は宣言する。日付として読める規則は date 型の検証と同じ `StructureValidator.isDateText`）。
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

## 規律

- `FieldDef` を使うときは必ず `schema.resolve(field)`、object の子フィールドの列挙は必ず `schema.effectiveFields(fields, node)` を通す。
- 実効必須は素の `field.required` を見ず、`schema.requiredAt(field, phaseIndex)` を通す（`phaseIndex` は `schema.currentPhaseIndex(root)`）。検査の状況の `activeFrom` も `definingRoot` 引数を省略しない。
- スキーマを読むのはカーネルの経路（`WorkspaceReader`）だけ。スキーマに持たせる情報を足すときは `WorkspaceSchema`（と `SchemaDocuments`）へ足し、`SchemaDef` には読み出しの窓口だけを足す。`SchemaDef` は data class ではない（`copy` は無い）。
- スキーマ文書に新しいキーを足すときは、`SchemaDocuments` とメタスキーマ `builtin-schemas/schema.yaml` を直す。
- 表示のヒントは `FieldHints` に置き、検証・走査・検査から読まない。
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
