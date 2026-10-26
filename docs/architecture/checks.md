# 検証と検査

検証に関わるものは 4 層に分ける。文法プリミティブ（yaml-core。構造と最小の構造チェック）／意味プリミティブ（semantic-core。定義の中で組み合わせる部品と、複雑なアルゴリズムの専用チェッカー）／組込み定義体（同梱の YAML）／ユーザ定義（利用者の YAML）。
検査の語彙（整合・詳細文書の存在・エンティティの整合・多重度と対象型・非循環・網羅マトリクス・遷移）はすべて定義体で、組込みは特権を持たない。判定・文面・配り方・文書 1 つの検証の組み立てはカーネル（workspace-core）にあり、アプリと CLI と変更セットの適用が同じ部品を使う。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `CheckLibrary` | semantic-core/schema/CheckDefinitions.kt | 関数のような定義・語彙の定義・基本の検査への展開（YAML を読んだままの値の上の置き換えだけ。検査の意味を知らない） |
| `ConstraintRule` / `ConstraintCheck` / `ConstraintForAll` / `CheckOp` / `ReportAt` / `ResponseDecl` / `RuleOrigin` | semantic-core/schema/Query.kt | 検査の宣言（パターン制約・判定・すべての要素について・値の条件・指摘の場所・対応・検査の出どころ） |
| `CheckerKind` / `CheckerArgs` / `CheckerRule` | semantic-core/schema/Checker.kt | 専用チェッカーを使う検査の宣言 |
| `ValueExpr` | semantic-core/schema/ValueExpr.kt | 値の式（[schema.md「値の式」](schema.md)） |
| `SemanticExpression` | semantic-core/semantic/Expression.kt | 共有評価部（値の読み出し・比較 `compare` / `test`・単項と四則・出現の選択 `select` / `preferDefining`・σ 候補 `sigmaCandidates`） |
| `SemanticQuery.checkFrom` / `forAllFrom` / `countMatchesFrom` | semantic-core/semantic/Query.kt | 判定の評価（値の式の評価・出どころ） |
| `SemanticRules` / `CheckOutcome` / `CheckReason` | semantic-core/semantic/CheckResults.kt | 検査 × 要素の結果を 4 つのどれかに決める 1 箇所 |
| `SemanticCheckers` / `SemanticFlow` | semantic-core/semantic/Checkers.kt・Flow.kt | 専用チェッカー（遷移検証の到達閉包）の実行 |
| `SemanticCheckSession` | semantic-core/semantic/CheckSession.kt | 検査の評価の覚え書き（同じ索引の上では 1 回だけ） |
| `SemanticGraphIntegrity` | semantic-core/semantic/GraphIntegrity.kt | 宣言のない関係名の判定 |
| `CheckCatalog` / `BuiltinDefinitionResources` | workspace-core/workspace/CheckCatalog.kt・資源 `builtin-definitions/` | 検査の定義の読み込みと組込み定義体（consistency・coverage・entities・flow・matrix・relations） |
| `WorkspaceChecks` / `CheckDelivery` / `CheckIssue` / `CheckResponse` / `IssueSource` / `IssueDelivery` / `WithheldCheck` / `RuleStatus` | workspace-core/validate/WorkspaceChecks.kt | 定義体の検査を評価して文書へ配る（`deliver`・`statuses`） |
| `CodedChecks` / `CodedDocumentChecks` | workspace-core/validate/ | コードで実装された検査の出どころと、ワークスペースの他の文書も見るコードの検査（ID パターンからの逸脱・重複所有・テンプレートの健全性）と、型の検査の文面と配り方 |
| `ElementRule` / `SemanticElementChecks` | semantic-core/schema/ElementRule.kt・semantic/ElementChecks.kt | 型の検査（オブジェクト型の要素ごとの検査）の宣言と評価（グラフを使わない。`SemanticValues` で要素を「いまの要素」として `where` / `check` を評価） |
| `DocumentValidator` / `DocumentValidation` | workspace-core/validate/DocumentValidator.kt | 文書 1 つの検証の全体の組み立て |
| `WorkspaceCheckRunner` | workspace-core/workspace/WorkspaceCheckRunner.kt | カーネル側の CLI の全文書の検証（`validateAll` / `deliverAll`） |
| `Validator` / `Issue`（＝`CheckIssue`）/ `IssueResponse`（＝`CheckResponse`） | validate/Validator.kt | root の窓口（索引とスキーマを渡して `DocumentValidator` を呼ぶ）。`validateDetailed` は保留も返す |
| `Readiness` | validate/Readiness.kt | 準備完了レポート（指摘をエンティティへ帰属し、エラー 0 で準備完了） |
| `Staging.newErrors` | agent/Staging.kt | 提出ゲート `no-new-errors`（出どころの `identity` で見分ける） |
| `SidePanel`（`IssueRow`） | ui/SidePanel.kt | 検証パネル。指摘のクリックは対応（`responses`）の選択メニュー |

