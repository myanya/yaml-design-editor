# samples/todos-sysdev で守ること

- `.yde/schemas/business/` と `.yde/views/business/` は samples/todos の写しです。ここを直さず、samples/todos を直してから丸ごと写します（オーナーの指示があったとき）。
- `design/generated/` は生成物です。手で直さず、業務モデルか生成の定義（`.yde/transforms/design/`）を直し、`./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直します。生成物が最新かは app-core の `TodosSysdevTest` が確かめます。
- 設計項目一覧は独自の ID（ENT・FLD・TYP など）を持ち、業務モデルを導出元として参照します。確定項目への移行は ADR-0039 に従います。導出記録の中の識別子を、確定項目と同じ ID の出現として登録しません。移行前のほかの設計書は、既存の ID を維持します。
- エンティティ・型の生成は ADR-0039 の経路を使います。導出 → 共通型の補正 → 利用箇所の補正 → 候補の統合 → `basic-final` の検査 → 確定項目の保存です。`stage` を再適用せず、`dependsOn` で今回の依存出力を読みます。
- 人が書く補正・指定は `design/basic/corrections.yaml`、追加は `design/basic/additions.yaml`。区分の定義と割当を分けます。生成中の対象は文字、確定項目だけが通常の設計 ID と参照です。
- `COR-001` はオーナーが選んだ TODO の ID の代表の説明です。検証用の写しで補正を増やすときも消しません。
- 未処理の補正・未対応の追加や方式は `basic-final` で生成を止めます。対応範囲は `docs/entity-generation-contract.md` を更新してください。中間結果を確定済みとして表示しません。
- 既存のエンティティ定義書・横断ビュー・C/U とテーブル生成は、保存した確定値を読みます。ビューへ基本設計の補正や型継承の計算を戻しません。
- ほかの設計書は既存の段の経路を使います。移行は設計書ごとに内容を議論します。詳細設計の索引は人が書く台帳、値の置換は詳細の補正一覧です。
- 詳細設計項目一覧（`design/detail/tables.yaml`）には、詳細設計にだけ出てくるものだけを持たせます（基本設計の値は導出元をたどって読む）。SQL の型は型対応表（`design/detail/sql-types.yaml`。人が書く）で決め、DDL はレポート `ddl` で作り直します（`TodosSysdevTest` が `design/ddl/schema.sql` が最新かを確かめます）。
- 業務モデルを読む生成には `scope: business` を書きます。`basic-final` だけは検査用の保存スキーマの原文を得るため `scope: design` を使い、業務の値は依存出力から読みます。設計の型は設計のスコープに置き、業務モデルから設計は使いません。
- 名前の決まり（ケバブからの機械的な変換だけ・URL は業務の ID のまま）と、データ系・画面系・エラーとメッセージ系の決まり（テーブル・カラムの名前・型の対応・採番・前提とデータの決まりの確かめ方・断りの出し方）は README の通りです。
- データの決まりの確かめ方（Java・SQL・対象外）は、エラー定義書・処理設計書・テーブルアクセス設計書・メッセージ定義書・Controller の生成の定義に同じ条件で書いています。1 つを直したら、ほかもそろえます（使うキーがメッセージ定義書にあるかは `TodosSysdevTest` が確かめます）。
- labs の下のワークスペースは入力にしません。
