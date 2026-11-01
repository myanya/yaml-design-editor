# ローカル接続の基盤

Ktor/CIO と公式 MCP Kotlin SDK を応用層から隔離する組込み JVM モジュール。
設計契約は [ローカル接続の ADR](../docs/decisions/0016-local-connections-and-shared-host.md)、現在の構成は [ローカル接続の設計](../docs/architecture/local-connections.md)にある。

初期値はすべて OFF。デスクトップの設定から loopback 待受を明示起動し、認証済み API で参照・提案・直接確定・定義済み操作を行う。
`server.mcp` は MCP 単独で参照・作業と定義済み操作を利用でき、API と共通のサービスを呼ぶ。
`server.client` は CLI の明示接続用で、通信だけを担い、待受を起動しない。
`server.web` は同梱画面と Cookie による接続・業務閲覧・定義済み操作を提供する。画面のビルドとブラウザ検査は [web-ui](../web-ui/README.md)。
モジュールがクラスパスにあるだけではポートを開かない。

検査は `./gradlew :local-server:test`。
HTTP と MCP の契約はインメモリで、CIO の起動・停止・ポート解放と CLI 用クライアントの通信は一時的な loopback ポートで確かめる。
`LocalServerDistributionSmoke` はテストにだけ置き、`verifyWindowsDistribution` が同梱 JAR と縮小ランタイムでも実行する。
検査用の経路・ツールは製品へ含めない。

依存の宣言は `gradle/libs.versions.toml`、解決結果は `gradle.lockfile`。
依存を変更したときだけ `./gradlew :local-server:test :local-server:dependencies --configuration runtimeClasspath --write-locks` で更新し、差分を確認する。
その後に `./gradlew test` で既存機能への影響も検査する。
Windows 配布を検査する場合は `verifyWindowsDistribution --no-parallel` も通す。

## MCP の相互運用の確認

`McpHttpTest` は CIO の実ポートと公式 Kotlin SDK で、通信セッションの終了・再接続、取消、期限切れ、部分停止、保存完了待ち、初期化拒否後の解放を検査する。
一時ワークスペースと OS が選んだ空きポートを使い、通常の `test` に含める。

公式 Inspector は別途取得して任意検査に使う。製品・通常のテストに Node は不要で、Inspector を配布物へ含めない。
以下は PowerShell での例。Inspector の取得にはネットワークを使い、検査自体は同じ PC 内で完結する。

```powershell
npm.cmd install --prefix scratchpad/mcp-inspector --cache scratchpad/npm-cache --no-save --package-lock=false --ignore-scripts --no-audit --no-fund @modelcontextprotocol/inspector@2.13.0
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat :local-server:verifyMcpInspector '-PinspectorCli=scratchpad/mcp-inspector/node_modules/@modelcontextprotocol/inspector/clients/launcher/build/index.js' --no-daemon --console=plain
```

`-PinspectorNode=Nodeの実行ファイル` で別の Node を指定できる。
McpInspectorCheck は Inspector CLI を呼び、一覧・参照・変更・直接確定・結果照会・停止時の拒否・再接続後の再送を検査する。
検査用の接続者とトークンを発行し、資格情報をコマンド引数へ置かない。一時設定・ワークスペース・待受は検査の最後に破棄する。
Inspector の操作は [公式 CLI 文書](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md) を参照。
