# ワークスペース

ワークスペースの物理配置（レガシー構成・`.yde` 構成）と機能の適用範囲（エディタ層・台帳層）の判定は `WorkspaceLayout` に一元化する。
アプリはディスクを映すセッション（`core.Workspace` の内部）と編集用のセッション（`AppState`）を持ち、ディスクの変更の取り込みは `Workspace.noteDiskChanges` が振り分ける。変更セットの適用はカーネルの `ChangeSetApplier` の 1 実装。

## 部品

| 部品 | ファイル | 責務 |
|---|---|---|
| `WorkspaceLayout` | workspace-core/layout/WorkspaceLayout.kt | 配置ポリシー。レガシー／`.yde` 構成の判定・ゾーン解決（`zoneOf` / `isReserved`）・台帳判定（`inLedger`）・索引除外（`indexExcluded`） |
| `Workspace` / `WorkspaceScan` | core/Workspace.kt | ヘッドレスなワークスペース操作の窓口（開く・読む・保存・検証・変更セット適用・`schemaFor`・`refresh`）。相対パス解決はワークスペース外への脱出を拒否 |
| `WorkspaceSession` | workspace-core | カーネルのセッション（読み込み・派生状態・履歴・トランザクション・保存・`fork`・`planRefactoring` / `applyRefactoring`） |
| `WorkspaceReader` | workspace-core | 読み込み（スキーマ・文書・派生状態の導出 `derive`） |
| `EditBuffer` / `WorkspaceRecords` | core/Documents.kt | 文書キャッシュ層の中核（複数ドキュメントの再出力・保存内容との比較・一覧レコード・一括編集） |
| `FileWatcher` | core/FileWatcher.kt | WatchService による再帰的なファイル監視。「変更があったか」のフラグだけを通知し、使えない環境では open() が null（ポーリングへフォールバック） |
| `ChangeSet` / `FileChange` / `ChangeSets` | workspace-core/changeset/ChangeSets.kt | 変更セット（複数の文書への変更の束・base ハッシュ・YAML の読み取り） |
| `ChangeSetApplier` / `ChangeSetApplication` | workspace-core/workspace/ChangeSetApplier.kt | 変更セットの適用の本体 |
| `RefactoringImpact` | workspace-core/workspace/RefactoringImpact.kt | リファクタリングの計画のうち定義文書を見て決まる分 |
| `Disclosure` | core/Disclosure.kt | 段階的開示（カタログ → 骨子 → ノード全文・文字数予算つきバンドル） |
| `Bench` | core/Bench.kt | 合成ワークスペースの生成と計測（[README.md「規模の計測」](README.md)） |
| `BuildInfo` | app/BuildInfo.kt | ビルド時に生成される `build-info.properties`（外部バージョン・内部バージョン・コミット）を読む |

## ワークスペース構造と layout

- 配置の判定は `WorkspaceLayout` の 1 つのポリシーオブジェクト。「どこが定義・記録ディレクトリか」「どこが台帳か」「索引から除外するか」の述語群に分解されている。機能側でプレフィックスやディレクトリ名の直書き判定を書かない。
- **2 つの構成モード**: `.yde/` が無ければレガシー構成（ルート直下の `_schemas` 等 7 予約ディレクトリ・台帳＝ワークスペース全体）。あれば `.yde` 構成（定義・記録は `.yde/schemas` 等のサブディレクトリ・`.yde/workspace.yaml` の `ledger:` で台帳ディレクトリを宣言。未宣言なら全体）。判定は `WorkspaceLayout.of(root)` で、`Workspace` が保持し `refresh()` で再評価する。
- `.yde/workspace.yaml` の変更は走査規則が変わるためスキーマ変更と同じ扱い（全再構築）。レガシー → `.yde` の移行は CLI `migrate`（ディレクトリ移動と構成ファイル作成だけ）。
- `.yde/workspace.yaml` の `files:`（`FilePolicy`＝`WorkspaceLayout.files`）は file 型の値の許可: `outside`（ワークスペースの外）・`allowedRoots`（外でも許す場所）・`missing`（無いファイル）。読めない値は既定（外は警告・無いファイルは違反）。ファイルの存在は `DocumentStore.exists`（YAML 以外も）が答え、`LoadedWorkspace.fileExists` と `WorkspaceSession.fileExists` が検証へ渡す。
- 構成ファイル（`workspace.yaml`）にはスキーマを当てない（`schemaFor` は null）。`_runs/`（runs ゾーン）は索引対象外。

## エディタ層と台帳層

