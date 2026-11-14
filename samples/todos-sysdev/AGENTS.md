# samples/todos-sysdev で守ること

- `.yde/schemas/business/` と `.yde/views/business/` は samples/todos の写しです。ここを直さず、samples/todos を直してから丸ごと写します（オーナーの指示があったとき）。
- `design/generated/` は生成物です。手で直さず、業務モデルか生成の定義（`.yde/transforms/design/`）を直し、`./gradlew runCli -q --args="generate samples/todos-sysdev --apply"` で作り直します。生成物が最新かは app-core の `TodosSysdevTest` が確かめます。
- 設計書の ID は業務の ID そのものにします（新しい ID が要るときは、業務モデルがシステム化に向いていないサインとして扱い、先に相談します）。
- 名前の決まり（ケバブからの機械的な変換だけ・URL は業務の ID のまま）と、表と列の名前が仮であることは README の通りです。
- labs の下のワークスペースは入力にしません。
