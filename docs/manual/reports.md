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
| rules | 名前を付けた組み立ての並び（`{id, when, build}`。下の「同じ形を 1 か所に書く」） |
| template | ひな形のファイル。定義と同じ場所からの相対パスです |
| output | 既定の出力先（ワークスペース相対）。穴 `{名前}` は build の値で埋めます。省略すると表示だけで、保存は CLI の `--out` だけです。YAML には書けません |

**`$view`** はビュー（グリッドかクロス集計）の行を受け取ります。行は `{id, title, key, cells: [{column, value}], values: {列の見出し: 値}}` の形です。
`$where` に条件（検査の `where` と同じ書き方。`path` は行の上のパス、値には `$名前` も書けます）を並べると行を絞り、`$one: true` なら最初の 1 行だけ（無ければ「無い」）になります。

**行を 1 行ずつ組み立てる**: `$each` の源にパスの代わりに `{$view: ビューID, $where: [条件]}` を書くと、行を 1 行ずつ「いまの場所」にして `$build` を組み立てます。
行の値（`values.列の見出し`）が配列やマップなら、その中もさらに `$each` で辿れます。値によって書き分けたい文字（状態ごとの印など）は、ひな形では比べられないので、ここで `$when` を使って作ります。

```yaml
build:
  open:
    $each: { $view: theme-rows, $where: [{ path: values.状態, notEquals: done }] }
    $build:
      id: { $copy: id }
      items:
        $each: values.項目
        $build:
          $first:
            - { $when: { path: state, equals: done }, $then: { $format: "{id} ✅ {date}" } }
            - { $format: "{id} 📋" }
```

**同じ形を 1 か所に書く**（`rules`）: 何か所でも同じ形を組み立てるときは、名前を付けて `rules` に置き、`{$apply: 名前}`（いまの場所に）か `{$each: パス, $apply: 名前}`（要素ごとに）で使います。
同じ名前の規則を並べると、上から順に `when` の条件を調べて最初に合ったものを使います（`when` を省いた規則はいつでも合う）。

```yaml
build:
  rows: { $each: { $view: orders }, $apply: size }
rules:
  - { id: size, when: [{ path: values.合計, gte: 5000 }], build: { $format: "{id} 大" } }
  - { id: size, build: { $format: "{id} 小" } }
```

## ひな形の書き方

| 書き方 | 意味 |
|---|---|
| `{パス}` | 穴。値の一式の中のパス（`sales.values.合計` のように `.` でたどります）。繰り返しの中では、いま見ている要素 → その外側 → 根の順に探します |
| `{パス:形}` | 形を添えた穴。数は DecimalFormat の形（`#,##0`）、日付は `yyyy/MM/dd` のような形です |
| `{パス:cell}` | Markdown の表のセルに入れる穴。値の縦棒（`\|`）の前に `\` を付け、改行を `<br>` にして、表の行が崩れないようにします |
| `{#each パス}` … `{/each}` | 配列の要素ごとに中を繰り返します。中では `{index}`（0 始まり）と `{position}`（1 始まり）、後ろにまだ要素があるか `more` も使えます（`{#if more}・{/if}` で並びを区切る）。マップなら要素は `{key}` と `{value}` です |
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
