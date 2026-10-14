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
- 一覧・一括編集（`WorkspaceRecords`）は「未保存編集を優先する木の取得」を注入で受ける。UI は docCache を、ヘッドレス経路はディスクを渡す。
- `Workspace.refresh` は、開いたセッションが読んだ結果を `SchemaLoader.fromKernel` でアプリの形にする。root でファイルからスキーマを読み直さない。

## 外部変更の取り込み

- 保存・外部変更・変更セット適用のいずれも、`Workspace.noteDiskChanges` が「スキーマ（`_schemas/`）・構成ファイル・派生スキーマの導出元文書が変わったら全再構築、それ以外は変更ファイルだけの増分更新」を振り分ける。UI・CLI・エージェント経路が共有する。
- 外部変更の検出: `FileWatcher` の変更フラグ（または 1 秒周期のフォールバック）→ `pollExternalChanges` がスタンプ比較 → 変更ファイルの再読込と索引更新。未編集の文書は自動反映、dirty なら競合バナーで利用者に選択させる（無断上書きしない）。
- 自動化エンジンの監視もイベント駆動＋フォールバック（[agents.md「自動実行」](agents.md)）。

## セッションの fork

- ワークスペースを開く・再読込するとき、読み込みは 1 回。ディスクを映すセッションを読み直した直後に、`WorkspaceSession.fork()` が読み込み済みの状態（スキーマ・文書・導出した結果・ストアの文面の控え）から編集用のセッションを起こす（ストアを読み直さない・パースし直さない・履歴は引き継がない）。
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
