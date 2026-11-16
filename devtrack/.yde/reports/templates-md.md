# 変換のひな型・生成・レポート

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/templates-md.md -->

YAML から YAML を組み立てる「変換のひな型」の指示と、それを使う定義（変換定義・生成の定義・レポート）に書ける語の一覧。ひな型は、スキーマの `scaffold`、操作の `build` と `do`、skill、図の記法でも同じ書き方を使う。レポートのひな形（文字の側。`{{パス}}`・`{{#each}}`・`{{#if}}`）の書き方は [manual/reports.md](../manual/reports.md)「ひな形の書き方」にある。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
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
