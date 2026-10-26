# {month} の売上

取消済みの注文は除きます。金額は円です。合計は {sales.values.合計:#,##0} 円でした。

## カテゴリー別

| カテゴリー | 売上 |
|---|---|
{#each sales.cells}
| {column} | {value:#,##0} |
{/each}

## この月の注文

{#if orders}
| 注文番号 | 受付日 | 状態 | 顧客 | 合計 |
|---|---|---|---|---|
{#each orders}
| {id} | {values.注文受付日} | {values.注文状態} | {values.注文顧客} | {values.注文合計金額（円）:#,##0} |
{/each}
{/if}
