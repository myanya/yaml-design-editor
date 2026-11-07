# 指摘台帳（BACKLOG）

<!-- この文書は devtrack から生成する（./gradlew devtrackDocs）。直接直さない。ひな形は devtrack/.yde/reports/backlog-md.md -->

UX試験・レビュー・開発中に見つかった「今のタスクとは本質が異なるが、気になるポイント」を**見つけた時点で**記録する台帳。対応するかどうか・いつやるかは後で個別に判断する（記録と対応判断を分離することで、脱線せずに指摘を失わない）。

**原本は [devtrack/](../devtrack/README.md)**（指摘・テーマ・所感メモの 1 件 1 ファイルの YAML）で、この文書と[記録](archive/backlog-closed.md)はそこから生成する。
登録・状態の変更・解決は devtrack の操作で行い、`./gradlew devtrackDocs` で作り直す。運用は [devtrack/README.md](../devtrack/README.md)「運用」。

- 状態: 📋 未着手 / 💬 議論待ち / 🚧 対応中 / ⏳ 必要になったら実装する（いずれ要ると分かっているが、需要が出るまで着手しない。実装の当たりを技術メモに残しておく）。解決（✅）と見送り（❌）にした指摘は[記録](archive/backlog-closed.md)に出る
- 状態の欄の絵文字の後ろは解決の記録（日付・種類・内容）。未解決の指摘では、一部の解決（一部）と、解決や見送りを取り消した記録（再開）

| ID | 起点 | 指摘 | 技術メモ（対応の当たり） | 状態 | 対応先 |
|---|---|---|---|---|---|
{#each items}
| {id} | {#if values.起点の日}{values.起点の日}{#if values.起点} {/if}{/if}{#if values.起点}{values.起点:cell}{/if} | {values.指摘:cell} | {#if values.技術メモ}{values.技術メモ:cell}{/if} | {values.状態:cell}{#if values.解決の記録}{#each values.解決の記録} {date} {kind}{#if note}: {note:cell}{/if}{#if targets}（{targets:cell}）{/if}{/each}{/if} | {values.対応先:cell} |
{/each}

## テーマ管理（複数指摘が絡む横断課題）

単発の指摘として扱うと議論が分断される横断課題を、テーマ（T-nn）として独立管理する。対応判断は急がず、関連指摘と考えられる方策をここへ蓄積し、議論の機が熟したら ROADMAP へ起こす。
束ねる指摘は、指摘の側でテーマを参照する（devtrack の `theme`）。

{#each themes}
### {id} {values.名前} 【{values.状態の補足}】

{values.本文}
{/each}
## {impressions.values.名前}

{impressions.values.本文}