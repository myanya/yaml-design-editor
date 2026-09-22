# 開発規約

本書はコードとドキュメントの書き方の規約を定義します。設計上の方針（依存方向・状態管理・安全性の原則）は [ARCHITECTURE.md](ARCHITECTURE.md) を参照。規約を変更したら本書を更新してください。

## 1. 言語・コードスタイル

- Kotlin 公式コードスタイル（`kotlin.code.style=official`）。インデント4スペース、1行はおおむね120桁まで。
- KDoc・コード内コメント・コミットメッセージ・UI文言・エラーメッセージは**日本語**で書く（識別子は英語）。
- コメントは「何をするか」の逐語訳ではなく、制約・理由・注意点を書く（例: 「索引はディスク基準のため未保存編集は反映されない」）。
- パッケージごとの配置は [ARCHITECTURE.md](ARCHITECTURE.md) §2 の依存ルールに従う。`model`〜`ai` の純粋ロジック層に Compose を import しない。

## 2. Compose UI の書き方

- 画面単位で `ui/` に1ファイル。ファイル内の大区分は `// ================ 見出し` 形式のセクションコメントで区切る。
- `YNode` ツリーを表示するコンポーザブルは、先頭で `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する（読み忘れると更新されない）。
- **`Dispatchers.Main` は使わない**（本プロジェクトの classpath には Main dispatcher の提供モジュールがなく、実行時例外になる）。バックグラウンド処理からUIへ反映するときは、スナップショット状態（`mutableStateOf` / `mutableStateListOf`）を直接更新する——スナップショット状態はスレッド安全で、変更は次フレームの再コンポーズに反映される。
- ツリーの変更は必ず `ctx.markEdited()`（または `doc.markEdited()`）とセットで行う。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape` を使う。生の `Color(0x...)` や Material 既定色をビューに直接書かない（テーマ切替が壊れるため）。
- 入力欄のプレースホルダーは呼び出し側で装飾しない（`Text("...")` のみ）。`CompactTextField` が「入力テキストと同サイズ・`Palette.SubText` 色」を一律に与える（画面ごとの濃さ・大きさのばらつき防止。UX-42）。
- 文字サイズの目安: 見出し 13–16sp、本文 12–13sp、補足 10–11sp。
- 破壊的操作（削除・破棄・一括変更）は `AlertDialog` で確認し、確定ボタンの文言に `Palette.ErrorRed` を使う。

## 3. エラー処理・検証

- ユーザー操作に対する失敗は例外を漏らさず `Result` / `runCatching` で受け、`state.status` へ日本語メッセージで反映する。
- 事前条件は `require(...) { "利用者に見せられる日本語メッセージ" }`。
- 検証結果は `Issue(severity, message, path)` に統一し、必ず `YPath` を紐付けてジャンプ可能にする。

## 4. テスト

- テストは `src/test/kotlin` にプロダクトと同じパッケージで置き、`kotlin.test` を使う。
- **新設するテストのメソッド名は日本語**のバッククォート文で書き、意図を文で表す（例: `` fun `保存後もUndoできる`() ``）。既存の英語名テスト（例: `idPatterns filter candidates`）は無理に改名せず、そのファイルへ手を入れる機会に合わせて直してよい。
- UIコンポーザブルは自動テスト対象外。**テストしたいロジックはコンポーザブルから純粋関数へ切り出し**、`internal` にしてテストする（例: `calculateDiff`, `combineProposal`, `parseResponse`）。
- ネットワークは叩かない。APIクライアントはレスポンス解析関数（`parseResponse(statusCode, body)`）を単体テストする。
- 実行: `./gradlew test`（コミット前に通すこと）。

## 5. Git

- コミットメッセージは日本語で「何を・なぜ」を要約する（既存履歴のスタイルに合わせる）。
- 論理的に独立した変更は分けてコミットする。
- `sample-workspace/` と `gradle/` は `.gitignore` 対象（履歴に含めない）。

## 6. ドキュメント更新規約（重要）

ドキュメントは以下の4つに役割分担しており、**同じ内容を複数に書かない**。迷ったら役割で判断し、他方にはリンクを張る。