## 定義層の 3 段

検査の書き方は 3 段で、上の段は検証の前に下の段へ展開される。

1. **基本の書き方**: スキーマの `constraints` に 1 件ずつ。プリミティブを直接書く（`match` ＋ `expect` / `count` / `check` / `forAll`、または `checker` ＋ `args` ＋ `messages`）か、定義を名前で使う（`use` ＋ `with`）。
2. **関数のような定義**（`functions`）: 名前・受け取る値（`params`）・中身（`checks`＝基本の検査のひな型）。渡された値をひな型の `$名前` へ差し込む（文字列がちょうど `$名前` なら値そのもの・文中なら文字列・`$$` は `$`・`$merge` は渡された object を混ぜ込む）。
3. **語彙の定義**（`vocabulary`）: どこに書けるか（`in`。スキーマ直下の `名前[]` や `relations[]`）と、書かれた内容からどの定義をどの値で使うか（`uses`。`when` / `unless`・`with` の値の指定＝そのままの値・`{from, default, map, format, join}`・`{schema: id}`）。書き方の形の最小の点検（`requires` / `removed` / `keys`）。

- 展開は `CheckLibrary`。差し込みは値の置き換えだけ（条件分岐・繰り返し・定義からの定義の呼び出しなし）。
- 定義の置き場は `kind: library`（どのスキーマ文書に書いてもワークスペース全体で使える）。組込み定義体は workspace-core の資源 `builtin-definitions/` で、`CheckCatalog` が全スキーマ文書の定義と合成してから各スキーマの検査を展開する。
- `SchemaDef.constraints` / `checkers` は展開済みの検査、`expandedChecks` は展開後の姿と由来。CLI `checks <workspace> [schema-id] [--definitions]` が展開の透明性を担う。
- 派生スキーマは導出元の語彙を自分のスキーマ ID で展開し直して引き継ぐ。専用チェッカーを使う検査は引き継がない。
- 利用者が定義した語彙のキーはメタスキーマの「知っている項目」に足される（`withVocabularyKeys`）。
- 組込みは特権を持たない。組込み定義体も利用者の定義も同じ `CheckLibrary` を通る（`CheckDefinitionTest` が利用者の定義だけで同じことができるのを固定）。
- 関係レジストリの語彙（`in: relations[]`）から展開された検査と、`kind: library` や組込み定義体に直接書いた `constraints` は特定のスキーマに属さず、どの文書の要素にも適用される。

## 検査の書き方（意味プリミティブ）

- **パターン制約**: `match`（＋ `where` / `also` / `limit`。[graph-query.md「クエリコア」](graph-query.md)）に `expect: exists / absent`・`count: {min, max}`（解＝bind 列の組の distinct 件数）・`check`・`forAll` のいずれか、`level`・`levelFrom`・`activeFrom`・`status`・`fixSite`・`message`・`reportAt`・`responses`。
- 意味は「起点束縛に一致する各エンティティについて、残りのパターンに解が存在すること／しないこと／件数が範囲内であること／解ごとに値の条件が成り立つこと」。起点は `kind: entity` のみ。
- **判定 `check`**: `{演算子: [値の式, 値の式]}` の配列（すべて成立が期待）。演算子は `eq` / `ne`・`lte` / `gte` / `lt` / `gt`・`oneOf`・`matches`・`startsWith` / `endsWith` / `contains`、`{not}` / `{or}`。評価は解ごとに出現割当て σ を反例量化し、条件を満たさない最初の組を反例として返す（決定的）。1 項目内の σ は一貫（同一束縛の両辺は同一出現）。値が読めない組は評価しない。反例は文面の列挙と `relatedIds` のジャンプになる。
- 順序の比較には `type`（number / date / time / datetime）を添えられ、省けば値を読んだ項目の型で比べる（`CheckTerm.kind`。`where` も同じ）。
- **すべての要素について `forAll`**: もう 1 つの集まり `node`・辿り始める束縛 `from`・連鎖 `steps`。集まりの要素ごとに連鎖を解き、解がなく候補の喪失も無かった要素が不足（`{count}` `{missing}`）。網羅マトリクス（`matrix`）はこれへ展開される語彙。
- **値の式と値の計算**: 読み出し・決まった値・`format`・`map`・`first`・`length` / `upper` / `lower` / `number`・日付の部分 `year` / `month` / `day`（数）/ `yearMonth`（文字。`ValueSemantics` の正準の形で読む）・四則演算（BigDecimal）・参照の本数 `refCount`（相手が見つからない参照も数える）・書かれた場所の数 `occurrenceCount`（スキーマ・ファイル・役割で絞る。`distinct: type` は型の種類の数）・集計 `sum` / `min` / `max` / `count`（配列を受け取る関数）。
  集合から配列へ `all`（同じ起点の全ての解にわたる形と、`from` の束縛を起点に固定した照合の形）・要素ごとの式 `each`。評価できないときは null（判定保留）。
  制約の `count`（解の組の件数の範囲）と `all` の件数は数え方の単位が違うので統合しない（[schema.md「値の式」](schema.md)）。
