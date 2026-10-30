# テーブル定義書: {table.values.物理名}（{table.values.論理名}）

{table.values.業務上の意味}

## カラム

| No. | 物理名 | 論理名 | 型 | NULL | PK | FK先 | 業務型 | 最大文字数 | 形式 | 選択肢 |
|---|---|---|---|---|---|---|---|---|---|---|
{#each columns}
| {position} | {values.物理名} | {values.論理名} | {values.SQL型} | {values.NULL可否} | {values.PK} | {#each values.FK先テーブル}{.}.{/each}{#each values.FK先カラム}{.}{/each} | {values.業務型} | {values.最大文字数} | {values.形式} | {#each values.選択肢}{.} {/each} |
{/each}

## 主キー

{#each table.values.主キー}
- {.}
{/each}

## 索引

{#each table.values.索引}
- {name}（unique: {unique}）: {#each columns}{.} {/each}
{/each}

## DBで保証する条件

{#each table.values.条件}
- **{name}**: {condition}
{/each}

## 物理設計の判断

{table.values.物理設計の判断}
