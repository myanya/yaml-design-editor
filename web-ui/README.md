# 業務 Web

React・TypeScript のブラウザ画面。接続コードでログインし、保存済みのダッシュボード・表・チャートと業務詳細を表示する。
利用方法は [業務 Web](../docs/manual/local-web.md)、認証と責務は [ローカル接続](../docs/architecture/local-connections.md) にある。

## 構成

| 場所 | 責務 |
|---|---|
| `src/main.tsx` | React・Router・Query・MUI と CSP nonce の組立て |
| `src/ui/App.tsx` | 接続画面と概要、接続の再確認と失効時の表示解除 |
| `src/ui/BusinessWorkspace.tsx` | 保存版ごとの取得、ビュー・区画・基準日・共有入力と詳細への遷移 |
| `src/ui/BusinessTable.tsx` / `BusinessValues.tsx` | 表・合成された詳細・ラベル・索引で解決した参照の表示 |
| `src/ui/BusinessChart.tsx` | ECharts の SVG 描画と数値表、図の依存を閉じる部品 |
| `src/api.ts` | Cookie 通信、Origin と CSRF の前提、期限と日本語エラー |
| `local-server/src/main/resources/web-api/openapi.json` | Web 通信の契約、TypeScript 型の生成元 |
| `scripts/asset-manifest.mjs` | 配信可能な同梱資源の一覧 |
| `browser-tests/` | 実 HTTP と Chromium による操作と表示の検査 |

意味の計算・YAML の編集・保存規則は持たず、`/web-api` で共通サービスの結果を受け取る。
チャートは ECharts 6.1.0 の必要な部品を遅延読込する。分類・集計・数値変換はサーバで済ませ、欠けた値を 0 に置き換えない。
コード・Cookie・CSRF を URL や localStorage / sessionStorage に保存しない。

## ビルドと検査

リポジトリのルートで `./gradlew :web-ui:bundle` を実行する。
Gradle が Node 24.21.0・npm 11.19.0 を取得し、ロックファイルによる `npm ci`、OpenAPI 型生成、型検査、Vite ビルドを行う。
生成物は `build/dist` に置き、local-server の資源へ同梱する。Node は開発時だけ必要で、製品には不要。
`./gradlew test` は Vitest も実行する。新しい依存は package.json に固定版で追加し、同じ npm でロックファイルを更新する。

実ブラウザ検査では、先に `:web-ui:bundle` を通し、取得された Node または同じ版の Node で Playwright の Chromium を取得する。
PowerShell の例（リポジトリのルート、Node が PATH にある場合）:

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path (Get-Location) 'web-ui/build/browsers'
node web-ui/node_modules/@playwright/test/cli.js install chromium
.\gradlew.bat :local-server:verifyWebBrowser --no-daemon --console=plain
```

PATH にない Node は `-PwebNode=実行ファイルの絶対パス` で指定する。
検査用の JVM が一時ワークスペースと loopback サーバを用意し、接続コードを子プロセスの環境へ渡す。
接続だけの最小例に加え、注文・TODO サンプルのコピーで条件変更・参照・チャート・保存版の競合・失効を検査する。
終了時にサーバ・ブラウザ・一時ワークスペースを回収する。画像は `build/browser-results` に残す。
所有中のデスクトップや利用者のワークスペースは操作しない。
