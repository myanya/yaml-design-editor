# CLAUDE.md

Kotlin + Compose for Desktop 製のスキーマ駆動YAMLエディタ。ワークスペース内のYAML文書を、`_schemas/*.yaml` のスキーマに基づくフォーム／ソース／差分で編集し、ID索引・参照解決・検証・AI生成支援を提供する。

## コマンド

```bash
./gradlew test                              # 単体テスト（コミット前に必ず実行）
./gradlew run                               # スタート画面で起動（履歴からワークスペース選択）
./gradlew run --args="/path/to/workspace"   # ワークスペース指定で起動
./gradlew runCli -q --args="help"           # ヘッドレスCLI（探索・検索・パッチ・検証・エージェント実行・評価・入出力変換。README §16）
./gradlew packageDistributionForCurrentOS   # 配布物作成
```

要件: JDK 17+。GUIアプリのため `run` は対話的に閉じる必要がある（検証はテスト・ビルド・`runCli` で行う）。

## ドキュメント体系（必ず参照・更新する）

| ドキュメント | 役割 |
|---|---|
| [README.md](README.md) | 利用者マニュアル（操作・スキーマ定義リファレンス・制約） |
| [docs/FEATURES.md](docs/FEATURES.md) | 機能台帳: 全機能の一覧・実装状況・テスト有無・未実装項目 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | クラス設計・設計方針・データフロー・拡張ポイント |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | 開発規約（スタイル・Compose作法・テスト・ドキュメント更新規約・UXフィードバック運用） |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 将来構想（サンドボックス型AI実行環境化）とフェーズ別ロードマップ |
| [docs/CORE-CALCULUS.md](docs/CORE-CALCULUS.md) | YAMLに対する計算体系（Core Calculus）の設計方針。新しい決定論的機能は必ずこの体系を一段挟む（ADR-0009・CONVENTIONS §9） |
| [docs/DESIGN-CHECK-CATALOG.md](docs/DESIGN-CHECK-CATALOG.md) | 設計チェックカタログ: 「設計への機械的チェック」のパターン台帳（R28-06）。検証機構を追加・変更したら対更新する |
| [docs/BACKLOG.md](docs/BACKLOG.md) | 指摘台帳: UX試験等で見つかった気になりポイントの即時記録（対応判断は後で個別に） |
| [docs/UX-SCENARIOS.md](docs/UX-SCENARIOS.md) | UX試験のシナリオ台帳: 触り心地試験の台本（全体通し S1 ほか）・実施トリガ・実施履歴 |
| [docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](docs/THREE-LAYER-CORE-MIGRATION-PLAN.md) | 3層コア移行の計画: フェーズ・完了条件・現行ファイルの移行先対応表 |
| [docs/MIGRATION-LEDGER.md](docs/MIGRATION-LEDGER.md) | 移行台帳: ベースライン記録・作業単位（YM/SM/WS-xx）の状態 |
| [docs/decisions/](docs/decisions/README.md) | ADR（設計決定 0001〜0009）と意図的差分一覧（INTENTIONAL-DIFFS.md） |

### 外部仕様化ワークスペース（`specs/`）

`specs/`は、製品の宣言言語、意味、振る舞い、表示と操作を、実装から独立した外部契約として検討・管理するワークスペースである。`specs/`に関する議論、調査、作成、更新、または仕様に基づく実装検討へ着手するときは、**最初に [specs/SPECIFICATION-GUIDE.md](specs/SPECIFICATION-GUIDE.md) を全文読み、その手順と情報の位置づけに従うこと**。仕様化の依頼だけから、開発本線のコード変更を推測しない。

**作業のワークフロー:**

