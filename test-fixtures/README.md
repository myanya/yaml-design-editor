# test-fixtures — 3層コア移行の共通fixture

3層コア移行（[3層コア移行計画](../docs/archive/three-layer-core-migration-plan.md) Phase 0）の検証データ。
新モジュールのテストはここを読み取り専用で参照する。**変更を伴うテストは一時ディレクトリへコピーしてから行う。**

由来: 当時の実験場（旧 `sample-workspace/_schemas`）の記法（match / fields / enum values / cases / definitions+$ref / ref scope）に合わせて手書きした最小データ。実験場（`labs/` 配下）のワークスペースは変更しない。

テストは `labs/` を読まない（実験場は試作に合わせて変わり続けるため。規則テストの規則 21）。labs の試作を使うテストは、その時点の写しをここに置いて読む（`schemas/guild-quest`・`workspaces/sysdev-template`・`workspaces/kakeibo`）。写しは labs と同期しない。

期待結果はすべて現行実装（2026-08-16、コミット 677fc43 相当）のCLIで確認済み。移行後の新実装はこの結果と一致すること（意図的差分は [意図的差分一覧](../docs/archive/intentional-diffs.md) 参照）。

## yaml/ — 単文書のround-trip素材（Phase 2で使用）

| ファイル | 内容 | 期待結果 |
|---|---|---|
| comments.yaml | 先頭・行内・中間・末尾コメント、二重／シングル引用符、日付らしい文字列（裸・引用符付き）、`"true"`・`"007"`、空文字、null、`~`、ブロック（`\|`）・折返し（`>`）スカラー | parse→dump→parse でplain treeが一致。コメント・キー順・引用符スタイルが保持される。`date_like: 2026-08-16` は素の文字列のまま（Dateへ型付けしない＝現行 `PlainDateResolver` の挙動）。引用符付きの `"true"`・`"007"` はdump後も引用符が残り型が変わらない |
| nested.yaml | map・sequence（ブロック／フロー／空）・入れ子object | round-trip一致。フロー表記 `[fast, safe]` が保持される |
| multidoc.yaml | `---` 区切りの3ドキュメント（コメント付き） | `parseAll` で3件。dump後も後続ドキュメントが失われない |

## schemas/ — スキーマ構文素材（Phase 3で使用）

| ファイル | 内容 | 期待結果 |
|---|---|---|
| task.yaml | `definitions` + `$ref: step`、enum `kind` の `cases`（manual→assignee必須／scheduled→cron必須）、`type: ref` + `scope.schemaIds` | パース・`$ref`解決・定義検証が通る（`workspaces/legacy-basic/_schemas/task.yaml` と同一内容。適用結果はlegacy-basicで確認済み） |

### guild-quest/ — 冒険者ギルドの依頼の振る舞い（labs/adventurers-guild の写し）

`BehaviorChecksTest` が使う。業務の State / Lifecycle を入れたとき（2026-11-11、コミット e11b8bb）の、依頼の振る舞いの宣言と、それが参照する定義だけの写し（`quest-behavior.yaml`・`quest.yaml`・`common.yaml`・`relations.yaml`）。

| 期待結果 |
|---|
| スキーマの読み込みと振る舞いの宣言のコンパイルに誤りが無い。依頼の Lifecycle は State 9・遷移 14（どれも操作による遷移） |

## transforms/ — YAML から YAML への変換の定義（P27 段C で追加）

| ファイル | 内容 | 期待結果 |
|---|---|---|
| sysdev-screen-items.yaml | 設計情報テンプレート（`sysdev-template`）で試すための例（テストでは使わない）。クエリで選んだ画面ごとに、元のユーザアクションと、画面設計書の項目の一覧を入れ子で組み立てる | 画面3件。`SCR-RESERVE-INPUT` の項目は7件（`purpose` だけ最大文字数 100 を持つ） |
| req-list.yaml | クエリで選んだ要素ごとに、値を組み立てる例（`workspaces/rename-target` で使う） | 要求3件。REQ-002・REQ-003 は `related: [{to: REQ-001}]` を持ち、REQ-001 は `related` を持たない（値が得られないキーは落ちる） |

## workspaces/ — 複数文書workspace（Phase 5〜9で使用）

### legacy-basic（レガシー構成: ルート直下 `_schemas/`）

内容: `items/`（fx-itemスキーマ）と `tasks/`（fx-taskスキーマ＝cases+$ref）。意図的な問題データを含む。

`validate` の期待結果（現行CLIで確認済み）: **errors: 3 / warnings: 0**、すべて `items/c.yaml`

1. `parts[0]` — ID「PT-001」がこのファイル内で重複（ERROR）
2. `parts[1]` — 同上（重複は両出現に報告される）
3. `link` — 参照「IT-404」を解決できない（ERROR）

