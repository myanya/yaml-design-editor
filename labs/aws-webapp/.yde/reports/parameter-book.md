# WEBアプリのパラメータ設計書

この文書は原本YAMLからの派生物です。採用した全設定を掲載します。AWSの全オプションの一覧ではありません。
refは設計IDの参照、attributeは展開する値、literalは固定文字列です。入力例は実環境の値ではありません。

## 実環境への入力

| ID | 項目 | 状態 | 入力例 |
|---|---|---|---|
{#each inputs}
| {id} | {values.名称} | {values.状態} | {values.入力例} |
{/each}

## AWSサービスの設定
{#each resources}

### {id} — {values.名称}

| 項目 | 値 |
|---|---|
| AWS型 | {values.AWS型} |
| 環境 | {values.環境} |
| 配備単位 | {values.配備単位} |

{values.目的}
{#if values.採用理由}
{values.採用理由}
{/if}

~~~yaml
{values.全設定}
~~~
{/each}

## アプリに渡す実行設定
{#each applications}

### {id} — {values.名称}

~~~yaml
{values.全設定}
~~~
{/each}

## CloudFormation外のサービス設定
{#each foundation}

### {id} — {values.名称}

{values.サービス}.{values.操作} / {values.Region}

~~~yaml
{values.要求設定}
~~~

確認: {values.確認方法}
{/each}
