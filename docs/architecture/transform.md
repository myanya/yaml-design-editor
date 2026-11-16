# 変換と決定論的射影

入力の YAML から別の形の YAML を組み立てる処理は、変換定義（選ぶ × 組み立てる × 規則を子に当てる）で書く。純粋で決定的、出力は保存しない。
値のレベルの決定論的な導出（ビューの値の式の列）は値の式の評価器（`SemanticValues`）の 1 実装で、`{パス}` の穴埋めは `Projection`。いずれも計算体系（CORE-CALCULUS）の Construct と Expression の実装。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `TransformDef` / `Template` | semantic-core/schema/TransformDef.kt | 変換定義の宣言とパース（`parse`・`ofTemplate`） |
| `SemanticTransform` / `TransformResult` / `TransformOrigin` | semantic-core/semantic/Transform.kt | 変換の評価器。組み立てた項と、出力の場所 → 入力の文書と場所 |
| `Projection` | schema/Projection.kt | `{パス}` プレースホルダのテンプレート置換（スコープ連鎖・1 段評価・解決トレース・穴の値の直し方）と値辞書 |
| `ViewCell.trace` / `ComputedTrace` | core/Views.kt | 計算セルの来歴（元値のテンプレート・プレースホルダごとの解決値と座標。値の式の列では集計の材料と、得られない理由） |
| `VirtualViews.valueCell` | semantic-core/semantic/VirtualViews.kt | 値の式の列の解決（行の出現の要素を「いまの要素」とする環境で `SemanticValues` を呼ぶ） |
| 組込みの変換定義 | workspace-core 資源 `builtin-transforms/` | `structural-schema.yaml`（構造のスキーマの導出） |

## 変換

**変換 ＝ 選ぶ × 組み立てる ×（入れ子には）規則を子に当てる**。入力を書き換えず、出力は新しい項（入力の部分木は写し。コメントと表記は持ち込まない）。

