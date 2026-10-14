# 操作（型の操作とアクション）

操作は、計算体系の「書き換え（Rewrite）」の初めての利用者定義体。スキーマに書いた定義から、受け取った値と前提をもとに変更セットを作る。
二層に分かれる: **型の操作**（`methods`。object の定義に書き、その型の要素 1 つを受け手にする）と、**横断の操作**（`actions`。スキーマ文書に書き、複数の要素や文書にまたがる）。
結果は常に変更セットで、適用は既存の `ChangeSetApplier`（検証・競合・Undo 1 回）。新しい書込経路は作らない。入口は CLI と画面（右ペインの「操作」タブ）で、エージェントの入口は後の段階。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `ActionDef` / `ActionTarget` / `ActionRequire` | semantic-core/schema/Action.kt | 横断の操作の宣言（受け手 `target`・受け取る値 `params`・前提 `require`・手 `do`）と読み取り |
| `ActionStep` / `ActionSteps` / `StepSite` / `StepScope` | semantic-core/schema/Action.kt | 手（set / insert / delete / create / call / each）の宣言と読み取り。型の操作の範囲の制限 |
| `MethodDef` / `InsertRule` / `IdNumbering` / `ActionParams` | semantic-core/schema/Action.kt | 型の操作（予約名 `create`）・配列の追加の決まり（採番 `id`・同じ明細 `key`）・受け取る値の宣言 |
| `FieldDef.methods` / `insertRule` | semantic-core/schema/Fields.kt | object と配列の項目に載せた操作の定義。読み取りは `SchemaTreeParser.parseField`、引き継ぎは `SchemaTree.narrowOnto` |
| `IdSeries` | semantic-core/schema/IdSeries.kt | 接頭辞を決めた系列の採番（skill の取り込みと共有） |
| `SemanticTransform.build` / `holds` / `TemplatePlace` | semantic-core/semantic/Transform.kt | ひな型 1 つの組み立てと条件の判定（受け取った値・いまの場所・束縛） |
| `ActionPlanner` / `ActionRequest` / `ActionPlan` / `ActionEntry` / `ActionFailure` | workspace-core/workspace/ActionPlanner.kt | 計画器（手を順に当てて変更セットを作る）と、使える操作の一覧（`catalog`・受け手ごとの `forEntity` / `forDocument` / `forWorkspace`） |
| `WorkspaceSchema.actions` | workspace-core/workspace/WorkspaceReader.kt・SchemaDocuments.kt | スキーマ文書の `actions:` の読み込み |
| CLI `action` | core/Cli.kt | 一覧・計画・適用（`--apply`・`--allow-errors`） |
| `AppState.ActionWork` / `startAction` / `planAction` / `applyAction` / `actionsForEntity` ほか | app/AppState.kt | 画面からの操作の作業（受け取る値の仮の文書・計画・ドライラン・結果）。入口はどれも `startAction` を呼ぶ |
| `ActionTabContent` / `ActionCard` | ui/ActionPane.kt | 右ペインの「操作」タブ（使える操作の一覧と、作業中のカード＝受け取る値のフォーム・要約・検証・差分・適用） |

## 流れ

