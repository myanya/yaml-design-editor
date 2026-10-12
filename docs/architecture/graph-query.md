# エンティティグラフとクエリ

情報管理を「物理層＝文書」と「論理層＝エンティティグラフ」の 2 層で捉える。グラフはノード＝論理 ID と文書、エッジ＝型付き参照で、常に走査結果から導出する（永続ストアを持たない）。
宣言（エンティティ型・関係レジストリ）で一級化し、照会はパターン照合の単一エンジン（クエリコア）に閉じる。読み書きの面はエンティティ合成の上に載せる。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `EntityDecl` / `EntityRole` / `entityFor` | semantic-core/schema/Relations.kt | スキーマの `entity:` 宣言（型・`idPattern` / `idType`・`defines`・`aspect` / `owns`。ID の判定は `matchesId`） |
| `RelationDef` / `Relations` / `EntityTypeDef` / `AspectDecl` / `EnforcementDef` | semantic-core/schema/Relations.kt | 関係レジストリ（`kind: relations`）の宣言。`labelOf` / `inverseLabelOf`・`ownedFieldKeys` |
| `SemanticEntityGraph`（root の `EntityGraph` は別名） | semantic-core/semantic/ | 走査結果から導出する読み取り専用のグラフ。エンティティ表・隣接（`ownRefs` / `outgoingRefs` / 入次）・文書ノード表・入れ子（`childIds` / `parentIds` / `topLevelIds`）・保有の線（`holdings` / `holdingsOf` / `holdersOf` / `holdingsIn`）・参照の逆引き表（`refIndexFor`） |
| `SemanticHolding` | semantic-core/semantic/Graph.kt | 保有の線 1 本（親の出現・子の出現・名前＝子を書く項目の `relation` かキー名・宣言の有無）。走査が `ScannedId.holderKey / holderRelation` に写した事実から作る |
| `QuerySpec` / `QueryNodeStep` / `QueryEdgeStep` / `QueryWhere` | semantic-core/schema/Query.kt | クエリの宣言モデルとパース（1 実装をビュー・foreach・制約・変換・CLI が共用） |
| `SemanticQuery` | semantic-core/semantic/Query.kt | パターン照合エンジン（`run`・`neighborIds`・`reach`・`matchesFrom` / `countMatchesFrom` / `checkFrom` / `forAllFrom`） |
| `Query` | index/Query.kt | 索引と loader を渡してカーネルの評価器を呼ぶ窓口 |
| `SemanticGraphIntegrity` | semantic-core/semantic/GraphIntegrity.kt | 宣言のない関係名の判定（走査結果の一式につき 1 回） |
| `SemanticRefactor` | semantic-core/semantic/Refactor.kt | エンティティ操作（rename / merge / delete）と文書移動の計画 |
| `Aspects` | core/Aspects.kt | エンティティ合成（`compose` → `EntityComposite`。`ownerSection` は書き先解決） |
| `Traverse` | core/Traverse.kt | 経路パターンで多段に辿る評価器（`neighborIds` の上の emit レンダラ） |
| `IdCrossView` / `GraphLensView` | ui/IdCrossView.kt・ui/GraphLensView.kt | エンティティページとグラフレンズ（配置は ui/GraphLayouts.kt） |
| `LensGraphs` | ui/LensGraph.kt | レンズの節点・辺・箱の入れ子を作る純ロジック（一体＝箱はエンティティ・側面で分ける＝箱は出現。絞り込み・起点の近傍・畳みと辺の付け替え） |

## 融合の原則

1. **記述は片側・閲覧は両側**: エッジは片側の文書に参照として書く（二重記帳しない）。読む面は関係レジストリの逆方向ラベルで両方向から対称に見せる。
2. **グラフは常にファイルから導出**: 永続ストアやグラフ DB を導入しない。索引の再構築でいつでも復元できることが Git 併用と原本主義の生命線。
3. **エンティティと文書は別種のノード**: 文書ノードの識別は `_document.id`（なければ相対パス）、包含は書かずに導出、文書ノードのプロパティは `_document` に限る。
4. **エッジ属性と具体化の使い分け**: 属性付き参照は「それ自身が ID を持たない注記」。関係自体を参照・詳細化したくなったら 1:N 具体化（新 ID ＋ 逆リンク）を使う。
5. **照会は宣言に閉じる**: パターン照合の語彙は決定的に保ち、集約（件数を除く）・任意深さの再帰・任意式は導入しない。

