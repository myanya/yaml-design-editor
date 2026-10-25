# 操作（型の操作とアクション）

操作は、計算体系の「書き換え（Rewrite）」の初めての利用者定義体。スキーマに書いた定義から、受け取った値と前提をもとに変更セットを作る。
二層に分かれる: **型の操作**（`methods`。object の定義に書き、その型の要素 1 つを受け手にする）と、**横断の操作**（`actions`。スキーマ文書に書き、複数の要素や文書にまたがる）。
結果は常に変更セットで、適用は既存の `ChangeSetApplier`（検証・競合・Undo 1 回）。新しい書込経路は作らない。入口は CLI・画面（操作メニュー・行の ⋯・右ペインの「操作」タブ → 浮いたパネル）・検査の指摘・エージェントのツール。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `ActionDef` / `ActionTarget` / `ActionRequire` | semantic-core/schema/Action.kt | 横断の操作の宣言（受け手 `target`・受け取る値 `params`・前提 `require`・手 `do`）と読み取り |
| `ActionInput` | semantic-core/schema/ActionInput.kt | フォーム用の `initial` と `submit` の宣言と点検 |
| `ActionAvailability` / `PreparedActionInput` | workspace-core/workspace/ActionPlanner.kt | 前提だけの利用可否と、初期値・入力元の比較値 |
| `ActionStep` / `ActionSteps` / `StepSite` / `StepScope` | semantic-core/schema/Action.kt | 手（set / insert / delete / create / call / each）の宣言と読み取り。型の操作の範囲の制限 |
| `MethodDef` / `InsertRule` / `IdNumbering` / `ActionParams` | semantic-core/schema/Action.kt | 型の操作（予約名 `create`）・配列の追加の決まり（採番 `id`・同じ明細 `key`）・受け取る値の宣言 |
| `FieldDef.methods` / `insertRule` | semantic-core/schema/Fields.kt | object と配列の項目に載せた操作の定義。読み取りは `SchemaTreeParser.parseField`、引き継ぎは `SchemaTree.narrowOnto` |
| `IdSeries` | semantic-core/schema/IdSeries.kt | 接頭辞を決めた系列の採番（skill の取り込みと共有） |
| `SemanticTransform.build` / `holds` / `TemplatePlace` | semantic-core/semantic/Transform.kt | ひな型 1 つの組み立てと条件の判定（受け取った値・いまの場所・束縛） |
| `ActionPlanner` / `ActionRequest` / `ActionPlan` / `ActionEntry` / `ActionFailure` | workspace-core/workspace/ActionPlanner.kt | 計画器（手を順に当てて変更セットを作る）と、使える操作の一覧（`catalog`・受け手ごとの `forEntity` / `forEntityType` / `forDocument` / `forWorkspace`。`ActionEntry.entityType` / `documentSchema` が受け手の種類） |
| `WorkspaceSchema.actions` | workspace-core/workspace/WorkspaceReader.kt・SchemaDocuments.kt | スキーマ文書の `actions:` の読み込み |
| CLI `action` | core/Cli.kt | 一覧・計画・適用（`--apply`・`--allow-errors`） |
| `AppState.ActionWork` / `startAction` / `planAction` / `applyAction` / `switchInsertToAction` ほか | app/AppState.kt | 画面からの操作の作業（対象の欄の木 `targetHolder`・受け取る値の仮の文書・計画・結果・パネルの開閉 `actionPanelOpen`・追加の作業からアクションへの橋渡し。入口はどれも `startAction`） |
| `ActionPlanner.insertingInto` | workspace-core/workspace/ActionPlanner.kt | その文書のその配列へ足すアクション（手の insert の `in` と `path`、受け手の文書）。追加のパネルの「操作に切り替える」の候補 |
| `ActionTabContent` / `ActionPanelHost` / `TargetPicker` | ui/ActionPane.kt | 右ペインの「操作」タブ（使える操作の一覧と作業中の開き直し）と、浮いたパネル（対象の欄・受け取る値のフォーム・要約・検証・差分・適用） |
| `ElementOpsButton`（ui/FormView.kt）・`IdCell` の ⋯（ui/ViewGridPane.kt） | ui/ | フォームの配列の行とビューグリッドの行の操作メニュー（その要素を対象にして `startAction`） |

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
- **画面**: 入口（エンティティページの操作メニュー・フォームとビューの行の ⋯・文書ヘッダーの「文書操作」メニュー・コマンドパレット・右ペインの「操作」タブ）は `AppState.startAction(entry, target)` を呼ぶだけで、受け手を知らない入口は `target = null` で呼ぶ。`Main` が載せる浮いたパネル（`ActionPanelHost`。「AI で作成」と同じ幅）に、対象の欄と受け取る値の欄が出る。
- 対象の欄（`TargetPicker`）は要素を受け手にする操作（`ActionEntry.entityType`）だけに出る。候補は上書き索引のうちその型の出現を持つ ID（`entityCandidates`）で、値は `ActionWork.targetHolder` の木に入り、`work.target` で読む。受け取る値は仮の文書の木を `FormCtx` と `FieldRow` で編集する（型・選択肢・参照の候補・ID の提案がそのまま効く）。
- 右ペインの `actionPanelContext` は表示中の横ぐしの選択IDを優先し、未選択・削除済みなら対象を空にする（背後の文書を使わない）。文書画面ではフォームで明示選択した要素、なければ文書ルートのIDを対象にする。台帳は型ごとの入口も残す。対象IDは文書別の `FormNavigationMemory.actionTarget` に保持し、スクロール追従では変えない。
- 項目の所有者は選択パスの最も近いID出現で決める（Termの位置選択）。削除されたIDを使わず、文書の先頭も推測しない。開始済みの操作対象は背後の選択に追随しない。
- 「確認」＝`planAction`（対象が無ければ案内して止まる → 計画と、ドライランの検証）→ 差分（`Staging` の適用後の文面）→「適用」＝`applyAction`（`applyViaSession`＝書いて保存まで。Undo 1 回で全部戻る）。検証エラーが残れば書かず、チェックで許す。対象に未保存の編集があれば拒む。
- パネルは外側のクリックで閉じず、Esc と「いったん隠す」は `actionPanelOpen = false`（作業は残り、「操作」タブの「作業中」から `showActionPanel`）。「やめる」＝`dismissAction` で捨てる。
- **編集と操作の札**: パネルの題名の脇に「編集」（追加の作業＝`insertInto`。この文書の木を変える。未保存）か「操作」（確認してから書いて保存）の札と副題を出す。追加の作業では、その配列へ足すアクション（`ActionPlanner.insertingInto`）があれば「まとめて足す操作があります」と案内し、`switchInsertToAction` が同じキーの値を持ち越してアクションの作業に変える。
- **閲覧専用**: 閲覧専用が封じるのは編集（`FormCtx.readOnly`・`addArrayElement`）で、操作の入口（操作メニュー・行の ⋯・ヘッダ・パレット・検査の指摘・「操作」タブ）は閲覧専用でも出す。組込みの改名・統合・削除は閲覧専用では隠す。閲覧専用で適用した操作の取り消し・やり直しは `afterHistoryStep` が保存まで行う（`actionRecordIds`＝操作の適用で積んだ履歴の id。未保存の編集を残さない）。
- **エージェント**: アクティビティの `actions` で許可した操作が `AgentToolbox` のツールになる（`action_` ＋名前。引数は受け取る値の定義から、受け手は `target`）。実行はステージングを重ねたセッションの上で `ActionPlanner.plan` を呼び、変更セットを `Staging` へ積む（原本は提出と承認まで変わらない）。前提に合わなければ理由を返す。`allowedFiles` / `allowedPaths` は見ない（[agents.md](agents.md)）。
- **作成経路の寄せ**: 配列エディタの追加ボタンとビューの新規行（配列モード）は `AppState.addArrayElement` → `ActionPlanner.newElement`（型の `create` ＋ 配列の `insert` の決まり）で要素を作る。要素の型の `create` に受け取る値があれば同じパネル（`ActionWork.insertInto`）で入力し、「追加」で未保存の編集として足す（計画と保存はしない）。
- 受け取る値が無ければその場で足す（採番つき。空の要素どうしは同じ明細と見ない）。文書の作成（`DocumentScaffolds.contents`）はルートの型の `create` の `build` を ID と表示名で重ねる。skill の取り込みとエージェントの `create_document` は寄せていない。
- **検査からの起動**: 検査の `responses` に `{action, label, with}` を書くと、`WorkspaceChecks.responsesOf` が `CheckResponse.RunAction`（受け手＝起点の ID・渡す値＝文面を指摘と同じひな型で埋めたもの）を作り、検証パネルのメニューから `startAction(name, target, prefill)` で対象の入ったパネルが開く。無い操作を指す対応は読み込みの誤り（`WorkspaceReader.buildSchemas`）。