- **定義の読み込み**: `SchemaTreeParser.parseField` が object の `methods` を `FieldDef.methods` へ、配列の `methods.insert` を `FieldDef.insertRule` へ読む。`SchemaDocuments.parse` が `actions:` を読む。形の点検は読み取り時に済ませる（未知のキー・宣言のない受け取る値・束縛名・型の操作の範囲・前提が仮の値で検査として読めること）。
- **型を使う側への引き継ぎ**: `SchemaTree.narrowOnto` が定義側の操作を引き継ぎ、同じ名前は使う側が置き換える。`create` だけは重ねる（`builds` を定義側から順に、`params` は同じキーを置き換えて足す）。
- **計画**（`ActionPlanner.plan`）: 名前の解決（アクションの id → `型名.操作名`）→ 受け手の解決（`entity` は ID の定義出現を優先、`schema` は文書 1 つ）→ 受け取った値を整える（宣言に無い名前は誤り・省いた値は `default`・宣言があれば構造の検証）→ 前提 → 手を順に当てる → 変更セット。
- **前提**: 受け手があれば `SemanticRules.evaluate` の 4 つの結果で決める。違反・保留・対象の要素でない（NOT_IN_SCOPE）は止め、その他の対象外は通す。受け手の無いアクションの前提は `expect` / `count` だけで、照合の全体の件数で判定する（解の不足が相手の見つからない参照によるときは保留で止める）。
- **手**: 対象の土台は `of`（束縛の要素）→ `in`（文書の指定に合う文書がちょうど 1 つ）→ 受け手の順。書き換えは作業用の写しの上に順に当て、後の手が前の手の結果を読める。`when` の条件に合わない手は飛ばし、要約に残す。
- **要素の作り方**（型の `create`）: 必須の初期値（`SchemaScaffold.defaultTree`。保存時に自動で入る値は `now`）の上に、`build` があれば定義側から順に重ね、無ければ受け取った値のうち項目のキーに合うものを写す。`params` の宣言があれば構造で検証し、無ければ項目のキーに合うことだけを見る。
- **配列の `insert` の決まり**: `id`（ID の項目が空なら接頭辞＋連番。既存は索引の全 ID と配列の ID とこの計画で付けた ID）・`key`（同じ値の要素があれば足さない）。配列がまだ無ければ要素 1 つの配列を置く 1 つの操作にする。
- **文書の `create`**: スキーマのルートの型の `create` で中身を作り、置き場所は `DocumentScaffolds.filePatternOf` と `pathFor`（ID だけの元）。既にあれば止める。
- **適用**: 変更セットは `ChangeSetApplier` をそのまま通る（競合・自動で入る値・適用後の姿の検証・1 トランザクション）。CLI は既定で計画だけを出し、`--apply` で書く。検証エラーが残るなら書かず、`--allow-errors` で書く。
- **画面**: 入口（エンティティページの操作メニュー・文書ヘッダの「操作 ▾」・コマンドパレット・右ペインの一覧）は `AppState.startAction` を呼ぶだけ。右ペインの「操作」タブにカードが出て、受け取る値は仮の文書の木を `FormCtx` と `FieldRow` で編集する（型・選択肢・参照の候補・ID の提案がそのまま効く）。
- 「確認」＝`planAction`（計画と、ドライランの検証）→ 差分（`Staging` の適用後の文面）→「適用」＝`applyAction`（`applyViaSession`＝書いて保存まで。Undo 1 回で全部戻る）。検証エラーが残れば書かず、チェックで許す。対象に未保存の編集があれば拒む。
- **検査からの起動**: 検査の `responses` に `{action, label, with}` を書くと、`WorkspaceChecks.responsesOf` が `CheckResponse.RunAction`（受け手＝起点の ID・渡す値＝文面を指摘と同じひな型で埋めたもの）を作り、検証パネルのメニューから `startAction(name, target, prefill)` でカードが開く。無い操作を指す対応は読み込みの誤り（`WorkspaceReader.buildSchemas`）。

## 規律

- 新しい書込経路を作らない。操作の結果は常に変更セットで、適用は `ChangeSetApplier`。計画器はファイルにもセッションにも書かない。
- 型の操作は受け手の部分木（保有する子を含む）に閉じる。`of` / `in` / `create` / `call` / 照合の `each` は宣言の段階で誤りにする。他の要素や文書を変えるのはアクション。
- 受け取った値の一式が「いまの場所」。要素は束縛名で指し（受け手は `self`）、文字列 `$名前` は受け取った値そのもの。前提の文面では `{id}` が起点の ID、`$id` が受け取った値。
- 受け手のキーは `target`。`on` は YAML が真偽値に読むため、キーに使わない。
- 「今」は `now` で渡す。式とひな型に非決定の値を入れない。
- 判定は `SemanticRules`、照合は `SemanticQuery`、ひな型は `SemanticTransform` を通す。計画器に評価ロジックを書かない。
- 保留（判定できない）は止める。黙って通さない。
- 前提で参照した受け取る値が無い（省かれた任意の値）ときは止める。絞り込みが黙って外れるのを防ぐ。
- フック（出来事で暗黙に走る手）は入れない。予約名の結び付け（追加ボタンと `insert`、`insert` と `create`）と、明示の `call` だけ。
- `methods` は解決した項目（`schema.resolve` / `fieldAt`）から読む。生の `FieldDef` から読むと型の引き継ぎが抜ける。
- 画面の入口は `AppState.startAction` を呼ぶだけで、計画や適用の規則を入口に書かない。作業の状態（受け取る値・計画・結果）は `AppState.actionWork` に置き、`remember` に置かない。
- 受け取る値の仮の文書はどこにも保存せず、セッションに載せない（編集の記録先が無い）。dirty や履歴に現れない。

## 拡張ポイント

- **手の種類の追加**: `ActionStep` と `ActionSteps.parse` に足し、`ActionPlanner.run` の分岐と、メタスキーマ `builtin-schemas/schema.yaml` の `actions` / `methods` の説明をそろえる。型の操作で書けるかを `StepScope` で決める。
- **入口の追加**: 画面なら `AppState.startAction` を呼ぶ（ビューの行の操作メニューなど）。エージェントのツールなら `ActionPlanner.plan` を呼び、返った変更セットをステージングへ渡す。入口ごとの規則を計画器に書かない。
- **予約の操作の追加**: `MethodDef.CREATE` と同じく名前を定数にし、既定の意味を計画器の 1 か所に持つ。
- **既存の作成経路の寄せ**: 配列エディタの追加・ビューの新規行・詳細文書を作成・skill の取り込み・エージェントの `create_document` を、型の `create` と配列の `insert` の呼び手に 1 つずつ寄せる。挙動が変わるものはコミットメッセージに書く。
