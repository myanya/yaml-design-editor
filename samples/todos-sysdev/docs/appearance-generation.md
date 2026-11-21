# 画面デザインの台帳と生成

ゲーム風のサイバーパンクを、濃紺の背景・シアンの主要操作・マゼンタの補助線で表す。
日本語の本文と入力欄を読みやすくし、状態は色と文字を併用する。点滅は使わず、動きを減らす利用者設定に従う。

## 入力と確定値

| 内容 | 人が書く場所 | 確定項目と利用先 |
|---|---|---|
| 配色・文字・余白・幅・動き・名前 | `design/detail/inputs/appearance/theme.yaml` | `final/application-theme.yaml` → 共通 CSS・業務画面・確認画面 |
| 部品と CSS の対応 | 既定値は `screen-styles-derived`、個別変更は `inputs/documents/corrections.yaml`、追加は `inputs/documents/additions.yaml` | `final/screen-designs/styles.yaml` → CSS |
| 入力欄・型・制約 | 基本設計から導出した画面設計と、詳細設計の補正・追加 | `final/screen-designs/operations/` → `final/application-web-forms.yaml` → Form |
| 入力の誤りの文 | 基本設計のメッセージ定義と補正・追加 | `design/basic/final/messages.yaml` → Form の検査と入力エラー表示 |

表の省略したパスは `design/detail/` を起点にする。台帳は業務モデルから導けない方式の追加で、入力 ID と理由を確定項目に保持する。
台帳は YDE のフォームで編集でき、ダッシュボード「画面デザイン」で配色などの確定値と部品のクラスを確認できる。
ビューとレポートは確定値だけを読む。未確定の台帳を直接画面へ反映しない。

`application-theme-input-gate` が色や単位、対応する出力方式を検査し、`application-theme` が保存する。
色は 6 桁の 16 進数、長さは px、アニメーションの時間は 0〜200 ms とする。文字列を CSS の宣言や外部資源として解釈しない。
CSS の通常の宣言は部品の対応表で管理し、`applies` に記す。従来の Tailwind ユーティリティ名から通常の CSS 宣言へ変更した。
Tailwind の `@apply` や Node の工程、外部フォントは使わず、独立アプリだけで配信する。

入力フォームは入力のある 10 操作を生成する。入力のない 2 操作は Form を作らない。
必須・文字数・改行禁止などの形式・日付・配列の重複禁止・入れ子の検査を接続する。
空文字だけを null にし、前後の空白は保持する。未対応の検査、型と検査の不一致、必要なメッセージの不足は生成前に拒否する。
これは入力の形式の検査であり、Service の業務検査の代わりにはしない。
Form と Service は、確定した項目キーで照合し、明細とクリア指定も別の入力型へ写す。
接続と対応範囲は [アプリの生成「画面入力と Service の接続」](application-generation.md#画面入力と-service-の接続) を参照する。

## 再生成と確認

1. 台帳の色や文字を YDE で変更し、保存する。
2. 作業用コピーで設計を `generate <コピー先> --apply` し、検証した生成結果を反映する。使用中のワークスペースを閉じる必要はない。
3. `scripts/generate-app.ps1 -Apply` でアプリを生成する。
4. `app/` で `gradlew.bat bootJar` を実行し、`java -jar build/libs/todos-generated.jar` で起動する。
5. `http://localhost:8085/design-preview` で配色・入力・通知・一覧を確認する。

確認画面ではタイトルの未入力・101 文字、概要の未入力で「入力検査を試す」を押し、欄の近くに日本語のエラーが出ることを見る。
正しい入力では形式確認の通知を出す。いずれも Service を呼ばず、DB を更新しない。
一覧と状態の値は表示例で、業務データではない。狭い画面での配置と、Tab キーでの移動、フォーカスの見え方も確認する。

業務の一覧・詳細・入力から確認・確定までの 26 画面も、同じ確定デザインと CSS を使う。
`http://localhost:8085/` から操作できる。テンプレートの場所と試し方は [業務画面の生成と確認](web-generation.md) を参照する。