- **定義の形**: `id`・`description`・`params`（受け取る値の名前）・`from`（`{match, where, also, limit}`＝既存のクエリ。書かなければ渡された項 1 つが入力）・`build`（出力のひな型）・`rules`（名前を付けた規則 `{id, when, build}`。同じ名前の規則は上から順に条件を調べ、最初に合ったものを使う）。クエリで選ぶ変換は行ごとに `build` を組み立て、結果を配列に並べる。
- **ひな型**: `$` で始まるキーを持つマップだけが指示で、それ以外は書いたとおりに出力される。値が得られない指示は「無い」になり、マップのキーごと出力から落ちる（配列の要素なら飛ばす）。

  | 指示 | 意味 |
  |---|---|
  | `{$copy: パス}` | その場所の値を部分木ごと写す（`.` はいまの場所） |
  | `{$format: "…{パス}…", $at: パス, $with: {名前: ひな型}}` | 文字のひな型をその場所の値で埋める（`Projection`）。`$with` は計算した値を穴へ（場所の値より先。値の式の `format` の `with` と同じ） |
  | `{$map: ひな型, $using: {a: b}}` | スカラーの値を辞書で置き換える |
  | `{$first: [ひな型, …]}` | 最初に得られた値 |
  | `{$date: ひな型, $as: 形}` | 年月日をそろえる（`2021/8/21`・`20210821`・`2021年8月21日` → `yyyy-MM-dd`。`$as` は y・M・d と区切りの形。読めなければ「無い」。`SemanticExpression.readDate` / `formatDate`） |
  | `{$number: ひな型, $as: 形}` | 数として読む（桁区切り・通貨記号・全角・`▲` の負を許す。値の式の `number` と同じ `SemanticExpression.numberOf`。整数は YAML を読んだときと同じ Int / Long）。`$as` は DecimalFormat の形（`00`・`#,##0`）で、その形の文字にする（連番の 0 詰め） |
  | `{$trim: ひな型}` | 前後の空白を落とし、続く空白（全角も）を 1 つに（`SemanticExpression.squashSpaces`。空なら「無い」） |
  | `{$case: ひな型, $as: 書き方}` | 名前の書き方をそろえる（`schema.NameCase`。camel・pascal・snake・kebab・constant。語は英数字以外の文字と大文字の境目で分け、英語の文法は入れない） |
  | `{$value: 値の式}` | 値の式（`ValueExpr`。検査の `check`・ビューの列と同じ書き方）をいまの場所で計算する。いまの場所と、式が `of` で指す束縛の要素だけを読める合成の環境（仮の文書名）で `SemanticValues` を呼ぶ。グラフ・項目の型は無いので `all` / `refCount` / `occurrenceCount` は「無い」、順序の種類は `type` で宣言する。数の結果は数の項・配列は配列の項・それ以外は文字 |
  | `{$each: パス, $build: ひな型}` / `{$eachEntry: パス, $build: ひな型}` | 配列の要素ごと／マップの項目ごとに組み立てる（`$apply: 規則名` とも書ける。`$index`＝0 始まり / `$position`＝1 始まり / `$key`。`no` は YAML が真偽値に読むので名前にしない）。`$each` の源には `{$view: ID, $where}` も書け、ビューの行を 1 行ずつ場所にする（レポート。`Template.Each.rows`） |
  | `$bind: 名前`（繰り返しに添える） | 現在の要素を名前付きの場所として、その `$build` の中で `$of` / `of` から読む |
  | `$flatten: true`（`$each` に添える） | 各要素から作った配列を順に連結する。配列の入れ子を一段だけ外し、来歴の出力番号も移す |
  | `{$zip: {名前: パス, …}, $build: ひな型}` | 並んだ配列を同じ番号で 1 行（`{名前: その番号の要素}`）にまとめて組み立てる（`$apply` とも書ける。長さが違えば長い方に合わせ、足りない名前は行に無い）。行は `ZipRow` で、名前から先のパスは元の配列の場所へ進むので、出どころも元の要素を指す |
  | `{$apply: 規則名, $at: パス, $with: {名前: ひな型}}` | 名前を付けた規則をその場所に当てる。`$with` は規則へ渡す値（規則の `with` に名前を並べ、規則の中で `$of: 名前` で読む）。値は書いた場所で計算する: この形ならいまの場所、繰り返しの略記（`{$each: パス, $apply: 規則名, $with: …}`）なら繰り返しの外側（`Template.Apply.outer`） |
  | `{$concat: [ひな型, …]}` | 配列をつなげる |
  | `{$group: パスかひな型, $by: ひな型, $build: ひな型}` | 配列の要素を、`$by`（要素をいまの場所にして計算したキー）が同じものごとにまとめ、まとまり `{key, items}` をいまの場所にして組み立てる（`$apply` とも書ける。`$bind` でまとまりに名前）。キーは構造で比べ、順はキーが初めて出た順。キーが「無い」要素は落とす（`$by` に `$when` を書けば絞り込みを兼ねる）。まとめた後の場所は入力の座標を持たないので、出どころは記録しない |
  | `{$entries: ひな型}` | `{key, value}` の並びからマップを作る（キーを計算で決めるマップ）。同じキーは後の値で置き換え、位置は初めのところ。キーがスカラーでない・値が無い項目は飛ばす |
  | `{$when: 条件, $then: ひな型}`・マップに `$when` を添える | 条件に合うときだけ出す |