- **値を読む文書の指定 `in`**（値の読み出しと `edge`）、**参照の束縛**（edge の `bind`）、**指摘の場所 `reportAt`**（値が書かれた場所・`{ref}`＝参照の場所）。
- **文面のひな型 `message`**: `{id}` `{束縛名.id/type/title}` `{count}` `{missing}` `{path}` `{create.places}`。
- **エラー時の対応 `responses`**（閉じた一覧）: 移動 `jump`（`referenceSources`＝参照を書き足せそうな文書へ）・値の書き込み `apply`・文書を作る `create: detailDocument`。検証パネルのメニューが宣言の順に並べる（文面と対応の直交性）。
- **操作の起動 `action`** も対応の 1 つ: 起点の要素を受け手に、スキーマに定義した操作（アクションか型の操作）を開く。渡す値の文面は指摘と同じひな型で埋める（[actions.md](actions.md)）。
- **検査の状況 `status`**: 満たしている検査も値つきで返す（`ruleStatuses`）。右パネル・エンティティページ・ビューの状態列・エージェントの `related` が共用し、文言は `RuleStatus.text` の 1 箇所。保留は「判定保留（理由）」で充足に数えない。
- **直せる場所への案内 `fixSite`**: 書き足し先のスキーマの文書の検証へ、指摘が出ている ID の一覧を INFO で出す。
- **重大度**: `level`（warning / error）と、関係レジストリの強制スイッチのキー名 `levelFrom`。写像は `CoverageLevel.toSeverity()` の 1 箇所。
- **共有評価部**: 値どうしの比較・出現からの値の読み出し・式側の出現選択は `SemanticExpression` を通す。比較の 3 値（順序の種類として読めなければ判定保留。種類は項目の型から＝[schema.md「値の式」](schema.md)）、選択の定義出現優先（→ 索引順）を全機能で同じにするため。`where`（存在量化）と `check`（反例量化）は σ 候補の列挙を共有する。
- **項目の型を引くのはカーネル**: `WorkspaceChecks` が文書の場所に合うスキーマから `fieldAt`（文書とパス → 解決済みの項目）を組み立て、`SemanticCheckSession` → `SemanticRules` → `SemanticQuery` の評価へ渡す。semantic-core はスキーマの割当てを知らない。

## 専用チェッカー

- 複雑なアルゴリズムの検査は専用チェッカーとしてコードに残し、語彙への結び付け・文面・重大度・配送だけを定義体が宣言する。宣言は `checker` ＋ `args` ＋ `messages`（指摘の種類 → 文面。書かなかった種類は指摘しない）。
- いまあるのは遷移検証 `flow`（`SemanticFlow`）: エッジ文書の行（from 参照と to 参照が同じ行の直下に並ぶ組）を有向エッジとみなし、到達閉包（BFS）で到達不能（`start` の宣言と開始データがあるときだけ）・行き止まり（`terminal` で除外）・宣言外遷移（`usage` ⊆ 宣言エッジ）を検出する。相手の見つからない参照が 1 つでもあれば、解の不足を理由とする指摘を黙らせる（ワークスペース全体での判定保留）。
- 「何段でも辿る」だけで足りる検査（非循環など）は専用チェッカーにせず、クエリの `repeat` で YAML に書く。
- 入力はグラフと全参照だけの純粋な関数。1 回分（`SemanticCheckerRun`＝対象の要素・指摘・判定しなかった種類とその理由）を返す。

