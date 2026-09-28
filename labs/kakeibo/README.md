# 家計簿の試作（labs）

家計簿を題材に、集計（月×費目の金額・件数）と残高の検査、銀行の CSV の取り込みを試すワークスペースです。試作なので中身は変わります。ある程度良いものになったら `samples/` へ置き直します。続きの作業は [HANDOVER.md](HANDOVER.md) から始め、これからの整備は [PLAN.md](PLAN.md) の計画に沿って進めます。

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
    monthly-summary.yaml  # 月次サマリ: 期首残高・収入・支出・期末残高。「新規行」で新しい月の文書を作る
    transactions.yaml     # 取引明細（日付の新しい順。金額・費目・メモはグリッドで編集できる）
    month-by-category.yaml    # 月×費目（クロス集計 kind: matrix。費目が増えても列は自動・合計つき）
    month-by-category-b.yaml  # 同じ表を、ふつうのグリッドの「要素ごとに列を広げる」列（each）で書いた比較用
    month-by-kind.yaml        # 月×区分（クロス集計。収入・支出の件数）
    budget-vs-actual.yaml     # 予算と実績（予算の行ごとに実績・予算・差額。予算はグリッドで直せる）
  skills/
    import-smbc.yaml      # 三井住友銀行の CSV を book/tx/ の配列へ取り込む（採番つき）
book/
  categories.yaml         # CAT-UNSORTED（未分類）は取り込んだ直後の費目
  months/M-2026-08.yaml, M-2026-09.yaml   # ファイル名は月の ID（新規行で作れるように）
  tx/2026-08.yaml, 2026-09.yaml
  budgets/2026-09.yaml
inbox/
  smbc-sample.csv         # 三井住友銀行の公式サイトのサンプル CSV（2021 年 8 月・3 行）
  smbc-sample-2.csv       # 同じ形で作った架空の 2021 年 9 月分（費目の規則が当たる摘要を入れてある）