- 条件の `includes` / `includesAny` は、場所の配列の要素で比べる（クエリの `where`・検査の `check` と同じ `SemanticExpression.testArray`）。
- 呼び出し側が渡す名前の付いた場所（`TransformDef.places`。生成の `model` と `element`）も `$of` で読める。宣言していない名前は、クエリの束縛名と同じく定義の誤り。条件の比べる値には、決まった値・受け取った値のほかに、別の場所の値（`{$copy: パス, $of: 名前}`）を書ける。
- 場所の指定には `$of: 束縛名`（省略＝いまの場所）と `$in: {schemaId, fileGlob}`（どの文書の出現から読むか。定義した側を優先して 1 つ）を添えられる。文字列 `$名前` は受け取った値、`$$` は文字 `$`。条件はクエリの `where` と同じ書き方で、`path: $名前` は場所の値の代わりに受け取った値・繰り返しの番号を比べる（条件の `param`。例 `{path: $index, gt: 0}`）。値が得られず決められない条件は成り立たない。
- **必ず終わる**: 同じ規則を同じ場所に当てている最中にもう一度同じ規則を同じ場所に当てると定義エラー。規則は `$at`・`$each`・`$eachEntry`・`$zip` で入力の木の子へ向かって当てる。
- **出どころ**: `TransformResult` は組み立てた項と、出力の場所 → 入力の文書と場所の一覧を持つ。`$concat` でつなげた配列も、つなげた後の番号で入力の場所を指す。fromを使った場合はクエリ結果も保持し、消費側で打切り・未解決経路を開示できる。
- **繰り返しの局所名**: `$bind` は `$each` / `$eachEntry` の要素に付け、その中だけで有効。同名は内側が優先し、評価後に外側へ戻る。`$in` で別の出現は選べない。名前付きの `rules` は呼出し側の局所名を取り込まない。範囲外の名前の点検は `schema/TemplateBindings.kt` を変換と操作で共有する。
- **規則へ渡す値**: 入れ子をたどる規則が外側の事実（親のテーブル名・親のキーの並びなど）を要るときは、規則の `with` に受け取る名前を並べ、当てる側が `$with` で渡す。渡す名前と受け取る名前が合わない定義・同じ名前の規則で受け取る名前が違う定義は、読み込みの段階で誤り。規則の中で読めるのは、渡された値と宣言された場所（`model` など）だけ。
- **可変長の連結**: `$flatten` の既定はfalseで、従来の入れ子を保つ。trueでは空配列・値なしを飛ばし、配列以外の結果は誤りにする。原本座標は `$concat` と同じ `relocateOrigins` で移し、親のコピーも子のコピーも保持する。
- **定義の誤りは読み込みの段階で分かる**: 未知の指示・指示と出力のキーの混在・宣言されていない規則名と受け取る値・クエリで付けていない束縛名は `TransformDef.parse` がエラーにする。
- **既存の共有部品を使う**: クエリは `SemanticQuery.run`、値の条件は `SemanticExpression.test` / `isEmpty`、文字のひな型と辞書は `Projection`、出現の選び方は `SemanticExpression.preferDefining`。
- **使いどころ**: CLI `transform <workspace> <定義.yaml> [--input] [--param] [--origins]`（定義に `builtin:structural-schema` と書くと組込みの定義）・構造のスキーマの導出（[schema.md「構造スキーマの導出」](schema.md)）・スキーマの `scaffold`（新しい文書のひな型の中身）・skill の取り込みの `build`（表の行か JSON の要素を項にして当てる。行の番号は `run` の `loop` で `$index` / `$position` に。取り込み先と採番の接頭辞も同じひな型）と書き出しの `build`（文書ごとに JSON の値を組み立てる。番号の位置 `output.index` と空き `output.fill` も同じひな型。[agents.md「skill」](agents.md)）。
- 拡張と応用（繰り返しの源にクエリ・値の式をひな型の指示として・利用者の変換定義の置き場・Markdown への派生）は ROADMAP の変換のテーマ。

## 生成（業務モデルから文書へ）

`BusinessModel` は項目の有効な値に加え、共通型との差分 `overrides` と組込み型の共通の性質 `builtinType` を項として返す。
スキーマの項目と名前付きの型には、`FieldDeclaration` から明記した属性と原文の場所を `declarations` として写す。差分が空でも明記は失わない。子の宣言は `item`・`value`・`fields`・`cases` に保持し、エンティティの子はその側面を参照する。設計用の ID と写し方は変換定義が決める。
型の展開は `BusinessModelTraversal` にまとめ、型の解決・狭め方には既存の `SchemaTree` を使う。現在の経路に同じ名前付きの型が再び現れたら、`recursiveType` にモジュール名を含む参照を残し、利用箇所に書かれた子だけを進む。原文の子と解決済みの子を対で渡し、継承した子を新たな明記として展開し直さない。
同じ型を使う別の項目や、共通の基底型を継承する別の型は、それぞれ展開する。有限の入れ子を固定の深さで省略しない。エンティティの側面も最初の再帰先を記録し、項目の導出とデータの決まりの収集で同じ経路の判定を使う。これは型のグラフを参照付きの有限の項（Term）に写す処理で、型の意味や値の評価規則は追加しない。
`Generators.plan` は `dependsOn` の依存順に生成する。読み取り用の重ね合わせでは保存済みの生成物を隠し、明示した依存先とその先の依存先の今回の成功した出力だけを載せる。依存しない先行出力・自身の前回の出力・消えた生成物は入力に混ざらない。手書きの文書はそのまま読める。
`$view` は `VirtualViews` → `ReportRows` で、その新しい木から読む。依存先が失敗すれば後続を実行せず、計画に誤りがあれば取引の操作を空にする。
`input` があれば段を当てる前に `SemanticTransform.runAt` で入力を組み立てる。`Stages` は入力の適用済みの段が宣言順の先頭と一致することを確かめ、残りだけを当てる。循環・存在しない依存先・段の順の不一致は計画の誤りになる。

