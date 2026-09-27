# 家計簿の試作（labs）

家計簿を題材に、集計（月×費目の金額・件数）と残高の検査を試すワークスペースです。試作なので中身は変わります。ある程度良いものになったら `samples/` へ置き直します。続きの作業は [HANDOVER.md](HANDOVER.md) から始めてください。

## 構成

```text
.yde/
  workspace.yaml          # 台帳は book 配下
  schemas/
    categories.yaml       # 費目（CAT-*。収入・支出の区分）
    months.yaml           # 月（M-YYYY-MM。前月への参照・期首残高）と検査 2 つ
    transactions.yaml     # 取引（TX-YYYYMMDD-NN。日付・区分・金額・費目・月・メモ）
    budgets.yaml          # 予算（BG-*。月×費目の予算額）と検査 1 つ
  views/
    monthly-sums.yaml     # 月×費目（金額）: 収入・食費・支出・期末残高
    monthly-counts.yaml   # 月×費目（件数）
book/
  categories.yaml
  months/2026-08.yaml, 2026-09.yaml
  tx/2026-08.yaml, 2026-09.yaml
  budgets/2026-09.yaml
```

## 決めていること

- **月はエンティティ**。取引は `date` のほかに `month` を参照で書きます。冗長ですが、検査「取引の日付は月に入っていること」で守ります。
- **残高は手書きの期首残高と検査**。累計の列は作りません。月の文書に `opening` を手で書き、検査「当月の期首残高＝前月の期首＋前月の収入−前月の支出」で連続を守ります。不便になったら、前月の期末を写す組込みエージェントを考えます。
- **金額は区分（`kind`）と正の数**。収入と支出は `where` で分けて別々に合計します。

## 検査とビュー

| 名前 | 中身 | 書き方の要点 |
| --- | --- | --- |
| `month-matches-date` | 取引の日付が、指す月の年月で始まること | `startsWith` の両辺に値の式 |
| `opening-continues` | 当月の期首残高＝前月の期首＋前月の収入−前月の支出 | `all` の `from` で前月を起点に固定し、絞り方の違う 2 つの合計を四則演算で組む |
| `budget-overrun` | 費目の支出の合計が予算以下 | `same` で月を合流させ、`sum` に `all` の解の一覧を渡す |
| 月×費目（金額） | 収入・食費・支出・期末残高 | 行＝月を起点に `all` で取引を集め、`sum` と `subtract` |
| 月×費目（件数） | 食費の件数・支出の件数 | 同じ `all` を `count` に渡す |

## わざと入れてある食い違い

- `book/tx/2026-09.yaml` の `TX-20260930-01` は月を `M-2026-08` と間違えています。`month-matches-date` がエラーにします。
- その取引が 8 月に数えられるので、9 月の期首残高 112000 は前月から計算した値と合わず、`opening-continues` がエラーにします。
- 9 月の食費は 45000 で予算 40000 を超えているので、`budget-overrun` が警告します。

## 動かし方

```bash
./gradlew run --args="labs/kakeibo"                                  # GUI で開く（台帳スペース → ビュー）
./gradlew runCli -q --args="validate labs/kakeibo"                   # 検査（上の 3 件が出る）
./gradlew runCli -q --args="view labs/kakeibo monthly-sums"          # 月×費目（金額）
```