1. **着手前**: docs/FEATURES.md で対象機能の位置（実装ファイル・テスト有無）を確認し、docs/ARCHITECTURE.md の該当する設計方針（特に §4）を読む。
2. **実装中**: docs/CONVENTIONS.md に従う。重要: UI文言・コメント・エラーメッセージは日本語／純粋ロジック層に Compose を持ち込まない／ツリー変更には `markEdited()`。
3. **完了前**: `./gradlew test` を通し、変更内容に応じてドキュメントを更新する（対応表は docs/CONVENTIONS.md §6）。機能を追加・変更したのにドキュメントが差分に含まれない場合は、更新漏れを疑うこと。`/sync-docs` スキルでチェックできる。
4. **完了報告**: 機能を追加・変更したら、報告に**試し方**（具体的な操作手順＋見てほしい観点）を必ず含める（docs/CONVENTIONS.md §7）。利用者が触った感想からチューニングの要否を決める。作業中に見つけた本題と異なる気になりポイントは、直しに行かず docs/BACKLOG.md へ即時登録する。

## 3層コア（コアに触る前に必読）

3層コア移行（Phase 0〜12。[docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](docs/THREE-LAYER-CORE-MIGRATION-PLAN.md)）と**移行後リファクタリング（root 5パッケージの解体。[docs/POST-MIGRATION-REFACTORING-PLAN.md](docs/POST-MIGRATION-REFACTORING-PLAN.md)）は完了**（2026-09-02・記録: [docs/MIGRATION-LEDGER.md](docs/MIGRATION-LEDGER.md)）。**併存期は解消済み**——走査・集約・判定（検証族）・リファクタリング計画・履歴（Undo/dirty）・書込の実体はコア3モジュールの単一所有で、アプリは `AppState` が `WorkspaceSession` 直結（編集はセッション直結・Undoはワークスペース横断＝D-07・`overlayIndex()` はセッション派生の IdIndex ファサード）、root に残るのは UIシェル・変換シェル・読み出しモデル（Traverse/Disclosure/Search/SchemaInference/IdSuggest）・SemanticBridge・root Cli 探索系＝意図した残置のみ。6E仮想文書（解決・テキスト表現は `semantic/VirtualViews.kt`・root `core/Views.kt` は変換シェル）・derive/メタスキーマ（展開コアは `semantic-core/schema/SchemaDerivation.kt`・組込原文は workspace-core 資源 `builtin-schemas/`）も完了。**SchemaDef 依存の残置分の解消は ROADMAP P27（スキーマ2層化）で管理**（段D で、root にだけ残っていた検査・検証の組み立て・変更セットの適用・ビュー宣言の読み取り・リファクタリングの影響の解決・文書キャッシュの履歴の併走は解消済み。残りは IdIndex ファサードと、パッケージ改名・Path 座標）——勝手に着手しない。コアを触る際の規約は docs/CONVENTIONS.md §8 と以下＝**引き続き有効**:

- [docs/decisions/](docs/decisions/README.md) のADRは本書・CONVENTIONS と同格の規約。特に: 依存方向 `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`（coreからアプリ・Compose・`Path` への逆依存禁止。ADR-0002）／可変YNode＋copy-on-write規律（ADR-0003）／Undo・履歴はworkspace-core単一所有（ADR-0006）／表記→意味の禁止（ADR-0008）。
- 移行は**移動であって複製ではない**。移動後もアプリ含む `./gradlew test` を緑に保つ。移行中のパッケージ改名は禁止（`model`・`yaml` 等のパッケージ名のままモジュールへ移す）。
- 台帳で done の単位はモジュール側にファイルがある（例: `model/`・`yaml/` → `yaml-core/src/`）。変更は移行先で行う。
- 既存挙動を変えるときは実装より先に [INTENTIONAL-DIFFS.md](docs/decisions/INTENTIONAL-DIFFS.md) へ登録する（登録なき挙動差分は移植ミスとして扱う）。
- `test-fixtures/` は読み取り専用の共通fixture（期待結果は [test-fixtures/README.md](test-fixtures/README.md)）。変更を伴うテストは一時ディレクトリへコピーして使う。

## 落とし穴（要注意）

