# 開発管理（devtrack）

YDE で YDE 自身の開発を管理する試行のワークスペースです。
いまは段階 0 で、[docs/BACKLOG.md](../docs/BACKLOG.md)（未解決の指摘台帳）の表を、1 件 1 ファイルに取り込んだ写しを置いています。

**原本は docs/BACKLOG.md です。** ここのファイルは直さず、BACKLOG.md を直してから取り込み直します。

## 中身

| 場所 | 内容 |
|---|---|
| `backlog/UX-*.yaml` | 指摘（BACKLOG.md の表から取り込んだ写し） |
| `themes/T-*.yaml` | テーマ（BACKLOG.md「テーマ管理」の見出しと最初の段落を手で写したもの） |
| `.yde/schemas/common.yaml` | 指摘とテーマの型、状態の選択肢、指摘ごとの検査 |
| `.yde/schemas/item.yaml`・`theme.yaml`・`relations.yaml` | 指摘とテーマの文書、指摘からテーマへの関係（束ねる指摘） |
| `.yde/views/` | ダッシュボード「開発管理」、指摘の一覧、起点の月×状態、テーマの一覧 |
| `.yde/skills/import-backlog.yaml` | BACKLOG.md の取り込みの定義 |

BACKLOG.md の列は次のように写します。

| BACKLOG.md の列 | 写す先 |
|---|---|
| ID | `id`（ファイル名にもなります） |
| 起点 | 先頭の日付を `origin_on`、その後ろを `origin` |
| 指摘 | 先頭の太字を件名 `title`。太字があれば欄の全体を本文 `body` にも写します（太字が無ければ欄の全体が件名） |
| 技術メモ（対応の当たり） | `memo` |
| 状態 | 絵文字を値（`todo`・`discussing` など）に。辞書に無い書き方はそのまま入り、検証が指摘します |
| 対応先 | `T-nn` があればテーマへの参照 `theme`。欄の文字は `targets` に（`—` は写しません） |

## 開く・取り込み直す

```bash
./gradlew run --args="$PWD/devtrack"                                                 # 画面で開く（ダッシュボード「開発管理」）。絶対パスで渡す
./gradlew runCli -q --args="import devtrack import-backlog docs/BACKLOG.md"          # 取り込みの予定を見る（書かない）
./gradlew runCli -q --args="import devtrack import-backlog docs/BACKLOG.md --apply"  # 取り込む
./gradlew runCli -q --args="validate devtrack"                                       # 検証
./gradlew runCli -q --args="view devtrack backlog-dashboard"                         # ダッシュボードを CLI で見る
```

- 画面の起動（`run`）は、相対パスを `desktop/` から解決するので、絶対パスで渡します（`$PWD` は bash と PowerShell のどちらでも今の場所です）。CLI（`runCli`）はリポジトリの根からの相対パスで動きます。
- 取り込みは作成と更新だけです。解決して BACKLOG.md から消えた指摘は `backlog/` に残るので、そろえるときは `backlog/` を空にしてから取り込みます。
- 写しは BACKLOG.md より古くてかまいません。試すときに取り込み直します。

## 段階

| 段階 | 内容 | 原本 |
|---|---|---|
| 0（いま） | BACKLOG.md を取り込み、画面と CLI で眺める。取り込めないものと、検査で見つかるものを洗い出す | docs/BACKLOG.md |
| 1 | 原本を YAML に切り替え、BACKLOG.md はレポートで生成する。登録は操作（ID の採番つき）にし、検証エラー 0 を CI で守る | devtrack |
| 2 | 解決の扱い（記録へ移す運用の置き換え）、テーマ、ROADMAP の状態表へ広げる | devtrack |
