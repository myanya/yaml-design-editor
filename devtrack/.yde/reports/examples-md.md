# 仕様の例

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/examples-md.md -->

仕様の語と決まりを示す例。1 つの例は小さなワークスペース（定義と文書）で、開いて検証したときの結果を添えている。
結果は repo-tests の `SpecTest` が実際に開いて確かめているので、ここに書いてあるとおりに動く。
**原本は [devtrack/](../../devtrack/README.md) の `spec/examples/`**（1 例 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

{#each examples}
## {title}

- ID: {id}
- 示す仕様: {#each illustrates}{.}{#if more}・{/if}{/each}
- 結果: {result}{#if message}（文面に「{message}」を含む）{/if}
{#if view}
- ビュー `{view.id}` の行（並びの順）:
{#each view.rows}
  - `{id}`{#if values}: {#each values}{key}＝{value}{#if more}、{/if}{/each}{/if}
{/each}
{/if}

{#each files}
`{path}`

```yaml
{text}
```

{/each}
{/each}
