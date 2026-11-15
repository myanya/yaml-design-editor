# samples/todos-sysdev で守ること

- `.yde/schemas/business/` と `.yde/views/business/` は samples/todos の写しです。ここを直さず、samples/todos を直してから丸ごと写します（オーナーの指示があったとき）。
- `design/generated/` は生成物です。手で直さず、業務モデルか生成の定義（`.yde/transforms/design/`）を直し、`./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直します。生成物が最新かは app-core の `TodosSysdevTest` が確かめます。
- 設計書の ID は業務の ID そのものにします（新しい ID が要るときは、業務モデルがシステム化に向いていないサインとして扱い、先に相談します）。
- 生成の定義には `stage`（基本設計書は `basic`・詳細設計書は `detail`）を書き、段の項を読みます。業務モデルに無いが全体に要るもの（監査の項目など）は、生成の定義ではなく段のルール（`.yde/stages/design/`）に書きます。基本設計の個別の直しは、補正一覧（`design/basic/corrections.yaml`）に書きます。
- 生成の定義には `scope: business` を書き、業務モデルのスコープ（`.yde/workspace.yaml` の scopes）だけを読みます。設計の側に自分の型を持つ文書を足すときは、設計のスコープに置きます（業務モデルから設計は使えません）。
- 名前の決まり（ケバブからの機械的な変換だけ・URL は業務の ID のまま）と、データ系・画面系・エラーとメッセージ系の決まり（テーブル・カラムの名前・型の対応・採番・前提とデータの決まりの確かめ方・断りの出し方）は README の通りです。
- データの決まりの確かめ方（Java・SQL・対象外）は、エラー定義書・処理設計書・テーブルアクセス設計書・メッセージ定義書・Controller の生成の定義に同じ条件で書いています。1 つを直したら、ほかもそろえます（使うキーがメッセージ定義書にあるかは `TodosSysdevTest` が確かめます）。
- labs の下のワークスペースは入力にしません。