## 4 つの結果

検査 1 件を要素 1 つについて評価した結果は次のどれか 1 つ（`CheckOutcome`。CORE-CALCULUS の CALC-31）。

| 結果 | 意味 | 理由（`CheckReason`） |
|---|---|---|
| 違反 | 検査に反している。指摘が出るのはこれだけ | — |
| 充足 | 確かめて問題が無かった | — |
| 保留 | データの不備のために決められなかった | 辿る途中に相手の見つからない参照がある／比べる値を数値として読めない |
| 対象外 | この要素・この時点では確かめるものが無い | 検査の対象でない／フェーズ未到達／比べる値が書かれていない／比べる組が無い／調べる相手の集まりが空／チェッカーの前提が宣言されていない |

- どの結果になるかを決めるのは `SemanticRules.evaluate` だけ。判定保留の規則（解の不足を理由とする違反だけを保留にし、見つかった解が根拠になる違反＝あってはならない解・上限の超過・条件を満たさない組は保留にしない）もここ。
- 「比べる値が書かれていない」を対象外にしたのは、値が無いのがふつうの検査で本当に確かめられなかった検査が埋もれないため。
- 判定を保留した検査は指摘にせず、`Validator.validateDetailed` の `withheld`（検査・対象の要素・場所・理由）に残す。準備完了レポート・検査の状況・CLI `validate` に出る。
- 文面の組み立てと配り方は違反の根拠を文面へ写すだけで、判定を持たない。

## 指摘の出どころ

- **検査の出どころ**（`RuleOrigin`）: どの文書の（`owner`＝スキーマ id・組込み定義体は `(builtin)`・コードは `(code)`）・どの書き方から展開された（`at`。`constraints[2]` など）・何番目の検査か。検査の `id` は呼び名で、見分けには使わない。
- **指摘の出どころ**（`IssueSource`。CALC-32）: 検査の出どころ・専用チェッカーの指摘の種類・対象の要素・配り方（`IssueDelivery`: REPORT＝報告先／NOTICE＝他の出現への案内／FIX_SITE／VALUE／REF）・根拠に関わった要素（ID 昇順・全件）。`identity` は「同じ検査の・同じ要素についての・同じ配り方」を見分けるキーで、場所の番号と文面を含めない。
- **コードで実装された検査の出どころ**（`CodedChecks`）: 構造の検証・ID の重複・参照の解決・宣言のない関係名・ID パターンからの逸脱・重複所有・テンプレートの健全性・YAML のパースエラーにも同じ形で付ける。`kind` は指摘の種類（yaml-core の `StructuralIssueKind`・semantic-core の `SemanticIssueKind`）で、指摘を作る場所がデータとして付ける。
- コードの検査の対象の要素は「指摘の場所を含む最も内側の ID を持つ要素」、`within` はその中の場所（番号を除いたキーの並び）。要素の挿入で場所がずれても同じ指摘として見分けられる。
- **載せ替え先**: 提出ゲート `no-new-errors`（同じ `identity` のエラーが適用前にもあれば既存。根拠に関わる要素が増えたときだけ新しいエラー）・準備完了レポート（NOTICE は数えない。保留の件数と内訳）・検査の状況・CLI `validate --sources`。

## 評価は 1 回

- 同じ走査結果（同じ索引）の上では、検査 × 要素の結果・専用チェッカーの 1 回分・検査に紐づく計算（直せる場所へ案内する要素の一覧など）を `SemanticCheckSession` が 1 回だけ求めて覚える。寿命は索引の一式と同じ（`KernelIndexView.checkSession`）。
- 検査とチェッカーへ渡す値は、中身ではなく同じものかどうかで見分ける（スキーマの読み込みで 1 つずつ作られ、中身の比較は重い）。
- 「全体をまとめて評価する」形にしないのは、アプリは 1 文字打つたびに索引が変わるから。聞かれた検査 × 要素だけを評価して覚える形なら、文書 1 つの検証は軽く、全体を回したときは最初の 1 回で済む。
- 共有の前提（`Validator` の `shareEvaluations`。既定 false）: 覚えた結果を他の文書と共有してよいのは、root と loader の中身が索引と同じ内容のときだけ。ディスクの索引とディスクの内容（`Workspace.validate`・CLI・準備完了・提出ゲート）・ステージングを重ねた索引とその内容・適用後の姿を重ねた索引・未保存の編集を重ねた索引と未保存優先の loader（アプリの全体検証）。索引に重ねていない内容（提案のプレビュー）や開いている文書 1 つの検証は共有しない。値の場所へ出す検査（出現を固定した評価）は覚えない。
- グラフの逆引き表・起点の探索・宣言のない関係名の判定・「すべての要素について」の相手の集まりは、索引の一式につき 1 回だけ作る。全体の検証は文書数に比例する（[README.md「規模の計測」](README.md)）。

