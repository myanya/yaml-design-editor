# Web の固定資源

[htmx 2.0.11](https://github.com/bigskysoftware/htmx/tree/v2.0.11) の配布 JavaScript とライセンスを同梱する。
`files.json` に版・取得元・SHA-256 を記録し、アプリの生成スクリプトが検査してコピーする。
アプリの利用時に外部 CDN や Node は不要。

取得元は公式リポジトリの `dist/htmx.min.js` と `LICENSE`。版を変えるときは両方を取得し直し、ハッシュと画面の結合検証を更新する。