## 規律

### 入力準備と確認の有効期間

- `ActionPlanner.availability` は実行と同じ `actionScope` / `checkRequire` を使う。入力参照のある前提だけを入力待ちとし、それ以外の違反・保留は理由付きで無効にする。手は実行せず履歴も作らない。UIは編集用セッションで判定し、索引と編集の版を購読する。入力後も `checkPreconditions` で未保存の変更を含めて検査する。
- `prepareInput(session, request, now)` は `params.default`、`input.initial`、明示指定の順で値を重ねる。`SemanticTransform.build` を使い、受け手の束縛と日時の `context` を渡す。出現は既存の `entityPlace`（定義出現優先・`$in` 絞り込み）に従い、無い値は補完しない。
- 入力準備は Construct、対象選択と差分比較は Term、利用可否は Query / Predicate。時計は呼出側が採取した引数で、評価器内では読まない。出どころは対象の出現・文書・パスと内容の比較値に保持する。新しい評価器・書込経路・検査語彙は追加しない。
- `ActionWork` が開始日時・初期値・明示指定・対象・入力元の比較値を保持する。対象変更は候補の確定後に再設定し、入力破棄は画面で確認する。初期値の読み直しでも開始日時は変えない。
- `submit: changed` は初期値との差だけを送る（必須項目と明示指定は含める）。未変更・空文字・空配列を区別し、項目の削除は操作の入力と手で明示する。実行の省略値、CLI・AI、保存時の自動値は変えない。
- 確認時に入力元を照合する。確認済みの入力・対象・ディスク側と編集側セッションの版を保存し、フォーム編集や版の変更で古い差分を取り下げる。適用直前にも照合し、同じ計画時刻で前提を再検査してから確認済みの変更セットを適用する。
- `input` の語彙はメタスキーマ、追加区画の共通ツリー、ひな型入力例にも載せる。`ActionInputTest` / `AppStateActionInputTest` が準備と適用の境界、`SchemaDefinitionCoverageTest` / `SchemaAdditionalOutlineTest` が定義UIの追従を検査する。

