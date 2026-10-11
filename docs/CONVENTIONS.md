# 開発規約

本書はコードとドキュメントの書き方の規約を定義します。設計上の方針（依存方向・状態管理・安全性の原則）は [architecture/](architecture/README.md) を参照。規約を変更したら本書を更新してください。

## 1. 言語・コードスタイル

- Kotlin 公式コードスタイル（`kotlin.code.style=official`）。インデント4スペース、1行はおおむね120桁まで。
- KDoc・コード内コメント・コミットメッセージ・UI文言・エラーメッセージは**日本語**で書く（識別子は英語）。
- コメントは「何をするか」の逐語訳ではなく、制約・理由・注意点を書く（例: 「索引はディスク基準のため未保存編集は反映されない」）。
- パッケージごとの配置は [architecture/README.md](architecture/README.md)「モジュールと依存方向」の依存ルールに従う。コアの3モジュールと、アプリの純粋ロジック層（`app.core`・`app.index`・`app.schema`・`app.validate`・`app.agent`・`app.ai`・`app.skill`）に Compose を import しない。

## 2. Compose UI の書き方

- 画面単位で `ui/` に1ファイル。ファイル内の大区分は `// ================ 見出し` 形式のセクションコメントで区切る。
- `YNode` ツリーを表示するコンポーザブルは、先頭で `doc.version` / `state.indexVersion` を読んで再コンポーズを購読する（読み忘れると更新されない）。
- **`Dispatchers.Main` は使わない**（本プロジェクトの classpath には Main dispatcher の提供モジュールがなく、実行時例外になる）。バックグラウンド処理からUIへ反映するときは、スナップショット状態（`mutableStateOf` / `mutableStateListOf`）を直接更新する——スナップショット状態はスレッド安全で、変更は次フレームの再コンポーズに反映される。
- ツリーの変更は必ず `ctx.markEdited()`（または `doc.markEdited()`）とセットで行う。
- 入力欄は `CompactTextField`、色は `Palette.*`、角丸は `PanelShape` を使う。生の `Color(0x...)` や Material 既定色をビューに直接書かない（テーマ切替が壊れるため）。
- 等幅で見せる文字（YAML ソース・差分・ログ・サンプル）は `AppFonts.Mono`（同梱の Cica。ビルド時に取得して資源 `fonts/cica/` に入る）。`FontFamily.Monospace` を書かない（OS 任せで日本語と英数の幅が揃わず、OS ごとに見た目が変わる）。
- 外部の UI ライブラリ（FileKit・Reorderable・Markdown の描画・Vico・ELK）は ui/ の 1 ファイルで包み、ライブラリの型やimport をその外へ出さない。より良いライブラリが見つかったとき、その 1 ファイルだけ書き換えれば済むようにするため。共通の部品（表＝`VirtualTable`・Markdown＝`MarkdownView`・ファイル選択＝`FilePickers`・並べ替え＝`ReorderableList`・グラフ＝`BarChart`・節点と辺の配置＝`GraphLayouts`）を画面ごとに作り直さない。
- 入力欄のプレースホルダーは呼び出し側で装飾しない（`Text("...")` のみ）。`CompactTextField` が「入力テキストと同サイズ・`Palette.SubText` 色」を一律に与える（画面ごとの濃さ・大きさのばらつき防止）。
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