| ドキュメント | 役割 | 更新するタイミング |
|---|---|---|
| [README.md](../README.md) | 利用者マニュアル（ビルド・操作方法・スキーマ定義リファレンス・制約） | 利用者から見える仕様・操作・UIが変わったとき |
| [docs/FEATURES.md](FEATURES.md) | 機能台帳（一覧・実装状況・テスト有無・未実装） | 機能の追加・変更・削除、テスト追加、制約の解消 |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | クラス設計・設計方針・拡張ポイント | パッケージ／クラスの追加・移動、設計方針・データフローの変更 |
| [docs/ROADMAP.md](ROADMAP.md) | 将来構想・フェーズ別ロードマップ | 方向性・優先度の変更、ロードマップ項目（Rx-xx）の着手・完了（完了時は FEATURES.md へ移す） |
| [docs/BACKLOG.md](BACKLOG.md) | 指摘台帳（UX試験・レビューで見つかった、今のタスクと本質が異なる気になりポイント） | 気になりポイントに気づいたとき**即時**に登録（§7）。対応判断・解決時に状態を更新 |
| [docs/UX-SCENARIOS.md](UX-SCENARIOS.md) | UX試験のシナリオ台帳（台本・実施トリガ・実施履歴） | シナリオの執筆・改訂、試験の実施後（履歴追記）、操作手順が変わる機能変更時（台本への反映） |
| [docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](THREE-LAYER-CORE-MIGRATION-PLAN.md) | 3層コア移行の計画（フェーズ・完了条件・移行先対応表） | 移行方針・フェーズ構成を変えるとき |
| [docs/MIGRATION-LEDGER.md](MIGRATION-LEDGER.md) | 移行台帳（作業単位の状態と記録） | 移行作業の着手・完了のたび（§8） |
| [docs/decisions/](decisions/README.md) | ADRと意図的差分一覧 | 設計決定の追加・変更（既存ADRは書き換えず新しいADRで上書き宣言）、既存挙動を変える前（INTENTIONAL-DIFFS.md へ登録） |
| [docs/CORE-CALCULUS.md](CORE-CALCULUS.md) | YAMLに対する計算体系の設計方針（ADR-0009 で採択・§9 の設計レビューの原本） | 計算体系の原則・体系構成を変えるとき（新しいADRで上書き宣言とセット） |
| [docs/DESIGN-CHECK-CATALOG.md](DESIGN-CHECK-CATALOG.md) | 設計チェックカタログ（「設計への機械的チェック」のパターン台帳。R28-06） | 検証機構の追加・変更時にパターンを1行追加、テンプレートで実証した制約の実例更新時 |
| [docs/CONVENTIONS.md](CONVENTIONS.md)（本書） | 書き方の規約 | 規約自体を変えるとき |

作業のたびに次を確認する:

1. **着手前**: 対象領域の FEATURES.md の該当行と ARCHITECTURE.md の該当節を読む。
2. **実装後**: 上の表に従って影響するドキュメントを更新する（機能追加なら FEATURES.md に行を追加し、README に利用方法を書く）。
3. コード内の行番号・件数など変わりやすい値はドキュメントに書かない（クラス名・ファイル名で参照する）。パッケージは接頭辞 `dev.toros.yde.<モジュール>` を省いた短い形で書く（§8-3）。

## 7. UXフィードバック運用（機能追加の完了報告と指摘の記録）

機能の追加・変更が「実際にどう触ればよいか分からない」まま放置されることを防ぎ、触り心地の指摘を開発ループへ確実に還流させるための規約。

- **完了報告には「試し方」を必ず含める**: 機能を追加・変更したら、完了報告に（1）具体的な操作手順（起動方法・画面のたどり方・入力する値の例）と（2）**見てほしい観点**（どこに注目し、何を感じたら教えてほしいか）を添える。利用者が触って感想を返し、それを踏まえて**チューニングするかどうかを決める**（作りっぱなしにしない）。試し方の台本は UX試験（ROADMAP P17）のシナリオとして [UX-SCENARIOS.md](UX-SCENARIOS.md) へ蓄積し、操作手順が変わったら該当シナリオへ反映する。
- **気になりポイントは [BACKLOG.md](BACKLOG.md) へ即時記録する**: 作業中・試験中に見つかった「今のタスクと本質が異なる指摘」は、その場で直しに行かず（脱線防止）、まず台帳へ1行登録する。登録の敷居は最低にする（完璧な分析は不要）。対応するか・いつやるかは後で個別に判断し、大きいものは ROADMAP の項目へ起こす。
- **良かった点も残す**: 方向性の確認になった所感（「この体験は正しい」）も台帳の所感メモへ残し、チューニングの判断材料にする。

## 8. 3層コアの規約（移行計画は完了・併存期も引き続き適用）

