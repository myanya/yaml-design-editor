# 派生スキーマ

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/schema-derive-md.md -->

派生スキーマの宣言（文書のスキーマの `derive`）に書ける語の一覧。導出元の文書（例 表の定義書）から、検証用のスキーマを読み込みのときに作る。
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
