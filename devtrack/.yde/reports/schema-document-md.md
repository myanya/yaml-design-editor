# スキーマ文書

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-document-md.md -->

スキーマ文書（`.yde/schemas/` の YAML）のトップレベルに書ける語（キー）と、`kind` に書ける種類の一覧。今の実装が読む語を書いたもので、使い方の説明は [manual/schema.md](../manual/schema.md) にある。
項目 1 つの定義（`root`・`definitions` の中身）に書ける語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{context.values.説明}

- メタスキーマ: {context.values.メタスキーマ}
- 読む実装: {context.values.読む実装}

## スキーマの種類

`kind` に書ける種類。種類によって、トップレベルに書ける語が変わる。

| kind | 適用先 | 意味 |
|---|---|---|
{#each kinds}
| `{values.名前}` | {values.適用先:cell} | {values.意味:cell} |
{/each}

## 語

「書ける種類」は、その語を書けるスキーマ文書の種類。「すべて」は種類を限らない。

{#each sections}
### {title}

| 語 | 書ける種類 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
{#each rows}
{#each forms}
| `{key}` | {#if any}すべて{/if}{#if kinds}{#each kinds}`{.}`{#if more}・{/if}{/each}{/if} | {value:cell} | {#if default}{default:cell}{/if} | {meaning:cell} |
{/each}
{/each}

{/each}
