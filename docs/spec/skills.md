# skill（取り込み・書き出し・コードの実行）

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/skills-md.md -->

skill の定義（`.yde/skills/`）に書ける語の一覧。種別（`kind`）は import（取り込み）・export（書き出し）・run（コードの実行）で、種別ごとに使う語が変わる。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

## skill の定義

skill 1 つ。import は input・target と、mapping か build を書きます。export は source・output と、columns か build を書きます（include で束ねる書き出しは、それらを書きません）。run は run を書きます。

- メタスキーマ: builtin-schemas/skill.yaml の root
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-02

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `id` | 必須 | ID の文字列 |  | skill の ID。 |
| `name` |  | 文字列 |  | 表示名。 |
| `description` |  | 文章 |  | 説明。 |
| `kind` | 必須 | import・export・run のどれか |  | 種別。 |
| `input` |  | {format, sheet, headerRow, path, tree, table, encoding, columns} |  | 取り込みの入力。 |
| `target` |  | {file, path, id, key} |  | 取り込みの先。 |
| `build` |  | ひな型 |  | 行のひな型。取り込みでは行（列の名前 → セル）を当てる変換のひな型で、mapping の代わりに書きます。書き出しでは文書ごとに JSON の値を組み立てるひな型で、columns の代わりに書きます。 |
| `rules` |  | {id, when, with, build} の配列 |  | build から $apply で当てる、名前を付けた組み立て。 |
| `mapping` |  | {column, path, type, required, map, split} の配列 |  | 取り込みの、列の対応。 |
| `include` |  | skill の ID の配列 |  | まとめて書き出す定義。同じ書き出し先へ 1 回で書き、どれかが誤りなら何も書きません。 |
| `source` |  | {match, rows} か {view} |  | 書き出しの対象。 |
| `columns` |  | {header, path, join} の配列 |  | 書き出しの列。 |
| `output` |  | {file, sheet, mode, index, fill, style, finalNewline} |  | 書き出しの出力。 |
| `run` |  | {command, workdir, timeoutSeconds, writes} |  | コードの実行。実行の前に、人がコマンドと権限を承認します（自動の実行とエージェントからは起動できません）。 |

## 取り込みの入力（input）

skill の input。

- メタスキーマ: builtin-schemas/skill.yaml の root.input
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-02

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `format` |  | xlsx・csv・json・markdown・docx のどれか |  | 形式。 |
| `sheet` |  | 文字列 |  | シートの名前（xlsx）。 |
| `headerRow` |  | 整数（1 始まり） |  | 見出しの行（xlsx）。 |
| `path` |  | キーを . で区切った文字列 | ルート | 行の配列の場所（json）。tree のときは取り込む場所です。 |
| `tree` |  | 真偽値 |  | 木のまま取り込みます（json）。配列の要素を、入れ子・数の型・null を保ったまま 1 件ずつ build に渡します。build が必要です。 |
| `table` |  | 整数（1 始まり） |  | 表の番号（docx）。 |
| `encoding` |  | 文字コードの名前 | UTF-8 | 文字コード（csv・json・markdown）。 |
| `columns` |  | 列の名前の配列 |  | 見出しの行が無い csv に、列の名前を与えます（1 行目からデータ）。 |

## 取り込みの先（target）

skill の target。

- メタスキーマ: builtin-schemas/skill.yaml の root.target
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-02

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `file` |  | 穴を含む文字列か、ひな型 |  | 取り込む先のファイル。穴は行の値（build では組み立てた明細の値）で埋めます。 |
| `path` |  | パスの文字列 | 1 行が 1 文書 | 文書の中の配列へ足します（例 entries）。 |
| `id` |  | {path, prefix, digits} |  | 採番。組み立てた明細に ID が無いとき、接頭辞と連番を付けます（配列への取り込みだけ）。 |
| `key` |  | 項目のパスの配列 |  | 同じ明細の見分け方。この項目の値が同じ明細が既にあれば取り込みません（配列への取り込みだけ）。 |

## 取り込みの採番

target の id。

- メタスキーマ: builtin-schemas/skill.yaml の root.target.id
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-02

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `path` |  | パスの文字列 |  | ID の項目。 |
| `prefix` |  | 穴を含む文字列か、ひな型 |  | 接頭辞。組み立てた明細の値で埋めます（例 TX-{date}-）。 |
| `digits` |  | 整数 | 2 | 連番の桁数。 |

## 列の対応（mapping の要素）

skill の mapping の要素。

- メタスキーマ: builtin-schemas/skill.yaml の root.mapping.item
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-02

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `column` | 必須 | 文字列 |  | 列の名前。 |
| `path` | 必須 | パスの文字列 |  | 取り込む先の場所。 |
| `type` |  | string・int・number・boolean・auto のどれか | string | 値の型。 |
| `required` |  | 真偽値 |  | 必須。 |
| `map` |  | 元の値 → 先の値 のマップ |  | 値の置き換えの辞書（例 未着手 → open）。 |
| `split` |  | 文字列 |  | 配列にするときの区切り。 |

## 書き出しの対象（source）

skill の source。

- メタスキーマ: builtin-schemas/skill.yaml の root.source
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `match` |  | glob の文字列 |  | 対象の文書。 |
| `rows` |  | パスの文字列 | 1 文書が 1 行 | 行にする配列の場所。 |
| `view` |  | ビュー ID |  | ビューを行と列の元にします。match・rows・columns とは一緒に書けません。 |

## 書き出しの列（columns の要素）

skill の columns の要素。

- メタスキーマ: builtin-schemas/skill.yaml の root.columns.item
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `header` | 必須 | 文字列 |  | 見出し。 |
| `path` | 必須 | パスの文字列 |  | 値の場所。 |
| `join` |  | 文字列 |  | 配列の値をつなぐ区切り。 |

## 書き出しの出力（output）

skill の output。

- メタスキーマ: builtin-schemas/skill.yaml の root.output
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-03

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `file` |  | ファイルの名前 |  | 出力のファイル。拡張子で形式が決まります（.xlsx・.csv・.md・.json・.html）。YAML には書けません。 |
| `sheet` |  | 文字列 |  | シートの名前（xlsx）。 |
| `mode` |  | array・value・merge のどれか | array | build の値の置き方。 |
| `index` |  | ひな型（文字列はパスの略記） |  | build で array のとき、組み立てた値を番号の位置に並べます。0 以上の整数を読みます。 |
| `fill` |  | ひな型 | null | 番号の空きに置く値。 |
| `style` |  | pretty・compact・lines のどれか | pretty | build の書式。 |
| `finalNewline` |  | 真偽値 | true | 末尾の改行。 |

## コードの実行（run）

skill の run。

- メタスキーマ: builtin-schemas/skill.yaml の root.run
- 読む実装: app-core/skill/Skills.kt
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: SK-01・SK-07

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
| `command` |  | 文字列の配列 |  | コマンドと引数。 |
| `workdir` |  | フォルダの場所 |  | 作業のフォルダ（ワークスペース相対）。 |
| `timeoutSeconds` |  | 整数 |  | 打ち切る秒数。 |
| `writes` |  | glob の文字列の配列 |  | 書き込みを宣言する場所。実行のあとに、宣言の外の変更を見つけて警告します。 |