## 検査の定義の読み込み

- `CheckCatalog`（workspace-core）: 組込み定義体（`builtinLibrary` / `builtinChecks` / `builtinRules` / `builtinCheckers`）・ワークスペースの定義の一式の収集（`libraryOf`）・スキーマ 1 件の検査の展開とパース（`checksOf`。出どころつき）・展開後の検査のパース（`rulesOf`）・語彙のキーのメタスキーマへの合成（`withVocabularyKeys`）。
- root の `SchemaLoader` とカーネルの `WorkspaceReader` が共有する。カーネルは、スキーマ文書の生の値から先に定義の一式を集め、スキーマごとに検査を展開して `WorkspaceSchema.constraints` / `checkers` / `expandedChecks` に持つ。
- `KernelCheckLoadingTest` が、検査の中身と出どころ・実行した結果が root とカーネルで一致することを確かめる。

## 配り方

- `WorkspaceChecks.deliver(document, schema, root, localIds, localRefs)` が文書 1 つへ配る（`CheckDelivery`＝指摘 `CheckIssue` と判定を保留した検査）。入力はワークスペースの全スキーマ（`WorkspaceSchema` をそのまま）・関係レジストリと強制スイッチ・走査結果（ids / refs / グラフ）・評価の覚え書き・文書の相対パスと中身を返す関数。
- 配り方の規則: 報告先 REPORT（スキーマの検査は、定義出現がそのスキーマの文書にあればそこ、なければそのスキーマの文書の先頭の出現。特定のスキーマに属さない検査は定義出現か先頭の出現）・他の出現への案内 NOTICE（エラー級は同格の ERROR・警告級は INFO）・値の場所 VALUE・参照の場所 REF・直せる場所への案内 FIX_SITE。
- 文面のひな型の差し込み・エラー時の対応の導出（`CheckResponse`＝移動・値の書き込み・文書の作成）・専用チェッカーの文面もここに集まる。聞かれた文書の分だけを評価する。
- コードで実装された検査のうちワークスペースの他の文書も見るもの（ID パターンからの逸脱・重複所有・テンプレートの健全性＝`CodedDocumentChecks`）も同じ入口が返す。
- カーネルでは文書ID の `CheckResponse`、アプリでは `Path` の `IssueResponse`（別名）。

## 文書 1 つの検証

- `DocumentValidator.validate(document, schema, root, parseError, documentId, indexed, scan)` が文書 1 つの結果（指摘と保留）を返す。どの指摘にも出どころが付く。
- 指摘の並び: 構造の検証 → ID の重複 → 参照の解決 → エンティティ型の点検 → 宣言のない関係名 → 定義体の検査（報告先・値と参照の場所・他の出現への案内）→ テンプレートの健全性 → 直せる場所への案内。同じ指摘の重複は除く。
- **型の検査**（オブジェクト型の `constraints`。[schema.md「型の連鎖と狭める規則」](schema.md)）は要素ごと: `WorkspaceChecks.deliver` が `CodedDocumentChecks.elementChecks` → `SemanticElementChecks.run` で、その文書の要素を解決した型の検査で評価する。
- 型の検査の出どころは検査を書いたスキーマ（`RuleOrigin(schemaId, 書かれた場所)`・kind は `element`）、指摘は要素の場所（VALUE）、保留はその文書で集める。グラフ・索引を使わないので ID の無い要素でも評価でき、他の要素へ線を辿る検査は型には書けない（スキーマの `constraints` へ）。
- **file 型の値の検査**（`CodedDocumentChecks.fileIssues`）: 書き方（`FilePath`）・拡張子（`FieldDef.extensions`）・ワークスペースの外・存在。外と無いファイルの扱いは `WorkspaceLayout.files`（`.yde/workspace.yaml` の `files:`＝`FilePolicy`）で、存在は `fileExists`（ストアに聞く。CLI は読み込んだストア、アプリはワークスペースのルート）に聞く。`fileExists` が無ければ存在は見ない。
- 台帳の外の文書（`indexed = false`）では、相手の見つからない参照を情報レベルへ降格し、ワークスペースの他の文書も見る検査を行わない（[workspace.md「エディタ層と台帳層」](workspace.md)）。中身が空の文書は空のマップとして検証する。
- 構造の検証は導出した最小の構造のスキーマで行う（[schema.md「構造スキーマの導出」](schema.md)）。参照の解決はエンティティと文書の名前空間を分ける。ID の重複は同一文書内だけ。
- root の `Validator` は索引とスキーマを渡して呼ぶ窓口。カーネル側の CLI `workspace validate` は `WorkspaceCheckRunner.validateAll` で全文書を同じ部品で検証する（出力の区分＝構造・意味・検査は出どころで分ける）。変更セットの適用も適用後の姿の上で同じ組み立てを使う。`KernelCheckLoadingTest` が文書ごとの指摘（並び順まで）と保留がアプリとカーネルで一致することを確かめる。