- コミットメッセージは日本語で「何を・なぜ」を要約する（既存履歴のスタイルに合わせる）。利用者に見える挙動を変えたときは、それをコミットメッセージに書く。
- 論理的に独立した変更は分けてコミットする。
- **改行コードは LF**（Windows のバッチ `*.bat` / `*.cmd` だけ CRLF）。決まりはリポジトリの `.gitattributes`（git の保存と取り出し）と `.editorconfig`（エディタ）に置き、開発者ごとの `core.autocrlf` に頼らない。ソースに生の制御文字（NUL など）を書かない（git がバイナリとみなし、差分と正規化から外れる。エスケープ `"\u0000"` で書く）。
- `samples/` は公開用のサンプル、`labs/` は今後の開発用の実験場で、どちらも履歴に含める。試作は `labs/` で作り、ある程度良いものができたら `samples/` へ置き直す。`scratchpad/` は `.gitignore` 対象（履歴に含めない）。
- **バージョンは2層**。外部バージョン（利用者に見せる。メジャー.マイナー.パッチ）は `gradle.properties` の `appVersion` で、リリース時に手で上げる。配布物の `packageVersion` もこれで、形式によってはメジャー 1 以上が要る。
- 内部バージョン（開発管理用。`yyyymmdd.ビルド番号`）はビルド時に git から自動算出する。HEAD のコミット日と、その日の何番目のコミットか。未コミットの変更があれば `-dirty`。`-PbuildVersion=…` で手動指定できる。
- 両方を `build-info.properties` として jar に入れ、スタート画面と CLI `version` が表示する（`app/BuildInfo.kt`）。

## 6. ドキュメント更新規約（重要）

文書は 2 種類に分ける。役割と更新のタイミングは [docs/README.md](README.md) の表が正で、本書には重複して書かない。

- **今の姿**: 何があるか・どう動くか・何を守るかを書く文書。書き直して小さく保つ。
- **記録**: 完了した計画・台帳・経緯。追記だけで `docs/archive/` に置き、読まない前提にする。CLAUDE.md からリンクしない。

規則:

1. 1 つの事実は 1 か所に書き、他からはリンクする。
2. **完了したら移す**。ROADMAP のテーマが完了したら本文を `archive/roadmap-history.md` へ、BACKLOG の指摘が解決したら行を `archive/backlog-closed.md` へ、その時点で移す。今の姿の文書に「〜だった」「当初は」「〜へ移した」を書かない。
3. 設計・マニュアル・規約・機能索引・CLAUDE.md には、チケット番号（P／R／D／UX と決定の番号）と日付を書かない。経緯は git log と archive が持つ。台帳（ROADMAP・BACKLOG・UX-SCENARIOS）は番号を持つ。
4. **形**: 1 行は 200 字まで。表のセルに 2 文以上書かない（説明が要るなら見出しと箇条書きにする）。1 ファイルは 2 万字まで（機能索引と台帳は 4 万字、マニュアルは 3 万字）。超えたら主題で分ける。
5. 見出しは短い名詞句にし、番号を付けない。他の文書やコードからの参照は「ファイル名＋見出しの文言」で書く（例: `architecture/checks.md「4 つの結果」`）。§番号や行番号で参照しない。
6. 行番号・件数など変わりやすい値を書かない。パッケージは接頭辞を省いた短い形で書く（§8）。
7. 機械チェック `scripts/docs-lint.sh`（サイズ・行長・チケット番号・セルの文数・切れたリンク）を通す。`/sync-docs` スキルが呼ぶ。

作業のたびに次を確認する:

1. **着手前**: 設計文書の該当する主題と、FEATURES の該当行を読む（どの文書かは docs/README.md）。
2. **実装後**: docs/README.md の表に従って更新する。機能を足したら FEATURES に 1 行、利用者に見える変更は manual/ に書く。
3. **完了したものは移す**（規則 2）。移す先が無いなら archive に新しいファイルを作り、`archive/README.md` に 1 行足す。

## 7. UXフィードバック運用（機能追加の完了報告と指摘の記録）

機能の追加・変更が「実際にどう触ればよいか分からない」まま放置されることを防ぎ、触り心地の指摘を開発ループへ確実に還流させるための規約。

