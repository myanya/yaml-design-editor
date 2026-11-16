# skill（取り込み・書き出し・コードの実行）

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/skills-md.md -->

skill の定義（`.yde/skills/`）に書ける語の一覧。種別（`kind`）は import（取り込み）・export（書き出し）・run（コードの実行）で、種別ごとに使う語が変わる。
今の実装が読む語を書いたもの。ほかの参照文書は [README.md](README.md) の表にある。
**原本は [devtrack/](../../devtrack/README.md) の `spec/`**（1 語 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{#each sections}
## {context.values.名前}

{context.values.説明}

{#if context.values.メタスキーマ}
- メタスキーマ: {context.values.メタスキーマ}
{/if}
- 読む実装: {context.values.読む実装}
{#if context.values.機能}
- 実装する機能（[FEATURES.md](../FEATURES.md) の ID）: {#each context.values.機能}{.}{#if more}・{/if}{/each}
{/if}

| 語 | 必須 | 値の形 | 省いたとき | 意味 |
|---|---|---|---|---|
{#each rows}
{#each values.形}
| `{values.キー}` | {#if required}必須{/if} | {value:cell} | {#if default}{default:cell}{/if} | {meaning:cell} |
{/each}
{/each}

{/each}