業務モデルを展開した項（`BusinessModel`）を入力に変換を当て、作った YAML を生成物の文書として書く（ADR-0031）。

| 部品 | ファイル | 責務 |
|---|---|---|
| `BusinessModel` | workspace-core/workspace/BusinessModel.kt | スキーマに分けて書いた業務モデルを 1 つの項に展開する（`entities`・`actions`・`states`・`lifecycles`・名前付きの型 `types`）。操作の効果は do・Lifecycle・自動の値を 1 列に |
| `BusinessModelTraversal` | workspace-core/workspace/BusinessModelTraversal.kt | 現在の経路の型参照と原文の子を保持し、再帰する型の共通部分を参照で閉じる |
| `BusinessModelViews` | workspace-core/workspace/BusinessModelViews.kt | 業務モデルの項のビューの側（`views`・`dashboards`）と、ダッシュボードからたどれる範囲（`reach`。ADR-0033） |
| `BusinessModelRules` | workspace-core/workspace/BusinessModelRules.kt | 業務モデルの項のデータの決まりの側（`constraints`。型の検査・スキーマの検査・関係レジストリ）と、操作が書くと破れうる決まり（操作の `checks`） |
| `Generators` / `GeneratorDef` / `GenerationPlan` | workspace-core/workspace/Generators.kt | 生成の定義（transforms ゾーン）の読み込み・計画（作る・作り直す・消す・変わらない）・セッションの 1 トランザクション・生成の系譜（`lineage`） |
| `TransformReads` | semantic-core/semantic/TransformReads.kt | 変換の定義が読む入力の場所（入力の足あと）を、定義を当てずに取り出す（名前の付いた入れ物からのパスのパターン） |
| `Stages` / `StageChangeDef` | workspace-core/workspace/Stages.kt | 段の項（業務モデルの項に、段の並びの順に規則と補正を当てる）・stages ゾーンの読み込み・段の項の頭の `stages` |
| `TermChanges` / `TermChangeDoc` / `TermChange` | semantic-core/semantic/TermChanges.kt | 項の変更（段の規則と補正）の読み取りと当て方（`each`・`where`・`set`・`add`・`after`・入れ子の `changes`・出どころ） |
| CLI `generate` | cli/commands/GenerateCommands.kt | 計画・検証・書き込み・最新かの検査・業務モデルの項の表示（引数を写すだけ） |