```

## 使い始める（毎月の流れ）

1. YDE でこのワークスペースを開き、台帳スペースの「ビュー」で「月次サマリ」を開きます。
2. 「新規行」に月の ID（`M-YYYY-MM`）を入れると `book/months/M-YYYY-MM.yaml` ができます。表の上で年月・前月・期首残高（前月の期末残高を写す）を埋めて保存します。
3. 取引を入れます。手で `book/tx/YYYY-MM.yaml` に書くか、設計スペースの「連携」で skill「三井住友銀行の入出金明細を取り込む」に CSV を渡し、プレビューを見て「適用（検証つき）」します。
4. 「取引明細（日付順）」で、未分類のままの費目を直します。検証パネルの警告が消えます。
5. 「予算と実績」で予算を書き、差額（マイナスは超過）を見ます。「月×費目」で費目ごとの合計、「月×区分」で件数を見ます。

## 決めていること

- **月はエンティティ**。取引は `date` のほかに `month` を参照で書きます。冗長ですが、検査「取引の日付は月に入っていること」で守ります。
- **残高は手書きの期首残高と検査**。累計の列は作りません。月の文書に `opening` を手で書き、検査「当月の期首残高＝前月の期首＋前月の収入−前月の支出」で連続を守ります。不便になったら、前月の期末を写す組込みエージェントを考えます。
- **金額は区分（`kind`）と正の数**。収入と支出は `where` で分けて別々に合計します。金額の項目とビューの合計の列は `display: grouped` で桁区切りに見せます（値は変えません）。
- **取り込みは追記と採番**。銀行の CSV には ID が無いので、取り込みが `TX-YYYYMMDD-NN` を付けます（同じ日の続き番号）。同じ明細（日付・区分・金額・メモが同じ）が既にあれば取り込まないので、同じ CSV を 2 回入れても増えず、手で直した費目も戻りません。費目は `CAT-UNSORTED`（未分類）で入り、検査が「未分類のまま」を警告します。

## 検査とビュー

| 名前 | 中身 | 書き方の要点 |
| --- | --- | --- |
| `month-matches-date` | 取引の日付が、指す月の年月で始まること | `startsWith` の両辺に値の式 |
| `opening-continues` | 当月の期首残高＝前月の期首＋前月の収入−前月の支出 | `all` の `from` で前月を起点に固定し、絞り方の違う 2 つの合計を四則演算で組む |
| `budget-overrun` | 費目の支出の合計が予算以下 | `same` で月を合流させ、`sum` に `all` の解の一覧を渡す |
| `unsorted-category` | 取り込んだ取引の費目が未分類のままでないこと | `expect: absent` で「未分類を指す取引が無いこと」（警告） |
| 月次サマリ | 期首残高・収入・支出・期末残高。新しい月はこの表の「新規行」で作り、年月・前月・期首残高をその場で埋める | 行＝月を起点に `all` で取引を集め、`sum` と `subtract`。`newRow: { filePattern: "book/months/{id}.yaml" }` |
| 取引明細（日付順） | 全取引を日付の新しい順に | `order: { path: date, type: date, direction: desc }`。ID の並びに頼らない |
| 月×費目（クロス集計） | 行＝月・列＝費目（費目一覧の順）・セル＝金額の合計・合計の行と列 | `kind: matrix`。`cells.match` で月から取引を集め、`columns: { via: category }` で費目ごとの列に広げる。費目を足せば列が増える |
| 月×区分（件数） | 行＝月・列＝収入／支出・セル＝件数 | `columns: { path: kind, values: [...] }`。値が列になる形 |
| 予算と実績 | 予算の行（月×費目）ごとに実績・予算・差額 | 実績は予算を起点に `also` で「同じ費目を参照する取引のうち同じ月のもの」を集めて `sum`（検査 `budget-overrun` と同じ照合）。差額は `subtract` |
| 月×費目（列を広げる書き方） | 上と同じ表を、ふつうのグリッドで | 列に `each: { idPattern: "CAT-*", bind: cat }` を書き、値の式の中で列の費目を `node: { same: cat }` で指す。合計の列は手で書く |

2 つの書き方の比べ方: クロス集計（`kind: matrix`）は行・列・セルを宣言するだけで、合計の行と未分類の列が自動で付きます。列を広げる書き方（`each`）は「ふつうの列の 1 つ」なので、年月のような他の列と混ぜられ、式を列ごとに変えられますが、合計の行は付きません。

## 取り込み（skill `import-smbc`）

CSV の各行を「見出し → セル」の項にして、ひな型（`build`）で明細を組み立てます。要点は次のとおりで、書き方の説明は [docs/manual/ai.md](../../docs/manual/ai.md)「行をひな型で組み立てて、配列へ追記する」にあります。

- 月が空の行（期首の残高行）は `$when` で飛ばす。年・月・日の 3 列を `$date` で `yyyy-MM-dd` にそろえる。`ご入金` があれば収入、無ければ支出。`¥10,000` は `$number` で 10000。摘要の埋め草の空白は `$trim` で落とす。
- **費目は摘要の言葉で当てます**（`$first` に `$when: { path: 摘要, contains: スーパー }` の規則を並べる。スーパー・コンビニ→食費、電気・デンキ・ガス→光熱費、家賃・ヤチン→家賃、給与・キユウヨ→給与）。どれにも当たらなければ `CAT-UNSORTED`（未分類）で入り、検査が警告します。言葉は skill に直書きなので、増やすときは `import-smbc.yaml` を直します。
- 取り込み先は `book/tx/{yyyy-MM}.yaml` の `entries`。ID は `TX-{yyyyMMdd}-` に 2 桁の続き番号。
- **取り込む前に、その月の文書を作っておきます**。月次サマリの「新規行」に `M-YYYY-MM` を入れると `book/months/M-YYYY-MM.yaml` ができるので、表の上で年月・前月・期首残高を埋めます。無いと `month` の参照が解決できず、GUI の「適用（検証つき）」が止まります。

分かっている制約: 期間が年をまたぐ CSV（12 月〜1 月）は、年を「対象日（自）_年」から取るので 1 月の明細の年を取り違えます。これは運用で逃がします（年をまたぐときは 12 月までと 1 月からで分けてダウンロードする。汎用ツールに特定の銀行向けの仕掛けは足さない）。実物のダウンロードが Shift_JIS のときは `input.encoding: windows-31j` にします（サンプルは UTF-8）。

## わざと入れてある食い違い

- `book/tx/2026-09.yaml` の `TX-20260930-01` は月を `M-2026-08` と間違えています。`month-matches-date` がエラーにします。
- その取引が 8 月に数えられるので、9 月の期首残高 112000 は前月から計算した値と合わず、`opening-continues` がエラーにします。
- 9 月の食費は 45000 で予算 40000 を超えているので、`budget-overrun` が警告します。

合計はエラー 2・警告 1 です。

取り込みの筋書き: 月次サマリの「新規行」で `M-2021-08` を作ってから `inbox/smbc-sample.csv` を取り込むと、取引が 2 件でき（費目は未分類のまま）、`unsorted-category` の警告が 2 件増えます。費目を直すと消えます。同じ CSV をもう一度取り込んでも、同じ明細として飛ばされて増えません。

## 動かし方

```bash
./gradlew run --args="labs/kakeibo"                                  # GUI で開く（台帳スペース → ビュー。設計スペース → 連携で取り込み）
./gradlew runCli -q --args="validate labs/kakeibo"                   # 検査（上の 3 件が出る）
./gradlew runCli -q --args="view labs/kakeibo monthly-summary"       # 月次サマリ。CLI の数は桁区切りしない
./gradlew runCli -q --args="view labs/kakeibo transactions"          # 取引明細（日付の新しい順）
./gradlew runCli -q --args="view labs/kakeibo month-by-category"     # 月×費目（クロス集計。最後の行が合計）
./gradlew runCli -q --args="view labs/kakeibo month-by-category-b"   # 同じ表の、列を広げる書き方
./gradlew runCli -q --args="view labs/kakeibo budget-vs-actual"      # 予算と実績（食費は差額がマイナス＝超過）
./gradlew runCli -q --args="import labs/kakeibo import-smbc labs/kakeibo/inbox/smbc-sample.csv"          # 取り込みのプレビュー（2 件。月の文書が無くても見られる）
./gradlew runCli -q --args="import labs/kakeibo import-smbc labs/kakeibo/inbox/smbc-sample.csv --apply"  # 取り込む（先に月次サマリの新規行で M-2021-08 を作る）
./gradlew runCli -q --args="import labs/kakeibo import-smbc labs/kakeibo/inbox/smbc-sample-2.csv"        # 費目の規則が当たるプレビュー（食費・光熱費・給与・家賃）
```