3層コア移行（[THREE-LAYER-CORE-MIGRATION-PLAN.md](THREE-LAYER-CORE-MIGRATION-PLAN.md)）は計画の全フェーズが完了した（2026-08-21・台帳の Phase 12 完了判定）。現在はアプリ（旧経路）とコア＋cli（新経路）の併存期であり、コア・シェル・残課題（6E 等）に触る作業では以下を通常規約に加えて**引き続き適用する**。[decisions/](decisions/README.md) のADRは本規約と同格であり、矛盾がある場合はADRを正として規約側を直す。

1. **着手前に台帳を見る**: コア（model / yaml / schema / index / validate / core）へ触る前に [MIGRATION-LEDGER.md](MIGRATION-LEDGER.md) で対象の移行状態を確認する。done（移動済み）のコードは移行先モジュールで変更する。
2. **移動であって複製ではない**: ファイルはモジュールへ `git mv` し、旧位置に残さない。各ステップでアプリ含む `./gradlew test` を緑に保つ。
3. **パッケージの名前（2026-09-22・UX-119 で改名済み）**: 接頭辞は `dev.toros.yde`、その下にモジュールの根＝yaml-core `yaml` / semantic-core `semantic` / workspace-core `workspace` / cli `cli` / アプリ `app`。モジュール名と同じ名前のパッケージ（`YamlIO`・`SemanticEngine`・`WorkspaceSession`・`AppState`）とアプリの `Main.kt` は根に置き、ほかは根の下にそのまま（`dev.toros.yde.semantic.schema`・`dev.toros.yde.app.ui` など。対応表は ARCHITECTURE §2）。新しいコードもこの規則に従う（同じ裸の名前を複数のモジュールに作らない）。**ドキュメントとコメントでは接頭辞 `dev.toros.yde.<モジュール>` を省いた短い形**（`schema.FieldDef`・`validate/Validator.kt`）で書き、同じ短い名前が複数のモジュールにあるとき（`schema`・`validate`・`core`）はモジュール名を添える。
4. **依存方向の厳守（ADR-0002）**: `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`。3coreからアプリ・Compose・AI・agent・skillへの依存禁止。yaml-core / semantic-core のmainソースへ `java.nio.file.Path` を持ち込まない（テストがfixture読込に使うのは可）。
5. **挙動差分は登録が先**: 既存挙動を変える場合、実装より先に [decisions/INTENTIONAL-DIFFS.md](decisions/INTENTIONAL-DIFFS.md) へ行を足す。登録なき挙動差分は移植ミスとして扱う。
6. **コミットの分類**: 変更は port（移動）／refactor／behavior-change のいずれかに分類し、1コミットに混ぜない（計画書§10.7）。
7. **fixtureは読み取り専用**: `test-fixtures/` の期待結果は現行実装で確認済み（[test-fixtures/README.md](../test-fixtures/README.md)）。変更を伴うテストは一時ディレクトリへコピーして使う。`sample-workspace/` は従来どおり変更しない。
8. **台帳の記録**: 作業単位の完了時に MIGRATION-LEDGER.md の状態と記録様式（旧実装・新しい公開API・維持する挙動・意図的差分）を更新する。

## 9. Core Calculus（新しい決定論的機能の設計レビュー）

新しい決定論的機能（検証・導出・ビュー・一括変更・リファクタリング等）を追加・拡張するときの規約。方針の原本は [CORE-CALCULUS.md](CORE-CALCULUS.md)（[ADR-0009](decisions/0009-core-calculus.md) で採択）。

