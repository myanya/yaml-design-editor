# view-chart

ビューの種類 `kind: chart`（表を図に）を足す拡張です。元の表（グリッドかクロス集計）の行を横軸の項目、数の列を系列にして、棒・積み上げ棒・横棒・折れ線・面・円・散布図で描きます。データは元の表が持ち、チャートは見せ方だけを書きます。

- 差し込み口の実装は `ChartViewKind`（`ViewKind`）で、入口（desktop・cli）の登録の一覧に並べて使います。
- 描画は拡張の外（desktop の `ui/ChartViewPane.kt`・`ui/Charts.kt`）にあるので、描画が読む定義の型（`VirtualChartDef` など）と解決の結果（`ChartData`）も公開します（ADR-0014）。
- 定義の形の断片は資源 `view-chart/schema.yaml` で、ビューのメタスキーマに合成されます。項目の並び（`order`）は grid と共有するので中核に残しています。
- 置き場は 3 つです。台帳のビュータブ、文書ビューの区画（`chart: {view}`）、ダッシュボードの区画（`view: チャートID`）。

定義の例:

```yaml
id: monthly-sales-chart
name: 月別売上
kind: chart
chart: stacked-bar
view: month-by-category        # 元の表（グリッドかクロス集計）
y: { label: 売上高（円）, format: "#,##0" }
drill: below                   # 棒を押すと、その項目の材料が図の下に並ぶ
```

書き方の全体は [docs/manual/views.md](../docs/manual/views.md)「チャート（`kind: chart`）— 表を図に」、仕組みは [docs/architecture/views.md](../docs/architecture/views.md)「ビューの種類の差し込み口」。