## エンティティ型と関係レジストリ

- エンティティ型の決め方は 2 段。要素の object（名前付きの型でも、その場の object でも）の `entity`（`{type, defines, aspect, owns}`。オブジェクト型。[schema.md「型の連鎖と狭める規則」](schema.md)）があればそれ。
- 無ければスキーマ文書ルートの `entity:`（単一 object または複数型のリスト＝`idPattern`（glob）か `idType`（ID の型の名前。型の形式で判別）で判別。`{type, idPattern, idType, defines, aspect, owns}`）。宣言のないスキーマは挙動不変。
- `idType` の解決は `WorkspaceSchema.entities`（木の定義の目録で `EntityDecl.resolvedIn`。[schema.md「型の連鎖と狭める規則」](schema.md)）。パーサ（`SemanticDeclParser.parseEntities`）は名前を写すだけで、semantic-core の判定（`matchesId` / `entityFor`）は解決済みの形式を見る。
- 関係レジストリは `_schemas` の `kind: relations` 文書。`relations:` に `{name, label, inverseLabel, source, target, cardinality, acyclic, level, description}` を列挙する。
- 同じ文書の `entities:` に型の表示名とアスペクト順序（`{type, label, description, aspects: [{name, label}]}`）、`enforcement:` に強制スイッチ（`undeclaredRelation` / `definingUniqueness` を warning / error。未宣言は非破壊の既定）。
- 型の表示名の既定はオブジェクト型の `label`（`WorkspaceReader.objectEntityTypes` が、レジストリに宣言の無い型だけを object の `entity` から足す。レジストリの宣言が常に勝つ）。
- 集約は `SchemaDocuments`（重複 name・重複 type・重複キーはロードエラー）→ `Workspace.relations` / `entityTypes` / `enforcement` → AppState ミラー。
- 索引への反映は走査時。`DocScanner` が ID 検出時に型と役割（定義／詳細化）を `IdEntry.entityType` / `role` へ付与し、`IdIndex.resolve` は定義出現を優先する。
- レジストリの表示への消費: バックリンクを表示する全面（関係タブ・エンティティページ・ビューの backlinks 列・文書ビュー・エージェントの `related`・グラフ・bundle）は relation 名の代わりに `label`（参照方向）／`inverseLabel`（被参照方向）を表示する。未宣言は名前のまま。表示は解決の意味論に関与させない。
- レジストリの検査への消費: 対象型・多重度・非循環は組込み定義体が関係の宣言から展開する検査（[checks.md](checks.md)）。コードに残るのは宣言のない関係名の判定（`SemanticGraphIntegrity`）だけ。
- 保有の線: ID を持つ要素の中に ID を持つ要素が書かれていれば、外側が内側を**保有**する（子から見れば**所属**。入れ子の線と同じ事実で、文書には何も書かない）。名前は、子を書く項目（array / object / map）に宣言した `relation`、無ければその項目のキー名。
- 保有の線の宣言: 関係レジストリには参照の関係と同じ `relations:` に書き、label / inverseLabel・source / target・cardinality がそのまま効く。宣言のない関係名の検査（`undeclaredRelation`）とスキーマの点検は項目に書いた名前だけを見る（キー名の既定は宣言ではない）。`relation` を書ける項目は ref と array / object / map（定義の点検）。
- 属性付き参照: `type: ref` のフィールドに `fields:` があるときだけ `{to: <ID>, <属性>...}` を許可する。`to` が参照値で、`RefEntry.path` は to 値の座標（ジャンプ・パッチ・バックリンクの意味を保つ）。属性は走査境界で、`RefEntry.attributes` として索引に載り、表示は括弧注記に閉じる。検証は to の解決 ＋ 属性の object 検証。
- 文書参照: ref フィールドの `target: document`（既定 entity。値から推測しない）で文書ノードへの参照にする。`RefEntry.targetKind` を走査時に付与し、解決は `graph.documents` に対して行う。エンティティ参照の解決・バックリンク・カバレッジには混ぜない。
- `infer-relations`（CLI）は索引の全参照から relation ごとの viaKey・参照元／先の型を集計してレジストリのドラフトを生成する。生成物は必ず `parseSchemaText` → `validateDefinition` を通る形。

## 導出グラフ