- **実装前に [CORE-CALCULUS.md §20](CORE-CALCULUS.md) のチェックリスト（10項目）を通す**: 対象選択は Query か・値導出は Expression か・判定は Predicate か・集合演算は既存 Aggregate で足りるか・変更は Rewrite → PatchOp に lower できるか・既存評価器と意味が重複しないか・provenance を付与できるか・評価は決定的か・Unknown / Conflict の意味が定義されているか・新しい書込経路を作っていないか。
- **専用評価器・専用書込経路の追加条件は「既存 calculus で表現不能であること」**（同 §10・§20）。表現できるなら組合せとして実装し、できないなら不足している最小プリミティブの追加を先に検討する（機能でなくプリミティブを増やす。同 §3.1）。
- **設計メモ・完了報告に、その機能が6体系（Term / Query / Expression / Predicate / Rewrite / Construct ＋ Aggregate）のどこへ落ちるかを書く**。新分類は同 §21 の分類表へ追記する。
- **LLM 境界**: 確定済み定義の実行結果を LLM に依存させない（AI＝定義を作る／Calculus＝実行する。同 §18）。
- **語彙の予算（R29-04・Phase 2 の決定 MP-04）**: 表面語彙（スキーマ・レジストリの新しいキー）を増やせるのは、**核にない演算へ名前を付けるとき**（例: flow の到達閉包）だけ。それ以外の新しい検証要求は、まず正準の綴り（[DESIGN-CHECK-CATALOG.md](DESIGN-CHECK-CATALOG.md) §8）の組合せで書けることを確認し、書けない場合も新キーより既存キーの拡張を優先する。表面語彙を追加・変更したときは計算体系の写像表（`specs/20-semantics/calculus/feature-mapping.yaml`）へ1行追加し、重複クラスタ（OC-01〜）への影響を確認する。
- **検査は、まず YAML の定義体で書けるかを考える（P29 Phase 6。CORE-CALCULUS §25・ARCHITECTURE §4.36）**: 検証に関わるものは4層——文法プリミティブ（yaml-core。YAML の構造と最小の構造チェックだけ・小さく保つ）／意味プリミティブ（semantic-core。どう解釈するか）／組込み定義体（同梱の YAML）／ユーザ定義。①既存のプリミティブの組合せで無理なく書ける検査は、コードで作らず**定義体**（`builtin-definitions/*.yaml` の `functions` / `vocabulary`）にする ②書けないなら、足りない部分を小さな意味プリミティブとして足す（想定される処理と計算は需要を待たずに用意してよい）③複雑なアルゴリズムの検査は**専用チェッカー**として個別に実装してよい（すべてを YAML で定義できる必要はない）④yaml-core には、YAML の構造と最小の構造チェックに属するものしか足さない——迷うものは意味側 ⑤**組込み定義体だけが使える特別な機能を作らない**（組込みにできることは利用者にもできる）⑥指摘のメッセージとエラー時の対応は、検査の実装へ作り込まず、定義の宣言（`message` / `responses`）で決める。設計メモ・完了報告には、その検査をどの層に置いたかを書く。
- **共有評価部を通す（R29-05/06・Phase 3）**: 値どうしの比較・出現からの値の読み出し・式側の出現選択は `semantic.SemanticExpression`（`compare` / `textAt` / `select`）を通し、`toDoubleOrNull` の比較や `firstOrNull { selector.matches }` を機能側へ書かない（比較の3値＝数値として読めなければ判定保留、選択の定義出現優先＝CALC-19 を全機能で同じにするため）。規則系の検証語彙（起点エンティティごとに判定して定義出現へ報告する型）の指摘は、まず YAML の定義体で書く。配り方そのものを足すときは、カーネルの `validate/WorkspaceChecks.kt`（workspace-core）に**診断を1つ足す**——判定式やメッセージ組立を配送先（報告先／出現先）ごとに書かない（ARCHITECTURE §4.32「規則の診断と配送」）。規則の level → 重大度は `CoverageLevel.toSeverity()`。
- **変更操作は Rewrite 共有基盤を通す（R29-07・Phase 4）**: 文書への変更を生成する操作（リファクタリング・一括変更の類）は「場所の列挙 × 絞り込みと中止条件 × 場所ごとの操作」で書き、列挙と集積は `semantic.SemanticRewrite`（`walkDocument` / `fieldHits` / `reach` / `forEachSchemaEntry` / `rewriteDocuments` / `PlanOps`）を使う——スキーマ誘導の走査や文書ループを操作ごとに書き直さない。中止条件は部分計画を返さず全対象の評価後にまとめて中止する。計画の内容（操作の順序を含む）を変える変更は `RefactoringPlanGoldenTest` の期待値差分としてレビューし、既存挙動の変更なら先に INTENTIONAL-DIFFS へ登録する（ARCHITECTURE §4.35）。
- **新しい YAML を組み立てる処理は、まず変換定義で書けるかを考える（P27 段C。CORE-CALCULUS §26・ARCHITECTURE §4.42）**: 入力の YAML から別の形の YAML を作る処理（スキーマからの導出・一覧や帳票のもとになる値の一式など）は、`SemanticTransform` の変換定義（選ぶ × 組み立てる × 規則を子に当てる）で書けるかを先に確かめ、書けないなら足りない部品を足す。組み立ての Kotlin を機能ごとに書き足さない。変換の出力はワークスペースへ保存しない（原本へ書くなら Rewrite を通す）。
- 意味論の正式な仕様（Phase 1 以降のスペック）は `specs/` の外部仕様化ワークスペースで管理する（着手前に [specs/SPECIFICATION-GUIDE.md](../specs/SPECIFICATION-GUIDE.md) 全読が規約）。