### 操作の共通規則

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
- 画面の入口は `AppState.startAction` を呼ぶだけで、計画や適用の規則を入口に書かない。受け手を知らない入口は対象を空で渡し、パネルの対象の欄に任せる（文書の先頭の ID を受け手に見立てない）。作業の状態（対象・受け取る値・計画・結果・パネルの開閉）は `AppState.actionWork` / `actionPanelOpen` に置き、`remember` に置かない。
- 受け取る値の仮の文書はどこにも保存せず、セッションに載せない（編集の記録先が無い）。dirty や履歴に現れない。
- 編集と操作を混ぜない。編集（フォームの追加・削除・追加のパネル）は未保存で閲覧専用では封じる。操作は確認してから書いて保存し、閲覧専用でも通す。パネルの「編集」「操作」の札とボタンの語（「行を追加」／「適用（書いて保存）」）でその違いを見せる。

## 拡張ポイント

- **手の種類の追加**: `ActionStep` と `ActionSteps.parse` に足し、`ActionPlanner.run` の分岐と、メタスキーマ `builtin-schemas/schema.yaml` の `actions` / `methods` の説明をそろえる。型の操作で書けるかを `StepScope` で決める。
- **入口の追加**: 画面なら `AppState.startAction` を呼ぶ（受け手を知っていれば渡し、知らなければ null）。エージェントのツールなら `ActionPlanner.plan` を呼び、返った変更セットをステージングへ渡す。入口ごとの規則を計画器に書かない。
- **対象の選び方の拡張**（複数の対象・いま触っている行を既定に）: 対象は `ActionWork.targetHolder` に閉じているので、欄の部品（`TargetPicker`）と `planAction` の読み出しを変えるだけで、入口と計画器は変えない。
- **予約の操作の追加**: `MethodDef.CREATE` と同じく名前を定数にし、既定の意味を計画器の 1 か所に持つ。
- **既存の作成経路の寄せ**: 配列エディタの追加・ビューの新規行・詳細文書を作成・skill の取り込み・エージェントの `create_document` を、型の `create` と配列の `insert` の呼び手に 1 つずつ寄せる。挙動が変わるものはコミットメッセージに書く。
