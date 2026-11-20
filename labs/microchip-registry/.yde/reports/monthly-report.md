# {month} の手続件数

受付日が {month} の申請・届出の件数です。合計は {kinds.values.合計} 件でした。

## 手続種別

| 手続種別 | 件数 |
|---|---|
{#each kinds.cells}
| {column} | {value} |
{/each}

## 経路

{#each channels}
| 経路 | 件数 |
|---|---|
{#each cells}
| {column} | {value} |
{/each}

オンライン利用率は {onlineRate}% です（目標 90% 以上）。
{/each}