- **完了報告には「試し方」を必ず含める**: 機能を追加・変更したら、完了報告に（1）具体的な操作手順（起動方法・画面のたどり方・入力する値の例）と（2）**見てほしい観点**（どこに注目し、何を感じたら教えてほしいか）を添える。
- 利用者が触って感想を返し、それを踏まえて**チューニングするかどうかを決める**（作りっぱなしにしない）。試し方の台本は [UX-SCENARIOS.md](UX-SCENARIOS.md) へ蓄積し、操作手順が変わったら該当シナリオへ反映する。
- **気になりポイントは [BACKLOG.md](BACKLOG.md) へ即時記録する**: 作業中・試験中に見つかった「今のタスクと本質が異なる指摘」は、その場で直しに行かず（脱線防止）、まず台帳へ1行登録する。登録の敷居は最低にする（完璧な分析は不要）。
- 対応するか・いつやるかは後で個別に判断し、大きいものは ROADMAP の項目へ起こす。
- **良かった点も残す**: 方向性の確認になった所感（「この体験は正しい」）も台帳の所感メモへ残し、チューニングの判断材料にする。

## 8. モジュールの規約

[decisions/](decisions/README.md) の ADR は本規約と同格。矛盾があれば ADR を正として規約側を直す。

- **依存方向**（ADR-0002）: `yaml-core ← semantic-core ← workspace-core ← cli／アプリ`。コアからアプリ・Compose・AI・agent・skill へ依存しない。
- yaml-core と semantic-core の main ソースへ `java.nio.file.Path` を持ち込まない（テストが fixture 読込に使うのは可）。
- **パッケージ名**: 接頭辞は `dev.toros.yde`、その下にモジュールの根（yaml-core `yaml` / semantic-core `semantic` / workspace-core `workspace` / cli `cli` / アプリ `app`）。
- モジュール名と同じ名前のクラス（`YamlIO`・`SemanticEngine`・`WorkspaceSession`・`AppState`）とアプリの `Main.kt` は根に置き、ほかは根の下に置く（`semantic.schema`・`app.ui` など。対応表は architecture/README.md「モジュールと依存方向」）。同じ裸の名前を複数のモジュールに作らない。
- **文書とコメントでは接頭辞を省いた短い形**（`schema.FieldDef`・`validate/Validator.kt`）で書き、同じ短い名前が複数のモジュールにあるとき（`schema`・`validate`・`core`）はモジュール名を添える。
- **移動であって複製ではない**: ファイルをモジュール間で移すときは `git mv` し、旧位置に残さない。各ステップで `./gradlew test` を緑に保つ。
- **fixture は読み取り専用**: `test-fixtures/` の期待結果は [test-fixtures/README.md](../test-fixtures/README.md) にある。変更を伴うテストは一時ディレクトリへコピーして使う。`labs/` 配下をテストから読まない。
- **設計の決定を変えるとき**は、既存の ADR を書き換えず、新しい ADR で上書きを宣言する。

## 9. Core Calculus（新しい決定論的機能の設計レビュー）

新しい決定論的機能（検証・導出・ビュー・一括変更・リファクタリング等）を追加・拡張するときの規約。方針の原本は [CORE-CALCULUS.md](CORE-CALCULUS.md)（[ADR-0009](decisions/0009-core-calculus.md) で採択）。

