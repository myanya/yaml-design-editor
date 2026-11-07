# 機能索引

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/features-md.md -->

機能から実装ファイルとテストを引く索引。1 行 1 機能で、経緯は書かない（記録は [archive/](archive/README.md) と git log）。
利用方法は [manual/](manual/README.md)、設計は [architecture/](architecture/README.md) が正。「設計」列は設計文書の主題、「操作の説明」列はその機能を説明する manual の見出し（機能を変えたらそこを直す。「—（内部）」は利用者が操作しない内部の基盤）。「未解決の指摘」はその機能に関わる [BACKLOG.md](BACKLOG.md) の指摘（着手前に読む。指摘の側の「関わる機能」から集める）。
**原本は [devtrack/](../devtrack/README.md) の `features/`**（1 機能 1 ファイルの YAML）。足す・直すときは YAML を書き、`./gradlew devtrackDocs` で作り直す。

- **テスト**: 印の無い名前は単体テスト（代表の名前。`./gradlew test --tests '*名前*'` で絞って回せる）。`UI` は Compose の画面を組み立てるテスト（`./gradlew test` に含まれる）。`検査` は `./gradlew test` に含まれない別の検査。`手動` は手動確認だけ（画面は skill `ui-check`）。「ほか」は代表の名前だけを書いたもの。
- 未実装は [ROADMAP.md](ROADMAP.md)、既知の制約は [BACKLOG.md](BACKLOG.md) と manual/limits.mdに置く。

{#each sections}
## {title}

| ID | 機能 | 主な実装 | テスト | 設計 | 操作の説明 | 未解決の指摘 |
|---|---|---|---|---|---|---|
{#each rows}
| {id} | {values.機能:cell} | {#each values.主な実装}{.:cell}{#if more}・{/if}{/each} | {#if values.テスト}{#each values.テスト}{.:cell}{#if more}・{/if}{/each}{/if}{#if values.手動}{#if values.テスト}・{/if}手動{#if values.手動の場面}（{values.手動の場面:cell}）{/if}{/if}{#if values.ほか} ほか{/if} | {#each values.設計}{.:cell}{#if more}・{/if}{/each} | {#if values.内部}—（内部）{/if}{#if values.操作の説明}{#each values.操作の説明}{.:cell}{#if more}・{/if}{/each}{/if} | {#if values.未解決の指摘}{#each values.未解決の指摘}{.}{#if more}・{/if}{/each}{/if} |
{/each}

{/each}
## 手動確認だけの機能

自動のテストが無く、手動で確かめている機能。画面の確認は skill `ui-check`（[.claude/skills/ui-check/SKILL.md](../.claude/skills/ui-check/SKILL.md)）の手順で行う。
テストを足したら、その機能の `tests` に書いて `manual_check` を外す。

{#each manualOnly}{id}{#if more}・{/if}{/each}

## 公開サンプル

[todos](../samples/todos/README.md)と[テーブルモデル](../samples/todos-sysdev-tablemodel/README.md): 操作・詳細化・SQL生成の検討。
[orders](../samples/orders/README.md): 複数の種類の参照・入れ子の明細（保有）・金額の整合の検査・参照を辿る集計・参照を 2 段辿るカテゴリー別の売上・出荷と取消の操作。
[diagrams](../samples/diagrams/README.md): 人物相関図・クラス図・受注のER図。レンズとフローで共通の記法を使い、列・主キー・外部キー・多重度を表示。
確認: YDEの検証と各READMEの試用手順。
