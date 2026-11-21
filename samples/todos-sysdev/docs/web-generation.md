# 業務画面の生成と確認

一覧・詳細・入力・確認の画面を、保存した設計の確定値から生成する。
生成したアプリは YDE を起動せずに動作し、ログインは設けない。
ビルドと DB の扱いは [アプリの生成と独立ビルド](application-generation.md) を参照する。

## 入力から出力まで

| 確定した設計 | 生成するもの |
|---|---|
| `screen-designs/` の画面と部品 | 画面ごとの Thymeleaf HTML、共通部品と表示用の値 |
| `controllers/` の入口と遷移 | 5 つの Controller、HTTP メソッド・URL・ハンドラ |
| 一覧と詳細の読出し、`application-access.yaml` | 読出し Service、型付き Mapper 呼出し、物理列と表示項目の対応 |
| `application-web-forms.yaml`・`application-web-bindings.yaml` | 入力保持、確認用メソッドと更新用メソッドの呼出し |
| `session.yaml`・基本設計のメッセージ | 二重確定防止、完了の通知、業務の断り・404・500 の表示 |
| デザイン台帳と `application-theme.yaml` | 業務画面とデザイン確認画面で共通に使う配色・文字・余白 |

基本設計のメッセージ以外は `design/detail/final/` を起点とする。
これらを `application-web.yaml` にまとめ、画面と入口の対応、未対応の部品、読出しと列の対応を検査して保存する。
レポートはこの保存値を使う。補正の適用や業務モデルの型解決を Web アプリの中で行わない。

HTML の生成元は `.yde/reports/application/business-page.html` と `business-fragments.html`。
前者が画面ごとの構成、後者が入力欄・項目表示・表・集計・操作ボタンなどの共通部品である。
画面固有の項目と対応は同じ確定値から `resources/web-model.json` に出す。これはアプリ内の表示用資源で、JSON の業務 API ではない。

生成後には、たとえば次のファイルがある。

- `app/src/main/resources/templates/todo-dashboard/list.html`
- `app/src/main/resources/templates/todo/detail.html`
- `app/src/main/resources/templates/todo/register-todo/input.html`
- `app/src/main/resources/templates/todo/register-todo/confirm.html`
- `app/src/main/java/example/todos/web/TodoController.java`
- `app/src/main/java/example/todos/service/TodoDashboardService.java`

画面は一覧 1、詳細 3、入力 10、確認 12 の計 26。業務の操作は 12。
テンプレート名・項目名・物理列名・呼出名を変更するときは、対応する設計の入力を補正して再生成する。
列の値は項目の対応で読み、画面側の列の並び順を SQL の戻り順と同一視しない。
絞り込みの項目名、集計区画のセルのパラメータ、基準日の欄、一覧への戻り先も確定値を使う。

## 入力・確認・確定

入力画面の初期値は基本設計の `initial` から読む。編集では DB の現在値、日付の初期値ではアプリの Clock を使う。
型・必須・文字数・形式を検査し、入力が原因になる業務の規則も確認する。誤りがあれば入力値を残して戻す。
確認では書き込まず、入力値を hidden 項目へ保持する。編集の確認には変更する項目と変更前後の値を表示する。

コメント行の追加・削除は htmx で入力領域を更新する。JavaScript を使えない場合も通常の POST で同じ画面へ戻る。
行をすべて削除した場合の空配列と、送られていない項目を区別する。「値を消す」の指定は入力した値より優先する。
htmx は固定版とライセンスを同梱し、実行時の外部 CDN は使わない。

確認ごとに別の流れの ID とトークンを発行し、セッションの token の役割を持つキーに保存する。
確定時は照合と消費を一度に行う。別タブの確認を上書きせず、同じ確定を再送しても二度書き込まない。
更新は Service が状態・前提・規則を確かめ直し、既存のトランザクションと同時更新の方式に従う。

完了後は画面 ID の許可一覧に含まれる起点へ戻り、通知を一度表示する。外部 URL は戻り先にしない。
入力・絞り込み・起点をセッションへ保存しない。見つからない対象は 404、想定外の失敗は受付番号を付けた 500 とし、原因はログに残す。

## 試し方

1. `app/` で `gradlew.bat bootJar` を実行し、`java -jar build/libs/todos-generated.jar` で起動する。
2. `http://localhost:8085/` を開き、カテゴリとタグを追加する。
3. TODO を登録し、確認画面の表示、入力へ戻ったときの値、確定後の一覧と通知を確認する。
4. 詳細からコメントと作業項目を追加する。着手・中断・再開・作業の完了・TODO の完了を操作し、状態と履歴を見る。
5. 編集でコメント行の追加・削除、値の消去、変更前後の表示を確認する。
6. タグ・カテゴリ・基準日で絞り、集計のセルから状態を絞った一覧へ移る。
7. 必須項目を空にする、長すぎる文字を入れる、確認の後に別タブで状態を変える、同じ確定を再送する。誤りや断りが表示され、誤って更新されないことを見る。
8. 画面幅を狭め、Tab キーで移動する。配色・入力欄・フォーカスは `/design-preview` と同じ台帳の値を使う。

`scripts/verify-app.ps1` はアプリを一時領域へコピーし、実際の HTTP と一時 H2 DB を使う結合テスト、独立ビルドを行う。
設計と生成物の一致・補正後の値・対応漏れは YDE 側のテストで確認する。
アプリ側でも、絞り込みの名前・基準日の欄・区画名・一覧の URL を変えた表示用データで対応を検証する。
設計内容と画面の見え方のオーナーレビューは別に継続する。