- **実装前に CORE-CALCULUS「新機能追加時の設計レビュー」のチェックリスト（10項目）を通す**: 対象選択は Query か・値導出は Expression か・判定は Predicate か・集合演算は既存 Aggregate で足りるか・変更は Rewrite → PatchOp に lower できるか・既存評価器と意味が重複しないか・provenance を付与できるか・評価は決定的か・Unknown / Conflict の意味が定義されているか・新しい書込経路を作っていないか。
- **専用評価器・専用書込経路の追加条件は「既存 calculus で表現不能であること」**。表現できるなら組合せとして実装し、できないなら不足している最小プリミティブの追加を先に検討する（機能でなくプリミティブを増やす）。
- **設計メモ・完了報告に、その機能が6体系（Term / Query / Expression / Predicate / Rewrite / Construct ＋ Aggregate）のどこへ落ちるかを書く**。新分類は CORE-CALCULUS「既存機能の再分類」の分類表へ追記する。
- **LLM 境界**: 確定済み定義の実行結果を LLM に依存させない（AI＝定義を作る／Calculus＝実行する）。
- **語彙の予算**: 表面語彙（スキーマ・レジストリの新しいキー）を増やせるのは、**核にない演算へ名前を付けるとき**（例: flow の到達閉包）だけ。それ以外の新しい検証要求は、まず正準の綴り（[DESIGN-CHECK-CATALOG.md](DESIGN-CHECK-CATALOG.md) §8）の組合せで書けることを確認し、書けない場合も新キーより既存キーの拡張を優先する。
- 表面語彙を追加・変更したときは、CORE-CALCULUS の分類表へ追記し、DESIGN-CHECK-CATALOG §8 の正準の綴りと重ならないことを確認する。
- **検査は、まず YAML の定義体で書けるかを考える**（CORE-CALCULUS「定義層モデル」・architecture/checks.md「定義層の 3 段」）。検証に関わるものは4層——文法プリミティブ（yaml-core。YAML の構造と最小の構造チェックだけ・小さく保つ）／意味プリミティブ（semantic-core。どう解釈するか）／組込み定義体（同梱の YAML）／ユーザ定義。
  - 既存のプリミティブの組合せで無理なく書ける検査は、コードで作らず**定義体**（`builtin-definitions/*.yaml` の `functions` / `vocabulary`）にする。
  - 書けないなら、足りない部分を小さな意味プリミティブとして足す（想定される処理と計算は需要を待たずに用意してよい）。
  - 複雑なアルゴリズムの検査は**専用チェッカー**として個別に実装してよい（すべてを YAML で定義できる必要はない）。
  - yaml-core には、YAML の構造と最小の構造チェックに属するものしか足さない——迷うものは意味側。
  - **組込み定義体だけが使える特別な機能を作らない**（組込みにできることは利用者にもできる）。
  - 指摘のメッセージとエラー時の対応は、検査の実装へ作り込まず、定義の宣言（`message` / `responses`）で決める。設計メモ・完了報告には、その検査をどの層に置いたかを書く。
- **共有評価部を通す**: 値どうしの比較・出現からの値の読み出し・式側の出現選択は `semantic.SemanticExpression`（`compare` / `textAt` / `select`）を通し、`toDoubleOrNull` の比較や `firstOrNull { selector.matches }` を機能側へ書かない（比較の3値＝数値として読めなければ判定保留、選択の定義出現優先を全機能で同じにするため）。
- 規則系の検証語彙（起点エンティティごとに判定して定義出現へ報告する型）の指摘は、まず YAML の定義体で書く。配り方そのものを足すときは、カーネルの `validate/WorkspaceChecks.kt`（workspace-core）に**診断を1つ足す**——判定式やメッセージ組立を配送先（報告先／出現先）ごとに書かない。規則の level → 重大度は `CoverageLevel.toSeverity()`。
- **変更操作は Rewrite 共有基盤を通す**: 文書への変更を生成する操作（リファクタリング・一括変更の類）は「場所の列挙 × 絞り込みと中止条件 × 場所ごとの操作」で書き、列挙と集積は `semantic.SemanticRewrite`（`walkDocument` / `fieldHits` / `reach` / `forEachSchemaEntry` / `rewriteDocuments` / `PlanOps`）を使う——スキーマ誘導の走査や文書ループを操作ごとに書き直さない。
- 中止条件は部分計画を返さず全対象の評価後にまとめて中止する。計画の内容（操作の順序を含む）を変える変更は `RefactoringPlanGoldenTest` の期待値差分としてレビューし、既存挙動の変更ならコミットメッセージに書く（architecture/schema.md「リファクタリング」）。
- **新しい YAML を組み立てる処理は、まず変換定義で書けるかを考える**（CORE-CALCULUS「Construct Calculus」・architecture/transform.md「変換」）。入力の YAML から別の形の YAML を作る処理（スキーマからの導出・一覧や帳票のもとになる値の一式など）は、`SemanticTransform` の変換定義（選ぶ × 組み立てる × 規則を子に当てる）で書けるかを先に確かめ、書けないなら足りない部品を足す。
- 組み立ての Kotlin を機能ごとに書き足さない。変換の出力はワークスペースへ保存しない（原本へ書くなら Rewrite を通す）。