- `SemanticEntityGraph` はセッションの意味解析が作り、索引がそのまま使う（作り直さない）。エンティティ表（id → 型・定義出現・出現一覧・タイトル）・隣接（出次と入次）・文書ノード表（documentId → file・contains）・入れ子の関係（同じ文書の中の ID 出現のパスの包含）・保有の線（入れ子の関係 1 本ごとに親の出現・子の出現・名前。`holdings` は子の ID → 文書 → パスの順で決定的）。
- 参照の逆引き表（被参照・文書ごとの参照・行ごとの参照）は同じ参照の並びにつき 1 回だけ作る（`refIndexFor`）。「すべての要素について」の相手の集まりも覚える（`entityIdsMatching`）。
- ディスク・オーバーレイ・ステージングの各索引が自分のグラフを持つ（索引の二層を崩さない）。

## 線の意味

- 参照を書いた要素は 1 つ: 参照の線は、向きによらず「その参照を含む最も内側の ID を持つ要素」が書いたものとして辿る。`dir: out` はその要素が直接書いた参照だけ（`ownRefs`）、`dir: in` の相手はその参照を直接書いた要素（`sourceEntryOf`）。値の式 `refCount` も同じ。
- 入れ子の線（`edge` の `contains: true`）: 参照ではなく要素の入れ子を辿る。`dir: out` は直下に入れ子になった ID を持つ要素、`dir: in` は外側の要素。文書ノードとの間は、その文書にいちばん外側で書かれた要素。`repeat` と組み合わせられる。参照を通らないため `relation` / `via` / `in` / `bind` / `row` とは一緒に書けない。「配下のどの要素が書いた参照でも辿る」は `contains` を `repeat: {min: 0}` で辿ってから参照の線、と部品の組合せで書く。
- 名前を書いた線（`relation`）は、参照に加えて、その名前の**保有の線**も辿る。`dir: out` は保有する要素（子）、`dir: in` は所属先（親）。文書ノードとの間は参照と同じ捉え方（out は「その文書に書かれた保有」、in は「その保有が書かれた文書」）。名前を書かない線は今までどおり参照だけ（既存のクエリの意味を変えない）。値の式 `refCount` と `Traverse`（`neighborIds`）も同じ。
- 保有の線の束縛: `bind` と `reportAt` のために、保有の線は参照の形（相手＝子・場所＝子の要素・キー＝子を書く項目）に写す（`SemanticQuery.holdingRef`）。参照の場所へ配る検査（`WorkspaceChecks.refIssues`）は、この文書の参照の相手に加えて、この文書の中に入れ子で書かれた要素も起点にする。
- `via` は場所の末尾のキーの並び（`QueryEdgeStep.viaMatches`）。1 語ならキー名、複数語なら参照が書かれた場所のキー（配列の番号を除く）の末尾と比べる。保有の線では子を書く項目のキーと子の要素のパスで比べる。
- 何段でも辿る線（`repeat: true` / `{min: 0}`）は、その線をくり返して辿り着ける要素の集まりを幅優先で求める（`reach`。一度調べた要素は二度調べない・隣は ID 昇順・最短の道すじを覚える→文面の `{path}`）。出発した要素へ戻る循環があれば出発した要素も入る（`node: {same}` で循環を調べられる）。
- 行を線とみなす指定（`row`）は、同じ行の直下の 2 つの参照を 1 本の線として辿る（遷移の表）。
- 読むための機能（文脈の開示 `follow: out`・エンティティページの関係の一覧）は入れ子の要素が書いた参照も見せる（`outgoingRefs`）。判定には使わない。

## クエリコア

パースは `QuerySpec.parse` の 1 実装で、ビュー `rows: match`・foreach `source: query`・スキーマ `constraints:`・変換 `from`・値の式の `all`（起点を固定した照合）・CLI `query` が共用する。評価は `SemanticQuery`。

```yaml
match:                                   # node で始まり node で終わる node / edge の交互連鎖
  - node: { entity: screen, bind: scr }  # kind: entity（既定）/ document、entity（型。並べればどれか）、schema、id、bind、typed、not
  - edge: { relation: uses, dir: out }   # dir: out（既定）/ in。relation・via・in・bind・contains・repeat・row
  - node: { entity: usecase, bind: uc }
also:                                    # 束縛済みノードからの分岐（1 段のみ・内部結合）
  from: scr
  steps: [ { edge: { relation: belongs } }, { node: { entity: domain, bind: dom } } ]
where:                                   # プロパティ述語。値の条件と not / or
  - { of: scr, path: status, equals: approved }
limit: 100
```