- 分割基準は「単一ファイルで完結するか」。スキーマ適用（match → フォーム・単文書検証・表示解決）は 1 文書とスキーマ定義で完結するためエディタ層＝台帳外の YAML にも効く。`match` は常にワークスペース相対。
- 台帳層（ID 索引・参照解決・バックリンク・カバレッジ・ビュー・エージェント・自動化・入出力 skill・検証の集計）は `inLedger` のファイルだけを対象にする。台帳外の文書では未解決参照を情報レベルへ降格する（`Validator.validate` の `indexed` フラグ）。
- 走査は台帳と構成ディレクトリのみ（`WorkspaceScan`。隠しディレクトリを枝刈り、`.yde` だけ例外）。台帳外 YAML の表示・編集は UI 側の別走査（`AppState.editorFiles`）で、ファイルツリーで視覚的に区別する。
- 書込権限: エージェントの書込可否は `inLedger`（メタアクティビティは定義ゾーンも可）に一本化（`AgentToolbox`）。`.yde/workspace.yaml` と `schemas` / `runs` ゾーンはメタでも書込不可。skill・evals のパース時の静的検査は保守的に「`_` または `.` 始まりのパス禁止」。

## 文書キャッシュと Workspace

- `AppState` は走査・スキーマ・索引・更新方針を保持する `core.Workspace` へ委譲し、Compose 購読用のミラー（`schemas` / `yamlFiles` / `indexVersion`）・文書キャッシュ（`docCache`）・ナビゲーション・ファイル操作を担う。ミラーは読み取り専用の写しで、core 側を更新したら `mirrorCore` で写し直し `indexVersion` を進める。
- `OpenDocument` は `EditBuffer` のシェル（[editing-history.md](editing-history.md)）。編集 API の意味を変えるときは `EditBuffer` を直す。
- `docCache` は `mutableStateMapOf` で、文書と内部の State を同じスナップショットから参照する。描画開始後に生成した文書は次の描画から見え、破棄したスナップショットの文書は公開しない。
- 一覧・一括編集（`WorkspaceRecords`）は「未保存編集を優先する木の取得」を注入で受ける。UI は docCache を、ヘッドレス経路はディスクを渡す。
- `Workspace.refresh` は、開いたセッションが読んだ結果を `SchemaLoader.fromKernel` でアプリの形にする。root でファイルからスキーマを読み直さない。

## 外部変更の取り込み

- 保存・外部変更・変更セット適用のいずれも、`Workspace.noteDiskChanges` が「スキーマ（`_schemas/`）・構成ファイル・派生スキーマの導出元文書が変わったら全再構築、それ以外は変更ファイルだけの増分更新」を振り分ける。UI・CLI・エージェント経路が共有する。
- 外部変更の検出は `FileWatcher` の変更フラグ（または 1 秒周期のフォールバック）から `pollExternalChanges` のスタンプ比較へ進む。編集・履歴がある場合は下記の候補取り込みを使い、それ以外は増分更新する。

- 自動化エンジンの監視もイベント駆動＋フォールバック（[agents.md「自動実行」](agents.md)）。

## 再読込と履歴の保持

- `AppState.reload` はディスク版の `core.Workspace` を候補として開き、編集用セッションの `refreshFrom` で取り込みを検査する。成功した場合だけディスク側と画面のミラーを交換する。
- `WorkspaceSession.refreshFrom` は既知の生テキストとの差から変更を求め、未保存文書・削除予約・アプリが渡す下書きの保護対象を残して外部版を候補へ反映する。派生状態の導出まで成功してから交換し、ストアへは書かない。
- Undo/Redo の両スタック・未表示文書を含む working・保存基準・削除予約・配置履歴を維持する。画面側の文書・下書き・カーソル・`historyLocations` も変更のない対象では保持する。
- 保護対象の外部変更は保存基準ごと未解決の競合として残し、無関係な変更は取り込む。保存と Undo/Redo は生テキストの比較で停止し、明示的な上書き許可かディスク版の採用で解決する。
- 採用した外部版に触れる既存の Undo/Redo 記録は `invalidatedHistory` に対象を記録し、履歴自体は消さず適用を拒否する。一括履歴の一部だけを適用せず、その後の新しい編集は通常どおり戻せる。
- スキーマ・導出元・構成が変わった場合は、保護した working と外部版を合わせた候補からスキーマ・割当・派生を再構築する。定義の読込エラーや、新構成が編集・履歴の対象を扱えない場合は全体を中止し、現在値・保存基準・履歴を残す。
- 保存・監視による同期でも、保持すべき状態がある場合は同じ `refreshFrom` を使う。失敗時にセッションの開き直しへ降格しない。
- カーネルの `reload()` は履歴を捨てて開き直す低水準 API のまま。通常の画面再読込には使わない。
- 明示的な破棄は `AppState.requestDiscardAndReload` → `ui/ReloadConfirmationDialog` → `confirmDiscardAndReload`。未保存の対象と両スタックの件数を示し、確認時の文書・下書き・セッションが変わっていない場合だけ実行する。
- 破棄では新しい Workspace・編集用セッション・ファイル一覧の読込成功後に交換し、文書キャッシュ・復帰先・通知・確認待ちを消す。キャンセルと読込失敗では残し、ディスク上の内容や配置を巻き戻さない。
- Core Calculus 上は既存 Term と変更履歴の整合を保つ状態管理で、Query・Expression・Predicate・Aggregate・Rewrite・Construct の演算や書込経路を追加しない。

