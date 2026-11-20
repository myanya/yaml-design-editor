# ワークスペースの構成と段

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/workspace-config-md.md -->

ワークスペースの構成（`.yde/workspace.yaml`）と、段の規則・補正（`.yde/stages/`）に書ける語の一覧。構成のファイルにはメタスキーマが無いので、語は読み取りの実装から書いている。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## ワークスペースの構成（workspace.yaml）

.yde/workspace.yaml のトップレベル。ここに無いキーは、検証の警告になります。読めないときは、台帳の宣言が無い（全体が台帳）ものとして続けます。

- メタスキーマ: builtin-schemas/workspace.yaml の root
- 読む実装: workspace-core/workspace/layout/WorkspaceLayout.kt の of
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: W-11

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `ledger` |  | フォルダの名前か、その配列 | ワークスペースの全体 | 台帳にするフォルダ。ID の索引と検証の集計の範囲です。 |
| `files` |  | {outside, allowedRoots, missing} |  | file 型の値（ファイルの場所）の扱い。 |
| `scopes` |  | {id, name, description, include, uses} の配列 |  | 定義のスコープ。生成が業務モデルとして読む範囲を分けます。 |
| `stages` |  | {id, name, description} の配列 |  | 段の並び。前の段の項に、その段の規則と補正を当てたものが、その段の項です。 |

## ファイルの場所の扱い（files）

workspace.yaml の files。

- メタスキーマ: builtin-schemas/workspace.yaml の root.files
- 読む実装: workspace-core/workspace/layout/FilePolicy.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: S-25・V-22

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `outside` |  | allow・warning・error のどれか | warning | ワークスペースの外を指す場所（絶対の場所と、.. で外へ出る場所）の扱い。 |
| `allowedRoots` |  | 絶対の場所の配列 |  | 外でも許す場所。ここに入る場所は、外として扱いません。 |
| `missing` |  | allow・warning・error のどれか | error | 無いファイルの扱い。 |

## スコープ（scopes の要素）

workspace.yaml の scopes の要素。定義のファイルは、ちょうど 1 つのスコープに入ります。ここに無いキーは誤りになります。

- メタスキーマ: builtin-schemas/workspace.yaml の root.scopes.item
- 読む実装: workspace-core/workspace/layout/DefinitionScopes.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-21

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | スコープの ID。 |
| `name` |  | 文字列 | ID | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `include` |  | glob の文字列の配列 |  | このスコープに入る定義のファイル（.yde からの相対。例 schemas/business/**）。 |
| `uses` |  | スコープの ID の配列 |  | このスコープが使うスコープ。一方向で、循環は書けません。 |

## 段（stages の要素）

workspace.yaml の stages の要素。書いた順が段の順です。ここに無いキーは誤りになります。

- メタスキーマ: builtin-schemas/workspace.yaml の root.stages.item
- 読む実装: workspace-core/workspace/layout/DefinitionStages.kt の parse
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-23

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | 文字列 |  | 段の ID。 |
| `name` |  | 文字列 | ID | 表示名。 |
| `description` |  | 文章 |  | 説明。 |

## 段の規則と補正

段の規則・補正の定義（.yde/stages/ の YAML）。業務モデルの項を、段ごとに変えます。規則（rule）は条件で選んで横ぐしに当て、補正（patch）は個別の要素に当てます。

- メタスキーマ: builtin-schemas/stage.yaml の root
- 読む実装: workspace-core/workspace/Stages.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-23

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | 規則・補正の ID。 |
| `name` |  | 文字列 |  | 表示名。出どころとして出す名前です。 |
| `description` |  | 文章 |  | 説明。 |
| `stage` | 必須 | 段の ID |  | 当てる段（workspace.yaml の stages）。 |
| `kind` | 必須 | rule か patch |  | 種類。段の中では規則を先に、補正を後に当てます。補正は、当たる要素が 1 つも無い変更を誤りにします。 |
| `changes` | 必須 | 変更の配列 |  | 上から順に当てる変更。 |
| `rules` |  | {id, when, with, build} の配列 |  | 値のひな型から $apply で使う、名前を付けた組み立て。 |

## 変更（changes の要素）

段の規則・補正の changes の要素。

- メタスキーマ: builtin-schemas/stage.yaml の root.changes.item
- 読む実装: semantic-core/TermChanges.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: C-23

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `each` |  | パスの形（[*] を書けます） | いまの場所そのもの | 選ぶ並びの場所（例 entities・actions[*].effects）。並びの要素ごとに当てます。 |
| `where` |  | 条件の配列 | すべての要素 | 要素を選ぶ条件（変換の $when と同じ書き方）。 |
| `bind` |  | 名前 |  | 選んだ要素の名前。中の変更の値と条件から $of で読めます。 |
| `set` |  | 要素の中の場所 → 値 のマップ |  | 置き換えます。値は変換のひな型です。置き換えた場所は、要素の origins に記録します。 |
| `add` |  | 要素の中の並びの場所 → 足す値 のマップ |  | 中へ足します。足した object には origin が付きます。 |
| `after` |  | 足す値 |  | 選んだ要素の後ろへ足します。足した object には origin が付きます。 |
| `changes` |  | 変更の配列 |  | 中の変更。選んだ要素を「いまの場所」にして当てます。 |

