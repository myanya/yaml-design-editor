# 開発管理（devtrack）

YDE で YDE 自身の開発を管理する試行のワークスペースです。
いまは段階 0 で、[docs/BACKLOG.md](../docs/BACKLOG.md)（未解決の指摘台帳）と、その記録 [docs/archive/backlog-closed.md](../docs/archive/backlog-closed.md)（解決・見送り）の表を、1 件 1 ファイルに取り込んだ写しを置いています。

**原本は Markdown の側（BACKLOG.md と backlog-closed.md）です。** ここのファイルは直さず、Markdown を直してから取り込み直します。

## 中身

| 場所 | 内容 |
|---|---|
| `backlog/UX-*.yaml` | 未解決の指摘（BACKLOG.md の表から取り込んだ写し） |
| `closed/UX-*.yaml` | 解決・見送りの指摘（backlog-closed.md の表から取り込んだ写し） |
| `themes/T-*.yaml` | テーマ（BACKLOG.md「テーマ管理」の見出しと最初の段落を手で写したもの） |
| `.yde/schemas/common.yaml` | 指摘とテーマの型、状態の選択肢、指摘ごとの検査 |
| `.yde/schemas/item.yaml`・`closed.yaml` | 台帳の指摘と記録の指摘の文書。どちらも同じ型・同じエンティティで、それぞれだけの決まり（起点の日・状態）を検査に持ちます |
| `.yde/schemas/theme.yaml`・`relations.yaml` | テーマの文書と、指摘からテーマへの関係（束ねる指摘） |
| `.yde/views/` | ダッシュボード「開発管理」、未解決の一覧、解決・見送りの一覧、起点の月×状態、テーマの一覧 |
| `.yde/skills/import-backlog.yaml`・`import-backlog-closed.yaml` | 取り込みの定義（台帳と記録） |

Markdown の列は次のように写します。

| 列 | 写す先 |
|---|---|
| ID | `id`（ファイル名にもなります） |
| 起点 | 先頭の日付を `origin_on`、その後ろを `origin`（記録には日付の無い起点があり、そのときは欄の全体を `origin`） |
| 指摘 | 先頭の太字を件名 `title`。太字があれば欄の全体を本文 `body` にも写します（太字が無ければ欄の全体が件名） |
| 技術メモ（対応の当たり） | `memo` |
| 状態 | 絵文字を値（`todo`・`discussing`・`resolved` など）に。台帳で辞書に無い書き方はそのまま入り、検証が指摘します。記録では ✅ / ❌ の後ろの文字を `resolution`、その中の日付を `resolved_on` に |
| 対応先 | `T-nn` があればテーマへの参照 `theme`。欄の文字は `targets` に（`—` は写しません） |

記録の表は書き方が節ごとに少し違うので、取り込みの定義で吸収します。

- 「FEATURES の解消済み制約」の節の 4 列の表（ID・内容・状態・備考）の行は、2 列目を件名、3 列目を状態として読みます。
- ID が `UX-` の番号でない行（`L-nn`、一部解決の「UX-162（一部）」）は取り込みません。
- 解決の日は、状態の欄に日付が書かれた記録にだけ入ります（多くの記録は日付を節の見出しにだけ持ち、Markdown の取り込みは見出しを読みません）。

## 開く・取り込み直す

```bash
./gradlew run --args="$PWD/devtrack"                                                                  # 画面で開く（ダッシュボード「開発管理」）。絶対パスで渡す
./gradlew runCli -q --args="import devtrack import-backlog docs/BACKLOG.md"                          # 台帳の取り込みの予定を見る（書かない）
./gradlew runCli -q --args="import devtrack import-backlog docs/BACKLOG.md --apply"                  # 台帳を取り込む
./gradlew runCli -q --args="import devtrack import-backlog-closed docs/archive/backlog-closed.md --apply"  # 記録を取り込む
./gradlew runCli -q --args="validate devtrack"                                                       # 検証
./gradlew runCli -q --args="view devtrack backlog-dashboard"                                         # ダッシュボードを CLI で見る
```

- 画面の起動（`run`）は、相対パスを `desktop/` から解決するので、絶対パスで渡します（`$PWD` は bash と PowerShell のどちらでも今の場所です）。CLI（`runCli`）はリポジトリの根からの相対パスで動きます。
- 取り込みは作成と更新だけです。解決して BACKLOG.md から記録へ移った指摘は `backlog/` に残るので、そろえるときは `backlog/` を空にしてから台帳を取り込み、記録も取り込み直します。
- 台帳と記録に同じ ID があると、検証が定義の競合として出します。記録の中に同じ ID が 2 行あると、記録の取り込みが止まります。
- 写しは Markdown より古くてかまいません。試すときに取り込み直します。

## 段階

| 段階 | 内容 | 原本 |
|---|---|---|
| 0（いま） | 台帳と記録を取り込み、画面と CLI で眺める。取り込めないものと、検査で見つかるものを洗い出す | Markdown |
| 1 | 原本を YAML に切り替え、Markdown はレポートで生成する。登録は操作（ID の採番つき）にし、検証エラー 0 を CI で守る | devtrack |
| 2 | 解決の扱い（記録へ移す運用の置き換え）、テーマ、ROADMAP の状態表へ広げる | devtrack |