- **流れ**: `Generators.plan` → 定義の `scope` ごとに業務モデルの項を 1 回作る（スコープのスキーマだけから。ADR-0032）→ 定義に `stage` があれば、スコープと段の組ごとに段の項を 1 回作る（[段の項（段階的詳細化）](#段の項段階的詳細化)）→ 定義ごとに `each` の要素へ `SemanticTransform.runAt`（場所 `model` と `element` を渡す）→ `output` を `TextTemplate.render` で埋める → 見出しのコメントを付けて文字にし、保存済みの内容と比べる → `WorkspaceTransaction`（作成・置き換え・削除）。
- **規律**: 生成物は原本ではない（原本は業務モデルと生成の定義）。見出し（`Generators.marker`）で見分け、見出しの無い文書は消さない。書き込みはセッションの取引だけで、検証・保存は今までの経路。業務モデルの項に設計の規則（表や URL の決め方）を入れない（規則は段の規則か生成の定義に書く。項が持つのは、参照先の型・State の値の列挙型・採番・入れ子の外側・作る型・書く値の項目ごとの基底の種類・操作が書くと破れうるデータの決まりのような業務の事実だけ。決まりをどう確かめるか（Java か SQL か対象外か）は生成の定義が決める）。定義の種類（`yde-*`）を詳細化する文書のスキーマは、業務のエンティティ型として数えない。

## 段の項（段階的詳細化）

業務モデルの項に、段の並び（`.yde/workspace.yaml` の `stages:`）の順に、その段の規則と補正を当てたものが段の項（ADR-0034）。
生成の定義は `stage` で段を選び、その段の項を `model` として読む。形は業務モデルの項と同じなので、段を変えても生成の定義の書き方は変わらない。

- **流れ**: `Stages.term` → `DefinitionStages.upTo(段)` で最初の段からの並び → 段ごとに、stages ゾーンの文書のうちその段のものを、規則（`kind: rule`）→ 補正（`kind: patch`）の順に（どちらも置き場の名前の順）`TermChanges.apply` で当てる → 項の頭に `stages`（当てた段と、段ごとの規則・補正の一覧）を足す。
- **当て方**（`TermChanges`）: 変更ごとに、いまの場所から `each`（`[*]` で入れ子の並びにも広がる。`PathPattern.expand`）の並びの要素を選び、`where`（変換の `$when` と同じ条件）に合うものへ、置き換え（`set`）→ 中へ足す（`add`）→ 後ろへ足す（`after`）→ 中の変更（`changes`）の順に当てる。値は変換のひな型（`SemanticTransform.runAt`）で、いまの場所は選んだ要素、`$of: model` は文書を当てる前の項、`$of: <bind>` は外側の変更で選んだ要素。
- **出どころ**: 足した object に `origin`（`{stage, stageLabel, by, label, kind}`）を付け、置き換えた場所は要素の `origins` に `{path, …}` を足す。業務モデルから来たものには付かない。
- **規律**: 段の項を作る計算は決定的で、渡した項を変えない（写しに当てる）。業務モデルの項には段の規則の結果を混ぜない（段を書かない生成の定義は、今までどおり業務モデルの項を読む）。補正は当たる要素が無い変更を誤りにし、規則は誤りにしない（全体の方針は、当たる要素が無いワークスペースでも書いてよい）。段の宣言と規則・補正の誤りは、その段を読む生成の誤りに出す。

## 変更候補の合成

`semantic.TermComposition` は、Query・Construct で選択・組み立てた項と変更候補を受け取る。変換の `$compose` から `TermCompositionFormat` を通して呼び出す。既存の `Stages` や補正ビューの上書き順を置き換えたものではない。

`$compose` の引数はひな型で、合成命令の項（`derived`・`additions`・`changes`）を組み立てる。設計用の ID、保存形式から命令への対応付け、結果の文書の組み立ては YAML に置く。命令のパーサーは形だけを検査し、候補の値の意味を決めない。書き方は [generate.md「変更候補を合成する」](../manual/generate.md#変更候補を合成する)。

- **入力**: 導出した項・追加する項・変更候補。生成中の対象名と属性の場所で選び、通常の ID 索引や前回の結果は読まない。項の同名キーを禁じる範囲も呼び出し側が渡す。
- **候補の採用**: 規則の既定値より個別指定を優先し、同じ優先度の異なる値は競合。同じ値は一つにまとめ、採用した候補・採用しなかった候補の値と原文の座標を残す。比較は YAML の構造と値で行い、文字を数値へ読み替えない。
- **失敗**: 対象消失・ID や同じ範囲のキーの重複・親子の重なる変更・存在しない入れ物への変更を指摘する。一つでも誤りがあれば合成結果の項は返さず、候補と理由だけを返す。キーの変更による重複も検査する。
- **反映**: 採用候補を `YamlOperation.Replace` に落とし、`YamlEdit.applyBatch` で所有する写しに反映する。入力の木と根拠は変更しない。保存経路は持たない。
- **確定との境界**: 型の狭め方は `SchemaTree`、型の指定の誤りは既存の定義検査で扱う。成立条件・参照・未解決の検査と保存用の組み立ては、この候補の合成だけでは行わない。

Core Calculus では Construct が組み立てた Term の候補を、Rewrite の競合検査を通して型付き操作へ落とす。`$compose` はその接続であり、式や条件の評価器を追加しない。組み替え前の `TransformOrigin` は出力の番号を指さないため取り除き、明示した `source` と採用結果 `decisions` を返す。誤りは変換の失敗となり、生成計画は後続を止め、取引の操作を返さない。

## レポート（YAML から文字へ）

変換で値の一式を作り、文字のひな形にはめ込む 2 段（以前の判断どおり）。集計はビューに任せ、レポートに評価器を足さない。

| 部品 | ファイル | 責務 |
|---|---|---|
| `Template.ViewRows` | semantic-core/schema/TransformDef.kt | `{$view: ID, $where, $one}`。行は呼び出し側が渡し、条件は行の上で `holds`。`$each` の源にもなる（行ごとに組み立てる。出どころの無い場所なので、フローの編集の経路にはならない） |
| `ReportRows` | semantic-core/semantic/ReportRows.kt | 解決した行 → 項（`{id, title, key, cells: [{column, value}], values}`） |
| `TextTemplate` | semantic-core/semantic/TextTemplate.kt | 文字のひな形（穴 `{パス}` / `{パス:形}`・表のセル `{パス:cell}`・`{#each}`〔`index`・`position`・並びの区切りの `more`〕・`{#if}`・`{{`）。タグだけの行は消す。埋まらない穴は名前を返す |
| `ReportDef` / `ReportDefinitions` | workspace-core/workspace/ReportDefinitions.kt | 定義のパース（`params` と `build` と `rules` は `TransformDef`。`template` は同じ場所の相対パス、`output` はワークスペース相対で YAML 不可） |
| `core/Reports` | core/Reports.kt | `.yde/reports` の読み込み（ひな形の点検も）・実行（`$view` は `Views.resolve` の行）・派生物の書き込み |
| `ReportPane` | ui/view/ReportPane.kt | ビュータブの一覧から選ぶ。受け取る値の欄・表示（`MarkdownView`）・保存 |

- **流れ**: `Reports.run` → `SemanticTransform.run(def.transform, 空のマップ, params, views)` → `TextTemplate.render(ひな形, 項)` → 文字と埋まらない穴。`output` も同じひな形で埋める。
- **規律**: 出力は派生物で、YAML と `.yde/` の下には書かない（`Reports.write` が拒む）。ひな形にコードや計算を足さない（並べ替え・集計はビューか `build`）。`$view` の行に出どころは持たせない（解決した行は派生）。レポートの定義は REPORTS ゾーン（メタスキーマ `builtin-report`。エージェントの提出ゲートも同じパース）。
- **文字列の出力形式**: `TextTemplate` の `sql-string` は SQL の文字列定数として引用符を二重にして囲むだけ。型を値の見た目から決めず、数値・真偽値・日付などの出し分けは定義の側で決める（型対応表 → ビュー → レポートの `build`）。空文字や false を持つ初期値は、値の有無と分けた object に組み立ててから `{#if}` に渡す。

## 計算列と決定論的射影

段階的詳細化の機械的に置換可能な領域（例: 値型の maxLength → `varchar(100)`）を、ビューの値の式の列（`value`）で参照時に導出する。専用の導出の語彙は無く、「クエリ＋値の式」（CORE-CALCULUS「Expression Calculus」）の形で書く。

- **穴埋めの評価器は `Projection` に一元化**: `{パス}` の穴埋めと値辞書は純ロジックの単一実装。消費者（値の式の `format`・スキャフォールド・テンプレートの健全性）はこれを共有する。任意式・コード実行は導入しない。
- **1 段評価**: 穴が参照できるのは原本に書かれた値だけ。計算値の上の計算はしない（依存グラフ・評価順序・循環検出を持つスプレッドシートエンジンにしない）。
- **参照を辿るのは `via` で 1 段**: 読み出しと入れ物の `via` は、行の要素に書かれた参照の線を辿る（検査の `edge` と同じ突き合わせ。ref 型でない項目は辿らない）。参照がちょうど 1 本のときだけ相手が決まる。相手の出現は `in` で選ぶ（定義出現優先 `SemanticExpression.select`）。
- **値の式の列は読み取り専用の派生列**: 列の `value`（`expression: true` の項目）に値の式を書き、行の出現（列の `schemaId` / `fileGlob`）の要素を「いまの要素」として評価する（[schema.md「値の式」](schema.md)）。`editable` とは排他。
  - ひな型の展開は `format`（ひな型を `in` / `via` で読み、`scopes` の入れ物 → いまの要素の順で穴を埋める）、辞書は `map`、既定値は `first`、配列の集計は `[*]`（`type: date` で日付の順序）。
  - 集合から配列へは `all`（行の要素を起点に固定した照合の解の配列。`where` で絞り、`path` で各要素の値を読む）。`count` / `sum` などの関数に渡せば、行＝カテゴリ・列＝状態ごとの件数や、月×費目の金額のような集計表を列ごとに書ける。来歴は集めた要素。
  - セルの座標は値の出どころ（ひな型の場所・読み出した値・最小と最大で選ばれた要素。合計は座標なし）、来歴は全ての材料（穴ごと・要素ごと）。
  - 得られなければ値なしで、理由（埋まらない穴・書かれていない値・辿れない参照）を来歴の元値に残す。表示で気づける。
  - クエリ行では束縛（`of`）も読め、`all` の `of` の形は行を 1 つの解と見なす。列側に評価を書かない。
- **未保存編集に追随**: 計算セルの値はビュー解決時に評価され、値の読み取りは loader（docCache 優先）が担う。グリッドは `AppState.editVersion` を購読して再解決する。
- **来歴はセルの一級情報**: 計算セルは評価結果に加えて元値と入力ごとの解決値・座標（`ViewCell.trace`）を持つ。UI のクリックは導出の説明と行き先の選択（元の値・各入力値）をポップアップで提示する。`read_view` / CLI にも同じ来歴を出す。
- 整合検証（`consistency`）は `expected`（`lookup` / `source` / `path` / `values` / `map` / `default`）を、組込み定義体がクエリ＋値の式の `check` へ展開する（[checks.md](checks.md)）。ビューの列と同じ評価器なので、見えている導出値と検証される期待値は一致する。テンプレートの解決不能プレースホルダは、そのテンプレートを書いた文書の座標に WARNING（テンプレートの健全性）。
- 古い `computed`（`lookup` / `source` / `path` / `values` / `map` / `default`）は定義エラーにし、同じ意味の `value` の書き方を文面で案内する（`ViewDefinitions`）。

## 規律

入れ子の配列の射影はConstructで行う。選択は入力中の場所と既存の繰り返し、値の導出と条件は既存のExpression・Predicateを使う。
局所名と可変長の連結はQueryのID選択やAggregateの集計とは異なる組み立ての演算で、検査語彙は増やさない。
順序は入力順で決定的。名前の衝突は局所的な隠蔽、値なしは省略、配列でない連結は誤りとし、原本の書込経路は持たない。

- 入力の YAML から別の形の YAML を組み立てる処理は、まず変換定義で書けるかを考える。組み立ての Kotlin を機能ごとに書き足さない。
- 選ぶのは既存のクエリ。ID の無い場所までクエリを広げない（選んだものの中は `$each` とパスで辿る）。
- 入れ子には `$apply` で規則を子に当てる。同じ規則を同じ場所へ当て直す定義はエラー。外側の事実は `$with` で渡し、規則から外側の繰り返しの名前を読めるようにはしない。
- 出力のキーや値を `$` で始めるときは `$$` と書く。
- 変換の出力はワークスペースへ保存しない。原本へ書くなら変更セットと承認を通す。
- 消費側に評価ロジックを書かない（`Display` / `AiHttp` と同じ流儀）。

## 拡張ポイント

- **ひな型の指示の追加**: `Template` のパースと `SemanticTransform` の評価に足す。出どころを結果に持たせる。
- **値の式の列で新しい計算が要るとき**: 値の式の語彙（[schema.md「値の式」](schema.md)）に足す。列や `VirtualViews` に評価を書かない。
- **組込みの変換定義の追加**: `builtin-transforms/` に YAML を足し、`BuiltinTransforms` から引けるようにする。