## 保存時の競合

- `WorkspaceSession.save` / `saveAsText` は書き込み・削除の直前に既知の生テキストと現物を比較する。dirty の正規化テキストとは分けて持ち、更新時刻に依存しない。
- 不一致は対象の保存失敗として返し、履歴と未保存内容を残す。`SaveResult.conflicts` とテキスト保存の `externalConflict` は読み取りエラーと区別する。
- `acknowledgeStoreText` は明示上書きで許可された版を記録する。dirty の基準は変えず、保存前に再び内容を比較する。`acceptStoreText` は採用したディスク版へ文書と保存基準を揃える。
- アプリは自動値を入れる前にも検査し、競合した文書を除いて保存する。テキスト文書・構成ファイルは `OpenDocument.expectedStoreText` を比較基準に使う。
- 未保存の文書は監視による取り込みから除く。ワークスペース構成が同じなら、同時に届いた他文書の通知でも、未表示文書の Undo と保存基準を保持する。
- ファイル配置変更の強制保存も検査を通す。検査済みの控えに物理移動と文面変更を適用した期待値を使い、現物の読み直しで外部変更を承認しない。
- 比較と書き込みの間を排他する OS ロック、および複数ファイルの保存全体の原子性は保証しない。

## セッションの fork

- ワークスペースを開くとき、`WorkspaceSession.fork()` がディスクを映すセッションの読み込み済みの状態から編集用セッションを起こす。読み込み・パースを重複せず、履歴は引き継がない。通常の再読込は上記の `refreshFrom` を使う。
- 文書の中身は初めは同じものを指すが、セッションは変更のたびに対象を写してから書き換える（ADR-0003）うえ、導出した結果は毎回新しく作る変わらない値なので、片方の編集はもう片方へ漏れない。未保存の変更を持つセッションからは起こせない。
- 2 つのセッションを 1 つにまとめない。編集用は未保存の編集と作業中のスキーマを持ち、ディスクを映す側はエージェント・自動実行・CLI の基準。

## 変更セットの適用

- `ChangeSetApplier.apply(base, changeSet, dryRun, requireValid, target)`: ①変更ごとにストアの内容に対して競合を判定し（`create` は既にあれば競合・`base` のハッシュの食い違いは競合）、メモリの上で操作を当てる。1 件でも失敗すれば何も書かない。
- ②適用後の姿を重ねた派生状態の上で、変更した文書をアプリの検証と同じ組み立て（`DocumentValidator`）で検証する。③`requireValid` なら検証のエラーが残るときも書かない。④書き込みはセッションの 1 つのトランザクション＋保存。Undo 1 回で全部の文書が戻る。
- `base` は競合の判定と検証の基準にするセッション、`target` は書き込み先。CLI と root の `Workspace.apply` は同じセッションを両方に使う。アプリの承認の経路（`applyViaSession`）は `base` にディスクを映した内部のセッション、`target` に編集用のセッションを渡す（検証の基準はディスクの内容）。
- 適用できるのはセッションが読み込んでいる YAML 文書と新しく作る文書だけ。変更セットの `file` はワークスペース相対のパスだけ。
- 保存時に自動で入る値（更新日・版。[auto-values.md](auto-values.md)）は、操作を当てた木へ検証の前に入れる（必須の更新日が無い新しい要素も通る）。同じトランザクションに含まれ、Undo 1 回で一緒に戻る。「今」は `apply` の `now` で渡す。
- root の `Workspace.apply` / `applyViaSession` はカーネルの適用を呼び、結果をファイルの座標へ写して、書いたファイルの分だけ索引を更新する。カーネル側の CLI は `workspace apply <root> <変更セット.yaml> [--dry-run] [--require-valid]`（終了コード 0＝成功・2＝競合や操作のエラー・3＝検証エラーで書き込まず）。