## 準備完了レポート

- `Readiness`（CLI `readiness [--pending]`）が全文書の検証結果（INFO 除く）をエンティティ単位に帰属させ（指摘のパスを含む最も内側の ID 出現。どの出現にも含まれない指摘は文書レベル unassigned）、「帰属エラー 0＝準備完了」を型別・フェーズ別の集計つきで機械判定する。警告は判定に使わない。他の出現への案内は数えない。保留は要素ごとの件数と内訳。ディスク基準・ID 昇順の決定的出力。

## 規律

- 検査を足す・変えるときは、まず YAML の定義体で書けるかを考える。組込み定義体は `workspace-core/src/main/resources/builtin-definitions/`（consistency・coverage・entities・flow・matrix・relations）で、挙動を変えるならこの YAML と、書き方の形を持つメタスキーマ（`builtin-schemas/schema.yaml`・`relations.yaml`）を直す。
- 定義体の YAML の文面に「: 」を含めるときは引用符で囲む（パースエラーになる）。
- 組込み定義体だけが使える特別な機能を作らない。yaml-core に検査の部品を足さない（解釈に関わるものは semantic-core）。
- チェッカーを足すときは `CheckerKind`・渡す値の型・`SemanticCheckers`・メタスキーマを揃える。「何段でも辿る」だけで足りる検査は専用チェッカーにしない。
- どの結果になるかの判定は `SemanticRules` の 1 箇所。「指摘が出ない＝充足」という判定を書かない（保留を充足に数えてしまう）。
- 指摘を作るときは必ず出どころを付ける（定義体は `WorkspaceChecks`、コードは `CodedChecks`）。指摘の種類は文面から読み取らず、作る場所がデータとして付ける。同じ指摘かどうかは `IssueSource.identity` で見る。
- 検証の組み立て・定義体の検査の配り方や文面を root に書き足さない。カーネル側の CLI と結果が食い違う。
- 全体を回す経路から `Validator.validate` を呼ぶときは `shareEvaluations = true` を渡す。索引に重ねていない内容の検証では渡さない。
- 検査やクエリの評価の中で全要素・全参照を総なめする処理を「評価のたび」に置かない。
- 値どうしの比較・出現からの値の読み出し・出現選択は `SemanticExpression` を通す。`toDoubleOrNull` の比較や `firstOrNull { selector.matches }` を機能側へ書かない。
- 配り方そのものを足すときは `WorkspaceChecks.deliver` に 1 つ足す。判定式やメッセージ組立を配送先ごとに書かない。
- 台帳外の文書では判定保留の哲学（型未宣言・未解決参照には黙る）を踏襲する。

## 拡張ポイント

- **検査の追加**: まず定義体（`functions` / `vocabulary`）。書けないなら足りない部分を小さな意味プリミティブとして semantic-core に足す。複雑なアルゴリズムは専用チェッカー。設計メモにどの層に置いたかを書く。
- **値の条件・値の計算の追加**: `CheckOp`（演算子と文面の言葉）と `SemanticExpression.compare` / `test` に足すと `check` と `where` の両方で使える。値の計算は `ValueExpr`・`SemanticExpression`・`SemanticValues`。メタスキーマの説明と manual/ を対で更新する。
- **エラー時の対応の追加**: `ResponseDecl` と `CheckResponse` に種類を足し、`WorkspaceChecks` で導出し、検証パネルのメニューに載せる。
- **配り方の追加**: `IssueDelivery` と `WorkspaceChecks.deliver` に足す。
- **組込み定義体の追加**: `builtin-definitions/` に YAML を 1 つ足し、書き方の形をメタスキーマへ書く。
