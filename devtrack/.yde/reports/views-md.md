# ビュー

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/views-md.md -->

ビューの定義（`.yde/views/` の YAML）に書ける語の一覧。グリッド（表）・クロス集計・文書ビューを扱う。ダッシュボードは [dashboards.md](dashboards.md)、グラフレンズと図の記法は [lens-diagrams.md](lens-diagrams.md)。チャートと業務フロー図は、あとから足せる種類で、ここには載せていない。
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
