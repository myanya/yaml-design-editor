# スキーマの項目定義

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-fields-md.md -->

スキーマの項目 1 つの定義に書ける語（キー）と、`type` に書ける基底の種類の一覧。今の実装が読む語を書いたもので、使い方の説明は [manual/schema.md](../manual/schema.md) にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{context.values.説明}

- メタスキーマ: {context.values.メタスキーマ}
- 読む実装: {context.values.読む実装}

## 基底の種類

`type` に書ける基底の種類。これ以外の名前は型の名前（組込みの型 `text`・`line`・`word` と、`definitions` に定義した型）で、解決すると末端がこのどれかになる。

{#each typeGroups}
### {title}

| type | 値の形 | 意味 |
|---|---|---|
{#each rows}
| `{values.名前}` | {values.値の形:cell} | {values.意味:cell} |
{/each}

{/each}
## 語

「書ける型」は、その語を書ける項目の基底の種類。「すべて」は種類を限らない。同じ語でも、種類によって値の形と意味が変わるものは行を分ける。

{#each sections}
### {title}

| 語 | 書ける型 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
{#each rows}
{#each forms}
| `{key}` | {#if any}すべて{/if}{#if types}{#each types}`{.}`{#if more}・{/if}{/each}{/if} | {value:cell} | {#if default}{default:cell}{/if} | {meaning:cell}{#if alias}（別の書き方）{/if}{#if deprecated}（廃止予定）{/if}{#if inherit}<br>{inherit:cell}。{/if} |
{/each}
{/each}

{/each}