- **結果は束縛の表**: 各行＝束縛名 → ノード（`QueryNodeRef`: kind・id・タイトル・型・座標）。エンティティの座標は定義出現（なければ先頭出現）、文書ノードはファイル＋空パス。行は束縛値の組で重複除去する。束縛した参照（edge の `bind`）は解の同一性に加わる。
- **文書ノード**（`kind: document`）: ノードは `graph.documents`。エッジの端の解決は次の node の kind が決める。文書ノードの out エッジは「その文書に書かれた全参照」。
- **プロパティ述語**: エンティティは「いずれかの出現の該当パスのスカラー」（多段定義の OR）。文書ノードは `_document` 配下。値の条件は `equals` / `notEquals` / `exists` / `empty` / `oneOf` / `matches` / `startsWith` / `endsWith` / `contains` / 大小、`{not}` / `{or}`（入れ子は 1 段・3 値）。値の読み取りは loader 注入。
- **ノード一致 `same`**: `node: {same: 束縛名}` は先行して束縛済みのノードそのものへの到達を要求する（他のキーと同居不可・先頭には書けない・宣言順で先行する束縛のみ）。「2 経路が同一ノードで合流すること」の照合で、値の比較ではなくノードの同一性で判定する（ADR-0008）。評価器では「同じノードを二度通らない」規則の意図的な例外。
- **決定性**: 起点候補は ID 昇順・エッジ展開は索引の安定順・行の並びは束縛値の辞書式比較・`limit` 超過は決定的打切り。1 本の照合経路の中で同じノードを二度通らない。未解決参照・型未宣言のエンティティは黙って照合から外す（判定保留）。
- **やらないこと**: 集約は件数のみ・任意深さの再帰パス（`*`）なし（`repeat` は集まりの計算で、経路の列挙ではない）・任意式なし・外部結合なし・分岐は `also` の 1 段まで。語彙の解禁は実例の需要で段階的に判断する。
- 消費面: トラバース（`Traverse` は `neighborIds` の上の emit レンダラ）・ビューのクエリ行（[views.md](views.md)）・foreach の `source: query`・パターン制約と判定（[checks.md「検査の書き方」](checks.md)）・変換の `from`（[transform.md](transform.md)）・値の式の `all`（起点を固定した `matchesFrom`。[schema.md「値の式」](schema.md)）・CLI `query`。

## エンティティ操作

- rename / merge / delete を 1 つの計画（`RefactoringPlan`＝文書ごとの型付き操作列）へ束ね、`session.planRefactoring` が作り `session.applyRefactoring` が 1 トランザクション（Undo 1 回）で適用する。計画は working 状態＝見たまま基準。
- rename(old, new): 全出現の ID フィールドと全参照（スカラー／属性付き参照の `to`）を書き換える。new が既存 ID と衝突したら拒否、`idPattern` / `idType` の逸脱は警告。
- merge(from, into): from への全参照を into へ張り替え、from の出現は「into の ID へ書き換えて詳細化として残す」か「要素ごと削除」。文書ルート出現の削除は拒否。
- delete(id): 入次数が 0 でなければ参照元一覧つきで拒否（黙って壊さない。削除する出現の中に書かれた参照はそこごと消えるので数えない）。
- delete の保有する要素: 削除する出現の中に入れ子で書かれた要素は一緒に消える。定義出現か全部の出現が消える要素と、それへの外からの参照（壊れる参照）を注意事項に並べ、計画は止めない（続けるか止めるかは人が決める）。詳細化の出現だけが消える要素は、その側面が消えることを注意事項にする。
- 文書の移動（`RefactoringRequest.MoveDocument`）はパス ID の文書参照の書き換えと物理移動を 1 計画にする。`_document.id` 宣言済みなら参照は安定。ビュー定義の対象から外れる警告は `RefactoringImpact`（[workspace.md](workspace.md)）。
- エージェントツールとしては公開しない（`apply_patch` で実現可能。ツール増はコンテキスト税）。入口は CLI `workspace refactor`・コマンドパレット・エンティティページの操作メニュー。

## エンティティページ

