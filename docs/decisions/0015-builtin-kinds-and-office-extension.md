# ADR-0015: chart・flow と文字の形式は app-core の組込みにし、拡張は Office の形式だけにする

- 日付: 2026-10-30
- 状態: 承認済み
- 上書き対象: ADR-0013 の一部（拡張のモジュール構成＝format-text・format-xlsx・format-docx・view-*、登録簿の置き場と層、入口が渡すもの）と、ADR-0014 の一部（拡張へ出すのは chart と flow・ビューの拡張が公開するもの・登録していない chart と flow は使えない）。差し込み口（`FormatCodec`・`ViewKind`）と登録簿、カーネルが種類を引数で受け取ること、POI を中核に入れないことはそのまま

## 文脈

ADR-0013 と ADR-0014 で、形式の 3 つ（format-text・format-xlsx・format-docx）とビューの種類の 2 つ（view-chart・view-flow）を拡張モジュールに出した。出した後に数えると、次のようになっていた。

- 5 つ合わせて main は 1,145 行で、全体（64,771 行）の 2% に満たない。形式の 3 つは 1 ファイルずつ（41・75・221 行）。
- 1 つの機能が 2 か所に分かれたまま。chart は計算 401 行が view-chart、描画 961 行が desktop にある。flow も同じ。ADR-0013 の「1 拡張が同じ形の閉じた箱になる」になっていない。
- 登録の手間がかかる。入口ごとの一覧が 2 つ、テストの 7 ファイルが登録簿を入れていた。登録簿は大域なので、入れ忘れたテストは走る順番次第で通ったり落ちたりしうる。
- 拡張は全部このリポジトリで作り、いつも一緒に配る。JDBC のドライバが別の jar なのは作る会社が違うからで、Spring Boot 4 が連携ごとに jar を分けたのは、何百もの連携から使う側が要るものだけを選ぶからである。YDE には、選んで外したい利用者がいない。

分けて得たもので残す価値があるのは 2 つだった。差し込み口（種類ごとの `when` の分岐が消え、定義の形を断片で足せる）と、Apache POI（大きなライブラリと、その推移依存の版の固定）を中核に入れない境界である。差し込み口はコードの形なので、モジュールをまとめても残る。

## 決定

### 組込みに戻す

- ビューの種類 chart・flow は app-core の `app.view.chart`・`app.view.flow` に置く。資源は `view/chart/schema.yaml`・`view/flow/schema.yaml`・`view/flow/flow-model.yaml`。
- 形式の差し込み口（`FormatCodec`・`Table`）と文字の形式（csv・json・markdown・html）は app-core の `app.format` に置く。
- `app.view` と `app.format` はカーネルだけを使い、app-core の層の一番下に置く。登録簿の `app.ext` はその上。
- チャートで押した形の材料を集める処理（`ChartData.selection`）は索引（`app.index`）を使うので、`app.core` に置く。種類の側に置くと、`app.schema` → `app.ext` → `app.view` → `app.index` → `app.schema` の循環になる。

### 登録簿は組込みと入口が足したもの

- `ExtensionRegistry` は組込みの形式とビューの種類を最初から持ち、入口が渡したものを後ろに足す。組込みと同じ ID を足すと二重登録の誤りになる。
- 入口（desktop・cli）が渡すのは別モジュールの拡張だけで、今は Excel と Word。
- 差し込み口 `ViewKind` はカーネルのまま。カーネルは大域の登録簿を持たず、種類を引数（`kinds`）で受け取る（ADR-0014 のまま）。chart と flow は、中核の種類（grid など）と違って差し込み口を通る。

### 拡張に残すのは Office の形式だけ

- format-xlsx と format-docx を format-office 1 つにまとめる（パッケージ `format.office`）。POI はここだけに置く。
- 拡張の形（README・共通の設定・テスト・公開するのは実装だけ）と、規則テスト（入口の一覧に載せる・app-core とカーネルだけを使う）は変えない。

### 別モジュールに出す基準

新しい形式やビューの種類は、まず app-core の組込みとして足す。別モジュールにするのは、中核に入れたくない大きな依存を持つとき（POI のように）か、使う側が選んで入れる需要が出たときだけにする。ビューの描画を別モジュール（view-*-ui）に出すことも、その需要が出るまでしない。

## 影響

- モジュールは 13 から 9 になる（yaml-core・semantic-core・workspace-core・app-core・agent・format-office・cli・desktop・repo-tests）。
- 入口とテストが登録しなくても、chart・flow・文字の形式を使える（これまでは、登録しないと使えない種類・形式の誤りだった）。登録簿を入れるテストは、Excel と Word を使うものだけになる。
- app-core の層は `app.view`・`app.format`（一番下）→ `app.ext` → `app.index`・`app.schema`・`app.validate`・`app.text` → `app.core` → … になる。規則テストの層の表もこれに合わせる。

## 却下した案

- 同じ種類どうしをまとめるだけ（`formats` 1 つ・`views` 1 つ）: モジュールの数は減るが、登録の手間とテストの登録漏れは残る。
- Excel と Word も app-core に入れる: POI と、その推移依存の版の固定（log4j）が中核に入る。外に置く手間は、1 モジュールなら小さい。
- ビューの種類をカーネルに戻す: chart の押した形の材料が app-core の索引を使う。カーネルは種類を引数で受け取る形のままにする。