## リファクタリングの影響

フローのprojection内の言及は、フィールド・ID・関係名・スキーマIDの変更計画に含める。
静的な意味を保証しない保守的な走査であり、規則・条件・辞書キーも確認対象として列挙し、自動置換しない。
埋め込みのビュー参照はref型として通常の改名計画へ含まれ、専用の書込経路を持たない。

- リファクタリングの計画のうちワークスペースの定義文書を見て決まる分（フィールドの改名へのビュー定義の追随・宣言節と定義文書の言及の警告・フィールドの削除の言及の警告・文書の移動でビュー定義の対象から外れる警告）は `RefactoringImpact` が作り、`WorkspaceSession.planRefactoring` が計画に含める。
- ビュー定義・スキーマ文書・定義文書の内容は保存されている内容から読む（`storedText` / `storedDocuments`。ディレクトリの直下のファイルだけ・ファイル名順）。どの入口（アプリ・CLI・シェル）でも同じ計画になる。root の `core.RefactorImpact` は窓口。
- root に意図して残す読み出しモデル: 画面とエージェントの文脈づくりのためのもの（`Traverse`・`Disclosure`・`Search`・`SchemaInference`・`IdSuggest`・`Aspects`・`Readiness`）。

## 規律

- `WorkspacePaths` が相対パスと実体の所属を検証する。新規作成先は最も近い既存祖先を確認し、外部リンク・壊れたリンクも拒否する。AI の書込先は内部リンクも拒否する。
- 変更セットの事前検証失敗では原本を書かないが、保存段階の I/O 失敗では部分保存があり得る。セッションのトランザクションとディスク全体の原子性を混同しない。
- 配置判定は `WorkspaceLayout` に一元化する。機能側で `startsWith("_")` やディレクトリ名の直書き判定を書かない。
- ディスクの変更の取り込みの振り分けは `Workspace.noteDiskChanges` だけ。AppState 側に振り分けを書かない。
- ディスクを映すセッションと編集用のセッションを 1 つにまとめない。読み込みは 1 回で、編集用は `fork()` で起こす。
- root の `Workspace.apply` / `applyViaSession` はカーネルの適用を呼ぶだけ。適用の規則を root に書き足さない。ファイルへ直接書く適用の経路を作らない。
- root でスキーマをファイルから読む処理を書き足さない。
- リファクタリングの計画をアプリ側で補強しない。
- 破壊的操作（削除・一括編集・ワークスペース切替・終了）は未保存文書があれば拒否または確認ダイアログを挟む。

## 拡張ポイント

- **ゾーンや台帳の決まりの追加**: `WorkspaceLayout` の述語に足し、消費側（走査・書込権限・スキーマ適用・監視）はそれを読む。
- **セッションの操作の追加**: `WorkspaceOperation` / `YamlOperation` として表し、トランザクションと履歴に載せる。
- **変更セットの形式の拡張**: `ChangeSets` の読み取りと `ChangeSetApplier` に足し、CLI `workspace apply` と root の `apply` の両方で同じ結果になることを `ChangeSetApplierTest` で確かめる。

## ファイル配置の変更

`FileArrangementPlanner` は配置対応・文書参照・型付き file 値・定義のパス指定を計画する。`PathRewrite` の glob はフォルダー全体なら接頭辞を移し、部分移動なら元の対象も残す選択を作る。未対応の言及は警告として開示する。
適用は `WorkspaceSession.applyFileArrangement`。`FileArrangementJournal` が一般ファイルを移動し、文書は既存のトランザクションと保存を通す。履歴と失敗時の復元はカーネルが所有する（[ADR-0011](../decisions/0011-file-arrangement-history.md)）。
UI は `AppState.applyFileArrangementFromUi` で準備と結果反映を EDT、カーネルの操作を IO に分ける。配置変更の Undo/Redo も同じ境界を使い、文書の再生成・キャッシュ更新・画面移動を IO で行わない。
計画・適用時の未保存、内容ハッシュの不一致、移動先の衝突、リンクを拒否する。定義修正は Rewrite の組合せで、AI には依存しない。
一般ファイルの部分移動も実際の移動対象を列挙して glob の追加候補に含める。計画時は読み込んだ原文と現在のストアも照合し、監視通知が届く前の変更を上書きしない。
`FileSystemDocumentStore` は隠し領域を列挙の段階で枝刈りする（`.yde` は例外）。UI の補助走査は拡張子を絞ってから実パスを確認し、参考ソース全件への不要なパス解決を避ける。