- エンティティ全体像の合成は `Aspects.compose` の純ロジックに一元化し、エンティティページ・`read_entity`・CLI `entity show` が同じ `EntityComposite` を消費する（人間と AI が同じ 1 枚を見る）。
- 出現の並びは「定義出現 → レジストリのアスペクト宣言順 → ファイルパス順 → 要素パス → アスペクト名」で決定的。値の読み取りは loader 注入。スキーマなし・object 解決不可の出現は `unassigned` へ落とす。
- 重複所有は隠さず注記する（`AspectField.duplicateOf`）。是正は検証（[checks.md](checks.md) の重複所有）が促す。
- 所有フィールドの編集は原本座標への `Slot` ルーティング（`ObjectFields` の include 選別描画）。専用の書込経路を作らない。閲覧専用モードは `FormCtx` 共有で波及する。
- 関係パネル（`RelationsPanel`）は参照・被参照に加えて、保有（◆。関係名の label でグループ化・子の役割と書かれた場所）と所属（◇。inverseLabel・親の出現のスキーマと書かれた場所）を並べる。エージェントの `related` / `read_entity` も同じ 2 節を出す。
- グラフレンズは `EntityGraph` の導出表示。節点と辺は `LensGraphs` が作る（純ロジック）。見え方は 2 つ: 一体（箱＝エンティティ。同じ ID の出現を 1 つにまとめる）と、側面で分ける（箱＝出現。文書ごとの書かれ方そのまま。同じエンティティの箱は定義出現から順に点線で結ぶ。参照は書いた出現から相手の定義出現へ）。
- レンズの箱の入れ子は保有の線。一体では定義出現の保有（誕生の場所。親は 1 つ）だけを入れ子にし、詳細化の出現の保有は線（親の側にひし形・矢じり無し）。側面で分けるでは文書の入れ子をそのまま箱にする。親の連鎖が自分へ戻れば切る。
- レンズの絞り込みは型（起点は対象外）・関係（保有の名前には「（保有）」の印。隠すと入れ子が切れて子はいちばん外側へ）・起点の近傍（辺と親子を幅優先。着いた箱の外側の箱も残す）。入れ子の箱は畳める: 既定は全部畳み、起点の外側の箱と起点（深さ 2 なら起点の子も）だけ開く。畳んだ箱の中につながる辺は箱へ付け替える。利用者の開閉は `ScreenMemory.lensExpanded`。
- レンズの配置は `GraphLayouts.arrangeNested`（ELK を包む。放射・階層・応力と、軽い力学）で決定的（同じグラフなら同じ絵）。段ごとに配置し、子を持つ箱は中を先に配置してその大きさの 1 つの節点として外側に置く。段をまたぐ辺は箱の縁から縁への直線。ノードクリックはエンティティページへ、見出しの左の印は開く・畳む。箱を動かすと中の箱も動く（`withMovedNested`）。
- 放射配置は起点からの幅優先の木（最短の道すじ）だけを配置に渡し、木に入らない辺は直線で描く。応力配置は節点数に上限があり、超えると階層に代替する（理由を画面に出す）。

## 規律

- 判定に `graph.outgoingRefs`（配下の全参照）を使わない。判定は `ownRefs`（直接書いた参照）で辿る。
- `via` の突き合わせは `QueryEdgeStep.viaMatches` を通す。
- 消費側（Views・Foreach・Validator・CLI・Transform）に評価ロジックを書かない。
- グラフの導出項目を増やすときは `SemanticEntityGraph` に閉じ、消費側で走査を書かない。評価のたびに全要素・全参照を総なめする処理を置かない（グラフの一式につき 1 回）。
- エンティティ合成の項目を増やすときは `Aspects` に閉じ、消費側で合成を書かない。
- レジストリのラベルなど表示専用の情報を検証・解決の意味論に関与させない。
- 保有の線を `ScannedRef` として索引の参照の並びに混ぜない（改名・統合・削除は索引の参照だけを書き換える）。クエリの束縛のために写す（`holdingRef`）だけ。名前は走査が決め（`ScannedId.holderKey / holderRelation`）、消費側でスキーマの項目を読み直さない。

## 拡張ポイント

- **エンティティ・関係語彙の追加**: 宣言モデル（`Relations.kt`）・読み取り（`SchemaDocuments`）・メタスキーマ（`builtin-schema` の entity・`builtin-relations`）・スキーマ設計 UI のエンティティ節をそろえて更新する。
- **クエリ語彙の追加**: 宣言モデルとパース（`Query.kt`）・評価器（`SemanticQuery`）・メタスキーマ（`builtin-view` の rows・`builtin-activity` の foreach.query・`builtin-schema` の constraints）をそろえて更新する。
- **エンティティ操作の追加**: `SemanticRefactor` に計画の生成を足し、`SemanticRewrite` の列挙器を使う。
