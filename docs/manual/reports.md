# レポート

レポートは、ワークスペースの YAML から Markdown などの文字の文書を作る仕組みです。
月次の売上報告のように、人に渡す文書を原本からいつでも作り直せます。出力は派生物で、原本（YAML）には書きません。

## 仕組み

「値の一式を作る → ひな形にはめ込む」の 2 段です。

1. **値の一式を作る**: 変換のひな型（[schema.md](schema.md)「変換」と同じ `$copy` / `$format` / `$value` / `$each` など）で、レポートに要る値を 1 つの項にまとめます。`{$view: ビューID}` でビューの解決結果（行）を受け取れるので、集計はビューに任せます。
2. **ひな形にはめ込む**: Markdown などの文字のひな形に、穴・繰り返し・条件で値を埋めます。コードは書けず、同じ入力からは同じ文字ができます。

## 定義

`.yde/reports/`（従来配置は `_reports/`）に定義とひな形を置きます。

```yaml
# .yde/reports/monthly-report.yaml
id: monthly-report
name: 月次売上レポート
params: [month]                       # 受け取る値（CLI の --param・画面の欄）
build:
  month: $month
  sales: { $view: month-by-category, $where: [{ path: id, equals: $month }], $one: true }
  orders: { $view: order-grid, $where: [{ path: values.注文月, equals: $month }, { path: values.注文状態, notEquals: cancelled }] }
template: monthly-report.md           # 同じ場所のひな形
output: "reports/{month}.md"          # 既定の出力先（ワークスペース相対。穴は build の値）
```

| 項目 | 意味 |
|---|---|
| id / name / description | レポートID・表示名・説明 |
| params | 受け取る値の名前の並び。ひな型の中では `$名前` で使います |
| build | 値の一式を作るひな型。`$view` のほか、変換の指示がすべて使えます |
| template | ひな形のファイル。定義と同じ場所からの相対パスです |
| output | 既定の出力先（ワークスペース相対）。穴 `{名前}` は build の値で埋めます。省略すると表示だけで、保存は CLI の `--out` だけです。YAML には書けません |

**`$view`** はビュー（グリッドかクロス集計）の行を受け取ります。行は `{id, title, key, cells: [{column, value}], values: {列の見出し: 値}}` の形です。
`$where` に条件（検査の `where` と同じ書き方。`path` は行の上のパス、値には `$名前` も書けます）を並べると行を絞り、`$one: true` なら最初の 1 行だけ（無ければ「無い」）になります。

## ひな形の書き方

| 書き方 | 意味 |
|---|---|
| `{パス}` | 穴。値の一式の中のパス（`sales.values.合計` のように `.` でたどります）。繰り返しの中では、いま見ている要素 → その外側 → 根の順に探します |
| `{パス:形}` | 形を添えた穴。数は DecimalFormat の形（`#,##0`）、日付は `yyyy/MM/dd` のような形です |
| `{#each パス}` … `{/each}` | 配列の要素ごとに中を繰り返します。中では `{index}`（0 始まり）と `{position}`（1 始まり）も使えます。マップなら要素は `{key}` と `{value}` です |
| `{#if パス}` … `{/if}` | 値があり、空・false・0 件でないときだけ中を出します |
| `{{` `}}` | 文字の `{` と `}` |

タグだけの行は行ごと消えるので、表の行を `{#each}` で書けます。

```markdown
| カテゴリー | 売上 |
|---|---|
{#each sales.cells}
| {column} | {value:#,##0} |
{/each}
```

埋まらなかった穴は空になり、画面と CLI に名前が出ます（定義エラーにはしません）。閉じ忘れは定義エラーです。

## 作る・保存する

- **画面**: 台帳スペースの「ビュー」の一覧にレポートが並びます。受け取る値を入れて「作る」で表示し（未保存の編集も映ります）、「Markdown で見る」で元の文字、「保存（出力先）」で `output` の場所に書きます。
- **CLI**: `report <workspace> <id> --param 名前=値 [--out <file>] [--print]`。`--print` で書かずに表示し、引数なしで一覧を出します。

```bash
./gradlew runCli -q --args="report samples/orders monthly-report --param month=2026-10 --print"
```

## 決まり

- 出力は派生物です。YAML と、定義・記録の場所（`.yde/` の下）には書きません。
- レポートの定義に集計を書きません。数はビュー（クロス集計・値の式の列）で求め、`$view` で受け取ります。
- ひな形は穴・繰り返し・条件だけです。並べ替えや計算が要るなら、ビューか `build` の側で行います。
