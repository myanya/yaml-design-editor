# 家計簿の試作（labs）

家計簿を題材に、集計（月×費目の金額・件数）と残高の検査、銀行の CSV の取り込みを試すワークスペースです。試作なので中身は変わります。ある程度良いものになったら `samples/` へ置き直します。続きの作業は [HANDOVER.md](HANDOVER.md) から始めてください。

## 構成

```text
.yde/
  workspace.yaml          # 台帳は book 配下
  schemas/
    categories.yaml       # 費目（CAT-*。収入・支出の区分）
    months.yaml           # 月（M-YYYY-MM。前月への参照・期首残高）と検査 2 つ
    transactions.yaml     # 取引（TX-YYYYMMDD-NN。日付・区分・金額・費目・月・メモ）と検査 1 つ
    budgets.yaml          # 予算（BG-*。月×費目の予算額）と検査 1 つ
  views/
    monthly-sums.yaml     # 月×費目（金額）: 収入・食費・支出・期末残高
    monthly-counts.yaml   # 月×費目（件数）
  skills/
    import-smbc.yaml      # 三井住友銀行の CSV を book/tx/ の配列へ取り込む（採番つき）
book/
  categories.yaml         # CAT-UNSORTED（未分類）は取り込んだ直後の費目
  months/2021-08.yaml, 2026-08.yaml, 2026-09.yaml
  tx/2026-08.yaml, 2026-09.yaml
  budgets/2026-09.yaml
inbox/
  smbc-sample.csv         # 三井住友銀行の公式サイトのサンプル CSV（2021 年 8 月・3 行）
```

## 決めていること

- **月はエンティティ**。取引は `date` のほかに `month` を参照で書きます。冗長ですが、検査「取引の日付は月に入っていること」で守ります。
- **残高は手書きの期首残高と検査**。累計の列は作りません。月の文書に `opening` を手で書き、検査「当月の期首残高＝前月の期首＋前月の収入−前月の支出」で連続を守ります。不便になったら、前月の期末を写す組込みエージェントを考えます。
- **金額は区分（`kind`）と正の数**。収入と支出は `where` で分けて別々に合計します。
- **取り込みは追記と採番**。銀行の CSV には ID が無いので、取り込みが `TX-YYYYMMDD-NN` を付けます（同じ日の続き番号）。同じ明細（日付・区分・金額・メモが同じ）が既にあれば取り込まないので、同じ CSV を 2 回入れても増えず、手で直した費目も戻りません。費目は `CAT-UNSORTED`（未分類）で入り、検査が「未分類のまま」を警告します。

## 検査とビュー

| 名前 | 中身 | 書き方の要点 |
| --- | --- | --- |
| `month-matches-date` | 取引の日付が、指す月の年月で始まること | `startsWith` の両辺に値の式 |
| `opening-continues` | 当月の期首残高＝前月の期首＋前月の収入−前月の支出 | `all` の `from` で前月を起点に固定し、絞り方の違う 2 つの合計を四則演算で組む |
| `budget-overrun` | 費目の支出の合計が予算以下 | `same` で月を合流させ、`sum` に `all` の解の一覧を渡す |
| `unsorted-category` | 取り込んだ取引の費目が未分類のままでないこと | `expect: absent` で「未分類を指す取引が無いこと」（警告） |
| 月×費目（金額） | 収入・食費・支出・期末残高 | 行＝月を起点に `all` で取引を集め、`sum` と `subtract` |
| 月×費目（件数） | 食費の件数・支出の件数 | 同じ `all` を `count` に渡す |

## 取り込み（skill `import-smbc`）

CSV の各行を「見出し → セル」の項にして、ひな型（`build`）で明細を組み立てます。要点は次のとおりで、書き方の説明は [docs/manual/ai.md](../../docs/manual/ai.md)「行をひな型で組み立てて、配列へ追記する」にあります。

- 月が空の行（期首の残高行）は `$when` で飛ばす。年・月・日の 3 列を `$date` で `yyyy-MM-dd` にそろえる。`ご入金` があれば収入、無ければ支出。`¥10,000` は `$number` で 10000。摘要の埋め草の空白は `$trim` で落とす。
- 取り込み先は `book/tx/{yyyy-MM}.yaml` の `entries`。ID は `TX-{yyyyMMdd}-` に 2 桁の続き番号。
- **取り込む前に、その月の文書（`book/months/M-YYYY-MM`）を作っておきます**。無いと `month` の参照が解決できず、GUI の「適用（検証つき）」が止まります。サンプルの 2021 年 8 月は用意してあります。

分かっている制約: 期間が年をまたぐ CSV（12 月〜1 月）は、年を「対象日（自）_年」から取るので 1 月の明細の年を取り違えます。実物のダウンロードが Shift_JIS のときは `input.encoding: windows-31j` にします（サンプルは UTF-8）。

## わざと入れてある食い違い

- `book/tx/2026-09.yaml` の `TX-20260930-01` は月を `M-2026-08` と間違えています。`month-matches-date` がエラーにします。
- その取引が 8 月に数えられるので、9 月の期首残高 112000 は前月から計算した値と合わず、`opening-continues` がエラーにします。
- 9 月の食費は 45000 で予算 40000 を超えているので、`budget-overrun` が警告します。

取り込みは実行していません（`inbox/` に CSV を置いてあるだけ）。取り込むと 2021 年 8 月の取引が 2 件でき、`unsorted-category` の警告が 2 件増えます。

## 動かし方

```bash
./gradlew run --args="labs/kakeibo"                                  # GUI で開く（台帳スペース → ビュー。設計スペース → 連携で取り込み）
./gradlew runCli -q --args="validate labs/kakeibo"                   # 検査（上の 3 件が出る）
./gradlew runCli -q --args="view labs/kakeibo monthly-sums"          # 月×費目（金額）
./gradlew runCli -q --args="import labs/kakeibo import-smbc labs/kakeibo/inbox/smbc-sample.csv"          # 取り込みのプレビュー
./gradlew runCli -q --args="import labs/kakeibo import-smbc labs/kakeibo/inbox/smbc-sample.csv --apply"  # 取り込む（book/tx/2021-08.yaml ができる）
```