- `YNode` は Compose の State ではない。ビューは `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する。読み忘れると画面が更新されない。
- `IdIndex` はディスク基準（正）。未保存編集の参照解決は `DocScanner.scan` によるローカルIDを重ねて判定する二層構造（docs/ARCHITECTURE.md §4.5）。更新は変更ファイル単位の増分（`update`）だが、**スキーマが変わったら必ず `rebuild`**（走査規則が変わるため）。保存・外部変更でのこの振り分けは `Workspace.noteDiskChanges` に一元化されている（R9-02）——AppState 側に振り分けを書かない。**UI層は `state.index` でなく `state.overlayIndex()`**（dirty文書を重ねた「見たまま判定」。UX-03。供給元は RA-02 でセッション派生＝`index.derivedIdIndex` ファサードに切替済み・挙動は同一）を読み、`remember` のキーに `state.editVersion` を含める。ヘッドレス経路（CLI・エージェント）はディスク索引のまま。
- YAML はコメント・表記スタイルを保持する（R8-01）。ノードを**置換**する経路では `inheritMetaFrom` で引き継ぐ（`Slot.set` / `Patcher` は対応済み）。構造の比較・派生成果物の出力は `dump` ではなく `YamlIO.dumpPlain`（コメント・スタイルで揺れるため。docs/ARCHITECTURE.md §4.17）。複数ドキュメントYAMLの書込は `OpenDocument`（`parseAll`/`dumpAll`）に閉じ、読み取り専用経路だけ `parseFirst` を使う。
- 構造の検証は、利用者が書いたスキーマの木（`FieldDef`）をそのまま読まない——そこから導出した最小の構造のスキーマ（yaml-core の `structure` パッケージ。ID・参照・フェーズ・表示のヒントを知らない）で行う（P27 段C の C-1。docs/ARCHITECTURE.md §4.43・意図的差分 D-30）。入口は workspace-core の `DerivedStructures`（`SchemaDef.structures` / `WorkspaceSchema.structures` の `validate(root)`）で、アプリとカーネルが共有する。値の形式的なチェックを変えるなら yaml-core の `structure/StructureValidator.kt`、利用者の語彙から構造への写し方を変えるなら組込みの変換定義 `builtin-transforms/structural-schema.yaml` を直す（利用者の語彙を足しただけでは、構造の検証には現れない）。利用者が書く木（`FieldDef`・`SchemaTree`〔旧名 `StructuralSchema`〕・その読み取り `SchemaTreeParser`・`PhaseProgress`）は semantic-core にある（C-2 で yaml-core から移動）——**yaml-core へ、ID・参照・関係・フェーズ・表示のヒントに関わるものを戻さない**（yaml-core に置くのは、YAML の項とパス・変更の基礎命令・最小の構造のスキーマとその検証だけ）。分け方の基準: その値1つと項目の決まりだけで決まるチェックは yaml-core、他の値・要素・文書・索引・フェーズを見るチェックは semantic-core（`cases` だけは yaml-core に残す）。
- スキーマを読むのは、カーネルの経路（`WorkspaceReader`）だけ（P27 段C の C-3b・意図的差分 D-32）。root の `SchemaDef` は、カーネルの `WorkspaceSchema`（スキーマの中身の原本）に、スキーマ定義文書のファイルの場所を足す薄い型——スキーマに持たせる情報を足すときは `WorkspaceSchema`（と、読み取りの `SchemaDocuments`）へ足し、`SchemaDef` には読み出しの窓口だけを足す。root に、スキーマをファイルから読む処理を書き足さない（`Workspace.refresh` は、セッションが読んだ結果を `SchemaLoader.fromKernel` でアプリの形にする）。`SchemaDef` は data class ではない（`copy` は無い。同じかどうかは、同じものかどうかで見分ける）。項目の表示と編集のためのヒント（ラベル・説明・選択肢のラベル・一覧の見せ方・初期値）は `FieldDef.hints`（`FieldHints`）の区画——検証・走査・検査から、ここを読まない。
- スキーマの `FieldDef` を使う際は必ず `schema.resolve(field)` を通す（`$ref` 解決）。object の子フィールドの列挙は必ず `schema.effectiveFields(fields, node)` を通す（enum の `cases` 合成。docs/ARCHITECTURE.md §4.7）。
- フィールド型を追加する場合の変更箇所は docs/ARCHITECTURE.md §6 に列挙されている（4箇所以上に分岐が必要）。
- フィールドの実効必須判定は素の `field.required` を見ず、必ず `schema.requiredAt(field, phaseIndex)` を通す（`requiredFrom` によるフェーズ累積必須を見落とす。`phaseIndex` は `schema.currentPhaseIndex(root)`。docs/ARCHITECTURE.md §4.15）。検査の状況（`Validator.ruleStatuses`。カバレッジの充足表示など）の `activeFrom` も同様に、`definingRoot` 引数を省略しない。
- 要素の1行要約（タイトル・サブタイトル・畳み込みヘッダ）は `schema/Display` に一元化されている（R10-01）。一覧・候補・要約の表示に独自のタイトル抽出を書かない。`IdEntry.title` は表示解決の結果（`role` ＞ 予約語）。enum のリッチ選択肢（`{value, label, description}`）の label / description は表示専用で、検証・`cases`・AI制約は常に `value`（docs/ARCHITECTURE.md §4.22）。
- フォーム系の新しい編集UIは `FormCtx` を通し、閲覧専用モード（`ctx.readOnly`）の表示分岐を入れる。`readOnly` 中の `markEdited` / スカラー書込は check で拒否される。
- UIシェルはスペース制（`app.UiSpace`: 編集・台帳・実行・設計。R12-01）。`uiSpace` の書き換えは `showSpace` / `showWorkspaceView` / ナビゲーション系メソッド経由に限る（ジャンプは編集／台帳へ着地する規約）。新しい画面をモーダル（AlertDialog）で作らない——確認ダイアログを除き、スペース内の常設画面にする。エージェント実行の状態は `RunController` に置き、コンポーザブルの `remember` へ戻さない（docs/ARCHITECTURE.md §4.24）。
- エージェントの編集は必ず `agent/Staging`（オーバーレイ）経由。原本への書込は人間の承認（`Workspace.apply`）のみ。変更セットの適用の本体（競合の判定 → 適用後の姿の検証 → セッションの1つのトランザクション＋保存）は、カーネルの `workspace.ChangeSetApplier`（P27 段D の D-b・意図的差分 D-35。変更セットの型 `core.ChangeSet` も workspace-core）——root の `Workspace.apply` / `applyViaSession` は、結果をファイルの座標へ写すだけ。適用の規則を root に書き足さない（カーネル側の CLI `workspace apply` と食い違う）。ファイルへ直接書く適用の経路を作らない。書込権限・予約ディレクトリ禁止は `AgentToolbox` が強制する（docs/ARCHITECTURE.md §4.10）。
- 検査を足す・変えるときは、まず YAML の定義体で書けるかを考える（P29 Phase 6。docs/ARCHITECTURE.md §4.36・CONVENTIONS §9）。整合検証（`consistency`）・詳細文書の存在（`coverage`）・エンティティの整合（浮遊詳細化・定義競合・型競合）と、関係レジストリの多重度・対象型（`cardinality`・`source` / `target`）は専用コードではなく組込み定義体 `workspace-core/src/main/resources/builtin-definitions/`（`consistency.yaml`・`coverage.yaml`・`entities.yaml`・`flow.yaml`・`matrix.yaml`・`relations.yaml`）——挙動を変えるならこの YAML と、書き方の形を持つメタスキーマ（`builtin-schemas/schema.yaml`・`relations.yaml`）を直す。定義体の YAML の文面に「: 」を含めるときは引用符で囲む（YAML のパースエラーになる）。関係レジストリから展開された検査は特定のスキーマに属さず、`Validator` がどの文書の要素にも適用する。遷移検証（`flow`）は、アルゴリズムをコードの専用チェッカー（`semantic/Flow.kt`。宣言は `schema/Checker.kt`）に残し、文面・重大度・語彙への結び付けを定義体（`flow.yaml`）が宣言する——チェッカーを足すときは `CheckerKind`・`SemanticCheckers`・メタスキーマを揃える。「何段でも辿る」だけで足りる検査（非循環など）は専用チェッカーにせず、クエリの `edge` の `repeat`（辿る計算だけが専用アルゴリズム＝`SemanticQuery` の `reach`）で YAML に書く。網羅マトリクス（`matrix`）は `forAll`（すべての要素について）へ展開される語彙。`SchemaDef.constraints` は語彙と定義の使用を**展開した後**の検査（由来は `expandedChecks`。CLI `checks` で確認できる）。組込み定義体だけが使える特別な機能を作らない。yaml-core に検査の部品を足さない（解釈に関わるものは semantic-core）。
- クエリの線（`edge`）は、向きによらず「その参照を含む最も内側の ID を持つ要素」が書いた参照として辿る（`dir: out`＝`graph.ownRefs`＝直接書いた参照だけ。docs/ARCHITECTURE.md §4.38・意図的差分 D-27）。入れ子の要素の参照まで辿りたいときは入れ子の線（`contains: true`。`repeat` で何段でも）を組み合わせる——判定に `graph.outgoingRefs`（配下の全参照）を使わない（あれは文脈の開示・エンティティページなど、読むための機能用）。`via` の突き合わせは `QueryEdgeStep.viaMatches` を通す（1語＝キー名・複数語＝場所の末尾のキーの並び）。まだ文書に入れていない内容を検証するときは、その内容を重ねた索引（`AppState.overlayIndexWith`）を渡す。
- 定義体の検査を評価して文書へ配る処理（報告先・他の出現への案内・値と参照の場所・直せる場所への案内・文面・エラー時の対応）は、カーネルの `validate.WorkspaceChecks`（workspace-core）にある。文書1つの検証の全体（構造の検証 → ID と参照 → エンティティ型の点検 → 宣言のない関係名 → 定義体の検査 → テンプレートの健全性 → 直せる場所への案内、という組み立て。台帳の外の文書での降格・同じ指摘の重複の除去）も、カーネルの `validate.DocumentValidator` にある（P27 段D の D-b・意図的差分 D-34。カーネル側の CLI と、変更セットの適用も、同じ部品で検証する）。root の `Validator` は、座標（`Path` と `IdIndex`）を写して呼び、結果を `Issue` へ写すだけ——検証の組み立て・定義体の検査の配り方や文面を root に書き足さない（カーネル側の CLI `workspace validate` と結果が食い違う。`KernelCheckLoadingTest` が一致を確かめている）。コードで実装された検査のうち、ワークスペースの他の文書も見るもの（ID のパターンからの逸脱・重複所有・テンプレートの健全性）も、同じ入口（`WorkspaceChecks.deliver`。実体は `validate.CodedDocumentChecks`）が返す——root の `Validator` に、文書の検査を書き足さない（P27 段D の D-a・意図的差分 D-33）。スキーマの定義そのものの点検（スキーマの定義の点検・宣言のない関係名の点検）は、workspace-core の `SchemaDefinitionChecks` にあり、カーネルのスキーマの読み込み（`WorkspaceReader.buildSchemas`）が読み込みエラーへ足す——root の `SchemaDef.validateDefinition()` は窓口で、点検を足すときはカーネルの側へ足す。検査の定義の読み込み（展開・パース・出どころ・メタスキーマへの語彙のキーの合成）は workspace-core の `CheckCatalog` の1実装で、root の `SchemaLoader` とカーネルの `WorkspaceReader` が共有する。スキーマ文書1件の読み取りと、関係レジストリの集約も、workspace-core の `SchemaDocuments` の1実装（P27 段C の C-3a）——スキーマ文書に新しいキーを足すときは、ここと、メタスキーマ（`builtin-schemas/schema.yaml`）を直す。root とカーネルに、別々の読み取りを書き足さない。
- 入力の YAML から別の形の YAML を組み立てる処理は、まず変換定義（`semantic.SemanticTransform`。docs/ARCHITECTURE.md §4.42・CORE-CALCULUS §26）で書けるかを考える——選ぶのは既存のクエリ（ID の無い場所までクエリを広げない。選んだものの中は `$each` とパスで辿る）、入れ子には規則を子に当てる（`$apply`。同じ規則を同じ場所へ当て直す定義はエラー）。変換の出力はワークスペースへ保存しない（原本へ書くなら、今までどおり変更セットと承認を通す）。出力のキーや値を `$` で始めるときは `$$` と書く。
- 検査の評価は、同じ索引の上では1回だけ（`semantic.SemanticCheckSession`。docs/ARCHITECTURE.md §4.39）。全体を回す経路から `Validator.validate` を呼ぶときは `shareEvaluations = true` を渡す（渡さないと、全体の検証の時間が規模の2乗で伸びる。`bench` の `validateGrowth` が 1.0 前後であることで確かめられる）。ただし共有してよいのは、root と loader の中身が渡す索引と同じ内容のときだけ——索引に重ねていない内容（提案のプレビューなど）を検証する呼び出しでは渡さない（覚え書きに、食い違った結果が残ってしまう）。検査やクエリの評価の中で、全要素・全参照を総なめする処理を「評価のたび」に置かない（グラフや索引の一式につき1回にする）。
- 検査の結果は4つ（違反・充足・保留・対象外。docs/ARCHITECTURE.md §4.37）。どの結果になるかの判定は `semantic.SemanticRules`（カーネル）の1箇所に置き、文面の組み立てと配り方（カーネルの `validate.WorkspaceChecks`。docs/ARCHITECTURE.md §4.41）は違反の根拠を文面へ写すだけ——「指摘が出ない＝充足」という判定を書かない（保留を充足に数えてしまう）。定義体の検査から指摘を作るときは、必ず出どころ（`IssueSource`: 検査の出どころ `RuleOrigin`・対象の要素・配り方・根拠に関わった要素）を付ける。コードで実装された検査（構造の検証・参照の解決など）の指摘を足すときも、カーネルの `validate.CodedChecks` で出どころを付ける（指摘の種類は、文面から読み取らず、指摘を作る場所がデータとして付ける。意図的差分 D-29）。同じ指摘かどうかの判定（提出ゲート `no-new-errors` など）は、文面と場所ではなく `IssueSource.identity` で行う。判定を保留した検査は指摘にせず、`Validator.validateDetailed` の `withheld` に残す。
- 文書の新規作成は `AppState.createDocumentFile` の単一経路（セッションの `CreateDocument` トランザクション→保存。R29-09・D-18）を通す。`Files.writeString` ＋ `reload()` で作らない——`reload()` はセッションを開き直すため **Undo 履歴が全て失われる**うえ、作成自体も Undo できなくなる。文書の作成・削除を含む履歴の Undo / Redo は `afterHistoryStep` がファイルの有無までその場で確定する（docs/ARCHITECTURE.md §4.35）。内部で変更を生成するコードは `edit.YamlOperation`（内部の正準）を使い、`core.PatchOp`／変更セットはエージェント・CLI・skill との交換形式に限る（R29-08）。
- ワークスペースの配置判定（予約ディレクトリ・台帳範囲・索引除外）は `schema/WorkspaceLayout` に一元化されている（R13-02）。機能側で `startsWith("_")` やディレクトリ名の直書き判定を書かない。`.yde/` 構成（定義は `.yde/schemas` 等・`ledger:` で台帳宣言）とレガシー構成（ルートの `_schemas` 等）の2モードがあり、`ledger` 宣言時は台帳外のYAMLが走査・索引・エージェントの対象外になる（エディタ層のスキーマ適用・単文書検証だけが効く。ARCHITECTURE §4.25）。`_runs/`（runs ゾーン）は索引対象外。
- `_schemas/` 配下の文書にはメタスキーマ（`builtin-schema`）**だけ**が適用される（`Workspace.schemaFor`。一般スキーマの match は及ばない）。派生スキーマ（`derive:`）の**導出元文書**の変更はスキーマ変更と同じ扱い（`noteDiskChanges` が全再構築へ振り分け、自動適用は承認待ちへ降格）。derive の写像項目を増やすときは `semantic-core/schema/SchemaDerivation.kt`（parse・compile＝展開コアの実体。読むのはカーネルの `WorkspaceReader` だけ）とメタスキーマ（workspace-core の資源 `builtin-schemas/schema.yaml`）を対で更新する（docs/ARCHITECTURE.md §4.23）。
- 定義文書（`_agents`/`_activities`/`_evals`/`_skills`/`_views`）への書込は `meta: true` のアクティビティのみ。`meta` かつ `approval: auto` はロード時に定義エラー。`_schemas`/`_runs` は meta でも書込不可。
- ビュー（`_views`）はデータを持たない射影。セルは原本座標 `(file, YPath)` に解決され、UIは docCache ツリー直接編集、エージェントは `read_view`→既存 `apply_patch`。ビュー専用の書込経路を作らない（docs/ARCHITECTURE.md §4.14）。ビュー定義の型は、カーネルの型（semantic-core の `semantic/VirtualViews.kt`。root の `ViewDef` などは、その別名）で、読み取りとリントは workspace-core の `workspace/ViewDefinitions.kt` にある（P27 段D の D-c。root の `core.Views` は、ファイルを読んで渡す窓口と、解決の結果をファイルの座標へ写すシェル）——定義項目の追加は、宣言の型・`ViewDefinitions.parseView` と、メタスキーマ `builtin-view`（workspace-core の資源 `builtin-schemas/view.yaml`）を対で更新する。root に、ビュー定義の型や読み取りを書き足さない。リファクタリングの計画は、ビュー定義の追随と言及の警告まで含めて、カーネルのセッション（`WorkspaceSession.planRefactoring`。ワークスペースの定義文書を見る分は `workspace.RefactoringImpact`）が作る——アプリ側で計画を補強しない（入口によって計画が食い違う。意図的差分 D-36）。
- skill は宣言型（import / export）が既定。コード実行skill（kind: run）は `skill/Runner.kt` の `Approval`（人間の事前承認。定義変更で失効）経由でしか実行できず、エージェント・自動実行からは起動不可（docs/ARCHITECTURE.md §4.21）。インポートは直接書かず変更セット（`Workspace.apply`）経由、エクスポートのYAML出力は禁止（原本主義）。入出力形式の追加は `skill/Tables.kt` に閉じる。
- AI呼び出しのHTTP送信は `ai/Http.kt`（`AiHttp.post`）に一元化されている（プロキシ・Basic認証）。OpenAI互換の接続先（Azure・ベースURL上書き）は `ai/Connection.kt` が解決する。独自に `HttpClient` を作らない（docs/ARCHITECTURE.md §4.18）。
- 評価（`EvalRunner`）はワークスペースの一時コピー上で実行する。原本に書くのはレポート（`_runs/eval-*.yaml`）だけ。実行記録の形式を変えたら `RunLog` と `RunMetrics` の両方を直す。
- カーネル（`AgentKernel`）のテストは疑似セッション注入で書く（`KernelTest` 参照）。実APIを叩くテストは書かない。
- 自動化エンジン（`AutomationEngine`）のテストは `tick(now)` / `processOnce()` を直接呼ぶ（タイマー・sleep を使わない。`AutomationTest` 参照）。自動適用後はスタンプ基準を更新し、自己変更で on-change を再発火させないこと。
