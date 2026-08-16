# test-fixtures — 3層コア移行の共通fixture

3層コア移行（[docs/THREE-LAYER-CORE-MIGRATION-PLAN.md](../docs/THREE-LAYER-CORE-MIGRATION-PLAN.md) Phase 0）の検証データ。
新モジュールのテストはここを読み取り専用で参照する。**変更を伴うテストは一時ディレクトリへコピーしてから行う。**

由来: `sample-workspace/_schemas` の記法（match / fields / enum values / cases / definitions+$ref / ref scope）に合わせて手書きした最小データ。`sample-workspace/` 自体は利用者の実験場のため複製・変更しない。

期待結果はすべて現行実装（2026-08-16、コミット 677fc43 相当）のCLIで確認済み。移行後の新実装はこの結果と一致すること（意図的差分は [docs/decisions/INTENTIONAL-DIFFS.md](../docs/decisions/INTENTIONAL-DIFFS.md) 参照）。

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

## 確認コマンド

```bash
./gradlew runCli -q --args="validate test-fixtures/workspaces/legacy-basic"
./gradlew runCli -q --args="validate test-fixtures/workspaces/yde-basic"
./gradlew runCli -q --args="catalog test-fixtures/workspaces/yde-basic"
./gradlew runCli -q --args="entity test-fixtures/workspaces/rename-target rename REQ-001 REQ-100"
```
