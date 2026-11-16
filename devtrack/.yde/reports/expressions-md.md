# 値の式・条件・クエリ

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/expressions-md.md -->

検査・ビュー・操作・スキーマの値の式に共通の、小さな言語の語の一覧。クエリ（`match` の node と edge）、条件（`where`）、値の比較（`check`）、値の式。メタスキーマはここの形を決めていないので、語は読み取りの実装から書いている。使い方の説明は [manual/checks.md](../manual/checks.md) にある。
今の実装が読む語を書いたもので、スキーマ文書のトップレベルの語は [schema-document.md](schema-document.md)、項目定義の語は [schema-fields.md](schema-fields.md)。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{#each sections}
## {context.values.名前}

{context.values.説明}

{#if context.values.メタスキーマ}
- メタスキーマ: {context.values.メタスキーマ}
{/if}
- 読む実装: {context.values.読む実装}

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
{#each rows}
{#each values.形}
| `{values.キー}` | {#if required}必須{/if} | {value:cell} | {#if default}{default:cell}{/if} | {meaning:cell} |
{/each}
{/each}

{/each}