注意（現行仕様）: **ファイル間**の同一ID（IT-001 が a.yaml と c.yaml に出現）はエラーではない。「同一エンティティの複数出現」としてentity合成の入力になる。ID重複がERRORになるのは**同一ファイル内**のみ（`Validator.kt` のドキュメント内重複検査）。`tasks/t1.yaml`（kind: manual + assignee）は cases 適用込みで問題なし。

### yde-basic（`.yde` 構成 + `ledger: doc`）

内容: `.yde/workspace.yaml`（`ledger: doc`）、`.yde/schemas/item.yaml`、台帳内 `doc/a.yaml`・`doc/b.yaml`、台帳外 `outside/note.yaml`。

期待結果（現行CLIで確認済み）:

- `validate` → errors: 0 / warnings: 0
- `catalog` → `doc/a.yaml` と `doc/b.yaml` の2件のみ。**`outside/note.yaml` は台帳外のため走査・索引の対象外**（ID `OUT-001` は索引に載らない）

### rename-target（entity renameで複数文書が更新される）

内容: fx-reqスキーマ、`reqs/one.yaml`（REQ-001）、`two.yaml`・`three.yaml`（`relates_to: [REQ-001]`）。

期待結果（現行CLIで確認済み）:

- `validate` → errors: 0 / warnings: 0
- `entity <ws> rename REQ-001 REQ-100`（プレビュー）→ 変更セットは**3ファイル**: `one.yaml` の `id`、`two.yaml`・`three.yaml` の `relates_to[0]` をそれぞれ REQ-100 へ set。適用は1つの変更セット（オール・オア・ナッシング）

### todos（共通型・詳細化・検証・ビューの回帰テスト）

[`TodosSampleTest`](../src/test/kotlin/dev/toros/yde/app/core/TodosSampleTest.kt) が使う専用データ。
公開サンプルから検証に必要なスキーマ・ビュー・TODO データだけを切り出して固定しており、`samples/todos` とは同期しない。
サンプルの業務モデルや操作定義の完成度を保証するものではなく、複数の本体機能を組み合わせた挙動を検証する。
テストは一時ディレクトリへコピーしてから読み込み、不正データもコピー先にだけ書く。

期待結果:

- 初期データの検証は指摘なし。共通型の制約と日付の表示形式が解決され、一覧と個票の同じ ID は同一要素として参照できる。
- 期限順ビューは `TODO-001` ～ `TODO-005` の順（個票のない `TODO-005` は末尾）。更新日はコメントの順序によらず最新の日付。
- コピー先に不正データを入れると、期限と作成日の前後関係・完了日の欠落・ID の形式・タイトルの改行を指摘する。

### sysdev-template（設計情報テンプレート。labs/sysdev-template の写し）

`ActionCliTest`（cli）が使う。2026-11-12 の labs/sysdev-template を、計画やメモの Markdown を除いて写した。テストは一時ディレクトリへコピーしてから操作を適用する。

期待結果: `validate` → errors: 0 / warnings: 20。CLI `action` の一覧・計画・`--apply`・前提で止まる操作は `ActionCliTest` のとおり。

### kakeibo（家計簿の試作。labs/kakeibo の写し）

`KakeiboLabTest`（repo-tests）が使う。2026-11-12 の labs/kakeibo を、計画やメモの Markdown を除いて写した。テストは一時ディレクトリへコピーしてから開く。

期待結果: `validate` → errors: 2 / warnings: 1（わざと入れてある食い違い）。ビューと skill の読み込み・取り込みのプレビュー・クロス集計と列を広げる書き方の一致は `KakeiboLabTest` のとおり。

## 確認コマンド

```bash
./gradlew :test --tests dev.toros.yde.app.core.TodosSampleTest
./gradlew runCli -q --args="validate test-fixtures/workspaces/legacy-basic"
./gradlew runCli -q --args="transform test-fixtures/workspaces/rename-target test-fixtures/transforms/req-list.yaml --origins"
./gradlew runCli -q --args="transform test-fixtures/workspaces/legacy-basic builtin:structural-schema --input _schemas/task.yaml"   # 構造のスキーマの導出（組込みの変換定義）
./gradlew runCli -q --args="validate test-fixtures/workspaces/yde-basic"
./gradlew runCli -q --args="catalog test-fixtures/workspaces/yde-basic"
./gradlew runCli -q --args="entity test-fixtures/workspaces/rename-target rename REQ-001 REQ-100"
./gradlew runCli -q --args="validate test-fixtures/workspaces/kakeibo"
./gradlew runCli -q --args="validate test-fixtures/workspaces/sysdev-template"
```
